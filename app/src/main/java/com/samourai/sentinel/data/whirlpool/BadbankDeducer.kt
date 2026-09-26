package com.samourai.sentinel.data.whirlpool

/**
 * Deduces the badbank output of a classified TX0 (issue #46).
 * Pure JVM (pattern: WhirlpoolDetector / Bip329Parser).
 *
 * Discriminator is account identity: the single output resolving to
 * the badbank account (xpub identity, same resolution as #6 part 2).
 * "Amount not a pool denomination" is unsafe as a discriminator —
 * real premix outputs carry a per-TX0 fee buffer (device goldens:
 * 2,500,605 / 250,605 sats), so they never equal a denomination
 * exactly. The non-uniform value vs the premix group is a confirming
 * signal: if the badbank-account output MATCHES the uniform premix
 * value, the shape is ambiguous and the deducer says nothing.
 */
object BadbankDeducer {

    sealed interface Result {
        /** The deduced badbank output, addressable as (txid, vout). */
        data class Badbank(val vout: Int, val valueSats: Long) : Result

        /** Classified Tx0 but no output resolves to the badbank account
         *  (the detector's documented false-negative shape). */
        object NoBadbankOutput : Result
    }

    fun deduce(
        view: WhirlpoolTxView,
        classification: WhirlpoolClassification.Tx0,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): Result? = null   // TODO(#46): implement — deduction goldens red until then
}
