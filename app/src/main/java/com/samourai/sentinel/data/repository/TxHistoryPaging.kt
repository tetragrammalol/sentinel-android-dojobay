package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.Address
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.TxResponse
import com.samourai.sentinel.helpers.fromJSON
import kotlinx.coroutines.CancellationException

/**
 * Dojo's default `keys.multiaddr.transactions`. /wallet calls
 * loadTransactions(0, null), so this is the page size when the
 * wallet body itself came back empty and the count has to be chosen
 * here. A non-empty wallet page uses that page's length instead,
 * which is the server's actual limit whenever n_tx is larger.
 */
internal const val DOJO_DEFAULT_TX_PAGE = 50

/**
 * Extra pages on top of the wallet page. 200 * 50 is 10_000 txs.
 * The loop also stops at n_tx, on an empty or repeated page, on a
 * page that is already stored, and on the row cap. This only cuts
 * off a server that keeps handing back new rows forever.
 */
internal const val TX_HISTORY_MAX_PAGES = 200

/**
 * Absolute cap on rows held for one pub. The page budget above,
 * times Dojo's default page size.
 */
internal const val TX_HISTORY_MAX_ROWS = TX_HISTORY_MAX_PAGES * DOJO_DEFAULT_TX_PAGE

internal class TxHistoryFetch(
    val txs: List<Tx>,
    val pagesFetched: Int,
    val pagingFailed: Boolean,
    val hitPageCap: Boolean,
    val stoppedAtKnownHistory: Boolean = false,
)

/**
 * n_tx for the pub this /wallet call was made with. Dojo strips the
 * top-level n_tx from /wallet; the per-address figure is the total.
 * One active key returns one address row, echoed in the form Dojo
 * stored, which is not always byte-identical to the query.
 */
internal fun nTxForActivePub(addresses: List<Address>?, pubKey: String): Int? {
    if (addresses.isNullOrEmpty()) return null
    val matched = addresses.firstOrNull { it.address == pubKey }
    if (matched != null) return matched.nTx
    if (addresses.size == 1) return addresses[0].nTx
    return null
}

/**
 * Bare txid of a stored hash. Room's key is "txid-collectionId",
 * and "txid-collectionId-N" when the same tx is stored once per
 * account. The txid itself has no '-'.
 */
internal fun bareStoredTxid(hash: String): String = hash.substringBefore('-')

/** Bare txid, compared case-insensitively. */
internal fun normalizedTxid(hash: String): String = bareStoredTxid(hash).lowercase()

/**
 * /wallet already returned [firstPage] (newest page). When [nTx] is
 * larger, read older pages from GET /txs until the total is in hand.
 *
 * [nTx] is frozen at the wallet page's figure, and also capped at
 * [TX_HISTORY_MAX_ROWS]. A later page that reports a higher n_tx
 * does not extend the walk.
 *
 * [knownTxids] are bare txids already stored for this pub. Paging
 * stops when a fetched page contains none that are not already
 * stored: that page is the overlap with known history. An empty
 * known set still walks until [nTx], the cap, or an empty page.
 * The first sync after enabling auto-labels is that walk.
 *
 * [fetchPage] runs only when another page is required. A throw, a
 * null body, or a body that is not a tx page stops the walk and
 * keeps every tx already collected, including [firstPage].
 * Cancellation still propagates.
 *
 * Page 0 of /txs is the same window as /wallet when [count] equals
 * that page's length (Dojo offset is page * count). The walk
 * therefore starts at page 1 whenever [firstPage] has rows.
 */
