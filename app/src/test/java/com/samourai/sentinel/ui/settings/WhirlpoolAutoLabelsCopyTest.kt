package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The toggle dialog is the only place this copy is shown. It has to
 * match what sync actually does.
 */
class WhirlpoolAutoLabelsCopyTest {

    @Test
    fun dialogDescribesHistoryPagingAndManualLabels() {
        val xml = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        val key = "whirlpool_auto_labels_dialog_message"
        val at = xml.indexOf(key)
        assertTrue(at >= 0)
        val open = xml.indexOf('>', at)
        val close = xml.indexOf("</string>", open)
        assertTrue(open > at && close > open)
        val message = xml.substring(open + 1, close)
        assertTrue(message.contains("reads the full transaction history once"))
        assertTrue(message.contains("can take a while"))
        assertTrue(message.contains("Labels you set or edit yourself are never overwritten"))
        assertTrue(message.contains("pull to refresh on the collections list"))
        assertTrue(message.contains("Some older Whirlpool transactions may remain unlabeled"))
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
