package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A new explorer host anywhere under the app, the wallet library,
 * or the entropy library fails this test. The two allowed files are
 * the ones that own the constants.
 */
class HardcodedExplorerHostTest {

    private val host = Regex(
        "(?i)(" +
            "blockstream\\.info|" +
            "blockchair\\.com|" +
            "blkchairbknpn73cfjhevhla7rkp4ed5gg2knctvv7it4lioy22defid\\.onion|" +
            "mempool\\.space|" +
            "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad\\.onion|" +
            "am-i\\.exposed|" +
            "api\\.oxt\\.me|" +
            "oxt\\.me|" +
            "explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid\\.onion" +
            ")",
    )

    private val allowed = setOf("ExplorerRepository.kt", "TxFactsResolver.kt")

    @Test
    fun `explorer hosts live only in the repository and the facts resolver`() {
        val roots = moduleRoots()
        assertTrue("app sources were not found from ${File(".").absolutePath}", roots.any { it.path.contains("app") || it.path.endsWith("src/main") })
        val offenders = mutableListOf<String>()
        val allowedHits = mutableSetOf<String>()
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension in SCANNED }.forEach { file ->
                val text = file.readText()
                if (!host.containsMatchIn(text)) return@forEach
                if (file.name in allowed) {
                    allowedHits += file.name
                } else {
                    offenders += file.path
                }
            }
        }
        assertTrue(
            "hardcoded explorer host outside ${allowed.joinToString()}: $offenders",
            offenders.isEmpty(),
        )
        assertTrue(allowedHits.containsAll(allowed))
    }

    @Test
    fun `production does not call the public facts url`() {
        val hits = moduleRoots()
            .filter { it.path.contains("sentinel") || !it.path.contains("extlibj") && !it.path.contains("boltzmann") }
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { file ->
                    file to file.readText()
                }
            }
            .filter { (file, text) -> file.name != "TxFactsResolver.kt" && text.contains("factsUrl(") }
            .map { it.first.path }
        assertTrue("factsUrl is a test seam, not a lookup: $hits", hits.isEmpty())
    }

    @Test
    fun `the analysis advisory names both parties from the allowed constants`() {
        val opener = appRoot().walkTopDown()
            .first { it.isFile && it.name == "ExplorerOpener.kt" }
            .readText()
        val confirm = opener.substringAfter("fun confirmAnalysis")
            .substringBefore("fun showAdvisory")
        assertTrue(confirm.contains("ExplorerAdvisory.analysisMessage"))
        assertTrue(confirm.contains("ExplorerRepository.AM_I_EXPOSED_NAME"))
        assertTrue(confirm.contains("TxFactsResolver.MEMPOOL_DISPLAY_NAME"))
        val strings = sequenceOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        listOf("explorer_analysis_base", "explorer_analysis_base_paired", "explorer_analysis_direct").forEach { name ->
            val line = strings.lineSequence().first { it.contains("name=\"$name\"") }
            assertTrue("$name must take both parties as placeholders", line.contains("%1\$s") && line.contains("%2\$s"))
            assertTrue("$name must not embed an explorer host: $line", !host.containsMatchIn(line))
        }
    }

    @Test
    fun `the webview is started only by the opener`() {
        val hits = appRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in WEBVIEW_OWNER }
            .filter { it.readText().contains("ExplorerWebViewActivity") }
            .map { it.path }
            .toList()
        assertTrue("a lookup bypassed ExplorerOpener: $hits", hits.isEmpty())
    }

    private fun appRoot(): File = sequenceOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun moduleRoots(): List<File> {
        val names = listOf("app", "extlibj", "boltzmann-java")
        return names.mapNotNull { name ->
            listOfNotNull(
                File("$name/src/main"),
                File("../$name/src/main"),
                if (name == "app") File("src/main") else null,
            ).firstOrNull { it.isDirectory }
        }.distinctBy { it.canonicalPath }
    }

    private companion object {
        val SCANNED = setOf("kt", "java", "xml")
        val WEBVIEW_OWNER = setOf("ExplorerOpener.kt", "ExplorerWebViewActivity.kt")
    }
}
