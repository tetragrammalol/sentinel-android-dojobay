package com.samourai.sentinel.ui.webview

import timber.log.Timber
import java.net.URI

/**
 * Logcat for the block-explorer health check. Tag [TAG] so a device
 * capture is `adb logcat -s ExplorerProbe`.
 *
 * Lines name a shortened onion (the same formatter as the row) or a
 * clearnet host. They never include a full onion, a query string, or
 * an API key. A URL result is an HTTP status or an exception class.
 */
internal object ExplorerProbeLog {
    const val TAG = "ExplorerProbe"

    fun start(baseUrl: String, socks: String) {
        log("start host=${host(baseUrl)} socks=${socksLabel(socks)}")
    }

    fun url(url: String, result: String, elapsedMs: Long) {
        log(urlLine(url, result, elapsedMs))
    }

    fun done(baseUrl: String, outcome: String, elapsedMs: Long) {
        log("done host=${host(baseUrl)} outcome=${token(outcome)} elapsedMs=${elapsedMs.coerceAtLeast(0L)}")
    }

    fun cacheHit(baseUrl: String, outcome: String, ageMs: Long) {
        log("cache hit host=${host(baseUrl)} outcome=${token(outcome)} ageMs=${ageMs.coerceAtLeast(0L)}")
    }

    fun cacheMiss(baseUrl: String) {
        log("cache miss host=${host(baseUrl)}")
    }

    fun abort(baseUrl: String, reason: String, socks: String) {
        log("abort host=${host(baseUrl)} reason=${token(reason)} socks=${socksLabel(socks)}")
    }

    internal fun urlLine(url: String, result: String, elapsedMs: Long): String =
        "url host=${host(url)} path=${pathOf(url)} result=${token(result)} elapsedMs=${elapsedMs.coerceAtLeast(0L)}"

    internal fun host(url: String): String {
        ExplorerBinding.shortenOnion(url)?.let { return it }
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return "unknown"
        ExplorerBinding.shortenOnion(host)?.let { return it }
        if (host.length > 80) return "unknown"
        return host
    }

    internal fun pathOf(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        if (path.isBlank()) return "/"
        return path
    }

    internal fun socksLabel(socks: String): String {
        val trimmed = socks.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true)) return "none"
        ExplorerBinding.shortenOnion(trimmed)?.let { return it }
        if (trimmed.length > 64 || trimmed.any { it.isWhitespace() } || trimmed.contains("://")) {
            return "unknown"
        }
        return trimmed.substringBefore("?")
    }

    private fun token(value: String): String {
        val trimmed = value.trim()
        val ok = trimmed.length in 1..40 && trimmed.all { ch ->
            ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-'
        }
        return if (ok) trimmed else "error"
    }

    private fun log(line: String) {
        Timber.tag(TAG).i(line)
    }
}
