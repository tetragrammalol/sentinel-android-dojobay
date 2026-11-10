package com.samourai.sentinel.ui.webview

/**
 * Wording for the third-party explorer warning.
 *
 * IP protection is claimed only when this load will go over Tor:
 * Tor is ready and the WebView will take the proxy path. A direct
 * load, including "Continue without Tor", says the third party sees
 * the IP address. Any other decision has not started a load, so the
 * text does not claim either outcome.
 */
object ExplorerAdvisory {

    enum class Transport {
        TOR,
        DIRECT,
        UNSENT,
    }

    fun transport(decision: ExplorerLoadDecision): Transport = when (decision) {
        ExplorerLoadDecision.LOAD_VIA_PROXY -> Transport.TOR
        ExplorerLoadDecision.LOAD_DIRECT,
        ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED -> Transport.DIRECT
        ExplorerLoadDecision.WAIT_FOR_TOR,
        ExplorerLoadDecision.BLOCK_TOR_OFF,
        ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED -> Transport.UNSENT
    }

    fun message(
        party: String,
        decision: ExplorerLoadDecision,
        base: String,
        tor: String,
        direct: String,
        unsent: String,
        guidance: String,
    ): String {
        val detail = when (transport(decision)) {
            Transport.TOR -> tor
            Transport.DIRECT -> fill(direct, party)
            Transport.UNSENT -> unsent
        }
        return listOf(fill(base, party), detail, guidance).joinToString(" ")
    }

    /**
     * External analysis. [site] is the page this confirmation opens.
     * That page then fetches the transaction from [api] inside the
     * same WebView, so the Tor, direct, and not-sent lines are the
     * load decision for both requests. The page cannot use a paired
     * explorer, so this text does not suggest one.
     */
    fun analysisMessage(
        site: String,
        api: String,
        decision: ExplorerLoadDecision,
        base: String,
        tor: String,
        direct: String,
        unsent: String,
    ): String {
        val detail = when (transport(decision)) {
            Transport.TOR -> tor
            Transport.DIRECT -> fill(direct, site, api)
            Transport.UNSENT -> unsent
        }
        return listOf(fill(base, site, api), detail).joinToString(" ")
    }

    private fun fill(template: String, vararg values: String): String {
        var out = template
        values.forEachIndexed { index, value ->
            out = out.replace("%${index + 1}\$s", value)
        }
        return out
    }
}