internal suspend fun fetchRemainingTxHistory(
    firstPage: List<Tx>,
    nTx: Int?,
    knownTxids: Set<String> = emptySet(),
    fetchPage: suspend (page: Int, count: Int) -> String?,
): TxHistoryFetch {
    val reported = nTx ?: return TxHistoryFetch(
        txs = firstPage,
        pagesFetched = 1,
        pagingFailed = false,
        hitPageCap = false,
    )
    if (firstPage.size >= reported && firstPage.size <= TX_HISTORY_MAX_ROWS) {
        return TxHistoryFetch(
            txs = firstPage,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
        )
    }
    if (firstPage.size >= TX_HISTORY_MAX_ROWS) {
        val kept = takeCapped(firstPage)
        return TxHistoryFetch(
            txs = kept,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = reported > kept.size,
        )
    }

    val known = normalizeKnown(knownTxids)
    val target = minOf(reported, TX_HISTORY_MAX_ROWS)
    if (reachedKnownHistory(firstPage, known)) {
        return TxHistoryFetch(
            txs = firstPage,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
            stoppedAtKnownHistory = true,
        )
    }

    val pageSize = if (firstPage.isNotEmpty()) firstPage.size else DOJO_DEFAULT_TX_PAGE
    var nextPage = if (firstPage.isNotEmpty()) 1 else 0
    var pagesFetched = 1
    var pagingFailed = false
    var stoppedAtKnown = false
    val merged = ArrayList<Tx>(minOf(firstPage.size + pageSize, TX_HISTORY_MAX_ROWS))
    val seen = HashSet<String>(firstPage.size * 2 + 1)
    for (tx in firstPage) {
        if (merged.size >= TX_HISTORY_MAX_ROWS) break
        if (seen.add(tx.hash)) merged.add(tx)
    }

    while (merged.size < target && pagesFetched < TX_HISTORY_MAX_PAGES) {
        val lastPage = (((target.toLong() + pageSize - 1) / pageSize) - 1).toInt()
        if (nextPage > lastPage) break

        val body = try {
            fetchPage(nextPage, pageSize)
        } catch (e: Throwable) {
            if (e is CancellationException || e is Error) throw e
            pagingFailed = true
            break
        }
        if (body == null) {
            pagingFailed = true
            break
        }
        val parsed = fromJSON<TxResponse>(body)
        val pageTxs = parsed?.txs
        if (parsed == null || pageTxs == null) {
            pagingFailed = true
            break
        }
        pagesFetched += 1
        // Later n_tx is ignored. target stays at the first reported
        // total, already capped.
        nextPage += 1
        val fresh = ArrayList<Tx>()
        for (tx in pageTxs) {
            if (merged.size >= TX_HISTORY_MAX_ROWS) break
            if (seen.add(tx.hash)) {
                fresh.add(tx)
                merged.add(tx)
            }
        }
        // A page whose txids are all already stored is the overlap
        // with known history. Nothing older needs another request.
        if (reachedKnownHistory(pageTxs, known)) {
            stoppedAtKnown = true
            break
        }
        // Repeated page, or a page with no rows: Dojo has nothing
        // further. Another request would loop. A repeat while this
        // pub already has stored rows is still an overlap: keep the
        // tail. An empty page is the server ending the account.
        if (fresh.isEmpty()) {
            if (pageTxs.isNotEmpty() && known.isNotEmpty()) stoppedAtKnown = true
            break
        }
    }

    val serverHasMore = reported > merged.size
    val hitPageCap = !pagingFailed && !stoppedAtKnown && serverHasMore &&
        (pagesFetched >= TX_HISTORY_MAX_PAGES || merged.size >= TX_HISTORY_MAX_ROWS)
    return TxHistoryFetch(merged, pagesFetched, pagingFailed, hitPageCap, stoppedAtKnown)
}

/**
 * Txids that should stop the walk. Empty when this pub does not yet
 * hold its history: the first sync after auto-labels is turned on
 * only has the newest page stored, and a failed walk can stop short
 * of [nTx]. Either one still pages until [nTx] or the row cap.
 * Once the stored rows plus any new txids on [firstPage] cover the
 * capped total, the overlap stop is safe.
 */
internal fun knownTxidsForPaging(
    storedTxids: Set<String>,
    firstPage: List<Tx>,
    nTx: Int?,
): Set<String> {
    if (storedTxids.isEmpty() || nTx == null) return emptySet()
    var newOnPage = 0
    for (tx in firstPage) {
        if (normalizedTxid(tx.hash) !in storedTxids) newOnPage += 1
    }
    val covered = storedTxids.size + newOnPage >= minOf(nTx, TX_HISTORY_MAX_ROWS)
    return if (covered) storedTxids else emptySet()
}

/**
 * True when this sync must not drop stored rows it did not refetch.
 * A known-history stop, a failed page, or the cap each leave a tail
 * on disk. A finished walk, including a genuinely empty wallet, does
 * not: the fetched set is the whole account.
 */
internal fun shouldPreserveUnfetched(fetch: TxHistoryFetch): Boolean =
    fetch.stoppedAtKnownHistory || fetch.pagingFailed || fetch.hitPageCap

/**
 * Fetched rows win. Confirmed stored rows whose bare txid was not in
 * the fetch are appended, with their stored hash, so the collection
 * reconcile still sees them. An unconfirmed stored row (no block
 * height, or height 0) is left out: it may have been replaced or
 * dropped, and the next sync would otherwise keep it forever.
 */
internal fun mergeUnfetchedTxs(fetched: List<Tx>, stored: List<Tx>): List<Tx> {
    if (stored.isEmpty()) return fetched
    val fetchedBare = HashSet<String>(fetched.size * 2 + 1)
    for (tx in fetched) fetchedBare.add(normalizedTxid(tx.hash))
    val out = ArrayList<Tx>(fetched.size + stored.size)
    out.addAll(fetched)
    for (tx in stored) {
        val height = tx.block_height
        if (height == null || height <= 0L) continue
        if (normalizedTxid(tx.hash) !in fetchedBare) out.add(tx)
    }
    return out
}

/** Fetched rows, plus the unfetched confirmed tail when [shouldPreserveUnfetched]. */
internal fun txsForReconcile(
    fetched: List<Tx>,
    stored: List<Tx>,
    fetch: TxHistoryFetch,
): List<Tx> {
    if (!shouldPreserveUnfetched(fetch)) return fetched
    return mergeUnfetchedTxs(fetched, stored)
}

