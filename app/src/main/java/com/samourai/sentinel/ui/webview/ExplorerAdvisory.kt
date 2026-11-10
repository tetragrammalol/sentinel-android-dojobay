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

    private fun fill(template: String, party: String): String = template.replace("%1\$s", party)
}
