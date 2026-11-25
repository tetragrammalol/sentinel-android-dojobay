package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #654: the pub-level cross-network guard, pure JVM - the
 * NetworkClassifierTest fixture pattern: deliberately-fake suffixes
 * on real prefixes (the classifier reads only the prefix), so no real
 * or plausible key material appears here.
 */
class DestinationNetworkGuardTest {

    private fun pub(prefix: String) = PubKeyModel(
        pubKey = "${prefix}fake-key-guard",
        label = "fixture",
        type = AddressTypes.BIP84,
        balance = 0L
    )

    @Test
    fun `a tpub in a mainnet app is cross-network`() {
        assertTrue(pubIsCrossNetwork(pub("tpub"), activeIsTestnet = false))
    }

    @Test
    fun `an xpub in a testnet app is cross-network`() {
        assertTrue(pubIsCrossNetwork(pub("xpub"), activeIsTestnet = true))
    }

    @Test
    fun `a tpub in a testnet app is not refused`() {
        assertFalse(pubIsCrossNetwork(pub("tpub"), activeIsTestnet = true))
    }

    @Test
    fun `an xpub in a mainnet app is not refused`() {
        assertFalse(pubIsCrossNetwork(pub("xpub"), activeIsTestnet = false))
    }

    @Test
    fun `an unrecognized prefix is never refused`() {
        assertFalse(pubIsCrossNetwork(pub("fakeprefix"), activeIsTestnet = false))
        assertFalse(pubIsCrossNetwork(pub("fakeprefix"), activeIsTestnet = true))
    }

    @Test
    fun `a blank pub is never refused`() {
        assertFalse(pubIsCrossNetwork(pub(""), activeIsTestnet = false))
        assertFalse(pubIsCrossNetwork(pub(""), activeIsTestnet = true))
    }

    @Test
    fun `an address-like pub abstains - never refused`() {
        // A standalone address pub is not an xpub: it renders raw on
        // the receive screen and never reaches a walk.
        assertFalse(pubIsCrossNetwork(pub("tb1qfake"), activeIsTestnet = false))
        assertFalse(pubIsCrossNetwork(pub("1Fake"), activeIsTestnet = true))
    }

    @Test
    fun `mixed-case prefixes classify as their lowercase family`() {
        assertTrue(pubIsCrossNetwork(pub("TPUB"), activeIsTestnet = false))
        assertFalse(pubIsCrossNetwork(pub("XPUB"), activeIsTestnet = false))
    }
}
