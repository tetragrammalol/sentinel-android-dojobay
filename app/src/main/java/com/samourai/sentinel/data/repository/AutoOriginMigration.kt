package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelPolicy

internal data class AutoOriginDemotion(
    val entries: List<LabelEntry>,
    val utxos: List<UtxoLabel>,
)

/**
 * Hand edits saved while the writer still kept an auto: origin.
 * Tx and utxo rows whose text is not one of [autoTexts] become
 * manual (origin null) so the next sync does not overwrite them.
 * Exact auto text stays auto. Address and other entry types are
 * left alone.
 */
internal fun planAutoOriginDemotion(
    entries: List<LabelEntry>,
    utxos: List<UtxoLabel>,
    autoTexts: Set<String> = WhirlpoolConfig.DEFAULT.labels.autoWritten(),
): AutoOriginDemotion {
    val tx = ArrayList<LabelEntry>()
    for (row in entries) {
        if (row.type != LabelType.TX.wire) continue
        if (!isHandEditedAutoLabel(row.origin, row.label, autoTexts)) continue
        tx.add(row.copy(origin = null))
    }
    val utxo = ArrayList<UtxoLabel>()
    for (row in utxos) {
        if (!isHandEditedAutoLabel(row.origin, row.label, autoTexts)) continue
        utxo.add(row.copy(origin = null))
    }
    return AutoOriginDemotion(tx, utxo)
}

internal fun isHandEditedAutoLabel(
    origin: String?,
    label: String,
    autoTexts: Set<String>,
): Boolean {
    if (origin?.startsWith(WhirlpoolLabelPolicy.AUTO_NAMESPACE) != true) return false
    return label !in autoTexts
}

/**
 * Runs [planAutoOriginDemotion] once. [alreadyRan] skips the scan.
 * [markRan] is called only after [apply] returns, so a failed attempt
 * does not stick the flag.
 */
internal suspend fun runAutoOriginMigration(
    alreadyRan: () -> Boolean,
    markRan: () -> Unit,
    loadEntries: suspend () -> List<LabelEntry>,
    loadUtxos: suspend () -> List<UtxoLabel>,
    apply: suspend (AutoOriginDemotion) -> Unit,
    autoTexts: Set<String> = WhirlpoolConfig.DEFAULT.labels.autoWritten(),
) {
    if (alreadyRan()) return
    val plan = planAutoOriginDemotion(loadEntries(), loadUtxos(), autoTexts)
    apply(plan)
    markRan()
}
