package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Network row paints the last check for this explorer immediately.
 * A recent one is not checked again. An older one stays up while a
 * new check runs. Amber is only "no result" or "different explorer".
 */
class ExplorerProbeMemoryTest {

    private val key = "PAIRED:http://explorer.example"
    private val now = 1_700_000_000_000L

    @Test
    fun `a recent result is shown and not checked again`() {
        val record = record(ExplorerProbeOutcome.REACHABLE, now - 1_000)
        val decision = ExplorerProbeMemory.decide(record, key, now)
        assertEquals(ExplorerProbeMemory.Plan.USE_FRESH, decision.plan)
        assertEquals(ExplorerProbeOutcome.REACHABLE, decision.outcome)
    }

    @Test
    fun `the freshness window is inclusive and a clock step backwards keeps the last result`() {
        val window = ExplorerProbeMemory.FRESH_FOR_MS
        assertTrue(window in 60_000L..10 * 60_000L)
        val atEdge = ExplorerProbeMemory.decide(record(ExplorerProbeOutcome.REACHABLE, now - window), key, now)
        assertEquals(ExplorerProbeMemory.Plan.USE_FRESH, atEdge.plan)
        val past = ExplorerProbeMemory.decide(
            record(ExplorerProbeOutcome.UNREACHABLE, now - window - 1),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST, past.plan)
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, past.outcome)
        val backwards = ExplorerProbeMemory.decide(
            record(ExplorerProbeOutcome.REACHABLE, now + 5_000),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST, backwards.plan)
        assertEquals(ExplorerProbeOutcome.REACHABLE, backwards.outcome)
    }

    @Test
    fun `a missing result or a different explorer is unknown`() {
        assertEquals(
            ExplorerProbeMemory.Plan.PROBE_UNKNOWN,
            ExplorerProbeMemory.decide(null, key, now).plan,
        )
        assertEquals(
            ExplorerProbeOutcome.PENDING,
            ExplorerProbeMemory.decide(null, key, now).outcome,
        )
        val other = ExplorerProbeMemory.decide(
            record(ExplorerProbeOutcome.REACHABLE, now),
            "PAIRED:http://other.example",
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.PROBE_UNKNOWN, other.plan)
        assertEquals(ExplorerProbeOutcome.PENDING, other.outcome)
        val pending = ExplorerProbeMemory.decide(
            record(ExplorerProbeOutcome.PENDING, now),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.PROBE_UNKNOWN, pending.plan)
    }

    @Test
    fun `the network screen asks the memory before it paints a check`() {
        val src = source("java/com/samourai/sentinel/ui/settings/NetworkActivity.kt")
        val refresh = src.substringAfter("fun refreshExplorerRow")
        assertTrue(refresh.contains("explorerProbes.refresh"))
        val coordinator = source("java/com/samourai/sentinel/ui/webview/ExplorerProbeCoordinator.kt")
        assertTrue(coordinator.contains("ExplorerProbeMemory.decide"))
        assertTrue(coordinator.contains("ExplorerProbeMemory.Plan.USE_FRESH"))
        assertTrue(coordinator.contains("ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST"))
        assertTrue(coordinator.contains("rememberProbe"))
        val repo = source("java/com/samourai/sentinel/ui/webview/ExplorerRepository.kt")
        assertTrue(repo.contains("outcome == ExplorerProbeOutcome.PENDING"))
        assertFalseDirectoryFetch(src)
    }

    private fun record(outcome: ExplorerProbeOutcome, at: Long) =
        ExplorerProbeMemory.Record(key, outcome, at)

    private fun assertFalseDirectoryFetch(network: String) {
        assertTrue(!network.contains("CommunityDojoRepository"))
        assertTrue(!network.contains("dojobay"))
    }

    private fun source(path: String): String {
        val file = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }
        return file.readText()
    }
}
