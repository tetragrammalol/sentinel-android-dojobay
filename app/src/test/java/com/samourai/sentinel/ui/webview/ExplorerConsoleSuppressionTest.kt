package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #521: with no WebChromeClient, Chromium's default client writes
 * page console messages to logcat - the source field is the full
 * page URL, so the paired explorer's onion host and the viewed
 * txid land in release logcat, never through Timber. The host must
 * set a WebChromeClient whose onConsoleMessage returns true:
 * handled means nothing is logged.
 */
class ExplorerConsoleSuppressionTest {

    @Test
    fun `the webview host swallows console messages`() {
        val host = appRoot().walkTopDown()
            .first { it.isFile && it.name == "ExplorerWebViewActivity.kt" }
            .readText()
        assertTrue(
            "ExplorerWebViewActivity sets no webChromeClient (#521)",
            host.contains("webChromeClient"),
        )
        val client = host.substringAfter("object : WebChromeClient()")
            .substringBefore("val target =")
        assertTrue(
            "the chrome client must override onConsoleMessage (#521)",
            client.contains("onConsoleMessage"),
        )
        assertTrue(
            "onConsoleMessage must return true - handled means not logged (#521)",
            Regex("""return\s+true""").containsMatchIn(client),
        )
        assertTrue(
            "the suppression must not re-log anything (#521)",
            !client.contains("Timber.") && !client.contains("Log."),
        )
    }

    private fun appRoot(): File =
        sequenceOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
}
