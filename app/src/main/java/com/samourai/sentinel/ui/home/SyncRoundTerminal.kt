package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.repository.CollectionTxSyncResult

/**
 * #499: one terminal discipline for both round kinds.
 *
 * Zero failures may produce Success. Some-but-not-all failures produce
 * the partial-Failed state and clear [Decision.clearLastRoundSignature]
 * (the external-arm signature). Total failure keeps each round's existing
 * ending: syncCollections fails immediately and clears that signature;
 * fetchBalance keeps its bounded automatic retry and leaves the signature
 * stamped so a parallel arm does not join the retry. Neither ending
 * clears the round-start signature the #490 trailing re-run compares.
 */
internal object SyncRoundTerminal {

    const val SERVER_UNREACHABLE = "Could not reach the server."

    fun partialReason(failed: Int, total: Int): String =
        "$failed of $total collections failed to sync."

    enum class Kind {
        /** launchSyncRound -> syncCollections. No auto-retry, no lastSynced write. */
        COLLECTIONS,

        /** fetchBalance. Bounded auto-retry on total failure; lastSynced on clean Success. */
        BALANCE,
    }

    sealed interface Post {
        data object Success : Post
        data object Idle : Post
        data class Failed(val reason: String) : Post
    }

    /**
     * @param post null when nothing new is posted (auto-retry stays on
     *   Syncing, or a standing Failed is left alone).
     * @param clearLastRoundSignature external arms may retry. Never the
     *   round-start signature.
     * @param writeLastSynced fetchBalance's clean Success only.
     * @param staleSuccessRetired null leaves the re-pair flag alone.
     */
    data class Decision(
        val post: Post?,
        val clearLastRoundSignature: Boolean,
        val writeLastSynced: Boolean,
        val armAutoRetry: Boolean,
        val autoRetryBudget: Int,
        val staleSuccessRetired: Boolean?,
    ) {
        fun toSyncState(now: Long): SyncState? {
            val terminal = post
            return when (terminal) {
                null -> null
                Post.Success -> SyncState.Success(now)
                Post.Idle -> SyncState.Idle
                is Post.Failed -> SyncState.Failed(terminal.reason, retryable = true)
            }
        }
    }

    fun decide(
        kind: Kind,
        failed: Int,
        total: Int,
        endpointEnabled: Boolean,
        userInitiated: Boolean,
        autoRetryBudget: Int,
        autoRetryLimit: Int,
        standingFailed: Boolean,
    ): Decision {
        // fetchBalance total failure: bounded retry, then the server
        // message. The signature stays stamped (#47 coalescing during
        // the retry window).
        if (kind == Kind.BALANCE && total > 0 && failed == total) {
            if (!userInitiated && autoRetryBudget < autoRetryLimit) {
                return Decision(
                    post = null,
                    clearLastRoundSignature = false,
                    writeLastSynced = false,
                    armAutoRetry = true,
                    autoRetryBudget = autoRetryBudget + 1,
                    staleSuccessRetired = null,
                )
            }
            return Decision(
                post = Post.Failed(SERVER_UNREACHABLE),
                clearLastRoundSignature = false,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = autoRetryBudget,
                staleSuccessRetired = null,
            )
        }

        if (failed > 0) {
            val reason = if (total > 0 && failed == total) {
                SERVER_UNREACHABLE
            } else {
                partialReason(failed, total)
            }
            return Decision(
                post = Post.Failed(reason),
                clearLastRoundSignature = true,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = autoRetryBudget,
                staleSuccessRetired = null,
            )
        }

        if (!endpointEnabled) {
            // #188: a clean round whose pairing vanished. syncCollections
            // always posts Idle. fetchBalance posts Idle only when a
            // user-earned Failed is not already standing, and resets the
            // retry budget the way its success path does.
            val retire = !standingFailed
            val postIdle = kind == Kind.COLLECTIONS || retire
            return Decision(
                post = if (postIdle) Post.Idle else null,
                clearLastRoundSignature = false,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = if (kind == Kind.BALANCE) 0 else autoRetryBudget,
                staleSuccessRetired = retire,
            )
        }

        return Decision(
            post = Post.Success,
            clearLastRoundSignature = false,
            writeLastSynced = kind == Kind.BALANCE,
            armAutoRetry = false,
            autoRetryBudget = if (kind == Kind.BALANCE) 0 else autoRetryBudget,
            staleSuccessRetired = null,
        )
    }
}

/**
 * #475/#499: per-collection failure count shared by both rounds.
 * Indeterminate, a thrown fetch, and a completion-gate miss each
 * count once. Proven-empty is not a failure, even when the tx table
 * has no row for it.
 */
internal class SyncRoundLedger {
    var failed: Int = 0
        private set

    private val failedIds = mutableSetOf<String>()
    private val provenEmptyIds = mutableSetOf<String>()

    fun record(id: String, result: CollectionTxSyncResult) {
        when (result) {
            is CollectionTxSyncResult.Fetched -> Unit
            CollectionTxSyncResult.ProvenEmpty -> provenEmptyIds.add(id)
            CollectionTxSyncResult.Indeterminate -> countFailure(id)
        }
    }

    fun recordThrown(id: String) {
        countFailure(id)
    }

    /**
     * Collections that neither failed, nor were proven empty, nor have
     * tx rows. [txCounts] is the table count per id; a missing id counts
     * as no rows. Returns the new misses so the caller can log them.
     */
    fun applyGate(ids: List<String>, txCounts: Map<String, Int>): List<String> {
        val misses = ArrayList<String>()
        ids.forEach { id ->
            if (id !in failedIds &&
                id !in provenEmptyIds &&
                (txCounts[id] ?: 0) == 0
            ) {
                countFailure(id)
                misses.add(id)
            }
        }
        return misses
    }

    private fun countFailure(id: String) {
        if (failedIds.add(id)) failed++
    }
}
