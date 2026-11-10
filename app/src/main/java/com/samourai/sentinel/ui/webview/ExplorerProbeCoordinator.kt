package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.tor.EnumTorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One Network-screen explorer check.
 *
 * The Tor SOCKS generation is part of the attempt. A probe that started
 * against a previous session (or before Tor was ready) is cancelled when
 * the live port is published, and a new one runs. Refreshes while that
 * same session is already in flight do not start another check. A tap
 * while the row is checking or unreachable does.
 *
 * The whole attempt is bounded by [PROBE_TIMEOUT_MS]. Timeout and any
 * other failure are stored as [ExplorerProbeOutcome.UNREACHABLE]. Leaving
 * the screen, or a Tor session change, does not store that failure.
 */
class ExplorerProbeCoordinator(
    private val scope: CoroutineScope,
    private val repository: ExplorerRepository,
    private val probe: suspend (baseUrl: String, endpoint: String) -> ProbeResult,
    private val onChanged: () -> Unit = {},
    private val timeoutMs: Long = PROBE_TIMEOUT_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val elapsedNow: () -> Long = { System.nanoTime() },
) {
    var outcome: ExplorerProbeOutcome = ExplorerProbeOutcome.PENDING
        private set

    private var session: Session? = null
    private var inFlight: Flight? = null
    private var job: Job? = null
    private var generation: Int = 0

    fun refresh(request: ExplorerProbeRequest) {
        val resolution = request.resolution
        val base = ExplorerBinding.probeBase(resolution)
        val key = explorerProbeKey(resolution)
        val ready = request.ready
        val endpoint = request.socksEndpoint?.takeIf { it.isNotBlank() }
        val sessionNow = if (ready && endpoint != null) {
            Session(request.socksGeneration, endpoint)
        } else {
            null
        }
        val previous = session
        // The first look at an already-running Tor session is not a
        // change. Dropping the cached check here would flash Checking…
        // every time the screen opened. A later generation or port is.
        val torChanged = previous != null && previous != sessionNow
        if (torChanged) {
            inFlight?.let { flight ->
                ExplorerProbeLog.abort(
                    flight.base,
                    if (sessionNow == null) "tor-not-ready" else "socks-changed",
                    endpoint ?: "none",
                )
            }
            cancelInFlight()
            repository.forgetProbe()
        } else if (previous == null && sessionNow != null && cacheBelongsToOtherSession(sessionNow)) {
            repository.forgetProbe()
        }
        session = sessionNow
        if (!ready || key == null || base == null || endpoint == null) {
            if (key == null || base == null) cancelInFlight()
            outcome = if (
                resolution is ExplorerBinding.ExplorerResolution.Bound &&
                ready &&
                base == null
            ) {
                ExplorerProbeOutcome.UNREACHABLE
            } else {
                ExplorerProbeOutcome.PENDING
            }
            return
        }
        if (torChanged) {
            outcome = ExplorerProbeOutcome.PENDING
            start(key, base, endpoint, logMiss = true)
            return
        }
        val record = repository.rememberedProbe()
        val decision = ExplorerProbeMemory.decide(record, key, now())
        when (decision.plan) {
            ExplorerProbeMemory.Plan.USE_FRESH -> {
                if (inFlight != null) cancelInFlight()
                outcome = decision.outcome
                val age = if (record != null && record.key == key) now() - record.atMillis else 0L
                ExplorerProbeLog.cacheHit(base, decision.outcome.name, age)
            }
            ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST -> {
                outcome = decision.outcome
                startIfIdle(key, base, endpoint)
            }
            ExplorerProbeMemory.Plan.PROBE_UNKNOWN -> {
                val flight = inFlight
                if (flight != null && flight.key == key && flight.endpoint == endpoint) return
                outcome = ExplorerProbeOutcome.PENDING
                startIfIdle(key, base, endpoint)
            }
        }
    }

    /**
     * Tap on the row. Starts one new attempt when the row is checking
     * or unreachable. A tap in any other state does nothing, and a
     * refresh does not call this.
     */
    fun retry(request: ExplorerProbeRequest) {
        val health = health(request).health
        if (health != ExplorerHealth.CHECKING && health != ExplorerHealth.UNREACHABLE) return
        if (!request.ready) return
        val key = explorerProbeKey(request.resolution) ?: return
        val base = ExplorerBinding.probeBase(request.resolution) ?: return
        val endpoint = request.socksEndpoint?.takeIf { it.isNotBlank() } ?: return
        repository.forgetProbe()
        outcome = ExplorerProbeOutcome.PENDING
        session = Session(request.socksGeneration, endpoint)
        cancelInFlight()
        start(key, base, endpoint, logMiss = true)
    }

    fun cancel() {
        cancelInFlight()
    }

    fun health(request: ExplorerProbeRequest): ExplorerHealthState {
        val torReady = request.ready
        val inProgress = request.torState == EnumTorState.STARTING ||
            (request.torState == EnumTorState.ON && !torReady)
        return explorerHealth(
            resolution = request.resolution,
            torReady = torReady,
            torInProgress = inProgress,
            proxyAvailable = !request.socksEndpoint.isNullOrBlank(),
            probe = outcome,
        )
    }

    private fun startIfIdle(key: String, base: String, endpoint: String) {
        val flight = inFlight
        if (flight != null && flight.key == key && flight.endpoint == endpoint) return
        cancelInFlight()
        start(key, base, endpoint, logMiss = true)
    }

    private fun start(key: String, base: String, endpoint: String, logMiss: Boolean) {
        val gen = generation
        val socksGeneration = session?.generation ?: ExplorerProbeMemory.NO_SESSION
        inFlight = Flight(key, base, endpoint)
        if (logMiss) ExplorerProbeLog.cacheMiss(base)
        val startedNs = elapsedNow()
        job = scope.launch {
            val result = try {
                withTimeout(timeoutMs) { probe(base, endpoint) }
            } catch (t: TimeoutCancellationException) {
                ProbeResult.Unreachable
            } catch (t: CancellationException) {
                throw t
            }
            if (gen != generation) return@launch
            inFlight = null
            job = null
            when (result) {
                ProbeResult.Reachable -> {
                    outcome = ExplorerProbeOutcome.REACHABLE
                    repository.rememberProbe(key, outcome, now(), socksGeneration, endpoint)
                }
                ProbeResult.Unreachable -> {
                    outcome = ExplorerProbeOutcome.UNREACHABLE
                    repository.rememberProbe(key, outcome, now(), socksGeneration, endpoint)
                }
                ProbeResult.NotReady -> Unit
            }
            ExplorerProbeLog.done(base, result.name.uppercase(), elapsedMs(startedNs))
            onChanged()
        }
    }

    private fun cancelInFlight() {
        generation += 1
        inFlight = null
        job?.cancel()
        job = null
    }

    private fun cacheBelongsToOtherSession(sessionNow: Session): Boolean {
        val cached = repository.rememberedProbe() ?: return false
        return cached.socksGeneration != sessionNow.generation ||
            cached.socksEndpoint != sessionNow.endpoint
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((elapsedNow() - startedNs).coerceAtLeast(0L)) / 1_000_000L

    private data class Session(val generation: Int, val endpoint: String)

    private data class Flight(val key: String, val base: String, val endpoint: String)

    companion object {
        const val PROBE_TIMEOUT_MS = 60_000L
    }
}

data class ExplorerProbeRequest(
    val resolution: ExplorerBinding.ExplorerResolution,
    val torState: EnumTorState,
    val torProgress: Int,
    val socksEndpoint: String?,
    val socksGeneration: Int,
) {
    val ready: Boolean
        get() = torState == EnumTorState.ON &&
            torProgress == 100 &&
            !socksEndpoint.isNullOrBlank()
}

internal fun explorerProbeKey(resolution: ExplorerBinding.ExplorerResolution): String? {
    val bound = resolution as? ExplorerBinding.ExplorerResolution.Bound ?: return null
    if (bound.baseUrl.isBlank()) return null
    return "${bound.kind}:${bound.baseUrl}"
}
