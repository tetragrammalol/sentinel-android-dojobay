package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.ui.webview.ExplorerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Community list shows the sibling explorer, not the Dojo pairing onion. */
class CommunityDojoSubtitleTest {

    private val pairingUrl = "http://pairdojo.onion/v2"

    @Test
    fun `subtitle shows the shortened explorer and never the pairing onion`() {
        val explorerUrl = "http://" + TxFactsResolver.MEMPOOL_ONION_HOST + "/testnet"
        val explorerLabel = ExplorerBinding.truncateExplorerLabel(explorerUrl)
        assertEquals("mempool…r6ad.onion", explorerLabel)

        val line = communityDojoSubtitle(
            CommunityDojoNode(
                version = "1.29.2",
                jurisdiction = "Singapore",
                status = "active",
                payload = CommunityDojoPayload(
                    pairing = Pairing(url = pairingUrl),
                    explorer = Explorer(url = explorerUrl),
                ),
            ),
        )
        assertEquals("v1.29.2 • Singapore • online • mempool…r6ad.onion", line)
        assertTrue(line.contains(explorerLabel))
        assertFalse(line.contains("pairdojo"))
        assertFalse(line.contains(pairingUrl))
    }

    @Test
    fun `a clearnet explorer shows no address`() {
        val clearnetUrl = "https://explorer.example/tx/abc"
        val line = communityDojoSubtitle(
            CommunityDojoNode(
                version = "1.29.2",
                jurisdiction = "Singapore",
                status = "active",
                payload = CommunityDojoPayload(
                    pairing = Pairing(url = pairingUrl),
                    explorer = Explorer(url = clearnetUrl),
                ),
            ),
        )
        assertEquals("v1.29.2 • Singapore • online", line)
        assertFalse(line.contains("explorer.example"))
        assertFalse(line.contains(clearnetUrl))
        assertFalse(line.contains("pairdojo"))
        assertFalse(line.contains(".onion"))
    }

    @Test
    fun `a node with no explorer shows no onion`() {
        val line = communityDojoSubtitle(
            CommunityDojoNode(
                version = "1.2",
                jurisdiction = "Singapore",
                status = "offline",
                payload = CommunityDojoPayload(
                    pairing = Pairing(url = pairingUrl),
                ),
            ),
        )
        assertEquals("v1.2 • Singapore • offline", line)
        assertFalse(line.contains("onion"))
        assertFalse(line.contains("pairdojo"))
    }
}