/**
 * Stored-row read for one pub when auto-labels are off. No network.
 *
 * [ToggleOffStoredRead.None]: do not read. n_tx 0 is an empty wallet,
 * so the fetched page is reconciled and a fee-table round still
 * clears. A page that already covers a positive n_tx, once a count
 * shows nothing past it, is the same.
 * [ToggleOffStoredRead.Rows]: n_tx is larger than the wallet page, or
 * a count already showed rows past the page.
 * [ToggleOffStoredRead.Count]: n_tx does not say the page is short.
 * Read a count, then the rows only when that count is past the page.
 */
internal enum class ToggleOffStoredRead { None, Count, Rows }

internal fun toggleOffStoredRead(
    nTx: Int?,
    walletPageSize: Int,
    storedCount: Int?,
): ToggleOffStoredRead {
    if (nTx == 0) return ToggleOffStoredRead.None
    if (nTx != null && nTx > walletPageSize) return ToggleOffStoredRead.Rows
    if (storedCount == null) return ToggleOffStoredRead.Count
    return if (storedCount > walletPageSize) ToggleOffStoredRead.Rows else ToggleOffStoredRead.None
}

/**
 * Toggle off fetched only the wallet page. Confirmed stored rows that
 * page did not include stay, via [mergeUnfetchedTxs] (the same merge
 * [txsForReconcile] uses, so an unconfirmed miss is dropped). n_tx 0
 * does not read and does not merge, so an empty page still clears.
 * Confirmations on the kept rows are recomputed from the tip.
 */
internal suspend fun txsForToggleOff(
    fetched: List<Tx>,
    walletPageSize: Int,
    nTx: Int?,
    latestBlockHeight: Long,
    readCount: suspend () -> Int,
    readRows: suspend () -> List<Tx>,
): List<Tx> {
    val stored = when (toggleOffStoredRead(nTx, walletPageSize, storedCount = null)) {
        ToggleOffStoredRead.None -> return fetched
        ToggleOffStoredRead.Rows -> readRows()
        ToggleOffStoredRead.Count -> {
            val count = readCount()
            if (toggleOffStoredRead(nTx, walletPageSize, count) != ToggleOffStoredRead.Rows) {
                return fetched
            }
            readRows()
        }
    }
    return refreshKeptConfirmations(
        mergeUnfetchedTxs(fetched, stored),
        fetched,
        latestBlockHeight,
    )
}

/**
 * Fetched rows already carry this round's confirmations. Rows kept
 * from storage did not: recompute them from the tip the same way the
 * fetched page was stamped.
 */
internal fun refreshKeptConfirmations(
    merged: List<Tx>,
    fetched: List<Tx>,
    latestBlockHeight: Long,
): List<Tx> {
    val fetchedBare = HashSet<String>(fetched.size * 2 + 1)
    for (tx in fetched) fetchedBare.add(normalizedTxid(tx.hash))
    for (tx in merged) {
        if (normalizedTxid(tx.hash) in fetchedBare) continue
        val txBlockHeight = tx.block_height ?: 0
        tx.confirmations =
            if (latestBlockHeight > 0L && txBlockHeight > 0L)
                (latestBlockHeight.minus(txBlockHeight)) + 1
            else 0
    }
    return merged
}

internal fun txHistoryLogLine(
    pubIndex: Int,
    nTx: Int?,
    fetched: Int,
    pagesFetched: Int,
    pagingFailed: Boolean,
    hitPageCap: Boolean,
): String {
    val note = when {
        pagingFailed -> " paging=failed"
        hitPageCap -> " paging=capped"
        else -> ""
    }
    val total = nTx?.toString() ?: "none"
    return "fetchFromServer: pub=$pubIndex n_tx=$total fetched=$fetched pages=$pagesFetched$note"
}

private fun normalizeKnown(knownTxids: Set<String>): Set<String> {
    if (knownTxids.isEmpty()) return emptySet()
    val known = HashSet<String>(knownTxids.size * 2 + 1)
    for (id in knownTxids) known.add(normalizedTxid(id))
    return known
}

private fun reachedKnownHistory(page: List<Tx>, known: Set<String>): Boolean {
    if (known.isEmpty() || page.isEmpty()) return false
    for (tx in page) {
        if (normalizedTxid(tx.hash) !in known) return false
    }
    return true
}

private fun takeCapped(page: List<Tx>): List<Tx> {
    if (page.size <= TX_HISTORY_MAX_ROWS) return page
    val seen = HashSet<String>(TX_HISTORY_MAX_ROWS * 2 + 1)
    val out = ArrayList<Tx>(TX_HISTORY_MAX_ROWS)
    for (tx in page) {
        if (out.size >= TX_HISTORY_MAX_ROWS) break
        if (seen.add(tx.hash)) out.add(tx)
    }
    return out
}
