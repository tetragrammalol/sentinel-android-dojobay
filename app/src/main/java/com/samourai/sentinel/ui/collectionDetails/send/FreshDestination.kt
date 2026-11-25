package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.core.segwit.P2SH_P2WPKH
import com.samourai.sentinel.data.AddressTypes
import com.samourai.wallet.segwit.SegwitAddress

/**
 * #635 slice 1: the send screen's destination and the fresh-address
 * guarantee behind it.
 *
 * A destination is either an address from outside our watch (typed,
 * pasted, scanned - the only kind before #635) or one of our own
 * collections. An own destination is composed only from a receive
 * address proven never used: no output in the synced history pays it,
 * and no addr label marks it as handed out. The address that reaches
 * compose is re-derived from the wallet's own keys and matched byte
 * for byte first - the same trust-nothing shape as the input-side
 * derived-script check in TransactionComposer.kt (#477).
 *
 * Nothing here calls Dojo: history is the synced transactions table,
 * labels are the local label_entries table. Nothing here mutates any
 * crypto state: the derivation calls are the same read-only ones
 * TransactionComposer's change sibling already makes.
 */

/** The send screen's destination. */
sealed interface Destination {

    /** An address from outside our watch: typed, pasted, or scanned. */
    data class External(val address: String) : Destination

    /**
     * One of our own collections. [freshAddress] is the first unused
     * receive address of the walk; [verified] is true only after the
     * same index was re-derived from the wallet's own keys and matched
     * byte for byte.
     */
    data class Own(
        val collectionId: String,
        val pubKey: String,
        val freshAddress: String,
        val verified: Boolean,
    ) : Destination
}

/** #635: why one walk candidate was not fresh. */
enum class FreshnessReason { HISTORY, LABEL }

/** #635: one skipped walk index and why. */
data class FreshSkip(val index: Int, val reason: FreshnessReason)

/**
 * #635: the walk's answer. Found carries the address, its index, and
 * every skip made on the way; Exhausted carries the skips and no
 * address - the caller refuses the destination, it never falls back
 * to a raw string.
 */
sealed interface FreshWalk {
    data class Found(val address: String, val index: Int, val skips: List<FreshSkip>) : FreshWalk
    data class Exhausted(val skips: List<FreshSkip>) : FreshWalk
}

/** How far the walk may go before giving up - the receive gap, in indexes. */
internal const val RECEIVE_WALK_CEILING = 100

/**
 * #635: the fresh verdict for one candidate, or null when it is fresh.
 * An address in the history is used; a labelled address is treated as
 * handed out even with no history on it yet (a label means a payer
 * already saw it). Both inputs are the caller's own reads.
 */
internal fun freshnessOf(
    candidate: String,
    usedAddresses: Set<String>,
    isLabelled: (String) -> Boolean,
): FreshnessReason? = when {
    usedAddresses.contains(candidate) -> FreshnessReason.HISTORY
    isLabelled(candidate) -> FreshnessReason.LABEL
    else -> null
}

/**
 * #635: walk the receive chain, index 0 up to [ceiling], and stop at
 * the first fresh candidate. [derive] is the wallet's own per-index
 * derivation; a null derivation fails the walk closed (Exhausted) - an
 * address the wallet cannot derive is never handed out. Every skip is
 * carried so the caller can say it out loud.
 *
 * #646 leg 4: [start] is where the walk starts - the stored Dojo
 * counter for an own destination. A slot below the counter was handed
 * out to someone once, so it is never re-offered; a negative start
 * clamps to 0, and a start near Int.MAX_VALUE clamps its end instead
 * of overflowing.
 */
internal fun firstFresh(
    start: Int = 0,
    ceiling: Int,
    usedAddresses: Set<String>,
    isLabelled: (String) -> Boolean,
    derive: (Int) -> String?,
): FreshWalk {
    val skips = mutableListOf<FreshSkip>()
    val first = start.coerceAtLeast(0)
    val last = first.coerceAtMost(Int.MAX_VALUE - ceiling) + ceiling
    for (index in first until last) {
        val candidate = derive(index) ?: return FreshWalk.Exhausted(skips.toList())
        val reason = freshnessOf(candidate, usedAddresses, isLabelled)
        if (reason == null) {
            return FreshWalk.Found(candidate, index, skips.toList())
        }
        skips += FreshSkip(index, reason)
    }
    return FreshWalk.Exhausted(skips.toList())
}

/**
 * #635: the receive address the wallet itself derives for [index] -
 * chain 0, the same per-type derivation as the change sibling in
 * TransactionComposer (P2PKH for 44, P2SH-P2WPKH for 49, P2WPKH for
 * 84). Null for a type the wallet does not derive, or a missing
 * network - the walk fails closed on it.
 */
internal fun derivedReceiveAddress(
    pubKey: String,
    type: AddressTypes?,
    index: Int,
): String? {
    val params = SentinelState.getNetworkParam() ?: return null
    val account = HD_Account(params, pubKey, "", 0)
    return when (type) {
        AddressTypes.BIP44 -> account.receive?.getAddressAt(index)?.addressString
        AddressTypes.BIP49 -> {
            val key = account.receive?.getAddressAt(index)?.ecKey ?: return null
            P2SH_P2WPKH(key.pubKey, params).addressAsString
        }
        AddressTypes.BIP84 -> {
            val key = account.getChain(0)?.getAddressAt(index)?.ecKey ?: return null
            SegwitAddress(key.pubKey, params).bech32AsString
        }
        else -> null
    }
}

/**
 * #635: which pub of a picked collection the fresh walk walks - its
 * receive account, never a standalone address or a spend source.
 *
 * A wallet-imported collection carries its receive keys under the
 * "Deposit BIPxx" labels (the send screen's own mapping, which renames
 * the BIP84 deposit to "Deposit" in memory), so the BIP84 deposit is
 * the walk key; anything else walks the first pub that is not a
 * standalone address. No eligible pub is null: the picker refuses the
 * destination loudly, it never walks pubs[0] blind.
 */
internal fun resolveDestinationPubIndex(
    isImportFromWallet: Boolean,
    labels: List<String?>,
    types: List<AddressTypes?>,
): Int? {
    if (isImportFromWallet) {
        val bip84 = labels.indexOfFirst { it == "Deposit BIP84" }
        if (bip84 >= 0) return bip84
        val deposit = labels.indexOfFirst { it == "Deposit" }
        return if (deposit >= 0) deposit else null
    }
    val first = types.indexOfFirst { it != AddressTypes.ADDRESS }
    return if (first >= 0) first else null
}
