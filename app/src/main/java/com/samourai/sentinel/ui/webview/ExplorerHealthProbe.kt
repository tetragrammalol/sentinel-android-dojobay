package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.util.OkHttpLogRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reachability of one bound explorer. Tor only: the route is decided
 * before any client exists, and the client is built from the proxy
 * [SentinelTorManager.getProxy] returns at call time. A previous
 * session's port is not reused. The call is cancellable, so a timeout
 * or a newer Tor session closes the socket instead of leaving it
 * blocked on a dead SOCKS port.
 *
 * Per call: 45s overall, 30s connect, 20s read and write, no connection
 * retry. The coordinator wraps the whole probe in a 60s budget. Success
 * is any 2xx from the tip-height path or, if that path is not served,
 * the explorer root.
 */
object ExplorerHealthProbe {
    const val CALL_TIMEOUT_SEC = 45L
    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 20L
    const val WRITE_TIMEOUT_SEC = 20L

    suspend fun probe(baseUrl: String, endpointAtSchedule: String): ProbeResult =
        runProbe(baseUrl, endpointAtSchedule)

    internal suspend fun runProbe(
        baseUrl: String,
        endpointAtSchedule: String,
        torReady: () -> Boolean = { SentinelTorManager.isTorReady() },
        currentProxy: () -> Proxy? = { SentinelTorManager.getProxy() },
        currentEndpoint: () -> String? = { SentinelTorManager.socksEndpoint() },
        openClient: (Proxy) -> OkHttpClient = { client(it) },
        fetchUrl: suspend (OkHttpClient, String) -> UrlFetch = { http, url -> fetch(http, url) },
    ): ProbeResult {
        val proxy = currentProxy()
        if (explorerProbeRoute(torReady(), proxy != null) != ExplorerProbeRoute.TOR) {
            return ProbeResult.NotReady
        }
        val liveProxy = proxy ?: return ProbeResult.NotReady
        val endpoint = currentEndpoint()
        if (endpoint == null || endpoint != endpointAtSchedule) {
            return ProbeResult.NotReady
        }
        ExplorerProbeLog.start(baseUrl, endpoint)
        val urls = ExplorerBinding.healthRequestUrls(baseUrl)
        if (urls.isEmpty()) return ProbeResult.Unreachable
        val http = openClient(liveProxy)
        try {
            for (url in urls) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                if (currentEndpoint() != endpoint || !torReady()) return ProbeResult.NotReady
                val started = System.nanoTime()
                val fetched = fetchUrl(http, url)
                ExplorerProbeLog.url(url, fetched.label, elapsedMs(started))
                if (fetched.successful) return ProbeResult.Reachable
            }
            return ProbeResult.Unreachable
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    fun client(proxy: Proxy): OkHttpClient {
        OkHttpLogRedaction.install()
        val builder = OkHttpClient.Builder()
            .proxy(proxy)
            .retryOnConnectionFailure(false)
        TlsPolicy.install(builder)
        builder.connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        return builder.build()
    }

    internal suspend fun fetch(client: OkHttpClient, url: String): UrlFetch {
        val call = try {
            client.newCall(Request.Builder().url(url).build())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return UrlFetch(successful = false, label = e.javaClass.simpleName)
        }
        return try {
            call.executeCancellable().use { response ->
                UrlFetch(successful = response.isSuccessful, label = response.code.toString())
            }
        } catch (c: CancellationException) {
            call.cancel()
            throw c
        } catch (e: Exception) {
            UrlFetch(successful = false, label = e.javaClass.simpleName)
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs).coerceAtLeast(0L)) / 1_000_000L
}

enum class ProbeResult {
    Reachable,
    Unreachable,
    NotReady,
}

internal data class UrlFetch(
    val successful: Boolean,
    val label: String,
)

internal suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ ->
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        cancel()
    }
}
