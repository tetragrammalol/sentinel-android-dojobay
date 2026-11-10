package com.samourai.sentinel.api.okHttp

import com.samourai.sentinel.util.ImportLimits
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * #293: OkHttp leases the connection until the network body is closed.
 * Dojo calls share [Call.await], and several of them never read the
 * body (a discarded `importXpub` response, a non-2xx branch, a sibling
 * wallet fetch abandoned when one pubkey fails). The pool then logs
 * the full URL at GC time (#304).
 *
 * Read the network body here and hand back an in-memory copy, so the
 * socket is released before any caller — success, HTTP error, or a
 * caller that ignores the body — can forget it. Callers still read
 * [Response.body] the way they did; closing the copy does not touch
 * the socket, which is already back in the pool.
 *
 * #503: [maxBodyBytes] caps that read. [ResponseBody.bytes] copies the
 * whole stream first, so a check in the caller is too late. The cap is
 * counted off the socket, and byte `maxBodyBytes + 1` throws before it
 * is retained. Null leaves every other Dojo call unbounded: a wallet
 * or history payload is not a previous transaction.
 */
internal fun Response.releasingConnection(maxBodyBytes: Int? = null): Response {
    val network = body ?: return this
    val type = network.contentType()
    val bytes = try {
        if (maxBodyBytes == null) {
            network.bytes()
        } else {
            readCapped(network, maxBodyBytes)
        }
    } catch (e: Exception) {
        try {
            network.close()
        } catch (closeError: Exception) {
            e.addSuppressed(closeError)
        }
        throw e
    }
    return newBuilder().body(bytes.toResponseBody(type)).build()
}

/**
 * Counts bytes as they arrive. Closing the stream releases the socket,
 * including when the body is only partly read.
 */
private fun readCapped(network: ResponseBody, maxBodyBytes: Int): ByteArray {
    network.byteStream().use { input ->
        return ImportLimits.readBoundedBytes(input, maxBodyBytes)
    }
}

/** Cancellation path: drop the socket, the caller will not read the body. */
internal fun Response.releaseConnectionQuietly() {
    try {
        body?.close()
    } catch (_: Exception) {
    }
}
