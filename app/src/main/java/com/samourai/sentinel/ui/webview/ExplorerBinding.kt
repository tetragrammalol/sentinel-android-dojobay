package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.ui.dojo.normalizeDeclaredNetwork
import java.net.URI

/**
 * Which explorer a lookup may use, decided before any request.
 *
 * An explicit Custom URL, the onion service, or the clearnet mempool
 * site wins over the paired Dojo and is kept across unpairing. The
 * onion service is http and never loads except through Tor. An
 * existing mempool choice stays the clearnet site. Otherwise the paired Dojo's
 * published explorer is used when it is the Dojo's own explorer -
 * an onion host (SR-23: any clearnet URL, the public mempool host
 * or another host, is a third party and never binds as the paired
 * explorer) - and its network matches the active network. Anything
 * else stays unbound: no silent third-party URL.
 *
 * Network, both directions: a testnet explorer is not used on
 * mainnet, and a mainnet explorer is not used on testnet. The Dojo's
 * declared network is the contract. With no declaration, a `/test`
 * segment on the Dojo API (the #250 whisper) or a testnet segment on
 * the explorer URL means testnet; a signet path matches neither
 * network. No testnet evidence means mainnet, which is what a manual
 * mainnet pairing looks like (`…/v2`, bare explorer root). A
 * declaration that conflicts with a positive testnet or signet path
 * matches nothing.
 */
object ExplorerBinding {

    const val CHOICE_PAIRED = "Paired"
    const val CHOICE_CUSTOM = "Custom"
    const val TXID_PLACEHOLDER = "{txid}"
    const val ADDRESS_PLACEHOLDER = "{address}"

    /** A non-onion label this short is shown whole. */
    const val LABEL_KEEP_WHOLE = 21

    /** Characters kept from the start of an onion host, before ".onion". */
    const val ONION_LABEL_HEAD = 7

    /** Characters kept from the end of an onion host, before ".onion". */
    const val ONION_LABEL_TAIL = 4

    /** Saved value and display label for the mempool onion service. */
    const val CHOICE_MEMPOOL_ONION = "mempool.onion"

    enum class ExplorerKind { PAIRED, CUSTOM, MEMPOOL, MEMPOOL_ONION }

    enum class ExplorerPage { TX, ADDRESS }

    enum class ExplorerChain { MAINNET, TESTNET }

    data class ExplorerFacts(
        val savedSelection: String?,
        val customUrl: String?,
        val publishedUrl: String?,
        val appIsTestnet: Boolean,
        val dojoDeclaredNetwork: String?,
        val dojoApiUrl: String?,
    )

    sealed class ExplorerResolution {
        data class Bound(
            val kind: ExplorerKind,
            val baseUrl: String,
            val displayUrl: String,
        ) : ExplorerResolution()

        object Unbound : ExplorerResolution()
    }

    /**
     * Blockstream, Blockchair, empty, unknown, and Custom with no URL
     * become the automatic paired choice. A saved Custom with a URL
     * stays Custom. "mempool" and the clearnet display name stay the
     * clearnet site. [CHOICE_MEMPOOL_ONION] stays the onion service.
     */
    fun migratedSelection(saved: String?, customUrl: String?): String {
        val value = saved?.trim().orEmpty()
        if (isMempoolOnion(value)) return CHOICE_MEMPOOL_ONION
        if (isExplicitMempool(value)) return TxFactsResolver.MEMPOOL_DISPLAY_NAME
        if (value.equals(CHOICE_CUSTOM, ignoreCase = true) && !customUrl.isNullOrBlank()) {
            return CHOICE_CUSTOM
        }
        return CHOICE_PAIRED
    }

    fun isMempoolOnion(saved: String?): Boolean {
        val value = saved?.trim()?.lowercase() ?: return false
        return value == CHOICE_MEMPOOL_ONION.lowercase()
    }

    fun isExplicitMempool(saved: String?): Boolean {
        val value = saved?.trim()?.lowercase() ?: return false
        return value == TxFactsResolver.MEMPOOL_DISPLAY_NAME.lowercase() || value == "mempool"
    }

    /**
     * Any host ending in `.onion` is reachable only through Tor,
     * whatever the saved choice. mempool.onion is that rule, not a
     * special case. Every other page follows the wallet's own Tor
     * requirement. A null URL does not add a requirement. The host
     * is the authority only: a path or a userinfo trick is not an
     * onion (SR-23).
     */
    fun lookupRequiresTor(url: String?, walletRequiresTor: Boolean): Boolean =
        walletRequiresTor || onionAuthority(url)

