package com.samourai.sentinel.data.entropy

import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.db.entity.TxEntropy
import com.samourai.sentinel.helpers.toJSON
import timber.log.Timber

/**
 * Ingest seam for the per-tx Boltzmann entropy cache (#72, fix
 * arc step 2: the fix).
 *
 * Extracted from the entropy block of
 * TransactionsRepository.fetchFromServer (:286-:340 at
 * d04e6a3); the repository rewires to this class in the wiring
 * commit.
 *
 * The flaw, fixed: the block analyzed each wallet payload row
 * as delivered - a per-pubkey slice - and cached the first
 * slice's answer under the bare txid permanently. Dojo
 * returns one partial tx per watched pubkey, and the payload
 * cannot prove completeness: a (1,1) slice is
 * indistinguishable from a true 1x1 hop, other participants'
 * IOs are unwatched, and even the merged view of a
 * watched-everything tx is short (live exhibit: full tx
 * 2x11, merged wallet view 2x9 - two unwatched outputs). So
 * the number is computed on the FULL tx only:
 *
 *  - slices are deduped to bare txids BEFORE the guard (one
 *    txid, one fetch, one guard check, one row);
 *  - the full tx comes from [fetcher] (ApiService.getTx ->
 *    esplora adapter at the wiring site);
 *  - a failed fetch writes no row - the InsufficientData
 *    convention extended to fetch failure: say nothing,
 *    re-attempted per sync (cheap guard, self-healing);
 *  - the four analysis arms and the guard are verbatim from
 *    the repository block.
 *
 * mergePartialTxs is deliberately NOT used here: the merged
 * wallet view is itself incomplete (the 2x9-of-2x11
 * exhibit), so merging cannot produce honest numbers - the
 * full tx supersedes it. (mergePartialTxs keeps its badbank
 * caller, untouched.)
 *
 * Budget: one fetch per uncached txid per sync. The
 * tx-details screen already pays this fetch class on every
 * open (fetchFee), so the cost is pre-paid on-device; pacing,
 * if ever wanted, belongs to the wiring - the guard
 * re-attempts.
 */
class TxEntropyIngest(
    private val cache: TxEntropyCache,
    private val fetcher: suspend (txid: String) -> Tx?,
    private val service: BoltzmannTxService = BoltzmannTxService(),
) {

    /**
     * Narrow cache seam (UtxoLabelSink pattern): the Room DAO
     * satisfies this by delegation at the wiring site; tests
     * fake it in-memory.
     */
    interface TxEntropyCache {
        suspend fun findByTxid(txid: String): TxEntropy?
        suspend fun insert(entry: TxEntropy)
    }

    data class Result(
        val analyzed: Int,
        val unresolvable: Int,
        val fetchFailed: Int,
    )

    suspend fun ingest(txs: Collection<Tx>, collectionId: String): Result {
        var analyzed = 0
        var unresolvable = 0
        var fetchFailed = 0
        // Dedup slices to bare txids BEFORE the guard: one txid,
        // one fetch, one row (an N-slice txid must not cost N
        // fetches). Insertion order preserved.
        val bareTxids = LinkedHashSet<String>()
        txs.forEach { tx ->
            bareTxids.add(tx.hash.removeSuffix("-$collectionId"))
        }
        bareTxids.forEach { bareTxid ->
            // Per-tx containment (the #48 lesson, re-learned
            // on-device: one unresolvable tx aborted the whole
            // batch under block-level runCatching - 2 rows of
            // 24). One bad tx never starves the rest.
            runCatching {
                if (cache.findByTxid(bareTxid) == null) {
                    // The payload cannot prove completeness -
                    // fetch the full tx and compute on that,
                    // never on a slice.
                    val full = fetcher(bareTxid)
                    if (full == null) {
                        // Fetch failure: say nothing - no row, no
                        // display. Re-attempted on future syncs
                        // (cheap guard, self-healing).
                        fetchFailed++
                        return@runCatching
                    }
                    val entry = when (val analysis = service.analyze(full)) {
                        is BoltzmannTxAnalysis.Honest -> TxEntropy(
                            txid = bareTxid,
                            nbCmbn = analysis.nbCmbn,
                            entropyBits = analysis.entropyBits,
                            linkabilityJson = analysis.linkability.toJSON() ?: "[]",
                            tooComplex = false,
                            computedAt = System.currentTimeMillis(),
                        )
                        BoltzmannTxAnalysis.TooComplex -> TxEntropy(
                            txid = bareTxid,
                            nbCmbn = 0,
                            entropyBits = 0.0,
                            linkabilityJson = "[]",
                            tooComplex = true,
                            computedAt = System.currentTimeMillis(),
                        )
                        BoltzmannTxAnalysis.ZeroEntropy -> TxEntropy(
                            txid = bareTxid,
                            nbCmbn = 1,
                            entropyBits = 0.0,
                            linkabilityJson = "[]",
                            tooComplex = false,
                            computedAt = System.currentTimeMillis(),
                        )
                        // Unresolvable inputs: say nothing - no row,
                        // no display. Re-attempted on future syncs
                        // (cheap guard, self-healing).
                        BoltzmannTxAnalysis.InsufficientData -> {
                            unresolvable++
                            return@runCatching
                        }
                    }
                    cache.insert(entry)
                    analyzed++
                }
            }
                .onFailure { Timber.e(it, "boltzmann entropy ingest failed") }
        }
        return Result(analyzed, unresolvable, fetchFailed)
    }
}
