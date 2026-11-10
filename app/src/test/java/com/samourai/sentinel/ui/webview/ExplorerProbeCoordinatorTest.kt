package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.tor.EnumTorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * The row stays on Checking… when a probe captured a SOCKS port from
 * before Tor was ready and a later refresh treated that attempt as
 * still in flight. These cases drive the coordinator with a virtual
 * clock: no real sleep, no Tor daemon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerProbeCoordinatorTest {

    private val onionHost = "abcd" + "e".repeat(48) + "wxyz"
    private val base = "http://$onionHost.onion"
    private val resolution = ExplorerBinding.ExplorerResolution.Bound(
        kind = ExplorerBinding.ExplorerKind.PAIRED,
        baseUrl = base,
        displayUrl = base,
    )

    @Test
    fun `timeout stores UNREACHABLE and hides the operator line`() = runTest {
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, _ ->
            kotlinx.coroutines.suspendCancellableCoroutine { }
        }
        try {
            coord.refresh(ready(endpoint = "127.0.0.1:9050", generation = 1))
            advanceUntilIdle()
            assertEquals(ExplorerProbeOutcome.UNREACHABLE, coord.outcome)
            assertEquals(ExplorerProbeOutcome.UNREACHABLE, repo.rememberedProbe()?.outcome)
            val health = coord.health(ready(endpoint = "127.0.0.1:9050", generation = 1))
            assertEquals(ExplorerHealth.UNREACHABLE, health.health)
            assertFalse(explorerOperatorVisible(health.health, "SG Directory Dojo"))
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `success stores REACHABLE and the row is green with the operator`() = runTest {
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, _ -> ProbeResult.Reachable }
        coord.refresh(ready(endpoint = "127.0.0.1:9050", generation = 1))
        advanceUntilIdle()
        assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
        assertEquals(ExplorerProbeOutcome.REACHABLE, repo.rememberedProbe()?.outcome)
        val health = coord.health(ready(endpoint = "127.0.0.1:9050", generation = 1))
        assertEquals(ExplorerHealth.BOUND, health.health)
        assertTrue(explorerOperatorVisible(health.health, "SG Directory Dojo"))
        assertFalse(explorerOperatorVisible(ExplorerHealth.CHECKING, "SG Directory Dojo"))
    }

    @Test
    fun `refreshes while a probe is in flight do not start another`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, endpoint ->
            calls += endpoint
            gate.await()
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 1)
            coord.refresh(request)
            coord.refresh(request)
            coord.refresh(request)
            runCurrent()
            assertEquals(listOf("127.0.0.1:9050"), calls)
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a tap while checking starts one new probe and a tap while green does not`() = runTest {
        val calls = mutableListOf<Int>()
        var release = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, _ ->
            calls += 1
            release.await()
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 1)
            coord.refresh(request)
            runCurrent()
            assertEquals(1, calls.size)
            coord.retry(request)
            runCurrent()
            assertEquals(2, calls.size)
            release.complete(ProbeResult.Reachable)
            runCurrent()
            val afterGreen = calls.size
            coord.retry(request)
            runCurrent()
            assertEquals(afterGreen, calls.size)
            assertEquals(ExplorerHealth.BOUND, coord.health(request).health)
        } finally {
            if (!release.isCompleted) release.complete(ProbeResult.Unreachable)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `probe started under a stale tor port re-runs when tor is ready and resolves`() = runTest {
        val calls = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<ProbeResult>>()
        val lines = mutableListOf<String>()
        val tree = capturingTree(lines)
        Timber.plant(tree)
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, endpoint ->
            calls += endpoint
            gates.getOrPut(endpoint) { CompletableDeferred() }.await()
        }
        try {
            // Stale session: boot looks complete and a SOCKS port is set,
            // but it is the previous daemon's port. The probe hangs on it.
            coord.refresh(ready(endpoint = "127.0.0.1:1111", generation = 1))
            runCurrent()
            assertEquals(listOf("127.0.0.1:1111"), calls)
            assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
            assertNull(repo.rememberedProbe())

            // The live port is published. Same explorer, new Tor session.
            coord.refresh(ready(endpoint = "127.0.0.1:2222", generation = 2))
            runCurrent()
            assertEquals(listOf("127.0.0.1:1111", "127.0.0.1:2222"), calls)
            gates.getValue("127.0.0.1:2222").complete(ProbeResult.Reachable)
            runCurrent()

            assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
            assertEquals(ExplorerHealth.BOUND, coord.health(ready("127.0.0.1:2222", 2)).health)
            assertTrue(lines.any { it.contains("abort") && it.contains("socks-changed") })
            assertTrue(lines.any { it.contains("done") && it.contains("outcome=REACHABLE") })
            assertFalse(lines.joinToString("\n").contains(onionHost))
        } finally {
            Timber.uproot(tree)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a probe does not start until tor is ready and then resolves`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, endpoint ->
            calls += endpoint
            gate.await()
        }
        try {
            coord.refresh(notReady(progress = 40, endpoint = null, generation = 0))
            coord.refresh(notReady(progress = 100, endpoint = null, generation = 0))
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
            assertEquals(
                ExplorerHealth.TOR_STARTING,
                coord.health(notReady(progress = 100, endpoint = null, generation = 0)).health,
            )

            coord.refresh(ready(endpoint = "127.0.0.1:2222", generation = 3))
            runCurrent()
            assertEquals(listOf("127.0.0.1:2222"), calls)
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(ExplorerHealth.BOUND, coord.health(ready("127.0.0.1:2222", 3)).health)
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `unreachable can be retried from the row`() = runTest {
        val calls = mutableListOf<Int>()
        var hang = true
        val gate = CompletableDeferred<ProbeResult>()
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, _ ->
            calls += 1
            if (hang) kotlinx.coroutines.suspendCancellableCoroutine { } else gate.await()
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 4)
            coord.refresh(request)
            advanceUntilIdle()
            assertEquals(ExplorerProbeOutcome.UNREACHABLE, coord.outcome)
            assertFalse(explorerOperatorVisible(coord.health(request).health, "SG Directory Dojo"))
            hang = false
            coord.retry(request)
            runCurrent()
            assertEquals(2, calls.size)
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(ExplorerHealth.BOUND, coord.health(request).health)
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `the screen follows the socks epoch and the row tap retries`() {
        val activity = source("java/com/samourai/sentinel/ui/settings/NetworkActivity.kt")
        assertTrue(activity.contains("getSocksEpochLiveData"))
        assertTrue(activity.contains("onExplorerRowTapped"))
        assertTrue(activity.contains("network_explorer_layout"))
        assertTrue(activity.contains("ExplorerHealth.CHECKING"))
        assertTrue(activity.contains("ExplorerHealth.UNREACHABLE"))
        assertFalse(activity.contains("network_explorer_retry"))
        val manager = source("java/com/samourai/sentinel/tor/SentinelTorManager.kt")
        val start = manager.substringAfter("private fun startInternal")
            .substringBefore("fun stop()")
        assertTrue(start.contains("setProxy(null)"))
        val stop = manager.substringAfter("fun stop()")
            .substringBefore("fun newIdentity")
        assertTrue(stop.contains("setProxy(null)"))
        val listeners = manager.substringAfter("RuntimeEvent.LISTENERS")
            .substringBefore("config {")
        assertTrue(listeners.contains("setProxy(next)"))
        assertFalse(listeners.contains("proxy ="))
    }

    @Test
    fun `a fresh check for this tor session is shown and a previous session is probed again`() = runTest {
        val fixed = 1_700_000_000_000L
        val repo = ExplorerRepository()
        val endpoint = "127.0.0.1:9050"
        repo.rememberProbe("PAIRED:$base", ExplorerProbeOutcome.REACHABLE, fixed, 5, endpoint)
        var calls = 0
        val same = ready(endpoint = endpoint, generation = 5)
        val first = coordinator(repo, now = { fixed }) { _, _ ->
            calls += 1
            ProbeResult.Unreachable
        }
        first.refresh(same)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ExplorerProbeOutcome.REACHABLE, first.outcome)
        assertEquals(ExplorerHealth.BOUND, first.health(same).health)
        assertTrue(explorerOperatorVisible(first.health(same).health, "SG Directory Dojo"))

        val reopened = coordinator(repo, now = { fixed }) { _, _ ->
            calls += 1
            ProbeResult.Unreachable
        }
        reopened.refresh(same)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ExplorerHealth.BOUND, reopened.health(same).health)

        val moved = ready(endpoint = "127.0.0.1:9051", generation = 6)
        reopened.refresh(moved)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, reopened.outcome)
        assertEquals(6, repo.rememberedProbe()?.socksGeneration)
        assertEquals("127.0.0.1:9051", repo.rememberedProbe()?.socksEndpoint)
        assertFalse(explorerOperatorVisible(reopened.health(moved).health, "SG Directory Dojo"))
    }

    private fun TestScope.coordinator(
        repo: ExplorerRepository,
        now: () -> Long = { System.currentTimeMillis() },
        probe: suspend (String, String) -> ProbeResult,
    ): ExplorerProbeCoordinator = ExplorerProbeCoordinator(
        scope = this,
        repository = repo,
        probe = probe,
        now = now,
    )

    private fun ready(endpoint: String, generation: Int) = ExplorerProbeRequest(
        resolution = resolution,
        torState = EnumTorState.ON,
        torProgress = 100,
        socksEndpoint = endpoint,
        socksGeneration = generation,
    )

    private fun notReady(progress: Int, endpoint: String?, generation: Int) = ExplorerProbeRequest(
        resolution = resolution,
        torState = if (progress > 0) EnumTorState.ON else EnumTorState.STARTING,
        torProgress = progress,
        socksEndpoint = endpoint,
        socksGeneration = generation,
    )

    private fun capturingTree(lines: MutableList<String>): Timber.Tree =
        object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += "${tag.orEmpty()} $message"
            }
        }

    private fun source(path: String): String {
        val file = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }
        return file.readText()
    }
}