    fun resolve(facts: ExplorerFacts): ExplorerResolution {
        val selection = migratedSelection(facts.savedSelection, facts.customUrl)
        if (selection == CHOICE_MEMPOOL_ONION) {
            val base = TxFactsResolver.mempoolOnionBase(facts.appIsTestnet)
            return ExplorerResolution.Bound(
                kind = ExplorerKind.MEMPOOL_ONION,
                baseUrl = base,
                displayUrl = base,
            )
        }
        if (selection == TxFactsResolver.MEMPOOL_DISPLAY_NAME) {
            val base = TxFactsResolver.mempoolClearnetBase(facts.appIsTestnet)
            return ExplorerResolution.Bound(
                kind = ExplorerKind.MEMPOOL,
                baseUrl = base,
                displayUrl = base,
            )
        }
        if (selection == CHOICE_CUSTOM) {
            val template = facts.customUrl?.trim().orEmpty()
            if (template.isNotEmpty()) {
                return ExplorerResolution.Bound(
                    kind = ExplorerKind.CUSTOM,
                    baseUrl = template,
                    displayUrl = template,
                )
            }
        }
        return pairedExplorer(facts) ?: ExplorerResolution.Unbound
    }

    fun pageUrl(resolution: ExplorerResolution, page: ExplorerPage, id: String): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        val clean = id.trim()
        if (clean.isEmpty()) return null
        return when (bound.kind) {
            ExplorerKind.CUSTOM -> customPage(bound.baseUrl, page, clean)
            else -> joinBase(bound.baseUrl, page, clean)
        }
    }

    /** Root used by the Tor health check. Null when there is nothing to probe. */
    fun probeBase(resolution: ExplorerResolution): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        val root = when (bound.kind) {
            ExplorerKind.CUSTOM -> customRoot(bound.baseUrl)
            else -> bound.baseUrl
        }.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) return null
        return root
    }

    /**
     * Tip height first; the explorer root if that path is not served.
     * Empty when [baseUrl] is not an absolute http(s) URL.
     */
    fun healthRequestUrls(baseUrl: String): List<String> {
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) return emptyList()
        return listOf("$root/api/blocks/tip/height", root)
    }

    /**
     * Short label for an explorer URL.
     *
     * An onion drops its scheme and path and keeps the first
     * [ONION_LABEL_HEAD] and last [ONION_LABEL_TAIL] characters of the
     * host before ".onion" (`mempool…r6ad.onion`). Any other value
     * longer than [LABEL_KEEP_WHOLE] keeps its first 14 and last 6
     * characters. A short value is shown whole.
     */
    fun truncateExplorerLabel(url: String): String {
        shortenOnion(url)?.let { return it }
        if (url.length > LABEL_KEEP_WHOLE) return "${url.take(14)}…${url.takeLast(6)}"
        return url
    }

    /**
     * `mempool…r6ad.onion` when [value] contains an onion host. Scheme,
     * port, and path are ignored. Null when there is no onion host.
     * A host of [ONION_LABEL_HEAD] + [ONION_LABEL_TAIL] characters or
     * fewer is shown whole.
     */
    internal fun shortenOnion(value: String): String? {
        val match = ONION_HOST.find(value.trim()) ?: return null
        val label = match.groupValues[1].lowercase()
        if (label.isEmpty()) return null
        val suffix = ".onion"
        if (label.length <= ONION_LABEL_HEAD + ONION_LABEL_TAIL) return label + suffix
        return label.take(ONION_LABEL_HEAD) + "…" + label.takeLast(ONION_LABEL_TAIL) + suffix
    }

    fun hostLabel(resolution: ExplorerResolution): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        return when (bound.kind) {
            ExplorerKind.MEMPOOL -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
            ExplorerKind.MEMPOOL_ONION -> CHOICE_MEMPOOL_ONION
            else -> truncateExplorerLabel(bound.displayUrl)
        }
    }

    /**
     * True when a load to [url] goes to a party that is not the
     * paired Dojo's own explorer, so the third-party advisory is
     * owed before the launch. That is either mempool choice and any
     * Custom page URL whose host is not an onion. mempool.onion is
     * still a third party: the onion only changes the route. A bound
     * PAIRED explorer is always an onion host, so it owes no
     * advisory. The page URL decides, not the saved template: a
     * Custom template with placeholders does not parse as a URI, but
     * the substituted page URL does.
     */
    fun requiresAdvisory(resolution: ExplorerResolution, url: String): Boolean {
        val bound = resolution as? ExplorerResolution.Bound ?: return false
        return when (bound.kind) {
            ExplorerKind.PAIRED -> false
            ExplorerKind.MEMPOOL, ExplorerKind.MEMPOOL_ONION -> true
            ExplorerKind.CUSTOM -> !TxFactsResolver.isOnionHost(url)
        }
    }

    private fun pairedExplorer(facts: ExplorerFacts): ExplorerResolution.Bound? {
        val raw = facts.publishedUrl ?: return null
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (TxFactsResolver.classify(trimmed) != TxFactsResolver.Destination.SELF) return null
        val chain = publishedChain(trimmed, facts.dojoDeclaredNetwork, facts.dojoApiUrl) ?: return null
        val app = if (facts.appIsTestnet) ExplorerChain.TESTNET else ExplorerChain.MAINNET
        if (chain != app) return null
        return ExplorerResolution.Bound(
            kind = ExplorerKind.PAIRED,
            baseUrl = trimmed.trimEnd('/'),
            displayUrl = raw,
        )
    }

    /**
     * Chain of a URL that already classified as the Dojo's own explorer.
     * Null means it matches neither active network.
     */
    fun publishedChain(url: String, declaredRaw: String?, apiUrl: String?): ExplorerChain? {
        val hint = pathHint(url)
        if (hint == PathHint.SIGNET) return null
        val declared = normalizeDeclaredNetwork(declaredRaw)
        val apiTest = NetworkClassifier.fromUrl(apiUrl) == DerivedNetwork.TESTNET
        if (declared == DerivedNetwork.MAINNET && (apiTest || hint == PathHint.TESTNET)) return null
        if (declared == DerivedNetwork.MAINNET) return ExplorerChain.MAINNET
        if (declared == DerivedNetwork.TESTNET) return ExplorerChain.TESTNET
        if (apiTest || hint == PathHint.TESTNET) return ExplorerChain.TESTNET
        return ExplorerChain.MAINNET
    }

    private enum class PathHint { TESTNET, SIGNET, UNSPECIFIED }

    private val TESTNET_SEGMENTS = setOf("test", "testnet", "testnet3", "testnet4")

    /** Host only: scheme, user, port, and path stay outside the match. */
    private val ONION_HOST = Regex(
        """(?i)(?:^|://|@)([a-z0-9-]+(?:\.[a-z0-9-]+)*)\.onion(?=$|[/:?#])"""
    )

    private fun pathHint(url: String): PathHint {
        val path = runCatching { URI(url.trim()).path }.getOrNull() ?: return PathHint.UNSPECIFIED
        val segments = path.split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return when {
            segments.any { it == "signet" } -> PathHint.SIGNET
            segments.any { it in TESTNET_SEGMENTS } -> PathHint.TESTNET
            else -> PathHint.UNSPECIFIED
        }
    }

    private fun joinBase(base: String, page: ExplorerPage, id: String): String {
        val root = base.trim().trimEnd('/')
        val segment = if (page == ExplorerPage.TX) "tx" else "address"
        return "$root/$segment/$id"
    }

    private fun customPage(template: String, page: ExplorerPage, id: String): String {
        if (page == ExplorerPage.TX) {
            return if (template.contains(TXID_PLACEHOLDER, ignoreCase = true)) {
                template.replace(TXID_PLACEHOLDER, id, ignoreCase = true)
            } else {
                template.trimEnd('/') + "/" + id
            }
        }
        if (template.contains(ADDRESS_PLACEHOLDER, ignoreCase = true)) {
            return template.replace(ADDRESS_PLACEHOLDER, id, ignoreCase = true)
        }
        val base = template
            .replace(Regex("(?i)/tx/\\{txid\\}/?$"), "")
            .replace(Regex("(?i)/tx/?$"), "")
            .trimEnd('/')
        return "$base/address/$id"
    }

    private fun customRoot(template: String): String {
        var root = template.trim()
        root = root.replace(Regex("(?i)\\{txid\\}"), "")
        root = root.replace(Regex("(?i)\\{address\\}"), "")
        root = root.trimEnd('/')
        root = root.replace(Regex("(?i)/tx$"), "")
        root = root.replace(Regex("(?i)/address$"), "")
        return root.trimEnd('/')
    }
}
