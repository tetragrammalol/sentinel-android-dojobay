package com.samourai.sentinel.core

import androidx.lifecycle.MutableLiveData

/**
 * Explicit, leak-proof representation of the app's sync lifecycle.
 *
 * This replaces the old `MutableLiveData<MutableList<Boolean>>` counter, which
 * could permanently leak a `true` entry when a fetch failed on a path that did
 * not decrement it (notably "unable to resolve host"), leaving the UI spinning
 * forever.
 */
sealed interface SyncState {

    /** Nothing in flight, nothing to report. */
    data object Idle : SyncState

    /** Tor is required but not yet usable. [progress] is 0..100, -1 if unknown. */
    data class WaitingForTor(val progress: Int = -1) : SyncState

    /**
     * Actively syncing. [done] of [total] collections completed.
     *
     * [slow] is set once the sync has been running long enough to be worth
     * mentioning to the user. It is purely informational - the work is NEVER
     * cancelled just because it is slow, because Tor circuits can legitimately
     * take minutes to build.
     */
    data class Syncing(
        val done: Int,
        val total: Int,
        val slow: Boolean = false
    ) : SyncState

    /** Sync finished successfully at [atMillis]. */
    data class Success(val atMillis: Long) : SyncState

    /** Sync failed. [retryable] drives whether we offer a Retry affordance. */
    data class Failed(val reason: String, val retryable: Boolean = true) : SyncState
}

/**
 * #679: true while a sync round is in flight - the utxo pool a
 * send-side verdict reads is then a half-written round, so a missing
 * coin is held, not rendered. WaitingForTor counts too: a round
 * deferred on the Tor climb has written nothing either. The terminal
 * states and Idle do not hold - a pool read then is the last
 * committed round, and a missing coin is a real verdict.
 */
fun isSyncInFlight(state: SyncState): Boolean =
    state is SyncState.Syncing || state is SyncState.WaitingForTor

/**
 * #679: the process-wide sync-state channel. HomeViewModel owns every
 * post - its private syncState field IS this bus since #679 - and the
 * send screen's ViewModel observes it, so a selection verdict rendered
 * against a mid-round dao read is held until the round ends. The same
 * cross-activity LiveData shape as UtxoMetaUtil.blockedChanges.
 *
 * The state outlives the Home activity's ViewModel: a recreated
 * HomeViewModel re-attaches to the standing value instead of a fresh
 * Idle. Deliberate - a rotation mid-round should not read as
 * "never synced".
 */
object SyncStateBus {
    val syncState: MutableLiveData<SyncState> = MutableLiveData(SyncState.Idle)
}

/**
 * The traffic-light state for the network indicator in the toolbar.
 *
 * - [GREEN]  Tor connected *and* all collections synced.
 * - [YELLOW] Connecting / bootstrapping / syncing. Rendered flashing.
 * - [RED]    Connection or sync failed.
 * - [NEUTRAL] Nothing attempted yet.
 */
enum class ConnectionIndicator {
    GREEN,
    YELLOW,
    RED,
    NEUTRAL
}
