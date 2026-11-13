package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Option order and Tor availability, which URL each choice binds,
 * including testnet, and when a dismissed advisory comes back.
 */
class ExplorerOptionsTest {

    private val txid = "ab".repeat(32)
    private val onion = "http://synthetic-self-explorer.onion"

    @Test
    fun `options stay in order and the onion row is greyed instead of hidden`() {
        val ready = ExplorerOptions.rows(facts(), torReady = true)
        assertEquals(
            listOf(
                ExplorerOptions.Id.PAIRED,
                ExplorerOptions.Id.MEMPOOL_ONION,
                ExplorerOptions.Id.MEMPOOL_SPACE,
                ExplorerOptions.Id.CUSTOM,
            ),
            ready.map { it.id },
        )
        assertTrue(ready[1].selectable)
        assertFalse(ready[1].needsTor)
        assertEquals(ExplorerOptions.Icon.ONION_PAIRED, ready[0].icon)
        assertEquals(ExplorerOptions.Icon.ONION, ready[1].icon)
        assertEquals(ExplorerOptions.Icon.GLOBE, ready[2].icon)

        val waiting = ExplorerOptions.rows(facts(), torReady = false)
        assertEquals(4, waiting.size)
        assertFalse(waiting[1].selectable)
        assertTrue(waiting[1].needsTor)
        assertTrue(waiting[0].selectable)
        assertTrue(waiting[2].selectable)
        assertTrue(waiting[3].selectable)
    }

    @Test
    fun `clearnet subline says Tor is off only with no Dojo`() {
        val bare = facts()
        assertTrue(ExplorerOptions.mempoolSpaceTorOff(bare, torOff = true))
        assertEquals(ExplorerOptions.SpaceInfo.TOR_OFF, ExplorerOptions.spaceInfo(bare, torOff = true))
        // Tor still starting is not off.
        assertFalse(ExplorerOptions.mempoolSpaceTorOff(bare, torOff = false))
        assertEquals(ExplorerOptions.SpaceInfo.OVER_TOR, ExplorerOptions.spaceInfo(bare, torOff = false))
        val paired = facts(api = "http://dojo.onion/v2", published = onion)
        assertFalse(ExplorerOptions.mempoolSpaceTorOff(paired, torOff = true))
        assertEquals(ExplorerOptions.SpaceInfo.OVER_TOR, ExplorerOptions.spaceInfo(paired, torOff = true))
    }

    @Test
    fun `paired shows the shortened onion and custom keeps its own icon`() {
        val paired = ExplorerOptions.rows(
            facts(published = "http://" + TxFactsResolver.MEMPOOL_ONION_HOST, declared = "mainnet"),
            torReady = true,
        )
        assertEquals("mempool…r6ad.onion", paired[0].addressLine)
        assertEquals("Paired explorer", ExplorerOptions.summary(facts()))
        assertEquals("mempool…r6ad.onion", ExplorerOptions.summary(
            facts(published = "http://" + TxFactsResolver.MEMPOOL_ONION_HOST, declared = "mainnet"),
        ))

        val clear = ExplorerOptions.rows(
            facts(custom = "https://explorer.example/tx/{txid}"),
            torReady = false,
        )[3]
        assertEquals(ExplorerOptions.Icon.GLOBE, clear.icon)
        assertEquals("https://explor…{txid}", clear.addressLine)

        val onionCustom = ExplorerOptions.rows(
            facts(custom = "http://custom.onion/tx/{txid}"),
            torReady = false,
        )[3]
        assertEquals(ExplorerOptions.Icon.ONION, onionCustom.icon)
        assertEquals("custom.onion", onionCustom.addressLine)
    }

