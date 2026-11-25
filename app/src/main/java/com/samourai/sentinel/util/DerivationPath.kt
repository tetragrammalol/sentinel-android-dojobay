package com.samourai.sentinel.util

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.wallet.util.XPUB

/**
 * #646 leg 1 / #647: the one shared derivation-path renderer.
 *
 * Notation is standard BIP32 with no stray spaces: m/84'/0'/0'/0/24.
 * The receive advanced view still builds its own string today -
 * re-pointing that surface (and any other path rendering) here is
 * #647's own slice; nothing in this file changes it.
 *
 * Reads public derivation data only: the purpose off the pub's
 * type, the coin off the active network, the account decoded
 * from the key, chain 0. No crypto state is written - the same
 * read-only decode the receive screen already performs.
 */

/** #646: hardened offset for the account child decoded off an XPUB. */
internal const val DERIVATION_HARDENED_OFFSET = 2_147_483_648L

/**
 * #646: the receive path for one slot, standard notation, pure.
 */
internal fun receivePathText(purpose: Int, coin: Int, account: Long, index: Int): String =
    "m/$purpose'/$coin'/$account'/0/$index"

/**
 * #646: the path line a destination-picker row shows for [pub]:
 * the pub's stored receive index - the same figure the receive
 * advanced view renders, so the two screens are comparable. The
 * caller resolves which pub of a collection that is
 * (resolveDestinationPubIndex); a standalone-address pub has no
 * chain to name and renders nothing.
 *
 * The walk's own index is NOT recomputed here: no Room read rides
 * a sheet render, and the tap's honest verdict stays the notice's
 * job.
 */
internal fun receivePathFor(pub: PubKeyModel, testnet: Boolean): String? {
    if (pub.type == AddressTypes.ADDRESS) return null
    val xpub = XPUB(pub.pubKey)
    xpub.decode()
    val account = xpub.child + DERIVATION_HARDENED_OFFSET
    return receivePathText(
        purpose = pub.getPurpose(),
        coin = if (testnet) 1 else 0,
        account = account,
        index = pub.account_index,
    )
}
