package com.samourai.sentinel.ui.webview

/**
 * Network-row state for the block explorer, and the only transport
 * the health check is allowed to use.
 *
 * The check goes over Tor or not at all. There is no direct route:
 * a missing proxy or a Tor daemon that is not ready blocks the
 * request, even when the WebView Tor gate would still allow a direct
 * page load.
 */
enum class ExplorerProbeRoute {
    TOR,
    BLOCK,
}

fun explorerProbeRoute(torReady: Boolean, proxyAvailable: Boolean): ExplorerProbeRoute =
    if (torReady && proxyAvailable) ExplorerProbeRoute.TOR else ExplorerProbeRoute.BLOCK

enum class ExplorerProbeOutcome {
    PENDING,
    REACHABLE,
    UNREACHABLE,
}

/**
 * Last probe for one bound explorer. Held by [ExplorerRepository]
 * so a new Network screen can paint it before the next check.
 * A result younger than [FRESH_FOR_MS] is shown as-is and not
 * probed again. An older result stays on screen while a check
 * runs, so the row does not flash amber on the way back to green.
 * A different explorer, or no result, is unknown: that is the
 * amber check, and a failed check is red.
 */
object ExplorerProbeMemory {
    const val FRESH_FOR_MS = 3 * 60 * 1000L

    data class Record(
        val key: String,
        val outcome: ExplorerProbeOutcome,
        val atMillis: Long,
    )

    enum class Plan {
        USE_FRESH,
        REPROBE_KEEPING_LAST,
        PROBE_UNKNOWN,
    }

    data class Decision(
        val plan: Plan,
        val outcome: ExplorerProbeOutcome,
    )

    fun decide(
        record: Record?,
        key: String,
        nowMillis: Long,
        freshForMs: Long = FRESH_FOR_MS,
    ): Decision {
        if (record == null || record.key != key || record.outcome == ExplorerProbeOutcome.PENDING) {
            return Decision(Plan.PROBE_UNKNOWN, ExplorerProbeOutcome.PENDING)
        }
        val age = nowMillis - record.atMillis
        val fresh = age in 0..freshForMs
        val plan = if (fresh) Plan.USE_FRESH else Plan.REPROBE_KEEPING_LAST
        return Decision(plan, record.outcome)
    }
}

enum class ExplorerHealth {
    CHECKING,
    TOR_STARTING,
    BOUND,
    THIRD_PARTY,
    NONE,
    UNREACHABLE,
    TOR_REQUIRED,
}

data class ExplorerHealthState(
    val health: ExplorerHealth,
    val hostLabel: String?,
)

fun explorerHealth(
    resolution: ExplorerBinding.ExplorerResolution,
    torReady: Boolean,
    torInProgress: Boolean,
    proxyAvailable: Boolean,
    probe: ExplorerProbeOutcome,
): ExplorerHealthState {
    val bound = resolution as? ExplorerBinding.ExplorerResolution.Bound
        ?: return ExplorerHealthState(ExplorerHealth.NONE, null)
    val label = ExplorerBinding.hostLabel(bound)
    if (explorerProbeRoute(torReady, proxyAvailable) != ExplorerProbeRoute.TOR) {
        val waiting = if (torInProgress) ExplorerHealth.TOR_STARTING else ExplorerHealth.TOR_REQUIRED
        return ExplorerHealthState(waiting, label)
    }
    return when (probe) {
        ExplorerProbeOutcome.PENDING ->
            ExplorerHealthState(ExplorerHealth.CHECKING, label)
        ExplorerProbeOutcome.UNREACHABLE ->
            ExplorerHealthState(ExplorerHealth.UNREACHABLE, label)
        ExplorerProbeOutcome.REACHABLE -> {
            val shown = if (bound.kind == ExplorerBinding.ExplorerKind.PAIRED) {
                ExplorerHealth.BOUND
            } else {
                ExplorerHealth.THIRD_PARTY
            }
            ExplorerHealthState(shown, label)
        }
    }
}
