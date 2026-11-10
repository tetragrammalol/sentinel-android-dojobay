package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SeenBackendWithFallback.withOxt and ExplorerApi.getUrl throw.
 * A new call site has to be routed through ExplorerRepository
 * before this test will pass.
 */
class VendoredExplorerCallerTest {

    @Test
    fun `nothing calls withOxt or ExplorerApi`() {
        val roots = listOf("app", "extlibj", "boltzmann-java", "xmanager-protocol", "bitcoinj")
            .mapNotNull { name ->
                listOf(File("$name/src/main"), File("../$name/src/main")).firstOrNull { it.isDirectory }
            }
        assertTrue(roots.isNotEmpty())
        val withOxt = mutableListOf<String>()
        val explorerApi = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .forEach { file ->
                    val text = file.readText()
                    if (text.contains("withOxt") && file.name != "SeenBackendWithFallback.java") {
                        withOxt += file.path
                    }
                    if (text.contains("ExplorerApi") && file.name != "ExplorerApi.java") {
                        explorerApi += file.path
                    }
                }
        }
        assertTrue("withOxt callers: $withOxt", withOxt.isEmpty())
        assertTrue("ExplorerApi callers: $explorerApi", explorerApi.isEmpty())
    }
}