    @Test
    fun `each option binds its own base and testnet uses the clearnet path`() {
        val onionMain = bound("mempool.onion", testnet = false)
        assertEquals(ExplorerBinding.ExplorerKind.MEMPOOL_ONION, onionMain.kind)
        assertEquals(TxFactsResolver.MEMPOOL_ONION_BASE, onionMain.baseUrl)
        assertTrue(onionMain.baseUrl.startsWith("http://"))
        assertFalse(onionMain.baseUrl.startsWith("https://"))
        assertEquals(
            TxFactsResolver.MEMPOOL_ONION_BASE + "/tx/" + txid,
            ExplorerBinding.pageUrl(boundResolution(onionMain), ExplorerBinding.ExplorerPage.TX, txid),
        )
        assertEquals(ExplorerBinding.CHOICE_MEMPOOL_ONION, ExplorerBinding.hostLabel(boundResolution(onionMain)))

        val onionTest = bound("mempool.onion", testnet = true)
        assertEquals(TxFactsResolver.MEMPOOL_ONION_TESTNET_BASE, onionTest.baseUrl)
        assertTrue(onionTest.baseUrl.endsWith("/testnet"))
        assertFalse(onionTest.baseUrl.contains("testnet4"))
        assertEquals(
            TxFactsResolver.MEMPOOL_ONION_TESTNET_BASE + "/address/bc1q",
            ExplorerBinding.pageUrl(boundResolution(onionTest), ExplorerBinding.ExplorerPage.ADDRESS, "bc1q"),
        )

        val clearMain = bound("mempool", testnet = false)
        assertEquals(ExplorerBinding.ExplorerKind.MEMPOOL, clearMain.kind)
        assertEquals(TxFactsResolver.MEMPOOL_CLEARNET_BASE, clearMain.baseUrl)
        val clearTest = bound("MEMPOOL.SPACE", testnet = true)
        assertEquals(TxFactsResolver.MEMPOOL_TESTNET_BASE, clearTest.baseUrl)
        assertEquals(TxFactsResolver.MEMPOOL_DISPLAY_NAME, ExplorerBinding.hostLabel(boundResolution(clearTest)))

        // Saved onion does not fall back to the clearnet site.
        assertFalse(onionMain.baseUrl.contains(TxFactsResolver.MEMPOOL_DISPLAY_NAME))
    }

    @Test
    fun `any onion host loads only over Tor and clearnet follows the wallet`() {
        val onionPage = TxFactsResolver.MEMPOOL_ONION_TESTNET_BASE + "/tx/" + txid
        assertTrue(ExplorerBinding.lookupRequiresTor(onionPage, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor(TxFactsResolver.MEMPOOL_ONION_BASE, walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor(TxFactsResolver.MEMPOOL_CLEARNET_BASE, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor(TxFactsResolver.MEMPOOL_CLEARNET_BASE, walletRequiresTor = true))
        assertFalse(ExplorerBinding.lookupRequiresTor(null, walletRequiresTor = false))
        // A Custom onion used to follow the wallet and load direct.
        assertTrue(ExplorerBinding.lookupRequiresTor("http://custom.onion/tx/" + txid, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor("HTTP://Custom.ONION/tx/" + txid, walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("https://evil.example/custom.onion", walletRequiresTor = false))
        assertFalse(
            ExplorerBinding.lookupRequiresTor("http://custom.onion@evil.example/tx/" + txid, walletRequiresTor = false),
        )
        assertFalse(
            TxFactsResolver.isMempoolOnionHost("https://evil.example/" + TxFactsResolver.MEMPOOL_ONION_HOST),
        )
    }

    @Test
    fun `advisory is owed by both mempool choices and a clearnet custom url only`() {
        val onionBound = boundResolution(bound("mempool.onion", testnet = false))
        val onionUrl = ExplorerBinding.pageUrl(onionBound, ExplorerBinding.ExplorerPage.TX, txid)!!
        assertTrue(ExplorerBinding.requiresAdvisory(onionBound, onionUrl))
        assertEquals(
            ExplorerBinding.CHOICE_MEMPOOL_ONION,
            ExplorerOptions.advisoryKey(ExplorerOptions.Id.MEMPOOL_ONION, onionUrl),
        )

        val clearBound = boundResolution(bound("mempool.space", testnet = true))
        val clearUrl = ExplorerBinding.pageUrl(clearBound, ExplorerBinding.ExplorerPage.TX, txid)!!
        assertTrue(ExplorerBinding.requiresAdvisory(clearBound, clearUrl))
        assertEquals(
            TxFactsResolver.MEMPOOL_DISPLAY_NAME,
            ExplorerOptions.advisoryKey(ExplorerOptions.Id.MEMPOOL_SPACE, clearUrl),
        )

        assertNull(ExplorerOptions.advisoryKey(ExplorerOptions.Id.PAIRED, onion))
        assertNull(ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, "http://custom.onion/tx/{txid}"))
        assertNull(ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, "  "))

        val customClear = "https://explorer.example/tx/{txid}?at=secret"
        assertEquals("custom:explorer.example", ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, customClear))
        assertEquals(
            "custom:explorer.example",
            ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, "https://Explorer.Example/address/xyz"),
        )
        assertFalse(ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, customClear)!!.contains("secret"))

        val customBound = ExplorerBinding.resolve(
            facts(saved = ExplorerBinding.CHOICE_CUSTOM, custom = "https://explorer.example/tx/{txid}"),
        )
        assertTrue(ExplorerBinding.requiresAdvisory(
            customBound,
            ExplorerBinding.pageUrl(customBound, ExplorerBinding.ExplorerPage.TX, txid)!!,
        ))
        val customOnion = ExplorerBinding.resolve(
            facts(saved = ExplorerBinding.CHOICE_CUSTOM, custom = "http://custom.onion/tx/{txid}"),
        )
        assertFalse(ExplorerBinding.requiresAdvisory(
            customOnion,
            ExplorerBinding.pageUrl(customOnion, ExplorerBinding.ExplorerPage.TX, txid)!!,
        ))
    }

