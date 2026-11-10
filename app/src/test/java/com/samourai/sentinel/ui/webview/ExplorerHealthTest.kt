package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorerHealthTest {

    private val paired = ExplorerBinding.ExplorerResolution.Bound(
        kind = ExplorerBinding.ExplorerKind.PAIRED,
        baseUrl = "http://synthetic-self-explorer.onion",
        displayUrl = "http://synthetic-self-explorer.onion",
    )
    private val custom = paired.copy(kind = ExplorerBinding.ExplorerKind.CUSTOM)
    private val mempool = paired.copy(
        kind = ExplorerBinding.ExplorerKind.MEMPOOL,
        baseUrl = "https://example.invalid/testnet",
        displayUrl = "https://example.invalid/testnet",
    )

    @Test
    fun `probe route is tor or nothing`() {
        assertEquals(ExplorerProbeRoute.TOR, explorerProbeRoute(torReady = true, proxyAvailable = true))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = true, proxyAvailable = false))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = false, proxyAvailable = true))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = false, proxyAvailable = false))
    }

    @Test
    fun `unbound wins over tor and a reachable paired explorer is the only green state`() {
        assertEquals(
            ExplorerHealth.NONE,
            explorerHealth(
                ExplorerBinding.ExplorerResolution.Unbound,
                torReady = false,
                torInProgress = false,
                proxyAvailable = false,
                probe = ExplorerProbeOutcome.PENDING,
            ).health,
        )
        assertEquals(ExplorerHealth.TOR_STARTING, state(paired, torReady = false, torInProgress = true))
        assertEquals(ExplorerHealth.TOR_REQUIRED, state(paired, torReady = false, torInProgress = false))
        assertEquals(
            ExplorerHealth.CHECKING,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.PENDING),
        )
        assertEquals(
            ExplorerHealth.UNREACHABLE,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.UNREACHABLE),
        )
        assertEquals(
            ExplorerHealth.BOUND,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
        assertEquals(
            ExplorerHealth.THIRD_PARTY,
            state(custom, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
        assertEquals(
            ExplorerHealth.THIRD_PARTY,
            state(mempool, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
    }

    @Test
    fun `the health check builds a client only after the tor gate`() {
        val src = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
        )
        val body = src.substringAfter("fun probe(")
        val routeAt = body.indexOf("explorerProbeRoute")
        val builderAt = body.indexOf("OkHttpClient.Builder")
        assertTrue(routeAt >= 0 && builderAt > routeAt)
        assertTrue(src.contains(".proxy(proxy)"))
        assertTrue(src.contains("TlsPolicy.install"))
        assertFalse(src.contains("Proxy.NO_PROXY"))
        assertFalse(src.contains("callTimeout(180"))
    }

    private fun state(
        resolution: ExplorerBinding.ExplorerResolution,
        torReady: Boolean,
        torInProgress: Boolean = false,
        proxy: Boolean = false,
        probe: ExplorerProbeOutcome = ExplorerProbeOutcome.PENDING,
    ): ExplorerHealth = explorerHealth(resolution, torReady, torInProgress, proxy, probe).health

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
