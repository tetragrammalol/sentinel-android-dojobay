package com.samourai.sentinel.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #530: a catch that does `throw CancellationException(e.message)` drops
 * the real type and stack and makes a failure look like cancellation.
 * Real cancellation throws the exception it caught, or
 * `CancellationException()` / a literal message. This gate reads main
 * sources, comments included, the same way a census does.
 */
class CancellationLaunderingGateTest {

    @Test
    fun `main sources do not rethrow a failure as cancellation`() {
        val root = sequenceOf(File("app/src/main"), File("src/main")).firstOrNull { it.isDirectory }
            ?: error("main sources not found from ${File(".").absolutePath}")
        val pattern = Regex(
            """(?<![A-Za-z0-9_])throw\s+CancellationException\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\.message\s*\)"""
        )
        val hits = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) {
                    hits.add("${file.path}:${index + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}
