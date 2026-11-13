package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sentences live in strings, filled with the display-name constant.
 * External analysis keeps its own wording and does not point at a Dojo,
 * Custom, or Settings.
 */
class ExplorerSettingsCopyTest {

    @Test
    fun `info sheets and advisories keep the agreed meaning`() {
        val strings = source(
            "app/src/main/res/values/strings.xml",
            "src/main/res/values/strings.xml",
        )
        listOf(
            "Your Dojo · onion",
            "%1\$s · onion over Tor",
            "%1\$s · website over Tor",
            "%1\$s · website, Tor off",
            ">Needs Tor<",
            "Your Dojo\\'s own onion explorer. Lookups stay with your Dojo and travel over Tor.",
            "without passing a Tor exit relay",
            "will see which transaction or address you look up, and can connect the lookups you make in the same session.",
            "A Tor exit relay and %1\$s can see what you look up. Your IP address stays hidden.",
            "Tor is off, so %1\$s also sees your IP address.",
            "A clearnet custom explorer is a third party. Over Tor, an exit relay can see the lookup. An onion custom explorer stays end-to-end over Tor: the lookup does not pass an exit relay.",
            "Don\\'t show again for this option",
            ">mempool.onion<",
            "Address: %1\$s",
        ).forEach { phrase ->
            assertTrue("missing: $phrase", strings.contains(phrase))
        }
        assertTrue(strings.contains("This opens %1\$s, a third party. It fetches the blockchain data from the %2\$s API (third-party servers), bypassing your paired explorer."))
        assertTrue(strings.contains(">Analyze externally<"))
        assertFalse(strings.contains("mempool.space"))
    }

    @Test
    fun `the opener wires both mempool advisories and leaves analysis alone`() {
        val opener = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
        )
        assertTrue(opener.contains("explorer_mempool_onion_base"))
        assertTrue(opener.contains("explorer_mempool_space_base"))
        assertTrue(opener.contains("explorer_mempool_space_tor"))
        assertTrue(opener.contains("explorer_custom_clearnet_tor"))
        assertTrue(opener.contains("explorer_advisory_dont_show"))
        assertTrue(opener.contains("advisorySuppressed"))
        assertTrue(opener.contains("rememberAdvisory"))
        assertTrue(opener.contains("lookupRequiresTor"))

        val analysis = opener.substringAfter("private fun confirmAnalysis")
            .substringBefore("private fun showAdvisory")
        assertTrue(analysis.contains("ExplorerAdvisory.analysisMessage"))
        assertTrue(analysis.contains("ExplorerRepository.AM_I_EXPOSED_NAME"))
        assertTrue(analysis.contains("TxFactsResolver.MEMPOOL_DISPLAY_NAME"))
        assertTrue(analysis.contains("explorer_analysis_base_paired"))
        assertFalse(analysis.contains("explorer_mempool_onion_base"))
        assertFalse(analysis.contains("explorer_third_party_guidance"))
        assertFalse(analysis.contains("guidance"))
        assertFalse(analysis.contains("Dojo"))
        assertFalse(analysis.contains("Custom"))
        assertFalse(analysis.contains("Settings"))

        val web = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerWebViewActivity.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerWebViewActivity.kt",
        )
        val routing = web.substringAfter("private fun beginRouting()")
            .substringBefore("private fun showBlocked")
        assertTrue(routing.contains("torRequiredForThisPage()"))
        assertTrue(web.contains("lookupRequiresTor"))
        val stopped = web.substringAfter("explorerWhenTorStopsBeforeLoad")
            .substringBefore("if (stopped")
        assertTrue(stopped.contains("torRequiredForThisPage()"))

        val sheet = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerOptionsBottomSheet.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerOptionsBottomSheet.kt",
        )
        assertTrue(sheet.contains("R.drawable.ic_tor_onion"))
        assertTrue(sheet.contains("R.drawable.ic_globe"))
        assertTrue(sheet.contains("R.color.green_ui_2"))
        assertTrue(sheet.contains("ExplorerInfoBottomSheet"))
        val row = source(
            "app/src/main/res/layout/item_explorer_option.xml",
            "src/main/res/layout/item_explorer_option.xml",
        )
        assertTrue(row.contains("@string/explorer_option_needs_tor"))
        assertTrue(row.contains("@drawable/ic_info_outline"))

        val list = source(
            "app/src/main/java/com/samourai/sentinel/ui/dojo/CommunityDojoAdapter.kt",
            "src/main/java/com/samourai/sentinel/ui/dojo/CommunityDojoAdapter.kt",
        )
        assertTrue(list.contains("communityDojoSubtitle(node)"))
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
