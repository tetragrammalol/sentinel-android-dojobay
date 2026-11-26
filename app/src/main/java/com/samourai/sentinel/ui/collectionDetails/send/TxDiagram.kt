package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.FormatsUtilGeneric
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bouncycastle.util.encoders.Hex

/**
 * #635: the pre-QR transaction diagram, Sparrow-style.
 *
 * The builder is pure: it sees the snapshot's inputs and outputs as
 * plain data, never the composer, the pool, or Android. The screen
 * walks the parsed snapshot into [TxDiagramInput]/[TxDiagramOutput]
 * and paints whatever comes back; every string resource stays in the
 * fragment, so this file stays JVM-testable.
 *
 * Destination is classified by script match - the composer's
 * receivers map is keyed by address (one output per key), so exactly
 * one output pays the destination and every other output is change.
 * Input values come from the pool the snapshot was composed from; a
 * reviewed input that left the pool closes the review (#566) before
 * this ever runs. Fee is the snapshot's own arithmetic - inputs minus
 * outputs - which agrees with the compose fee by construction.
 */

/** One snapshot input: its "txid:vout" key and its value in sats. */
internal class TxDiagramInput(
    val outpoint: String,
    val sats: Long,
)

/** One snapshot output: its scriptPubKey and its value in sats. */
internal class TxDiagramOutput(
    val script: ByteArray,
    val sats: Long,
)

/** One painted diagram row. [label] is the stored name, when one exists. */
internal data class TxDiagramRow(
    val kind: Kind,
    val label: String?,
    val ordinal: Int,
    val sats: Long,
) {
    enum class Kind { INPUT, DESTINATION, CHANGE }
}

internal data class TxDiagram(
    val rows: List<TxDiagramRow>,
    val feeSats: Long,
    /**
     * #644 slice 3: the payment's own sats - the matched destination
     * output's value, or 0 when nothing matched - so the high-fee
     * test (fee strictly above 10% of the payment) reads pure data.
     */
    val paymentSats: Long,
    /**
     * True when the snapshot has no change output: a single output
     * paying the destination - the spend-all remap shape. The screen
     * says this out loud instead of implying a dropped change.
     */
    val spendsEntirely: Boolean,
)

internal fun diagramFor(
    inputs: List<TxDiagramInput>,
    outputs: List<TxDiagramOutput>,
    destinationAddress: String,
    params: NetworkParameters,
    coinLabels: Map<String, String>,
    addressLabels: Map<String, String>,
): TxDiagram {
    val destinationScript = scriptForAddress(destinationAddress, params)
    val rows = mutableListOf<TxDiagramRow>()
    inputs.forEachIndexed { index, input ->
        rows.add(
            TxDiagramRow(
                kind = TxDiagramRow.Kind.INPUT,
                label = coinLabels[input.outpoint],
                ordinal = index + 1,
                sats = input.sats,
            )
        )
    }
    var destinationSeen = false
    var paymentSats = 0L
    outputs.forEach { output ->
        val isDestination = !destinationSeen && output.script.contentEquals(destinationScript)
        if (isDestination) {
            destinationSeen = true
            paymentSats = output.sats
        }
        val name = if (isDestination) {
            destinationAddress
        } else {
            addressOfScript(output.script, params) ?: ""
        }
        rows.add(
            TxDiagramRow(
                kind = if (isDestination) {
                    TxDiagramRow.Kind.DESTINATION
                } else {
                    TxDiagramRow.Kind.CHANGE
                },
                label = addressLabels[name],
                ordinal = 0,
                sats = output.sats,
            )
        )
    }
    return TxDiagram(
        rows = rows,
        feeSats = inputs.sumOf { it.sats } - outputs.sumOf { it.sats },
        paymentSats = paymentSats,
        spendsEntirely = outputs.size == 1 &&
            outputs[0].script.contentEquals(destinationScript),
    )
}

/**
 * The script the composer would pay [address] - the same split as
 * TransactionComposer's own scriptForAddress (bech32 vs base58), so
 * the match inside [diagramFor] agrees with the output the composer
 * actually built.
 */
internal fun scriptForAddress(address: String, params: NetworkParameters): ByteArray =
    if (FormatsUtilGeneric.getInstance().isValidBech32(address)) {
        Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
    } else {
        ScriptBuilder.createOutputScript(Address.fromBase58(params, address)).program
    }

/**
 * The address a script pays, or null when it does not resolve. The
 * same read as Utxo.getOutPoints: bech32 scripts via Bech32UtilGeneric,
 * everything else via bitcoinj.
 */
internal fun addressOfScript(scriptBytes: ByteArray, params: NetworkParameters): String? =
    try {
        val script = Hex.toHexString(scriptBytes)
        if (Bech32UtilGeneric.getInstance().isBech32Script(script)) {
            Bech32UtilGeneric.getInstance().getAddressFromScript(script, params)
        } else {
            Script(scriptBytes).getToAddress(params).toString()
        }
    } catch (t: Throwable) {
        null
    }
