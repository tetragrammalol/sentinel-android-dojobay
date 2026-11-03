package com.samourai.sentinel.ui.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #223: the legacy Java util/PrefsUtil is gone. Its callers read the same
 * default preferences file through the Kotlin PrefsUtil, with the Java
 * door's keys and defaults kept exactly. Source-level.
 */
class LegacyPrefsConsolidationTest {

    @Test
    fun theJavaPrefsUtilIsGoneAndNothingImportsIt() {
        assertFalse(File(path("util/PrefsUtil.java")).exists() || File(path("util/PrefsUtil.java").removePrefix("app/")).exists())
        val root = sequenceOf(File("app/src/main/java"), File("src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filter { file ->
                file.readLines().any { line ->
                    // Code only: commented-out calls are not callers.
                    val code = line.substringBefore("//")
                    code.contains("com.samourai.sentinel.util.PrefsUtil") || code.contains("PrefsUtil.getInstance(")
                }
            }
            .map { it.name }
            .toList()
        assertTrue("still on the Java door: $offenders", offenders.isEmpty())
    }

    @Test
    fun keysAndDefaultsAreTheJavaDoorsOwn() {
        val prefs = read("ui/utils/PrefsUtil.kt")
        // The scanner was always secure by default; the property is not.
        assertTrue(prefs.contains("fun scannerDisplaySecure(): Boolean = readBoolean(\"displaySecure\", true)"))
        assertTrue(prefs.contains("var displaySecure by booleanPref(defaultValue = false)"))
        // The legacy "ENABLE_TOR" key, not the "enableTor" toggle.
        assertTrue(prefs.contains("fun legacyEnableTor(): Boolean = readBoolean(ENABLE_TOR, false)"))
        assertTrue(prefs.contains("const val ENABLE_TOR = \"ENABLE_TOR\""))
        assertTrue(prefs.contains("fun simImsi(): String = readString(SIM_IMSI, \"\")"))
        assertTrue("clear() was synchronous", prefs.contains("fun clearAllNow(): Boolean = prefs.edit().clear().commit()"))

        assertTrue(read("ui/views/codeScanner/CameraFragmentBottomSheet.java").contains("new PrefsUtil(requireContext()).scannerDisplaySecure()"))
        assertTrue(read("core/hd/HD_WalletFactory.java").contains("new PrefsUtil(context).clearAllNow();"))
        assertTrue(read("util/WebUtil.java").contains("new PrefsUtil(context).legacyEnableTor()"))
        assertTrue(read("util/SIMUtil.java").contains("new PrefsUtil(context).simImsi()"))
    }

    private fun path(name: String) = "app/src/main/java/com/samourai/sentinel/$name"

    private fun read(name: String): String {
        val relative = path(name)
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
