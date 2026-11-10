package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.tor.EnumTorState

/**
 * Toolbar traffic light. Same precedence HomeViewModel used inline.
 *
 * [SyncState.Failed] is red before any Tor arm, so a round that
 * reports a failure paints the Home dot red even when Tor and the
 * Dojo are healthy. A round that only skipped off-network
 * collections posts [SyncState.Success] and stays green once Tor
 * is up. That is how Home and the Network screen agree.
 */
internal fun homeConnectionIndicator(
    sync: SyncState,
    torRequired: Boolean,
    torState: EnumTorState,
    torProgress: Int,
    endpointEnabled: Boolean,
): ConnectionIndicator = when {
    sync is SyncState.Failed -> ConnectionIndicator.RED
    torRequired && torState == EnumTorState.OFF -> ConnectionIndicator.RED
    torRequired && torState == EnumTorState.STARTING -> ConnectionIndicator.YELLOW
    // ON lands at boot=5. GREEN means the climb finished.
    torRequired && torState == EnumTorState.ON && torProgress < 100 ->
        ConnectionIndicator.YELLOW
    sync is SyncState.WaitingForTor -> ConnectionIndicator.YELLOW
    sync is SyncState.Syncing -> ConnectionIndicator.YELLOW
    sync is SyncState.Success -> ConnectionIndicator.GREEN
    // Tor is up, but nothing is configured to sync against.
    torRequired && !endpointEnabled -> ConnectionIndicator.RED
    else -> ConnectionIndicator.NEUTRAL
}
