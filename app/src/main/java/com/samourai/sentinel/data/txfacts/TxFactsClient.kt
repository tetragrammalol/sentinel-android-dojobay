package com.samourai.sentinel.data.txfacts

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.tor.SentinelTorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * #119 Route A fetcher. Deliberately NOT ApiService.buildClient: that
 * client appends the Dojo access token (?at=...) to every request it
 * sees and installs a trust-all hostname verifier - pointing it at a
 * third party would leak the Dojo token and break TLS. This client is
 * bare: Tor SOCKS proxy, bounded timeouts, standard verification,
 * zero interceptors (asserted by test). Pattern precedent:
 * CommunityDojoRepository's directory fetcher.
 *
 * A fallback rung exists, so a dead/wrong-dialect self explorer must
 * not hang the tap: 30s bounds everywhere (the Dojo client's
 * callTimeout(0) is a no-fallback luxury this client doesn't have).
 */
object TxFactsClient {

    sealed class FetchResult {
        data class Ok(
            val facts: MempoolTxAdapter.LiveFacts,
            val fromSelf: Boolean,
        ) : FetchResult()

        /** Network/timeout class - connection never yielded a usable HTTP answer. */
        data class Unreachable(val detail: String) : FetchResult()

        /** Reached, but not a compatible esplora-shaped tx (e.g. btc-rpc-explorer). */
        data class Incompatible(val detail: String) : FetchResult()
    }

    internal fun buildClient(proxy: Proxy): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    suspend fun fetch(url: String, fromSelf: Boolean, tipHeight: Long?): FetchResult =
        withContext(Dispatchers.IO) {
            val proxy = SentinelTorManager.getProxy()
                ?: return@withContext FetchResult.Unreachable("Tor proxy unavailable")
            val client = buildClient(proxy)
            try {
                val response = client.newCall(Request.Builder().url(url).build()).await()
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    FetchResult.Incompatible("HTTP ${response.code}")
                } else {
                    MempoolTxAdapter.fromJson(body, tipHeight)
                        ?.let { FetchResult.Ok(it, fromSelf) }
                        ?: FetchResult.Incompatible("unparseable body")
                }
            } catch (e: Exception) {
                FetchResult.Unreachable(e.message ?: e.toString())
            }
        }
}
