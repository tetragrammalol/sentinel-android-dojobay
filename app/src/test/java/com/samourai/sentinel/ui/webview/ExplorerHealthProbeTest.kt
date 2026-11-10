package com.samourai.sentinel.ui.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The client is built from the proxy read at call time. A scheduled
 * endpoint that no longer matches the live SOCKS port does not open
 * a request. Timeouts are the bounded ones, not an unbounded call.
 */
class ExplorerHealthProbeTest {

    private val onionHost = "abcd" + "e".repeat(48) + "wxyz"
    private val base = "http://$onionHost.onion"
    private val proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved("127.0.0.1", 9050),
    )

    @Test
    fun `the tor client is bounded and does not retry`() {
        val client = ExplorerHealthProbe.client(proxy)
        try {
            assertEquals(proxy, client.proxy)
            assertEquals(45_000, client.callTimeoutMillis)
            assertEquals(30_000, client.connectTimeoutMillis)
            assertEquals(20_000, client.readTimeoutMillis)
            assertEquals(20_000, client.writeTimeoutMillis)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(60_000L, ExplorerProbeCoordinator.PROBE_TIMEOUT_MS)
            assertEquals(45L, ExplorerHealthProbe.CALL_TIMEOUT_SEC)
            assertEquals(30L, ExplorerHealthProbe.CONNECT_TIMEOUT_SEC)
            assertEquals(20L, ExplorerHealthProbe.READ_TIMEOUT_SEC)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `a stale scheduled port is not used and the live port is`() = runBlocking {
        var fetches = 0
        var seen: Proxy? = null
        val stale = ExplorerHealthProbe.runProbe(
            baseUrl = base,
            endpointAtSchedule = "127.0.0.1:1111",
            torReady = { true },
            currentProxy = { proxy },
            currentEndpoint = { "127.0.0.1:2222" },
            fetchUrl = { _, _ ->
                fetches += 1
                UrlFetch(true, "200")
            },
        )
        assertEquals(ProbeResult.NotReady, stale)
        assertEquals(0, fetches)

        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += "${tag.orEmpty()} $message"
            }
        }
        Timber.plant(tree)
        try {
            val live = ExplorerHealthProbe.runProbe(
                baseUrl = base,
                endpointAtSchedule = "127.0.0.1:2222",
                torReady = { true },
                currentProxy = { proxy },
                currentEndpoint = { "127.0.0.1:2222" },
                fetchUrl = { client, url ->
                    fetches += 1
                    seen = client.proxy
                    if (url.endsWith("/api/blocks/tip/height")) {
                        UrlFetch(false, "404")
                    } else {
                        UrlFetch(true, "200")
                    }
                },
            )
            assertEquals(ProbeResult.Reachable, live)
            assertEquals(2, fetches)
            assertEquals(proxy, seen)
            val text = lines.joinToString("\n")
            assertTrue(text.contains("start host="))
            assertTrue(text.contains("socks=127.0.0.1:2222"))
            assertTrue(text.contains("result=404"))
            assertTrue(text.contains("result=200"))
            assertTrue(text.contains("path=/api/blocks/tip/height"))
            assertFalse(text.contains(onionHost))
            assertFalse(text.contains("supersecret"))
            assertTrue(lines.all { it.startsWith(ExplorerProbeLog.TAG) })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `tor that is not ready does not open a client`() = runBlocking {
        var opened = 0
        val result = ExplorerHealthProbe.runProbe(
            baseUrl = base,
            endpointAtSchedule = "127.0.0.1:9050",
            torReady = { false },
            currentProxy = { proxy },
            currentEndpoint = { "127.0.0.1:9050" },
            openClient = {
                opened += 1
                throw AssertionError("client built before Tor was ready")
            },
        )
        assertEquals(ProbeResult.NotReady, result)
        assertEquals(0, opened)
    }

    @Test
    fun `a 2xx tip height is success and the call can be cancelled`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("800000"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {})
            .build()
        try {
            val ok = ExplorerHealthProbe.fetch(
                client,
                server.url("/api/blocks/tip/height").toString(),
            )
            assertTrue(ok.successful)
            assertEquals("200", ok.label)

            val started = CompletableDeferred<Unit>()
            val hanging = client.newBuilder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    started.complete(Unit)
                }
            }).build()
            val job = launch(Dispatchers.IO) {
                ExplorerHealthProbe.fetch(hanging, server.url("/hold").toString())
            }
            withTimeout(5_000) { started.await() }
            val joinStarted = System.nanoTime()
            job.cancelAndJoin()
            val joinMs = (System.nanoTime() - joinStarted) / 1_000_000L
            assertTrue("cancel returned in ${joinMs}ms", joinMs < 2_000)
            assertTrue(job.isCancelled)
            hanging.dispatcher.executorService.shutdown()
            hanging.connectionPool.evictAll()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun `log lines keep the short onion and drop the query`() {
        val nasty = "http://user:secret@$onionHost.onion/api/blocks/tip/height?at=supersecret"
        val line = ExplorerProbeLog.urlLine(nasty, "200", 12)
        assertTrue(line.contains("result=200"))
        assertTrue(line.contains("elapsedMs=12"))
        assertTrue(line.contains("path=/api/blocks/tip/height"))
        assertTrue(line.contains(ExplorerBinding.shortenOnion(nasty)!!))
        assertFalse(line.contains(onionHost))
        assertFalse(line.contains("supersecret"))
        assertFalse(line.contains("secret@"))
        assertEquals("none", ExplorerProbeLog.socksLabel("none"))
        assertEquals("127.0.0.1:9050", ExplorerProbeLog.socksLabel("127.0.0.1:9050"))
    }
}
