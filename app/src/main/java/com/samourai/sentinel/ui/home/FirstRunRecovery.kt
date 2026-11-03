package com.samourai.sentinel.ui.home

import com.samourai.sentinel.ui.settings.NetworkSwitch

/**
 * #223: first-run setup completes when a Dojo actually pairs, not when
 * the network was chosen - and until then, Home re-offers the choice
 * instead of dead-ending.
 *
 * Before #223 a failed or abandoned first pairing left a toast and Home:
 * the network choice was already spent, and the only way back to it was
 * finding Settings > Network. Now:
 *  - right after a failed pairing, and on every launch while setup is
 *    still incomplete, Home offers [Plan]: pair again, or switch network
 *    when the keys don't pin it (NetworkSwitch, #208's rule);
 *  - once a pairing succeeds, setup is complete and Home behaves as
 *    before (a later Dojo removal re-opens the Dojo wizard, not this).
 *
 * Pure Kotlin; HomeActivity renders and acts.
 */
object FirstRunRecovery {

    /** What Home does about setup when it starts (past the firstRun choice). */
    enum class LaunchStep {
        /** A Dojo is paired: nothing to set up. */
        NOTHING,
        /** Open the Dojo wizard (setup was completed before, or a pairing was asked for). */
        PAIR,
        /** Setup never completed: offer [Plan] (pair again / change network). */
        OFFER_RECOVERY,
    }

    fun onLaunch(endpointEnabled: Boolean, setupComplete: Boolean, pairRequested: Boolean): LaunchStep =
        when {
            endpointEnabled -> LaunchStep.NOTHING
            pairRequested -> LaunchStep.PAIR
            setupComplete -> LaunchStep.PAIR
            else -> LaunchStep.OFFER_RECOVERY
        }

    enum class Trigger {
        /** The pairing that was just tried failed, or was cancelled/declined. */
        PAIRING_FAILED,
        /** A launch with setup still incomplete. */
        LAUNCH,
    }

    /**
     * The sheet's content. Positive pairs. Negative switches to
     * [switchTarget] (testnet = true) when non-null - otherwise it just
     * closes ("Not now").
     */
    data class Plan(
        val label: String,
        val message: String,
        val positiveText: String,
        val negativeText: String,
        val switchTarget: Boolean?,
    )

    fun plan(trigger: Trigger, currentIsTestnet: Boolean, networkEstablished: Boolean): Plan {
        val network = if (currentIsTestnet) "Testnet" else "Mainnet"
        val row = NetworkSwitch.rowState(currentIsTestnet, networkEstablished)
        val switchTarget = (row as? NetworkSwitch.Row.Switchable)?.targetIsTestnet
        val head = when (trigger) {
            Trigger.PAIRING_FAILED -> "The Dojo didn't pair, so Sentinel can't load balances yet."
            Trigger.LAUNCH -> "No Dojo is paired yet, so Sentinel can't load balances."
        }
        val networkLine = if (switchTarget != null) {
            " You're on $network - picked the wrong network? Switch below."
        } else {
            " You're on $network, set by your public keys."
        }
        return Plan(
            label = when (trigger) {
                Trigger.PAIRING_FAILED -> "Dojo not connected"
                Trigger.LAUNCH -> "Finish setting up"
            },
            message = head + networkLine,
            positiveText = when (trigger) {
                Trigger.PAIRING_FAILED -> "Try again"
                Trigger.LAUNCH -> "Pair a Dojo"
            },
            negativeText = when (switchTarget) {
                true -> "Switch to Testnet"
                false -> "Switch to Mainnet"
                null -> "Not now"
            },
            switchTarget = switchTarget,
        )
    }
}
