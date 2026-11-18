package com.samourai.sentinel.core

import com.samourai.sentinel.ui.dojo.PairingChainDecision
import com.samourai.sentinel.ui.dojo.pairingChainDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #351: the chain whisper and the pairing-path chain decision, the
 * NetworkClassifierTest / PairingNetworkDecisionTest idioms.
 *
 * The whisper is convention evidence ONLY: testnet mounts answer at
 * /test (3), /test4 (dojobay convention), /testnet4, /testnet5;
 * mainnet at the root - null, absence of evidence, never a verdict.
 * The decision warns exactly once, on the #348 shape: app in testnet
 * mode (its generation is always testnet3 - SentinelState resolves
 * fromID(ID_TESTNET)) against a differently-generated testnet mount.
 * Mainnet-mode mismatches stay with the network guard; no double
 * warning.
 */
class DojoChainWhisperTest {

    // ---- the whisper ----

    @Test
    fun `test mounts whisper testnet3`() {
        assertEquals(DojoChain.TESTNET_3, DojoChainWhisper.fromUrl("http://abc123.onion/test/v2"))
        assertEquals(DojoChain.TESTNET_3, DojoChainWhisper.fromUrl("http://x.onion/testnet/v2"))
        assertEquals(DojoChain.TESTNET_3, DojoChainWhisper.fromUrl("http://x.onion/testnet3/v2"))
    }

    @Test
    fun `test4 mounts whisper testnet4`() {
        assertEquals(DojoChain.TESTNET_4, DojoChainWhisper.fromUrl("http://x.onion/test4/v2"))
        assertEquals(DojoChain.TESTNET_4, DojoChainWhisper.fromUrl("http://x.onion/testnet4/v2"))
    }

    @Test
    fun `testnet5 mounts whisper testnet5`() {
        assertEquals(DojoChain.TESTNET_5, DojoChainWhisper.fromUrl("http://x.onion/testnet5/v2"))
        assertEquals(DojoChain.TESTNET_5, DojoChainWhisper.fromUrl("http://x.onion/test5/v2"))
    }

    @Test
    fun `root mount and garbage stay silent - null is absence of evidence`() {
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/v2/"))
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/"))
        assertNull(DojoChainWhisper.fromUrl(null))
        assertNull(DojoChainWhisper.fromUrl("not a url"))
    }

    @Test
    fun `segment equality never substring - attest and contest stay silent`() {
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/attest/v2"))
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/contest/v2"))
        // query and fragment are outside the parser
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/v2?a=/testnet4"))
        assertNull(DojoChainWhisper.fromUrl("http://x.onion/v2#/testnet4"))
    }

    @Test
    fun `case and whitespace insensitive like fromUrl`() {
        assertEquals(DojoChain.TESTNET_4, DojoChainWhisper.fromUrl("  HTTP://X.ONION/TEST4/v2 "))
    }

    // ---- the decision ----

    @Test
    fun `null whisper proceeds in both modes - the network guard owns mainnet`() {
        assertEquals(
            PairingChainDecision.PROCEED,
            pairingChainDecision(appIsTestnet = true, whisper = null)
        )
        assertEquals(
            PairingChainDecision.PROCEED,
            pairingChainDecision(appIsTestnet = false, whisper = null)
        )
    }

    @Test
    fun `testnet3 whisper proceeds in testnet mode - the app's own generation`() {
        assertEquals(
            PairingChainDecision.PROCEED,
            pairingChainDecision(appIsTestnet = true, whisper = DojoChain.TESTNET_3)
        )
    }

    @Test
    fun `testnet4 or testnet5 whisper on a testnet app warns - the 348 shape`() {
        assertEquals(
            PairingChainDecision.CONFIRM_CHAIN_MISMATCH,
            pairingChainDecision(appIsTestnet = true, whisper = DojoChain.TESTNET_4)
        )
        assertEquals(
            PairingChainDecision.CONFIRM_CHAIN_MISMATCH,
            pairingChainDecision(appIsTestnet = true, whisper = DojoChain.TESTNET_5)
        )
    }

    @Test
    fun `any testnet whisper on a mainnet app proceeds - one warning per dialog`() {
        assertEquals(
            PairingChainDecision.PROCEED,
            pairingChainDecision(appIsTestnet = false, whisper = DojoChain.TESTNET_4)
        )
        assertEquals(
            PairingChainDecision.PROCEED,
            pairingChainDecision(appIsTestnet = false, whisper = DojoChain.TESTNET_5)
        )
    }
}