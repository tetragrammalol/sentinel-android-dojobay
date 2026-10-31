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
 * The loop also stops at n_tx, on an empty or repeated page, and
 * on a failed request. This only cuts off a server that keeps
 * handing back new rows forever.
 */
internal const val TX_HISTORY_MAX_PAGES = 200

internal class TxHistoryFetch(
    val txs: List<Tx>,
    val pagesFetched: Int,
    val pagingFailed: Boolean,
    val hitPageCap: Boolean,
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
 * /wallet already returned [firstPage] (newest page). When [nTx] is
 * larger, read older pages from GET /txs until the total is in hand.
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
    fetchPage: suspend (page: Int, count: Int) -> String?,
): TxHistoryFetch {
    val reported = nTx ?: return TxHistoryFetch(
        txs = firstPage,
        pagesFetched = 1,
        pagingFailed = false,
        hitPageCap = false,
    )
    if (firstPage.size >= reported) {
        return TxHistoryFetch(
            txs = firstPage,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
        )
    }

    val pageSize = if (firstPage.isNotEmpty()) firstPage.size else DOJO_DEFAULT_TX_PAGE
    var nextPage = if (firstPage.isNotEmpty()) 1 else 0
    var target = reported
    var pagesFetched = 1
    var pagingFailed = false
    val merged = ArrayList<Tx>(firstPage.size + pageSize)
    val seen = HashSet<String>(firstPage.size * 2 + 1)
    for (tx in firstPage) {
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
        if (parsed.n_tx > target) target = parsed.n_tx
        nextPage += 1
        val fresh = ArrayList<Tx>()
        for (tx in pageTxs) {
            if (seen.add(tx.hash)) fresh.add(tx)
        }
        // Repeated page, or a page with no rows: Dojo has nothing
        // further. Another request would loop.
        if (fresh.isEmpty()) break
        merged.addAll(fresh)
    }

    val hitPageCap = !pagingFailed && merged.size < target && pagesFetched >= TX_HISTORY_MAX_PAGES
    return TxHistoryFetch(merged, pagesFetched, pagingFailed, hitPageCap)
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
