package com.samourai.sentinel.data.txfacts

import org.junit.Assert.assertEquals
import org.junit.Test

class TxFactsResolverTest {

    private val selfOnion = "http://synthetic-self-explorer.onion"
    private val txid = "AB".repeat(16)

    @Test
    fun `absent or blank explorer is public`() {
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify(null))
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify(""))
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify("   "))
    }

    @Test
    fun `clearnet mempool stays public and any onion is the dojo explorer`() {
        // A Dojo that publishes the official onion is publishing its own
        // explorer. Clearnet mempool.space stays a third party, including
        // a testnet path on the same host. Another clearnet host is the
        // Dojo's own explorer.
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://mempool.space"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://mempool.space/testnet"),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("http://" + TxFactsResolver.MEMPOOL_ONION_HOST),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("https://my-explorer.example"),
        )
    }

    @Test
    fun `declared sibling onion is self`() {
        assertEquals(TxFactsResolver.Destination.SELF, TxFactsResolver.classify(selfOnion))
    }

    @Test
    fun `garbage declared url is public not crash`() {
        // Red on the first gate run of this branch: classify() had no
        // usable-host leg, so "::::" fell into SELF. The fix pins it
        // PUBLIC - the documented safe default.
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify("::::"))
    }

    @Test
    fun `schemeless declared url is public not self`() {
        // java.net.URI parses a schemeless string as a path - host is
        // null, so it can never be a usable SELF destination.
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("synthetic-self-explorer.onion"),
        )
    }

    @Test
    fun `self facts url joins api tx against declared base`() {
        val r = TxFactsResolver.factsUrl(txid, selfOnion + "/", testnet = false)
        assertEquals(TxFactsResolver.Destination.SELF, r.destination)
        assertEquals("$selfOnion/api/tx/$txid", r.url)
    }

    @Test
    fun `public facts url uses clearnet base - the onion serves the page not the api`() {
        assertEquals(
            "https://mempool.space/api/tx/$txid",
            TxFactsResolver.factsUrl(txid, null, testnet = false).url,
        )
        assertEquals(
            "https://mempool.space/testnet/api/tx/$txid",
            TxFactsResolver.factsUrl(txid, null, testnet = true).url,
        )
    }
}
