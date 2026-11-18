package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DojoChain

/**
 * #351: the pairing-path CHAIN decision, made pure - the
 * PairingNetworkDecision (#250) shape, one level down.
 *
 * NetworkClassifier's whisper answers testnet-vs-mainnet; the
 * directory label and the keychain vote cannot see a chain
 * GENERATION at all (tb-format keys vote identically on testnet3/4/5),
 * so the only chain evidence that exists is the mount convention in
 * DojoChainWhisper. Sentinel's active testnet generation is testnet3
 * (SentinelState resolves NetworkParameters.fromID(ID_TESTNET)); a
 * Dojo mounted on a different testnet generation is the #348 shape:
 * balances resolve by address-format accident, and an empty sweep
 * reads as "no UTXOs" with no hint the rig is pointed at the wrong
 * chain.
 *
 * The table (#351, evidence = whisper only):
 *   whisper null (mainnet root mount,
 *   or no URL)                -> PROCEED: no chain evidence,
 *                               the network guard owns mainnet
 *   whisper TESTNET_3         -> PROCEED: this is the app's own
 *                               generation, nothing to warn about
 *   whisper TESTNET_4/5 on an
 *   app in testnet mode       -> CONFIRM_CHAIN_MISMATCH: loud
 *                               warning, Pair stays available -
 *                               the whisper is convention, a
 *                               relabelled or proxied mount could
 *                               false-positive, so the user decides
 *   whisper TESTNET_4/5 on an
 *   app in MAINNET mode       -> PROCEED: the network guard
 *                               (ConfirmMismatch) already warns -
 *                               one warning per dialog, never two
 */
enum class PairingChainDecision { PROCEED, CONFIRM_CHAIN_MISMATCH }

fun pairingChainDecision(
    appIsTestnet: Boolean,
    whisper: DojoChain?,
): PairingChainDecision =
    if (appIsTestnet &&
        whisper != null &&
        whisper != DojoChain.TESTNET_3
    ) {
        PairingChainDecision.CONFIRM_CHAIN_MISMATCH
    } else {
        PairingChainDecision.PROCEED
    }