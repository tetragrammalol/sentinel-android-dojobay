package com.samourai.sentinel.data.repository

import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.api.dojoTxsQuery
import com.samourai.sentinel.data.Address
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.TxResponse
import com.samourai.sentinel.helpers.fromJSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * #197. /wallet is Dojo's newest page. These cover the walk over
 * /txs: when it must not run, when it collects the rest, and when a
 * bad page leaves the rows already in hand. Hashes are synthetic.
 * Amounts are omitted; the page shape is what is under test.
 */
class TxHistoryPagingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a page that already covers n_tx makes no further request`() {
        val cases = listOf(
            0 to 0,
            1 to 1,
            49 to 49,
            50 to 50,
            50 to 40,
        )
        for ((size, nTx) in cases) {
            var calls = 0
            val first = List(size) { tx("w-$it") }
            val result = runBlocking {
                fetchRemainingTxHistory(first, nTx) { _, _ ->
                    calls += 1
                    error("no extra page")
                }
            }
            assertEquals("size=$size n_tx=$nTx", 0, calls)
            assertSame(first, result.txs)
            assertEquals(1, result.pagesFetched)
            assertFalse(result.pagingFailed)
            assertFalse(result.hitPageCap)
        }
    }

    @Test
    fun `a missing n_tx makes no further request`() {
        var calls = 0
        val first = List(50) { tx("w-$it") }
        val result = runBlocking {
            fetchRemainingTxHistory(first, null) { _, _ ->
                calls += 1
                error("no extra page")
            }
        }
        assertEquals(0, calls)
        assertSame(first, result.txs)
    }

    @Test
    fun `one tx past the wallet page is fetched with that page's count`() = runBlocking {
        val first = List(50) { tx("w-%02d".format(it)) }
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(first, 51) { page, count ->
            calls += page to count
            pageJson(nTx = 51, page = page, count = count, hashes = listOf("older-1"))
        }
        assertEquals(listOf(1 to 50), calls)
        assertEquals(51, result.txs.size)
        assertEquals("w-00", result.txs.first().hash)
        assertEquals("older-1", result.txs.last().hash)
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `later pages are collected until n_tx and duplicates are skipped`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 5) { page, count ->
            calls += page to count
            val hashes = when (page) {
                1 -> listOf("b", "c")
                2 -> listOf("d")
                else -> error("page $page")
            }
            pageJson(nTx = 5, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1 to 2, 2 to 2), calls)
        assertEquals(listOf("a", "b", "c", "d"), result.txs.map { it.hash })
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a page that raises n_tx keeps the walk going`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 3) { page, count ->
            calls += page
            val hashes = if (page == 1) listOf("c", "d") else listOf("e")
            // Page 1 reports a higher total than the wallet page did.
            pageJson(nTx = 5, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.txs.map { it.hash })
        assertEquals(3, result.pagesFetched)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a repeated page stops the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 8) { page, count ->
            calls += page
            pageJson(nTx = 8, page = page, count = count, hashes = listOf("b"))
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `an empty page stops the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a")), 6) { page, count ->
            calls += page
            pageJson(nTx = 6, page = page, count = count, hashes = emptyList())
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("a"), result.txs.map { it.hash })
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `an empty wallet page starts at txs page 0`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(emptyList(), 1) { page, count ->
            calls += page to count
            pageJson(nTx = 1, page = page, count = count, hashes = listOf("only"))
        }
        assertEquals(listOf(0 to DOJO_DEFAULT_TX_PAGE), calls)
        assertEquals(listOf("only"), result.txs.map { it.hash })
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a failed later page keeps the wallet page and the pages already read`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a")), 5) { page, count ->
            calls += page
            if (page == 1) {
                pageJson(nTx = 5, page = page, count = count, hashes = listOf("b"))
            } else {
                throw IOException("timeout")
            }
        }
        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(2, result.pagesFetched)
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `a null page body keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 4) { _, _ -> null }
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun `an error object keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 4) { _, _ ->
            """{"status":"error","error":"denied"}"""
        }
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun `api not configured during paging keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a")), 3) { _, _ ->
            throw ApiNotConfigured()
        }
        assertEquals(listOf("a"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
    }

    @Test
    fun `cancellation leaves the page loop`() {
        try {
            runBlocking {
                fetchRemainingTxHistory(listOf(tx("a")), 3) { _, _ ->
                    throw CancellationException("stop")
                }
            }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun `the page cap stops a server that never runs out of rows`() = runBlocking {
        var calls = 0
        val result = fetchRemainingTxHistory(listOf(tx("w")), 10_000) { page, count ->
            calls += 1
            assertEquals(calls, page)
            assertEquals(1, count)
            pageJson(nTx = 10_000, page = page, count = count, hashes = listOf("x$calls"))
        }
        assertEquals(TX_HISTORY_MAX_PAGES - 1, calls)
        assertEquals(TX_HISTORY_MAX_PAGES, result.txs.size)
        assertEquals("w", result.txs.first().hash)
        assertEquals("x${TX_HISTORY_MAX_PAGES - 1}", result.txs.last().hash)
        assertTrue(result.hitPageCap)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `dojo txs json parses without a top level block height`() {
        val parsed = fromJSON<TxResponse>(pageJson(51, 1, 50, listOf("older-1")))
        assertEquals(51, parsed!!.n_tx)
        assertEquals(1, parsed.page)
        assertEquals(50, parsed.n_tx_page)
        assertEquals(listOf("older-1"), parsed.txs!!.map { it.hash })
        assertNull(parsed.block_height)
    }

    @Test
    fun `dojo json pages are read over http`() = runBlocking {
        server.enqueue(MockResponse().setBody(pageJson(3, 1, 1, listOf("p1"))))
        server.enqueue(MockResponse().setBody(pageJson(3, 2, 1, listOf("p2"))))
        val client = httpClient()
        try {
            val seen = mutableListOf<String>()
            val result = fetchRemainingTxHistory(listOf(tx("p0")), 3) { page, count ->
                val path = dojoTxsQuery("acct", page, count)
                seen += path
                val response = client.newCall(
                    Request.Builder().url(serverBase() + path).build()
                ).execute()
                response.use { if (it.isSuccessful) it.body?.string() else null }
            }
            assertEquals(
                listOf(
                    "/txs?active=acct&page=1&count=1",
                    "/txs?active=acct&page=2&count=1",
                ),
                seen,
            )
            assertEquals(seen, listOf(server.takeRequest().path, server.takeRequest().path))
            assertEquals(2, server.requestCount)
            assertEquals(listOf("p0", "p1", "p2"), result.txs.map { it.hash })
            assertFalse(result.pagingFailed)
            assertEquals(3, result.pagesFetched)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `an http error keeps the wallet page`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val client = httpClient()
        try {
            val result = fetchRemainingTxHistory(listOf(tx("p0")), 4) { page, count ->
                val response = client.newCall(
                    Request.Builder().url(serverBase() + dojoTxsQuery("acct", page, count)).build()
                ).execute()
                response.use { if (it.isSuccessful) it.body?.string() else null }
            }
            assertEquals("/txs?active=acct&page=1&count=1", server.takeRequest().path)
            assertEquals(1, server.requestCount)
            assertEquals(listOf("p0"), result.txs.map { it.hash })
            assertTrue(result.pagingFailed)
            assertEquals(1, result.pagesFetched)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `n_tx comes from the active pub`() {
        val rows = listOf(
            Address(address = "other", nTx = 99),
            Address(address = "acct", nTx = 12),
        )
        assertEquals(12, nTxForActivePub(rows, "acct"))
        assertEquals(7, nTxForActivePub(listOf(Address(address = "echoed", nTx = 7)), "acct"))
        assertNull(nTxForActivePub(rows, "missing"))
        assertNull(nTxForActivePub(emptyList(), "acct"))
        assertNull(nTxForActivePub(null, "acct"))
        assertNull(nTxForActivePub(listOf(Address(address = "acct", nTx = null)), "acct"))
    }

    @Test
    fun `the sync log line is n_tx fetched and pages`() {
        assertEquals(
            "fetchFromServer: pub=2 n_tx=73 fetched=73 pages=2",
            txHistoryLogLine(2, 73, 73, 2, pagingFailed = false, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=0 n_tx=none fetched=4 pages=1",
            txHistoryLogLine(0, null, 4, 1, pagingFailed = false, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=1 n_tx=80 fetched=50 pages=1 paging=failed",
            txHistoryLogLine(1, 80, 50, 1, pagingFailed = true, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=1 n_tx=10000 fetched=200 pages=200 paging=capped",
            txHistoryLogLine(1, 10_000, 200, 200, pagingFailed = false, hitPageCap = true),
        )
    }

    @Test
    fun `the txs query is active page and count`() {
        assertEquals("/txs?active=acct&page=0&count=50", dojoTxsQuery("acct", 0, 50))
        assertEquals("/txs?active=acct&page=2&count=50", dojoTxsQuery("acct", 2, 50))
    }

    @Test
    fun `fetchFromServer feeds paged txs through the existing mangle path`() {
        val repo = source(
            "src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val api = source(
            "src/main/java/com/samourai/sentinel/api/ApiService.kt",
            "app/src/main/java/com/samourai/sentinel/api/ApiService.kt",
        )
        val start = repo.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = repo.indexOf("private fun keepTransactionWithVariousPubkeys")
        assertTrue(start >= 0 && end > start)
        val fn = repo.substring(start, end)
        assertTrue(fn.contains("fetchRemainingTxHistory("))
        assertTrue(fn.contains("apiService.getTxs("))
        assertTrue(fn.contains("txHistoryLogLine("))
        assertTrue(fn.contains("history.txs.map"))
        assertFalse(fn.contains("response.txs.map"))
        val mapAt = fn.indexOf("history.txs.map")
        val saveAt = fn.indexOf("newTransactions.addAll(items)")
        assertTrue(mapAt >= 0 && saveAt > mapAt)
        assertTrue(api.contains("suspend fun getTxs("))
        assertTrue(api.contains("dojoTxsQuery(pubKey, page, count)"))
        val utxoAt = repo.indexOf("suspend fun fetchUTXOS")
        assertTrue(utxoAt > end)
        val utxo = repo.substring(utxoAt)
        assertFalse(utxo.contains("getTxs("))
        assertFalse(utxo.contains("fetchRemainingTxHistory"))
    }

    private fun tx(hash: String) = Tx(
        hash = hash,
        time = 1L,
        version = 1,
        locktime = 0,
        result = null,
        inputs = emptyList(),
        out = emptyList(),
        block_height = null,
    )

    /** Dojo GET /txs body. No block_height, no amounts. */
    private fun pageJson(nTx: Int, page: Int, count: Int, hashes: List<String>): String {
        val txs = hashes.joinToString(",") { hash ->
            """{"hash":"$hash","time":1,"version":1,"locktime":0,"inputs":[],"out":[]}"""
        }
        return """{"n_tx":$nTx,"page":$page,"n_tx_page":$count,"txs":[$txs]}"""
    }

    private fun serverBase(): String = server.url("/").toString().removeSuffix("/")

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
