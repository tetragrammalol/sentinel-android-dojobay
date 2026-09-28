package com.samourai.sentinel.core

/**
 * #42: derivation primitive for network state.
 *
 * Version-byte prefixes are unambiguous for the xpub family:
 * tpub/upub/vpub are testnet keys, xpub/ypub/zpub are mainnet.
 * Everything else is UNKNOWN — never MAINNET, because defaulting
 * unrecognized input to mainnet is exactly the shape of the bug
 * this fixes (a wrong verdict that rejects correct keys).
 *
 * Deliberately dependency-free: core must not reach into ui/util,
 * and this needs no bitcoinj. Validation of the xpub itself stays
 * with FormatsUtil.isValidXpub; this only answers "which network".
 */
enum class DerivedNetwork { MAINNET, TESTNET, UNKNOWN }

object NetworkClassifier {

    /**
     * Case-insensitive to match the legacy isPublicKeyTesnet
     * behavior (payload.lowercase().startsWith). Addresses are not
     * classified here — this primitive is for xpub-family keys only.
     */
    fun fromXpub(xpub: String): DerivedNetwork {
        val key = xpub.lowercase()
        return when {
            key.startsWith("tpub") || key.startsWith("upub") ||
                key.startsWith("vpub") -> DerivedNetwork.TESTNET
            key.startsWith("xpub") || key.startsWith("ypub") ||
                key.startsWith("zpub") -> DerivedNetwork.MAINNET
            else -> DerivedNetwork.UNKNOWN
        }
    }
}
