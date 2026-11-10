package com.samourai.sentinel.data.txfacts

import java.net.URI

/**
 * #119 Route A: destination cascade for the live tx-facts fetch.
 * Pure, JVM-testable - no Android, no network. Production lookups
 * do not call [factsUrl]; [com.samourai.sentinel.ui.webview.ExplorerBinding]
 * is the gate, and it does not use the PUBLIC URL as a fallback.
 *
 * Disclosure class is host-derived, NEVER type-string-derived.
 * Any `.onion` host, including the official mempool onion, is the
 * Dojo's own explorer (SELF). A clearnet mempool.space URL is a
 * third party (PUBLIC) even when a Dojo published it. A blank,
 * schemeless, or otherwise host-less URL is PUBLIC too: it is not
 * a usable destination. [factsUrl] still builds the clearnet API
 * URL for that PUBLIC rung; callers that want "no silent fallback"
 * must stop at [classify] instead of requesting the URL.
 *
 * Testnet: the PUBLIC API base gains `/testnet`. SELF is used as
 * declared.
 */
object TxFactsResolver {

    const val MEMPOOL_DISPLAY_NAME = "mempool.space"
    const val MEMPOOL_CLEARNET_BASE = "https://$MEMPOOL_DISPLAY_NAME"
    const val MEMPOOL_TESTNET_BASE = "$MEMPOOL_CLEARNET_BASE/testnet"

    /** Official onion. A Dojo that publishes it is publishing its own explorer. */
    const val MEMPOOL_ONION_HOST =
        "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"

    enum class Destination { SELF, PUBLIC }

    data class Resolved(val destination: Destination, val url: String)

    fun classify(explorerUrl: String?): Destination =
        if (explorerUrl.isNullOrBlank() ||
            isPublicInstance(explorerUrl) ||
            !hasUsableHost(explorerUrl)
        ) {
            Destination.PUBLIC
        } else {
            Destination.SELF
        }

    /** Clearnet mempool.space only. Any onion host, including [MEMPOOL_ONION_HOST], is not public. */
    fun isPublicInstance(url: String): Boolean = try {
        val host = URI(url.trim()).host?.trimEnd('.') ?: return false
        host.equals(MEMPOOL_DISPLAY_NAME, ignoreCase = true)
    } catch (e: Exception) {
        false
    }

    /**
     * Gate defect fix (first gate run of this branch, red at
     * TxFactsResolverTest "garbage declared url is public not crash"):
     * classify() treated every non-public URL as SELF, so garbage
     * ("::::") reached the SELF rung - java.net.URI never surfaced
     * the problem to isPublicInstance, because a throw lands in its
     * catch (returns false) and a lenient parse yields a null host
     * that equals nothing. A declared URL with no usable host is
     * unusable as a destination: PUBLIC per the documented safe
     * default. Also pins schemeless declarations ("host.onion"
     * parses as a path, no host) to PUBLIC.
     */
    private fun hasUsableHost(url: String): Boolean = try {
        !URI(url.trim()).host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** /api/tx/{txid} against the resolved base. Self bases are used
     *  as-is (directory payloads carry bare onion roots). */
    fun factsUrl(txid: String, selfExplorerUrl: String?, testnet: Boolean): Resolved {
        return when (classify(selfExplorerUrl)) {
            Destination.SELF -> Resolved(
                Destination.SELF,
                selfExplorerUrl!!.trim().trimEnd('/') + "/api/tx/" + txid,
            )
            Destination.PUBLIC -> Resolved(
                Destination.PUBLIC,
                (if (testnet) MEMPOOL_TESTNET_BASE else MEMPOOL_CLEARNET_BASE) +
                    "/api/tx/" + txid,
            )
        }
    }
}
