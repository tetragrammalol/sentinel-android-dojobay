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
