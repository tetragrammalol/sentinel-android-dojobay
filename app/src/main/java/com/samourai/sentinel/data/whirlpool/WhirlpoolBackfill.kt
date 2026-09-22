package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Tx

/**
 * Flag-gated, idempotent backfill over fetched transactions
 * (issue #6, part 2). Pure JVM. The caller (sync wiring) supplies
 * the in-memory tx list from fetchFromServer — no DB read, so no
 * race with saveTx's async launch and no new DAO surface.
 *
 * The same underlying tx can arrive once per watched pubkey
 * (keepTransactionWithVariousPubkeys); counts are deduped by txid
 * so the reported number is honest, while the writer itself stays
 * idempotent under repeats regardless.
 */
class WhirlpoolBackfill(private val writer: WhirlpoolAutoWriter) {

    data class Result(
        val processed: Int,
        val written: Int,
        val handsOff: Int,
        val unclassified: Int,
    )

    suspend fun run(txs: Collection<Tx>, collectionId: String): Result {
        val seen = HashSet<String>()
        var written = 0
        var handsOff = 0
        var unclassified = 0
        for (tx in txs) {
            val view = WhirlpoolTxAdapter.toView(tx, collectionId)
            if (!seen.add(view.txid)) continue
            when (writer.process(view)) {
                WhirlpoolAutoWriter.Outcome.WRITTEN -> written++
                WhirlpoolAutoWriter.Outcome.HANDS_OFF -> handsOff++
                WhirlpoolAutoWriter.Outcome.UNCLASSIFIED -> unclassified++
            }
        }
        return Result(seen.size, written, handsOff, unclassified)
    }
}
