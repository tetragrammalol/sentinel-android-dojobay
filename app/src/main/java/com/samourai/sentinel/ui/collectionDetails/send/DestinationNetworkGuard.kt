package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.data.PubKeyModel

/**
 * #654: the pub-level cross-network guard for the destination doors.
 *
 * A pub is classified by its OWN xpub serialization
 * (NetworkClassifier.fromXpub), never by the active network: the
 * destination walk (SendViewModel.setOwnDestination ->
 * firstFresh/derivedReceiveAddress) derives on the ACTIVE network
 * parameters, so a testnet account key in a mainnet app derives a
 * perfectly valid mainnet address - validity is exactly why nothing
 * refused it, and the address belongs to no wallet the recipient
 * tracks on mainnet (#654 device repro; the reverse is the same
 * class).
 *
 * The address-level guards cannot catch this class:
 * SendViewModel.isWrongNetworkAddress compares the DERIVED address's
 * hrp against the active network, and the derivation already ran on
 * the active network's parameters - the derived address always
 * looks right. The collection-level grey (#429
 * collectionRowNetworkState) cannot either: a mixed collection
 * derives no verdict and renders Normal while the one pub a tap would
 * resolve can still be cross-network. The pub is the narrowest door
 * - this guard sits on it.
 *
 * UNKNOWN (an unrecognized prefix, a standalone address, a blank) is
 * never cross-network: refusing an unrecognized prefix is the #42
 * shape (a wrong verdict that rejects correct keys). A named mismatch
 * only - both directions.
 *
 * Watch-only: this classifies public derivation data only; no crypto
 * code, no key material, no Dojo calls.
 */
internal fun pubIsCrossNetwork(pub: PubKeyModel, activeIsTestnet: Boolean): Boolean =
    when (NetworkClassifier.fromXpub(pub.pubKey)) {
        DerivedNetwork.TESTNET -> !activeIsTestnet
        DerivedNetwork.MAINNET -> activeIsTestnet
        DerivedNetwork.UNKNOWN -> false
    }
