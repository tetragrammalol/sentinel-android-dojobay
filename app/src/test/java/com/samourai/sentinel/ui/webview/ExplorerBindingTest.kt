package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precedence, migration, network match, and the unbound rule.
 * No Android and no network: a lookup that is not bound has no URL.
 */
class ExplorerBindingTest {

    private val onion = "http://synthetic-self-explorer.onion"
    private val txid = "ab".repeat(32)
    private val address = "bc1qexampleaddress"

    @Test
    fun `explicit mempool and custom win over the paired explorer and survive unpairing`() {
        val paired = facts(published = onion, declared = "mainnet")
        assertKind(ExplorerBinding.ExplorerKind.PAIRED, ExplorerBinding.resolve(paired))

        val mempool = ExplorerBinding.resolve(
            paired.copy(savedSelection = "Mempool", publishedUrl = null),
        ) as ExplorerBinding.ExplorerResolution.Bound
        assertEquals(ExplorerBinding.ExplorerKind.MEMPOOL, mempool.kind)
        assertEquals(TxFactsResolver.MEMPOOL_CLEARNET_BASE, mempool.baseUrl)

        val custom = ExplorerBinding.resolve(
            paired.copy(
                savedSelection = ExplorerBinding.CHOICE_CUSTOM,
                customUrl = "http://custom.onion/tx/{txid}",
                publishedUrl = null,
            ),
        ) as ExplorerBinding.ExplorerResolution.Bound
        assertEquals(ExplorerBinding.ExplorerKind.CUSTOM, custom.kind)
        assertEquals("http://custom.onion/tx/$txid", ExplorerBinding.pageUrl(custom, ExplorerBinding.ExplorerPage.TX, txid))
    }

    @Test
    fun `legacy choices migrate to paired and explicit choices stay`() {
        listOf("Blockstream", "Blockchair", "", "  ", null, "Oxt", "unknown").forEach { saved ->
            assertEquals(saved, ExplorerBinding.CHOICE_PAIRED, ExplorerBinding.migratedSelection(saved, ""))
        }
        assertEquals(
            ExplorerBinding.CHOICE_CUSTOM,
            ExplorerBinding.migratedSelection("custom", "http://custom.onion/tx/{txid}"),
        )
        assertEquals(
            ExplorerBinding.CHOICE_PAIRED,
            ExplorerBinding.migratedSelection(ExplorerBinding.CHOICE_CUSTOM, "  "),
        )
        assertEquals(
            TxFactsResolver.MEMPOOL_DISPLAY_NAME,
            ExplorerBinding.migratedSelection("mempool", null),
        )
        assertEquals(
            TxFactsResolver.MEMPOOL_DISPLAY_NAME,
            ExplorerBinding.migratedSelection("MEMPOOL.SPACE", null),
        )
    }

    @Test
    fun `nothing published stays unbound with no third party url`() {
        val unbound = ExplorerBinding.resolve(facts())
        assertTrue(unbound is ExplorerBinding.ExplorerResolution.Unbound)
        assertNull(ExplorerBinding.pageUrl(unbound, ExplorerBinding.ExplorerPage.TX, txid))
        assertNull(ExplorerBinding.pageUrl(unbound, ExplorerBinding.ExplorerPage.ADDRESS, address))
        assertNull(ExplorerBinding.probeBase(unbound))
    }

    @Test
    fun `clearnet mempool published by a dojo does not bind`() {
        val resolution = ExplorerBinding.resolve(
            facts(published = TxFactsResolver.MEMPOOL_CLEARNET_BASE, declared = "mainnet"),
        )
        assertTrue(resolution is ExplorerBinding.ExplorerResolution.Unbound)
    }

    @Test
    fun `unpairing drops a paired binding and repairing restores it`() {
        val paired = facts(published = onion, declared = "mainnet")
        assertKind(ExplorerBinding.ExplorerKind.PAIRED, ExplorerBinding.resolve(paired))

        val unpaired = ExplorerBinding.resolve(paired.copy(publishedUrl = null))
        assertTrue(unpaired is ExplorerBinding.ExplorerResolution.Unbound)
        assertEquals(
            ExplorerBinding.CHOICE_PAIRED,
            ExplorerBinding.migratedSelection(ExplorerBinding.CHOICE_PAIRED, null),
        )

        val repaired = ExplorerBinding.resolve(paired)
        val bound = repaired as ExplorerBinding.ExplorerResolution.Bound
        assertEquals(ExplorerBinding.ExplorerKind.PAIRED, bound.kind)
        assertEquals("$onion/tx/$txid", ExplorerBinding.pageUrl(bound, ExplorerBinding.ExplorerPage.TX, txid))
        assertEquals(
            "$onion/address/$address",
            ExplorerBinding.pageUrl(bound, ExplorerBinding.ExplorerPage.ADDRESS, address),
        )
    }

