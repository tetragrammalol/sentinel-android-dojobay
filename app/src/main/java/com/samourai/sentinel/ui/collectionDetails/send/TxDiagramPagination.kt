package com.samourai.sentinel.ui.collectionDetails.send

/**
 * #644 slice 1: Sparrow-style pagination for the tx diagram sides.
 *
 * Sparrow's TransactionDiagram caps a side at 8 inputs / 6 outputs
 * and folds the overflow into one "N more..." aggregate row - the
 * phone adaptation of its scrollable side windows. Pure data: rows
 * in, sides out. The render walks [TxDiagramPage]; the builder in
 * TxDiagram.kt is untouched.
 *
 * Fee and the spends-entirely line are not side rows (fee is its own
 * branch in Sparrow), so they pass straight through to the page.
 */
internal data class TxDiagramSide(
    /** The rows the side paints, in order. */
    val rows: List<TxDiagramRow>,
    /**
     * Rows hidden behind the aggregate, or 0 when the side fits.
     * Sparrow's shape: cap - 1 real rows plus the aggregate, so a
     * side never paints more than its cap.
     */
    val moreCount: Int,
    /** The summed sats of the hidden rows, for the aggregate's value. */
    val moreSats: Long,
)

internal data class TxDiagramPage(
    val inputs: TxDiagramSide,
    val outputs: TxDiagramSide,
    val feeSats: Long,
    /**
     * #644 slice 3: true when the fee is strictly above 10% of the
     * payment - Sparrow's warning shape. Derived here on pure data so
     * the render never re-derives it; exactly 10% stays quiet, and a
     * page with no matched payment never warns.
     */
    val feeIsHigh: Boolean,
    val spendsEntirely: Boolean,
)

/** Sparrow's own caps: MAX_UTXOS / MAX_PAYMENTS. */
internal const val TX_DIAGRAM_MAX_INPUTS = 8
internal const val TX_DIAGRAM_MAX_OUTPUTS = 6

internal fun paginateTxDiagram(diagram: TxDiagram): TxDiagramPage =
    TxDiagramPage(
        inputs = paginateSide(
            diagram.rows.filter { it.kind == TxDiagramRow.Kind.INPUT },
            TX_DIAGRAM_MAX_INPUTS,
        ),
        outputs = paginateSide(
            diagram.rows.filter { it.kind != TxDiagramRow.Kind.INPUT },
            TX_DIAGRAM_MAX_OUTPUTS,
        ),
        feeSats = diagram.feeSats,
        feeIsHigh = diagram.paymentSats > 0L &&
            diagram.feeSats * 10 > diagram.paymentSats,
        spendsEntirely = diagram.spendsEntirely,
    )

private fun paginateSide(rows: List<TxDiagramRow>, cap: Int): TxDiagramSide =
    if (rows.size <= cap) {
        TxDiagramSide(rows = rows, moreCount = 0, moreSats = 0L)
    } else {
        val shown = rows.take(cap - 1)
        val hidden = rows.drop(cap - 1)
        TxDiagramSide(
            rows = shown,
            moreCount = hidden.size,
            moreSats = hidden.sumOf { it.sats },
        )
    }

/**
 * #672: interleave a shorter side's invisible padding so its real
 * rows spread evenly down the card instead of bunching mid-card.
 *
 * Both columns share one row grid - [rowCount] slots, the taller
 * side's count - and every real row paints a branch through the
 * card's center hub. Half-before/half-after padding (#666 item 2)
 * parked the short side's rows mid-card, so its branches overlapped
 * in a tight clump next to the hub while the tall side fanned wall
 * to wall. Real row k (0-based, m = [slots].size) lands on grid slot
 * floor((k + 1) * rowCount / (m + 1)) - the slot whose center is
 * nearest Sparrow's (k + 1)/(m + 1) mark, its own full-height fan. A
 * lone row still lands mid-card, so #666 item 2's ask survives.
 *
 * Pure: the side's real sats in, sats-or-null out, same sats in the
 * same order. The nulls stay invisible - the canvas draws neither
 * curve nor circle for them, and the screen inserts the placeholder
 * rows at the same slots.
 */
internal fun distributeSlots(slots: List<Long?>, rowCount: Int): List<Long?> {
    val real = slots.size
    if (rowCount <= real) return slots
    val distributed = MutableList<Long?>(rowCount) { null }
    slots.forEachIndexed { k, sats ->
        distributed[(k + 1) * rowCount / (real + 1)] = sats
    }
    return distributed
}
