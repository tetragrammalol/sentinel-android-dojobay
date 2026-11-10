package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.util.OkHttpLogRedaction
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Reachability of the bound explorer. Tor only: [explorerProbeRoute]
 * runs before any client exists, and the client is built with the
 * live SOCKS proxy plus [TlsPolicy]. Timeouts match the onion branch
 * of ApiService.buildClient (180s connect/read/write, no call ceiling).
 * A tip-height GET is enough; the explorer root counts when that path
 * is not served. Failure, including a blocked direct attempt, is false.
 */
object ExplorerHealthProbe {

    fun probe(baseUrl: String): Boolean {
        val proxy = SentinelTorManager.getProxy()
        if (explorerProbeRoute(SentinelTorManager.isTorReady(), proxy != null) != ExplorerProbeRoute.TOR) {
            return false
        }
        val liveProxy = proxy ?: return false
        val client = torClient(liveProxy)
        val urls = ExplorerBinding.healthRequestUrls(baseUrl)
        if (urls.isEmpty()) return false
        for (url in urls) {
            if (fetchOk(client, url)) return true
        }
        return false
    }

    private fun torClient(proxy: Proxy): OkHttpClient {
        OkHttpLogRedaction.install()
        val builder = OkHttpClient.Builder()
            .proxy(proxy)
        TlsPolicy.install(builder)
        builder.connectTimeout(180, TimeUnit.SECONDS)
        builder.readTimeout(180, TimeUnit.SECONDS)
        builder.writeTimeout(180, TimeUnit.SECONDS)
        builder.callTimeout(0, TimeUnit.MILLISECONDS)
        return builder.build()
    }

    private fun fetchOk(client: OkHttpClient, url: String): Boolean = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.isSuccessful
        }
    } catch (_: Exception) {
        false
    }
}