    @Test
    fun `an explicit mempool choice is not cleared by unpairing`() {
        val resolution = ExplorerBinding.resolve(
            facts(saved = "mempool", published = null, testnet = true),
        ) as ExplorerBinding.ExplorerResolution.Bound
        assertEquals(ExplorerBinding.ExplorerKind.MEMPOOL, resolution.kind)
        assertEquals(TxFactsResolver.MEMPOOL_TESTNET_BASE, resolution.baseUrl)
        assertEquals(
            "${TxFactsResolver.MEMPOOL_TESTNET_BASE}/tx/$txid",
            ExplorerBinding.pageUrl(resolution, ExplorerBinding.ExplorerPage.TX, txid),
        )
        assertEquals(TxFactsResolver.MEMPOOL_DISPLAY_NAME, ExplorerBinding.hostLabel(resolution))
    }

    @Test
    fun `network must match in both directions`() {
        val bare = onion
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = bare, declared = "mainnet", testnet = false)),
        )
        assertUnbound(facts(published = bare, declared = "mainnet", testnet = true))
        assertUnbound(facts(published = bare, declared = "testnet", testnet = false))
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = bare, declared = "testnet", testnet = true)),
        )

        val apiTest = "http://dojo.onion/test/v2"
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = bare, api = apiTest, testnet = true)),
        )
        assertUnbound(facts(published = bare, api = apiTest, testnet = false))

        val explorerTest = "$onion/testnet"
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = explorerTest, testnet = true)),
        )
        assertUnbound(facts(published = explorerTest, testnet = false))
        assertUnbound(facts(published = "$onion/testnet4", declared = "mainnet", testnet = false))
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = "$onion/test", declared = "testnet", testnet = true)),
        )

        assertUnbound(facts(published = bare, declared = "mainnet", api = apiTest, testnet = false))
        assertUnbound(facts(published = bare, declared = "mainnet", api = apiTest, testnet = true))
        assertUnbound(facts(published = "$onion/signet", declared = "mainnet"))
        assertUnbound(facts(published = "$onion/signet", declared = "testnet", testnet = true))
    }

    @Test
    fun `manual mainnet pairing with no testnet evidence binds on mainnet only`() {
        val api = "http://dojo.onion/v2"
        assertKind(
            ExplorerBinding.ExplorerKind.PAIRED,
            ExplorerBinding.resolve(facts(published = onion, api = api, testnet = false)),
        )
        assertUnbound(facts(published = onion, api = api, testnet = true))
    }

    @Test
    fun `custom address falls back from a tx template and the label matches the directory cut`() {
        val resolution = ExplorerBinding.resolve(
            facts(
                saved = ExplorerBinding.CHOICE_CUSTOM,
                custom = "http://custom.onion/tx/{txid}",
            ),
        )
        assertEquals(
            "http://custom.onion/address/$address",
            ExplorerBinding.pageUrl(resolution, ExplorerBinding.ExplorerPage.ADDRESS, address),
        )
        val whole = "https://mempool.space"
        assertEquals(21, whole.length)
        assertEquals(whole, ExplorerBinding.truncateExplorerLabel(whole))
        assertEquals("https://mempoo…pace/x", ExplorerBinding.truncateExplorerLabel(whole + "/x"))
    }

    @Test
    fun `health urls probe the tip then the root`() {
        val bound = ExplorerBinding.resolve(facts(published = onion, declared = "mainnet"))
        assertEquals(onion, ExplorerBinding.probeBase(bound))
        assertEquals(
            listOf("$onion/api/blocks/tip/height", onion),
            ExplorerBinding.healthRequestUrls(onion),
        )
        assertEquals(
            "http://custom.onion",
            ExplorerBinding.probeBase(
                ExplorerBinding.resolve(
                    facts(saved = ExplorerBinding.CHOICE_CUSTOM, custom = "http://custom.onion/tx/{txid}"),
                ),
            ),
        )
    }

    private fun facts(
        saved: String? = ExplorerBinding.CHOICE_PAIRED,
        custom: String? = null,
        published: String? = null,
        testnet: Boolean = false,
        declared: String? = null,
        api: String? = null,
    ) = ExplorerBinding.ExplorerFacts(saved, custom, published, testnet, declared, api)

    private fun assertKind(kind: ExplorerBinding.ExplorerKind, resolution: ExplorerBinding.ExplorerResolution) {
        val bound = resolution as ExplorerBinding.ExplorerResolution.Bound
        assertEquals(kind, bound.kind)
    }

    private fun assertUnbound(facts: ExplorerBinding.ExplorerFacts) {
        assertTrue(ExplorerBinding.resolve(facts) is ExplorerBinding.ExplorerResolution.Unbound)
    }
}
