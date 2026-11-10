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
