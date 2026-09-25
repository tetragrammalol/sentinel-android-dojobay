package com.samourai.sentinel.data.entropy

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.prevOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

/**
 * Oracles are the vendored engine's own model (LaurentMT's
 * Boltzmann): an interpretation is any consistent story,
 * including ones where one entity holds several inputs.
 *
 * External cross-checks for these exact shapes:
 *  - 2x2 uniform coinjoin = 3 interpretations, E = 1.585 —
 *    the canonical example from the original Boltzmann paper.
 *  - 5x5 uniform mix = 1496 combinations, E = 10.55 — the
 *    figure boltzmann-c documents for a real Whirlpool 0.05
 *    pool tx. 1496 = 120 bijections + merged-entity
 *    interpretations (600+450+200+100+25+1).
 *
 * The issue's illustrative "120 = 5!" counts bijections only;
 * no fee/shape combination makes this engine produce it for a
 * 5x5. We assert engine truth, never marketing numbers. See
 * PR body for the issue-text discrepancy note.
 */
class BoltzmannTxServiceTest {

    private val service = BoltzmannTxService()

    private fun txOf(ins: List<Pair<String, Long>>, outs: List<Pair<String, Long>>): Tx =
        Tx(
            hash = "aa".repeat(32),
            time = 0L,
            version = 1,
            locktime = 0,
            result = null,
            inputs = ins.mapIndexed { vin, (addr, value) ->
                Inputs(
                    vin = vin,
                    sequence = null,
                    prev_out = prevOut(
                        addr = addr,
                        txid = "bb".repeat(32),
                        value = value,
                        vout = 0,
                        xpub = null,
                    ),
                )
            },
            out = outs.mapIndexed { n, (addr, value) ->
                Out(n = n, value = value, addr = addr, xpub = null)
            },
            block_height = null,
        )

    @Test
    fun uniformFiveByFiveMixYieldsTheEnginesCanonicalNumbers() {
        // Real mix shape: inputs carry a small fee buffer over the
        // denomination, outputs are exact. Fee slack only permits
        // same-unit-count aggregate matches, so the partition
        // count is the canonical 1496, every cell 512/1496.
        val ins = (0 until 5).map { "in$it" to 1_000_100L }
        val outs = (0 until 5).map { "out$it" to 1_000_000L }
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue(analysis is BoltzmannTxAnalysis.Honest)
        val honest = analysis as BoltzmannTxAnalysis.Honest
        assertEquals(1496, honest.nbCmbn)
        assertEquals(log2(1496.0), honest.entropyBits, 1e-6)
        assertEquals(5, honest.linkability.size)
        honest.linkability.forEach { row ->
            assertEquals(5, row.size)
            row.forEach { cell -> assertEquals(512.0 / 1496.0, cell, 1e-9) }
        }
    }

    @Test
    fun sameAddressInputsAreOneEntityNotFlatteringEntropy() {
        // Two of five inputs share an address: one merged entity.
        // Partition count over {A=2 units, b, c, d} vs 5 outputs =
        // 60 + 60 + 90 + 30 + 10 + 15 + 1 = 266 — strictly below
        // the unmerged 1496: an honest merge can only remove
        // interpretations, never add them. Entropy drops with it.
        val ins = listOf(
            "a" to 1_000_100L,
            "a" to 1_000_100L,
            "b" to 1_000_100L,
            "c" to 1_000_100L,
            "d" to 1_000_100L,
        )
        val outs = (0 until 5).map { "out$it" to 1_000_000L }
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue(analysis is BoltzmannTxAnalysis.Honest)
        val honest = analysis as BoltzmannTxAnalysis.Honest
        assertEquals(266, honest.nbCmbn)
        assertEquals(log2(266.0), honest.entropyBits, 1e-6)
        assertTrue(honest.nbCmbn < 1496)
    }

    @Test
    fun twoByTwoUniformIsTheCanonicalThreeInterpretations() {
        // The original Boltzmann paper's example: 2 bijections +
        // "one entity spent both inputs to both outputs" = THREE
        // interpretations, E = log2(3), cells 2/3. The zero-value
        // output (OP_RETURN shape) must be dropped before compute.
        val ins = (0 until 2).map { "in$it" to 1_000_100L }
        val outs = (0 until 2).map { "out$it" to 1_000_000L } + ("opreturn" to 0L)
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue(analysis is BoltzmannTxAnalysis.Honest)
        val honest = analysis as BoltzmannTxAnalysis.Honest
        assertEquals(3, honest.nbCmbn)
        assertEquals(log2(3.0), honest.entropyBits, 1e-6)
        assertEquals(2, honest.linkability.size)
        honest.linkability.forEach { row ->
            assertEquals(2, row.size)
            row.forEach { cell -> assertEquals(2.0 / 3.0, cell, 1e-9) }
        }
    }

    @Test
    fun beyondTheTxosCapReportsTooComplex() {
        val ins = (0 until 13).map { "in$it" to 1_000_000L }
        val outs = listOf("out0" to 12_000_000L, "out1" to 1_000_000L)
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue(analysis is BoltzmannTxAnalysis.TooComplex)
    }

    @Test
    fun plainSingleInputSpendIsZeroEntropy() {
        val ins = listOf("in0" to 3_000_000L)
        val outs = listOf("out0" to 2_000_000L, "out1" to 1_000_000L)
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue(analysis is BoltzmannTxAnalysis.ZeroEntropy)
    }
}
