package com.samourai.sentinel.data.entropy

import com.samourai.boltzmann.Boltzmann
import com.samourai.boltzmann.beans.BoltzmannSettings
import com.samourai.boltzmann.beans.Txos
import com.samourai.boltzmann.linker.TxosLinkerOptionEnum
import com.samourai.sentinel.data.Tx
import it.unimi.dsi.fastutil.doubles.DoubleBigList
import it.unimi.dsi.fastutil.objects.ObjectBigList
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log2

/**
 * Per-tx Boltzmann entropy + linkability over the vendored
 * boltzmann-java engine (issue #7). Pure JVM (pattern:
 * WhirlpoolDetector / Bip329Parser).
 *
 * The engine call is local-only: analyze() feeds engine-built
 * [Txos] maps; the engine's txid-string entry point (network
 * fetch) is never touched from app code.
 *
 * Honesty contract (issue #7):
 *  - Numbers displayed are exactly what the vendored engine
 *    computes — never derived, never shaped into marketing.
 *    Engine truth for the reference shape (uniform 5x5 mix):
 *    1496 interpretations, log2(1496) ~= 10.55 bits — the
 *    same figure boltzmann-c documents for a real Whirlpool
 *    0.05 pool tx. The engine counts interpretations where
 *    several inputs belong to one entity: real possibilities,
 *    demanded by the issue's own clustering argument. The
 *    issue's illustrative "120 = 5!" (bijections only) is not
 *    a number this engine can produce for any 5x5; see the PR
 *    body. "Anon set 5^n" is never displayed — nbCmbn is.
 *  - nbCmbn == 0 is the engine's not-processed sentinel (txos
 *    cap tripped): reported as TooComplex. Never hang, never lie.
 *  - Inputs spending the same address are ONE entity: merged
 *    upfront (values summed) — same semantics as the engine's
 *    MERGE_INPUTS. Not merging would overstate entropy in the
 *    flattering direction.
 *  - Outputs to the same address are NOT merged (the engine's
 *    MERGE_OUTPUTS is explicitly not recommended): unique keys
 *    per output instead.
 *  - Time-box: the engine's own maxDuration is in SECONDS
 *    (default 600) and is pinned to 1 here as defense in depth.
 *    The caller-facing box is [timeBoxMs] via withTimeoutOrNull.
 *    Blocking CPU work cannot be interrupted — with the txos
 *    cap the worst case is a bounded thread that drains while
 *    the caller already holds TooComplex.
 */
sealed interface BoltzmannTxAnalysis {
    /** nbCmbn >= 2 interpretations: the honest numbers. */
    data class Honest(
        val nbCmbn: Int,
        val entropyBits: Double,
        /**
         * Linkability matrix indexed [output][input];
         * cell = P(input -> output). Uniform 5x5 mix: every
         * cell 0.2. Deterministic links = cells at 1.0.
         */
        val linkability: List<List<Double>>,
    ) : BoltzmannTxAnalysis

    /** Engine declined (txos cap, nbCmbn == 0) or time-box exceeded. */
    object TooComplex : BoltzmannTxAnalysis

    /** nbCmbn == 1: single interpretation, zero entropy. */
    object ZeroEntropy : BoltzmannTxAnalysis
}

class BoltzmannTxService(
    /** Caller-facing time-box in milliseconds (issue #7: ~500ms). */
    private val timeBoxMs: Long = DEFAULT_TIME_BOX_MS,
) {

    private val engine = Boltzmann(
        BoltzmannSettings().apply {
            maxDuration = 1
            maxTxos = MAX_TXOS
            maxCjIntrafeesRatio = 0f
            options = arrayOf(
                TxosLinkerOptionEnum.PRECHECK,
                TxosLinkerOptionEnum.LINKABILITY,
                TxosLinkerOptionEnum.MERGE_INPUTS,
            )
        }
    )

    suspend fun analyze(tx: Tx): BoltzmannTxAnalysis {
        val txos = toTxos(tx)
        val result = withTimeoutOrNull(timeBoxMs) { engine.process(txos) }
            ?: return BoltzmannTxAnalysis.TooComplex
        return when (result.nbCmbn) {
            0 -> BoltzmannTxAnalysis.TooComplex
            1 -> BoltzmannTxAnalysis.ZeroEntropy
            else -> BoltzmannTxAnalysis.Honest(
                nbCmbn = result.nbCmbn,
                // entropy is log2(nbCmbn) by definition; the fallback
                // computes the same number if the engine ever nulls it.
                entropyBits = result.entropy ?: log2(result.nbCmbn.toDouble()),
                linkability = result.matLnkProbabilities.toNestedLists(),
            )
        }
    }

    /**
     * Tx (Dojo wallet-response shape) -> engine Txos.
     *
     * Insertion order is load-bearing: the engine indexes
     * deterministic links positionally against these keys.
     * Zero-value IOs (OP_RETURN & friends) are dropped — the
     * engine filters them anyway; dropping early keeps the
     * positional story short.
     */
    fun toTxos(tx: Tx): Txos {
        val inputs = LinkedHashMap<String, Long>()
        tx.inputs.forEach { input ->
            val prev = input.prev_out ?: return@forEach
            if (prev.value <= 0L) return@forEach
            val key = prev.addr ?: "${prev.txid}:${prev.vout}"
            // Same address = one entity: values sum (honest merge).
            inputs.merge(key, prev.value, Long::plus)
        }
        val outputs = LinkedHashMap<String, Long>()
        tx.out.forEach { out ->
            if (out.value <= 0L) return@forEach
            outputs[uniqueKey(outputs, out.addr ?: "o${out.n}")] = out.value
        }
        return Txos(inputs, outputs)
    }

    private fun uniqueKey(map: Map<String, Long>, key: String): String {
        if (key !in map) return key
        var i = 2
        while ("$key#$i" in map) i++
        return "$key#$i"
    }

    private fun ObjectBigList<DoubleBigList>.toNestedLists(): List<List<Double>> {
        val rows = ArrayList<List<Double>>(size64().toInt())
        for (o in 0L until size64()) {
            val row = get(o)
            val cells = ArrayList<Double>(row.size64().toInt())
            for (i in 0L until row.size64()) cells.add(row.getDouble(i))
            rows.add(cells)
        }
        return rows
    }

    companion object {
        const val DEFAULT_TIME_BOX_MS = 500L

        /** Complexity cap: max inputs OR outputs (engine default, kept). */
        const val MAX_TXOS = 12
    }
}
