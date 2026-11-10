package com.samourai.sentinel.ui.tools.sweep

import com.samourai.wallet.api.backend.beans.UnspentOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.MainNetParams
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #477 (leg 4): the sweep's claimed utxos are held against the prev tx -
 * hash, output bounds, value, script - before any tx is built or signed.
 * All fixtures are constructed locally (OP_TRUE scripts); no addresses,
 * xpubs, or real txids appear anywhere in this file.
 */
class SweepUtxoVerifierTest {

    private val params = MainNetParams.get()
    private val opTrue = byteArrayOf(0x51)

    /**
     * One dummy OP_TRUE input pins the unambiguous legacy serialization
     * shape (no-input txs serialize 0x00 0x01 at bytes 4-5, which a
     * segwit parser mis-reads as BIP144 marker+flag - CI run
     * 42434828377).
     */
    private fun prevTx(vararg outputs: Pair<Long, ByteArray>): Transaction {
        val tx = Transaction(params)
        tx.addInput(TransactionInput(params, tx, byteArrayOf(0x51)))
        outputs.forEach { (value, script) ->
            tx.addOutput(TransactionOutput(params, tx, Coin.valueOf(value), script))
        }
        return tx
    }

    private fun hexOf(tx: Transaction): String = Hex.toHexString(tx.bitcoinSerialize())

    private fun claimed(
        tx: Transaction,
        outputIndex: Int,
        value: Long,
        script: ByteArray?
    ): UnspentOutput = UnspentOutput().apply {
        tx_hash = tx.hash.toString()
        tx_output_n = outputIndex
        tx_version = -1
        tx_locktime = -1
        this.value = value
        this.script = script?.let { Hex.toHexString(it) }
        confirmations = 1
    }

    private fun expectRefusal(utxos: List<UnspentOutput>, fetch: suspend (String) -> String?) {
        try {
            runBlocking { verifySweepUtxos(utxos, params, fetch) }
            fail("expected a SweepUtxoVerificationException")
        } catch (expected: SweepUtxoVerificationException) {
        }
    }

    @Test
    fun honestClaimedDataVerifies() {
        // One prev tx, two outputs - both claimed utxos verify per-index.
        val tx = prevTx(100_000L to opTrue, 50_000L to opTrue)
        val utxos = listOf(
            claimed(tx, 0, 100_000L, opTrue),
            claimed(tx, 1, 50_000L, opTrue)
        )
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        runBlocking { verifySweepUtxos(utxos, params) { txid -> served[txid] } }
    }

    @Test
    fun valueLieIsRefused() {
        val tx = prevTx(100_000L to opTrue)
        val lie = claimed(tx, 0, 99_999L, opTrue)
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        expectRefusal(listOf(lie)) { txid -> served[txid] }
    }

    @Test
    fun scriptLieIsRefused() {
        val tx = prevTx(100_000L to opTrue)
        val lie = claimed(tx, 0, 100_000L, byteArrayOf(0x60))
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        expectRefusal(listOf(lie)) { txid -> served[txid] }
    }

    @Test
    fun hashLieIsRefused() {
        // The fetch serves a genuine but different tx under the claimed id.
        val claimedTx = prevTx(100_000L to opTrue)
        val otherTx = prevTx(90_000L to opTrue)
        val lie = claimed(claimedTx, 0, 100_000L, opTrue)
        val served = mapOf(claimedTx.hash.toString() to hexOf(otherTx))
        expectRefusal(listOf(lie)) { txid -> served[txid] }
    }

    @Test
    fun missingPrevTxIsRefused() {
        val tx = prevTx(100_000L to opTrue)
        val utxo = claimed(tx, 0, 100_000L, opTrue)
        expectRefusal(listOf(utxo)) { _ -> null }
    }

    @Test
    fun outOfRangeIndexIsRefused() {
        val tx = prevTx(100_000L to opTrue)
        val lie = claimed(tx, 5, 100_000L, opTrue)
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        expectRefusal(listOf(lie)) { txid -> served[txid] }
    }

    @Test
    fun nullScriptClaimIsRefused() {
        val tx = prevTx(100_000L to opTrue)
        val lie = claimed(tx, 0, 100_000L, null)
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        expectRefusal(listOf(lie)) { txid -> served[txid] }
    }

    @Test
    fun malformedClaimedScriptHexIsRefusedBeforeBuildSignOrBroadcast() {
        // The decoder's own rejects: a non-hex digit, and an odd length.
        // Both used to escape as DecoderException, which the sweep button
        // does not catch, so its toast and spinner cleanup never ran and
        // the lines after verifySweepUtxos (build, sign, broadcast) were
        // not the refusal path either.
        val tx = prevTx(100_000L to opTrue)
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        listOf("zz", "abc").forEach { bad ->
            val lie = claimed(tx, 0, 100_000L, opTrue).apply { script = bad }
            var built = 0
            var signed = 0
            var broadcast = 0
            try {
                runBlocking {
                    verifySweepUtxos(listOf(lie), params) { txid -> served[txid] }
                    built++
                    signed++
                    broadcast++
                }
                fail("expected a SweepUtxoVerificationException for script $bad")
            } catch (expected: SweepUtxoVerificationException) {
                assertEquals(0, built)
                assertEquals(0, signed)
                assertEquals(0, broadcast)
                assertTrue(expected.message!!.contains("script unparseable"))
            } catch (other: Exception) {
                fail("script $bad escaped as ${other.javaClass.name}: ${other.message}")
            }
        }
    }

    @Test
    fun scriptHexTheDecoderAcceptsStillVerifies() {
        // BouncyCastle ignores ASCII whitespace. A stricter pattern would
        // refuse a script the real decoder accepts.
        val tx = prevTx(100_000L to opTrue)
        val utxo = claimed(tx, 0, 100_000L, opTrue).apply { script = "5 1" }
        runBlocking { verifySweepUtxos(listOf(utxo), params) { hexOf(tx) } }
    }

    @Test
    fun scriptDecodeCancellationIsNotWrapped() {
        val tx = prevTx(100_000L to opTrue)
        val utxo = object : UnspentOutput() {
            override fun getScriptBytes(): ByteArray {
                throw CancellationException("sweep cancelled")
            }
        }.apply {
            tx_hash = tx.hash.toString()
            tx_output_n = 0
            value = 100_000L
            script = "51"
            confirmations = 1
        }
        try {
            runBlocking { verifySweepUtxos(listOf(utxo), params) { hexOf(tx) } }
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("sweep cancelled", expected.message)
        } catch (other: Exception) {
            fail("cancellation wrapped as ${other.javaClass.name}: ${other.message}")
        }
    }

    @Test
    fun nullNetworkParametersFailClosed() {
        val tx = prevTx(100_000L to opTrue)
        val utxo = claimed(tx, 0, 100_000L, opTrue)
        val served = mapOf(tx.hash.toString() to hexOf(tx))
        try {
            runBlocking { verifySweepUtxos(listOf(utxo), null) { txid -> served[txid] } }
            fail("expected a SweepUtxoVerificationException")
        } catch (expected: SweepUtxoVerificationException) {
        }
    }
}
