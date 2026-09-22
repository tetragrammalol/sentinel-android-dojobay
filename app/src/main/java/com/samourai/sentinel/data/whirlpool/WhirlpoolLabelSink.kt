package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.repository.LabelRepository

/**
 * Android Sink: LabelRepository -> WhirlpoolAutoWriter.Sink
 * (issue #6, part 2). This is the whirlpool package's only
 * Android-side file; txid lowercasing is enforced by the repository
 * layer's storage invariant, not duplicated here.
 */
class WhirlpoolLabelSink(
    private val labelRepository: LabelRepository,
) : WhirlpoolAutoWriter.Sink {

    override suspend fun findTxLabel(txid: String): ExistingLabel? =
        labelRepository.getTxLabel(txid)?.let {
            ExistingLabel(it.label, it.origin)
        }

    override suspend fun writeAutoTxLabel(
        txid: String, label: String, origin: String,
    ) = labelRepository.setAutoTxLabel(txid, label, origin)
}
