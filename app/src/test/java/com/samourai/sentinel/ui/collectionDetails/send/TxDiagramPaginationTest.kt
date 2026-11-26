package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #644 slice 1: the pagination split - Sparrow's own caps (8 inputs /
 * 6 outputs) with one aggregate row per side when a side overflows.
 * Pure JVM: rows in, sides out, no Android, no network parameters.
 */
class TxDiagramPaginationTest {

    private fun inputRow(ordinal: Int, sats: Long) =
        TxDiagramRow(
            kind = TxDiagramRow.Kind.INPUT,
            label = null,
            ordinal = ordinal,
            sats = sats,
        )

    private fun changeRow(sats: Long) =
        TxDiagramRow(
            kind = TxDiagramRow.Kind.CHANGE,
            label = null,
            ordinal = 0,
            sats = sats,
        )

    private fun diagramOf(inputs: List<TxDiagramRow>, outputs: List<TxDiagramRow>) =
        TxDiagram(
            rows = inputs + outputs,
            feeSats = 42L,
            paymentSats = 0L,
            spendsEntirely = false,
        )

    @Test
    fun under_the_caps_no_aggregate_row() {
        val page = paginateTxDiagram(
            diagramOf(
                (0 until 3).map { inputRow(it + 1, 1000L + it) },
                listOf(changeRow(500L)),
            )
        )
        assertEquals(3, page.inputs.rows.size)
        assertEquals(0, page.inputs.moreCount)
        assertEquals(0L, page.inputs.moreSats)
        assertEquals(1, page.outputs.rows.size)
        assertEquals(0, page.outputs.moreCount)
        assertEquals(0L, page.outputs.moreSats)
    }

    @Test
    fun at_the_caps_no_aggregate_row() {
        val page = paginateTxDiagram(
            diagramOf(
                (0 until 8).map { inputRow(it + 1, 1000L) },
                (0 until 6).map { changeRow(500L) },
            )
        )
        assertEquals(8, page.inputs.rows.size)
        assertEquals(0, page.inputs.moreCount)
        assertEquals(6, page.outputs.rows.size)
        assertEquals(0, page.outputs.moreCount)
    }

    @Test
    fun inputs_over_the_cap_fold_into_one_aggregate() {
        // 10 inputs: 7 shown, 3 folded. Hidden sats 1007 + 1008 + 1009.
        val page = paginateTxDiagram(
            diagramOf(
                (0 until 10).map { inputRow(it + 1, 1000L + it) },
                listOf(changeRow(500L)),
            )
        )
        assertEquals(7, page.inputs.rows.size)
        assertEquals(1000L, page.inputs.rows.first().sats)
        assertEquals(1006L, page.inputs.rows.last().sats)
        assertEquals(3, page.inputs.moreCount)
        assertEquals(3024L, page.inputs.moreSats)
    }

    @Test
    fun outputs_over_the_cap_fold_into_one_aggregate() {
        // 8 outputs: 5 shown, 3 folded. Hidden sats 505 + 506 + 507.
        val page = paginateTxDiagram(
            diagramOf(
                listOf(inputRow(1, 1000L)),
                (0 until 8).map { changeRow(500L + it) },
            )
        )
        assertEquals(5, page.outputs.rows.size)
        assertEquals(3, page.outputs.moreCount)
        assertEquals(1518L, page.outputs.moreSats)
    }

    @Test
    fun one_over_the_cap_is_two_hidden_rows() {
        val page = paginateTxDiagram(
            diagramOf(
                (0 until 9).map { inputRow(it + 1, 1000L) },
                listOf(changeRow(500L)),
            )
        )
        assertEquals(7, page.inputs.rows.size)
        assertEquals(2, page.inputs.moreCount)
        assertEquals(2000L, page.inputs.moreSats)
    }

    @Test
    fun fee_and_spends_entirely_pass_through() {
        val page = paginateTxDiagram(
            TxDiagram(
                rows = listOf(inputRow(1, 1000L), changeRow(500L)),
                feeSats = 42L,
                paymentSats = 500L,
                spendsEntirely = true,
            )
        )
        assertEquals(42L, page.feeSats)
        assertFalse(page.feeIsHigh)
        assertTrue(page.spendsEntirely)
    }

    @Test
    fun fee_is_high_strictly_above_10_percent_of_the_payment() {
        fun pageOf(feeSats: Long, paymentSats: Long) = paginateTxDiagram(
            TxDiagram(
                rows = listOf(inputRow(1, 1000L), changeRow(500L)),
                feeSats = feeSats,
                paymentSats = paymentSats,
                spendsEntirely = false,
            )
        )
        // 1500 sat fee on a 10000 sat payment: 15% - warns.
        assertTrue(pageOf(1_500L, 10_000L).feeIsHigh)
        // Exactly 10% stays quiet: Sparrow's edge is strictly above.
        assertFalse(pageOf(1_000L, 10_000L).feeIsHigh)
        // No matched payment (0 sats): never warns, whatever the fee.
        assertFalse(pageOf(1_500L, 0L).feeIsHigh)
    }
}
