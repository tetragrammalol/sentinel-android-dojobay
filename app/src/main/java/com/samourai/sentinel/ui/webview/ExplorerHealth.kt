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
    UNCONFIRMED,
}

/**
 * Last probe for one bound explorer. Held by [ExplorerRepository]
 * so a new Network screen can paint it before the next check.
 * A result younger than [FRESH_FOR_MS] is shown as-is and not
 * probed again. A timeout ([ExplorerProbeOutcome.UNCONFIRMED])
 * stays fresh only for [UNCONFIRMED_FRESH_FOR_MS], so a cold Tor
 * circuit does not sit on the row for three minutes. An older
 * result stays on screen while a check runs, so the row does not
 * flash on the way back to green. A different explorer, or no
 * result, is unknown: that is the amber check. A timeout is amber.
 * A definitive failure is red.
 */
object ExplorerProbeMemory {
    const val FRESH_FOR_MS = 3 * 60 * 1000L

    /**
     * A timeout is not proof the explorer is down. Long enough that
     * a burst of refreshes does not open a second socket, short
     * enough that the next look checks again.
     */
    const val UNCONFIRMED_FRESH_FOR_MS = 15_000L

    /** A record written before a SOCKS generation was stored. */
    const val NO_SESSION = -1

    data class Record(
        val key: String,
        val outcome: ExplorerProbeOutcome,
        val atMillis: Long,
        val socksGeneration: Int = NO_SESSION,
        val socksEndpoint: String? = null,
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
        unconfirmedFreshForMs: Long = UNCONFIRMED_FRESH_FOR_MS,
    ): Decision {
        if (record == null || record.key != key || record.outcome == ExplorerProbeOutcome.PENDING) {
            return Decision(Plan.PROBE_UNKNOWN, ExplorerProbeOutcome.PENDING)
        }
        val window = if (record.outcome == ExplorerProbeOutcome.UNCONFIRMED) {
            unconfirmedFreshForMs
        } else {
            freshForMs
        }
        val age = nowMillis - record.atMillis
        val fresh = age in 0..window
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
    UNCONFIRMED,
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
        ExplorerProbeOutcome.UNCONFIRMED ->
            ExplorerHealthState(ExplorerHealth.UNCONFIRMED, label)
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

/**
 * Operator name and flag under the Block Explorer row. Only a green
 * paired row shows it. Checking, unreachable, and every other state
 * leave the line blank.
 */
fun explorerOperatorVisible(health: ExplorerHealth, operator: String?): Boolean {
    if (health != ExplorerHealth.BOUND) return false
    return !operator.isNullOrBlank()
}

/** "Checking… 12s". The count is whole seconds, rounded down. */
fun explorerCheckingLabel(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    return "Checking… ${seconds}s"
}

/**
 * Checking, a timeout, and a definitive failure can be tapped.
 * Tor that is not ready, and a finished green row, cannot.
 */
fun explorerRowRetryable(health: ExplorerHealth): Boolean =
    health == ExplorerHealth.CHECKING ||
        health == ExplorerHealth.UNREACHABLE ||
        health == ExplorerHealth.UNCONFIRMED

/**
 * Second line under the Block Explorer row. The operator name is
 * only there when the row is green. A tappable row says to tap it.
 * Every other state leaves the line empty.
 */
fun explorerDetailText(health: ExplorerHealth, operator: String?): String? {
    if (explorerOperatorVisible(health, operator)) return operator
    if (explorerRowRetryable(health)) return "Tap to retry"
    return null
}
