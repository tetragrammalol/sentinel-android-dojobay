package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import org.bitcoinj.core.ECKey
import org.bitcoinj.params.MainNetParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #635: the pre-QR diagram builder. Pure JVM - the builder sees the
 * snapshot's inputs and outputs as plain data, so classification,
 * naming and the fee arithmetic run without Android or a PSBT.
 */
class TxDiagramTest {

    private val params = MainNetParams()
    private val destinationKey = ECKey.fromPrivate(ByteArray(32) { 0x42 })
    private val changeKey = ECKey.fromPrivate(ByteArray(32) { 0x43 })

    private fun addressOf(key: ECKey): String =
        SegwitAddress(key.pubKey, params).bech32AsString

    private fun scriptOf(address: String): ByteArray =
        Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)

    @Test
    fun `payment and change classify by script, labels land on their rows`() {
        val destination = addressOf(destinationKey)
        val change = addressOf(changeKey)
        val diagram = diagramFor(
            inputs = listOf(
                TxDiagramInput(outpoint = "tx-a:0", sats = 10_000L),
                TxDiagramInput(outpoint = "tx-b:1", sats = 5_000L),
            ),
            outputs = listOf(
                TxDiagramOutput(scriptOf(destination), 12_000L),
                TxDiagramOutput(scriptOf(change), 1_700L),
            ),
            destinationAddress = destination,
            params = params,
            coinLabels = mapOf("tx-a:0" to "labelled coin"),
            addressLabels = mapOf(destination to "payee"),
        )
        assertEquals(4, diagram.rows.size)
        val labelled = diagram.rows[0]
        assertEquals(TxDiagramRow.Kind.INPUT, labelled.kind)
        assertEquals("labelled coin", labelled.label)
        assertEquals(1, labelled.ordinal)
        assertEquals(10_000L, labelled.sats)
        val plain = diagram.rows[1]
        assertEquals(TxDiagramRow.Kind.INPUT, plain.kind)
        assertNull(plain.label)
        assertEquals(2, plain.ordinal)
        assertEquals(5_000L, plain.sats)
        val payment = diagram.rows[2]
        assertEquals(TxDiagramRow.Kind.DESTINATION, payment.kind)
        assertEquals("payee", payment.label)
        assertEquals(12_000L, payment.sats)
        val changeRow = diagram.rows[3]
        assertEquals(TxDiagramRow.Kind.CHANGE, changeRow.kind)
        assertEquals(1_700L, changeRow.sats)
        assertEquals(10_000L + 5_000L - 12_000L - 1_700L, diagram.feeSats)
        assertFalse(diagram.spendsEntirely)
    }

    @Test
    fun `bip69 change-first order still classifies - first script match is the payment`() {
        val destination = addressOf(destinationKey)
        val change = addressOf(changeKey)
        val diagram = diagramFor(
            inputs = listOf(TxDiagramInput(outpoint = "tx-a:0", sats = 20_000L)),
            outputs = listOf(
                TxDiagramOutput(scriptOf(change), 1_700L),
                TxDiagramOutput(scriptOf(destination), 12_000L),
            ),
            destinationAddress = destination,
            params = params,
            coinLabels = emptyMap(),
            addressLabels = mapOf(change to "my change"),
        )
        assertEquals(3, diagram.rows.size)
        assertEquals(TxDiagramRow.Kind.CHANGE, diagram.rows[1].kind)
        assertEquals("my change", diagram.rows[1].label)
        assertEquals(TxDiagramRow.Kind.DESTINATION, diagram.rows[2].kind)
        assertEquals(20_000L - 13_700L, diagram.feeSats)
        assertFalse(diagram.spendsEntirely)
    }

    @Test
    fun `a single output paying the destination spends entirely`() {
        val destination = addressOf(destinationKey)
        val diagram = diagramFor(
            inputs = listOf(TxDiagramInput(outpoint = "tx-a:0", sats = 10_000L)),
            outputs = listOf(TxDiagramOutput(scriptOf(destination), 9_900L)),
            destinationAddress = destination,
            params = params,
            coinLabels = emptyMap(),
            addressLabels = emptyMap(),
        )
        assertEquals(2, diagram.rows.size)
        assertEquals(TxDiagramRow.Kind.INPUT, diagram.rows[0].kind)
        assertEquals(TxDiagramRow.Kind.DESTINATION, diagram.rows[1].kind)
        assertTrue(diagram.spendsEntirely)
        assertEquals(100L, diagram.feeSats)
    }
}
