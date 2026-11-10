package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorerAdvisoryTest {

    private val party = "Example"

    private val base = "This opens a third party. %1\$s learns which transaction or address you looked up, and can link lookups made in the same session."
    private val tor = "Your IP address stays hidden: this load goes over Tor."
    private val direct = "This load does not go over Tor, so %1\$s also sees your IP address."
    private val unsent = "Nothing is sent unless this load goes over Tor."
    private val guidance = "The private option is a paired Dojo explorer. Pair a Community Dojo that publishes an explorer, or set Custom in Settings > Explorer."

    private val torMessage =
        "This opens a third party. Example learns which transaction or address you looked up, and can link lookups made in the same session. " +
            "Your IP address stays hidden: this load goes over Tor. " +
            guidance

    private val directMessage =
        "This opens a third party. Example learns which transaction or address you looked up, and can link lookups made in the same session. " +
            "This load does not go over Tor, so Example also sees your IP address. " +
            guidance

    @Test
    fun `tor path claims ip protection only because the load goes over tor`() {
        assertEquals(ExplorerAdvisory.Transport.TOR, ExplorerAdvisory.transport(ExplorerLoadDecision.LOAD_VIA_PROXY))
        val text = advise(ExplorerLoadDecision.LOAD_VIA_PROXY)
        assertEquals(torMessage, text)
        assertTrue(text.contains("stays hidden"))
        assertTrue(text.contains("this load goes over Tor"))
        assertFalse(text.contains("sees your IP"))
        assertFalse(text.contains("not your IP"))
    }

    @Test
    fun `direct paths warn that the third party sees the ip address`() {
        listOf(
            ExplorerLoadDecision.LOAD_DIRECT,
            ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED,
        ).forEach { decision ->
            assertEquals(ExplorerAdvisory.Transport.DIRECT, ExplorerAdvisory.transport(decision))
            val text = advise(decision)
            assertEquals(directMessage, text)
            assertTrue(text.contains("does not go over Tor"))
            assertTrue(text.contains("Example also sees your IP address"))
            assertFalse(text.contains("stays hidden"))
        }
    }

    @Test
    fun `a load that has not started does not claim the ip is hidden`() {
        listOf(
            ExplorerLoadDecision.WAIT_FOR_TOR,
            ExplorerLoadDecision.BLOCK_TOR_OFF,
            ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED,
        ).forEach { decision ->
            assertEquals(ExplorerAdvisory.Transport.UNSENT, ExplorerAdvisory.transport(decision))
            val text = advise(decision)
            assertTrue(text.contains(unsent))
            assertFalse(text.contains("stays hidden"))
            assertFalse(text.contains("sees your IP"))
        }
    }

    @Test
    fun `every variant names the lookup and the paired dojo option`() {
        ExplorerLoadDecision.values().forEach { decision ->
            val text = advise(decision)
            assertTrue(text.contains("Example learns which transaction or address you looked up"))
            assertTrue(text.contains("link lookups made in the same session"))
            assertTrue(text.contains("paired Dojo explorer"))
            assertTrue(text.contains("Community Dojo"))
            assertTrue(text.contains("Settings > Explorer"))
            assertFalse(text.contains("dojo"))
            assertFalse(text.contains("webview"))
        }
    }

    @Test
    fun `the dialog uses these sentences and the same load decision as the webview`() {
        val strings = source(
            "app/src/main/res/values/strings.xml",
            "src/main/res/values/strings.xml",
        )
        assertTrue(strings.contains(base))
        assertTrue(strings.contains(tor))
        assertTrue(strings.contains(direct))
        assertTrue(strings.contains(unsent))
        assertTrue(strings.contains(guidance))
        assertFalse(strings.contains("not your IP"))
        assertFalse(strings.contains("webview routes over Tor"))

        val opener = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
        )
        assertTrue(opener.contains("ExplorerAdvisory.message"))
        assertTrue(opener.contains("explorerLoadDecision"))
        assertTrue(opener.contains("WebViewFeature.PROXY_OVERRIDE"))
        assertTrue(opener.contains("SentinelTorManager.isTorReady()"))
        assertTrue(opener.contains("SentinelState.isTorRequired()"))
        assertTrue(opener.contains("ExplorerRepository.AM_I_EXPOSED_NAME"))
        assertTrue(opener.contains("TxFactsResolver.MEMPOOL_DISPLAY_NAME"))
        assertFalse(opener.contains("explorer_third_party_advisory"))
    }

    @Test
    fun `external analysis names both third parties and the real load`() {
        val site = "am-i.exposed"
        val api = "mempool.space"
        val base =
            "This opens %1\$s, a third party. Its page fetches the blockchain data from the %2\$s API (third-party servers). %2\$s learns which transaction you looked up, and can link lookups made in the same session."
        val direct = "This load does not go over Tor, so %1\$s and %2\$s also see your IP address."
        val strings = source(
            "app/src/main/res/values/strings.xml",
            "src/main/res/values/strings.xml",
        )
        assertTrue(strings.contains(base))
        assertTrue(strings.contains(direct))
        assertTrue(strings.contains(">Analyze externally<"))

        ExplorerLoadDecision.values().forEach { decision ->
            val text = ExplorerAdvisory.analysisMessage(
                site = site,
                api = api,
                decision = decision,
                base = base,
                tor = tor,
                direct = direct,
                unsent = unsent,
                guidance = guidance,
            )
            assertTrue(text.contains("This opens am-i.exposed, a third party."))
            assertTrue(text.contains("fetches the blockchain data from the mempool.space API (third-party servers)"))
            assertTrue(text.contains("mempool.space learns which transaction you looked up"))
            assertFalse(text.contains("am-i.exposed learns"))
            assertTrue(text.contains("paired Dojo explorer"))
            assertFalse(text.contains("dojo"))
            assertFalse(text.contains("Mempool"))
            assertFalse(text.contains("webview"))
            when (ExplorerAdvisory.transport(decision)) {
                ExplorerAdvisory.Transport.TOR -> {
                    assertTrue(text.contains(tor))
                    assertFalse(text.contains("sees your IP"))
                }
                ExplorerAdvisory.Transport.DIRECT -> {
                    assertTrue(text.contains("so am-i.exposed and mempool.space also see your IP address"))
                    assertFalse(text.contains("stays hidden"))
                }
                ExplorerAdvisory.Transport.UNSENT -> {
                    assertTrue(text.contains(unsent))
                    assertFalse(text.contains("stays hidden"))
                    assertFalse(text.contains("sees your IP"))
                }
            }
        }
    }

    private fun advise(decision: ExplorerLoadDecision): String = ExplorerAdvisory.message(
        party = party,
        decision = decision,
        base = base,
        tor = tor,
        direct = direct,
        unsent = unsent,
        guidance = guidance,
    )

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
