package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.ui.webview.ExplorerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Community list: Dojo onion and explorer share the one onion formatter. */
class CommunityDojoSubtitleTest {

    @Test
    fun `dojo onion and explorer use the seven and four cut and a missing one stays missing`() {
        val host = TxFactsResolver.MEMPOOL_ONION_HOST
        val both = communityDojoSubtitle(
            CommunityDojoNode(
                version = "1.29.2",
                jurisdiction = "Singapore",
                status = "active",
                payload = CommunityDojoPayload(
                    pairing = Pairing(url = "http://$host/v2"),
                    explorer = Explorer(url = "http://$host/testnet"),
                ),
            ),
        )
        assertEquals(
            "v1.29.2 • Singapore • online • mempool…r6ad.onion • mempool…r6ad.onion",
            both,
        )
        assertFalse(both.contains(host))

        val explorerOnly = communityDojoSubtitle(
            CommunityDojoNode(
                status = "offline",
                payload = CommunityDojoPayload(
                    explorer = Explorer(url = "http://custom.onion/tx/{txid}"),
                ),
            ),
        )
        assertEquals("offline • custom.onion", explorerOnly)

        val clearnet = communityDojoSubtitle(
            CommunityDojoNode(
                status = "active",
                payload = CommunityDojoPayload(
                    pairing = Pairing(url = "http://short.onion"),
                    explorer = Explorer(url = "https://explorer.example/tx/{txid}"),
                ),
            ),
        )
        assertEquals(
            "online • short.onion • https://explor…{txid}",
            clearnet,
        )
    }
}
