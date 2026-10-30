package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.whirlpool.ExistingLabel
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelPolicy
import java.util.Locale

/**
 * Display-only account perspective for an auto-written Whirlpool Tx0
 * label (#343, mix labels deferred).
 *
 * Sync stores one tx row per (txid, associatedPubKey), but the
 * auto-writer stores a single type-"tx" label keyed by bare txid
 * ("Whirlpool Tx0"). Every account row would otherwise show that same
 * string. This helper picks the text for the row being bound:
 *
 *  - Deposit (funds leaving) -> "Whirlpool Tx0"
 *  - Premix                   -> "Whirlpool Premix"
 *  - Bad Bank                 -> "Bad Bank"
 *  - any other role           -> the stored label, unchanged
 *
 * It never writes. [WhirlpoolLabelPolicy] decides whether the stored
 * row is ours to reinterpret: only an `auto:` origin may be remapped,
 * and only when the stored text is the configured Tx0 label. A label
 * the user typed (null origin), an empty origin, or a BIP-329 import
 * is returned as stored. Any other auto text, including "Whirlpool
 * Mix", is returned as stored. Remix amount rendering is a separate
 * path and is not touched here.
 *
 * Pure JVM. No Android, Room, or network. Callers pass the role they
 * already resolved from the collection's pub label.
 */
object TxPerspectiveLabel {

    enum class Role {
        DEPOSIT,
        PREMIX,
        BAD_BANK,
        OTHER,
    }

    /**
     * Premix-side display of an auto Tx0. Not a stored label and not
     * part of [WhirlpoolConfig.Labels] — the writer must keep writing
     * the single global Tx0 string.
     */
    const val PREMIX_DISPLAY = "Whirlpool Premix"

    /**
     * Account role of a collection pub, from its label. Wallet import
     * uses "Deposit", "Deposit BIP84/49/44", "Premix", and "Bad Bank"
     * (the tab title "Badbank" is accepted too). Anything else,
     * including Postmix, is [Role.OTHER] and is not remapped.
     */
    fun roleForPubLabel(label: String?): Role {
        val normalized = label?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            normalized == "bad bank" || normalized == "badbank" -> Role.BAD_BANK
            normalized == "premix" -> Role.PREMIX
            normalized == "deposit" || normalized.startsWith("deposit ") -> Role.DEPOSIT
            else -> Role.OTHER
        }
    }

    /**
     * Text to show for one row.
     *
     * @param storedLabel text from label_entries, or null when the tx has none
     * @param origin provenance of that row; null means the user typed it
     * @param role account of the row being shown
     */
    fun display(
        storedLabel: String?,
        origin: String?,
        role: Role,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): String? {
        if (storedLabel == null) return null
        if (!isAutoTx0(storedLabel, origin, config)) return storedLabel
        return when (role) {
            Role.DEPOSIT -> config.labels.tx0
            Role.PREMIX -> PREMIX_DISPLAY
            Role.BAD_BANK -> config.labels.badbank
            Role.OTHER -> storedLabel
        }
    }

    /**
     * True only for the auto-written Tx0 label. Manual and imported
     * rows fail the policy check and must be shown as typed.
     */
    private fun isAutoTx0(
        storedLabel: String,
        origin: String?,
        config: WhirlpoolConfig,
    ): Boolean {
        if (storedLabel != config.labels.tx0) return false
        return WhirlpoolLabelPolicy.decide(ExistingLabel(storedLabel, origin)) ==
            WhirlpoolLabelPolicy.Decision.Write
    }
}
