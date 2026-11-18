package com.samourai.sentinel.core

import java.net.URI

/**
 * #351: the Dojo mount's testnet CHAIN whisper.
 *
 * NetworkClassifier.fromUrl answers "testnet vs mainnet" (a /test
 * mount segment); it cannot say WHICH testnet, and Sentinel's whole
 * network model collapses the family to one boolean - while testnet3
 * is sunset and testnet4/5 reuse its address formats (BIP94/95: same
 * tb HRP, same script version bytes, same WIF prefix), so Sentinel
 * interoperates with a testnet4 Dojo by accident and cannot tell the
 * chains apart. Pointed at the wrong generation, the user gets silent
 * emptiness (#348: a funded testnet4 key swept against a
 * testnet3-indexed Dojo answered "no UTXOs").
 *
 * This whisper stays deliberately OUT of DerivedNetwork: the pairing
 * payload and the keychain votes can never see a chain generation
 * (tpub-family keys vote identically on every testnet), so a
 * TESTNET_4 enum member would be a lie about what the wallet knows.
 * It is convention evidence, same grade as fromUrl: testnet mounts
 * answer at /test (testnet3), /test4 (the dojobay convention), and
 * their kin; mainnet mounts at the root, which stays null - absence
 * of evidence, never a verdict.
 *
 * java.net.URI like NetworkClassifier: no okhttp, no Android.
 */
enum class DojoChain { TESTNET_3, TESTNET_4, TESTNET_5 }

object DojoChainWhisper {

    private val CHAIN_SEGMENTS = linkedMapOf(
        "test" to DojoChain.TESTNET_3,
        "testnet" to DojoChain.TESTNET_3,
        "testnet3" to DojoChain.TESTNET_3,
        "test4" to DojoChain.TESTNET_4,
        "testnet4" to DojoChain.TESTNET_4,
        "testnet5" to DojoChain.TESTNET_5,
        "test5" to DojoChain.TESTNET_5,
    )


    /**
     * The chain a testnet-mounted Dojo index is on, or null for a
     * mainnet root mount / unparseable / no URL. Segment equality,
     * never substring (the fromUrl discipline: /attest/v2 stays
     * silent). Query and fragment are outside the parser.
     */
    fun fromUrl(url: String?): DojoChain? {
        val path = url?.let { runCatching { URI(it.trim()).path }.getOrNull() } ?: return null
        val segments = path.split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return segments.firstNotNullOfOrNull { CHAIN_SEGMENTS[it] }
    }
}