    @Test
    fun `a dismissed advisory returns when the route gets worse`() {
        assertTrue(ExplorerOptions.shouldShowAdvisory(null, ExplorerOptions.Privacy.TOR))
        assertFalse(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.TOR, ExplorerOptions.Privacy.TOR))
        // Tor was on, then it is off: nothing is sent, or the load would be direct.
        assertTrue(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.TOR, ExplorerOptions.Privacy.UNSENT))
        assertTrue(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.TOR, ExplorerOptions.Privacy.DIRECT))
        // Accepted the direct load. A later Tor load is not worse.
        assertFalse(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.DIRECT, ExplorerOptions.Privacy.TOR))
        assertFalse(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.DIRECT, ExplorerOptions.Privacy.UNSENT))
        assertFalse(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.DIRECT, ExplorerOptions.Privacy.DIRECT))
        // Accepted "nothing sent". Sending in the clear is worse. Sending over Tor is not.
        assertTrue(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.UNSENT, ExplorerOptions.Privacy.DIRECT))
        assertFalse(ExplorerOptions.shouldShowAdvisory(ExplorerOptions.Privacy.UNSENT, ExplorerOptions.Privacy.TOR))

        assertEquals(ExplorerOptions.Privacy.TOR, ExplorerOptions.privacyOf(ExplorerLoadDecision.LOAD_VIA_PROXY))
        assertEquals(ExplorerOptions.Privacy.DIRECT, ExplorerOptions.privacyOf(ExplorerLoadDecision.LOAD_DIRECT))
        assertEquals(ExplorerOptions.Privacy.DIRECT, ExplorerOptions.privacyOf(ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED))
        assertEquals(ExplorerOptions.Privacy.UNSENT, ExplorerOptions.privacyOf(ExplorerLoadDecision.BLOCK_TOR_OFF))
        assertEquals(ExplorerOptions.Privacy.UNSENT, ExplorerOptions.privacyOf(ExplorerLoadDecision.WAIT_FOR_TOR))
        assertEquals(ExplorerOptions.Privacy.UNSENT, ExplorerOptions.privacyOf(ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED))
    }

    @Test
    fun `dismissal prefs round trip and ignore a broken line`() {
        val stored = mapOf(
            ExplorerBinding.CHOICE_MEMPOOL_ONION to ExplorerOptions.Privacy.TOR,
            TxFactsResolver.MEMPOOL_DISPLAY_NAME to ExplorerOptions.Privacy.DIRECT,
            "custom:explorer.example" to ExplorerOptions.Privacy.UNSENT,
            ExplorerRepository.AM_I_EXPOSED_NAME to ExplorerOptions.Privacy.TOR,
        )
        val raw = ExplorerOptions.encodeDismissals(stored) + "\nnot-a-row\n\tTOR\nbad\tNOPE"
        assertEquals(stored, ExplorerOptions.decodeDismissals(raw))
        assertTrue(ExplorerOptions.decodeDismissals(null).isEmpty())
        assertTrue(ExplorerOptions.decodeDismissals("   ").isEmpty())
        assertFalse(raw.contains("?"))
    }

    private fun bound(saved: String, testnet: Boolean): ExplorerBinding.ExplorerResolution.Bound =
        ExplorerBinding.resolve(facts(saved = saved, testnet = testnet)) as ExplorerBinding.ExplorerResolution.Bound

    private fun boundResolution(bound: ExplorerBinding.ExplorerResolution.Bound) = bound

    private fun facts(
        saved: String? = ExplorerBinding.CHOICE_PAIRED,
        custom: String? = null,
        published: String? = null,
        testnet: Boolean = false,
        declared: String? = null,
        api: String? = null,
    ) = ExplorerBinding.ExplorerFacts(saved, custom, published, testnet, declared, api)
}
