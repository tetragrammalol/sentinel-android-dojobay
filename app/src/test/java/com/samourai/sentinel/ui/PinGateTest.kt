package com.samourai.sentinel.ui

import android.content.Intent
import com.samourai.sentinel.core.access.UnlockResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * #528. CLEAR_TASK is ignored without NEW_TASK, confirming exit has
 * to remove the task, an unavailable vault has to say so on the pad,
 * and the unlock latch has to drop on every path that stays.
 */
class PinGateTest {

    @Test
    fun `clear task and new task are distinct bits that must both be set`() {
        val clear = Intent.FLAG_ACTIVITY_CLEAR_TASK
        val fresh = Intent.FLAG_ACTIVITY_NEW_TASK
        assertTrue(clear != 0)
        assertTrue(fresh != 0)
        assertTrue(clear and fresh == 0)
        val paired = clear or fresh
        assertTrue(paired and clear != 0)
        assertTrue(paired and fresh != 0)
    }

    @Test
    fun `every clear-task intent also sets new task`() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if ("FLAG_ACTIVITY_CLEAR_TASK" in line && "FLAG_ACTIVITY_NEW_TASK" !in line) {
                        offenders += "${file.path}:${index + 1}"
                    }
                }
            }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/MainActivity.kt") >= 1)
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/SentinelActivity.kt") >= 2)
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/settings/NetworkActivity.kt") >= 1)
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/settings/SettingsActivity.kt") >= 1)
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt") >= 2)
        assertTrue(pairedFlagCount("app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt") >= 1)
    }

    @Test
    fun `only a successful unlock with no migration leaves the screen`() {
        assertTrue(unlockLeavesScreen(UnlockResult.Success, migrationPending = false))
        assertFalse(unlockLeavesScreen(UnlockResult.Success, migrationPending = true))
        listOf(
            UnlockResult.WrongPin,
            UnlockResult.NotConfigured,
            UnlockResult.Unavailable("Could not open saved unlock data. Try again."),
        ).forEach { result ->
            assertFalse(result.toString(), unlockLeavesScreen(result, migrationPending = false))
            assertFalse(result.toString(), unlockLeavesScreen(result, migrationPending = true))
        }
    }

    @Test
    fun `busy clears on every path that does not leave the screen`() = runBlocking {
        val staying = listOf(
            UnlockResult.WrongPin,
            UnlockResult.NotConfigured,
            UnlockResult.Unavailable(UNAVAILABLE),
        )
        staying.forEach { result ->
            val recorder = Recorder()
            deliver(result, recorder)
            assertFalse(result.toString(), recorder.busy)
            assertEquals(result.toString(), 1, recorder.clears)
        }

        val migration = Recorder()
        var migrated = false
        deliver(UnlockResult.Success, migration, success = {
            migrated = true
            false
        })
        assertTrue(migrated)
        assertFalse(migration.busy)
        assertEquals(1, migration.clears)

        listOf("prepare", "wrong", "unavailable", "success").forEach { site ->
            val recorder = Recorder()
            try {
                deliver(
                    result = when (site) {
                        "wrong" -> UnlockResult.WrongPin
                        "unavailable" -> UnlockResult.Unavailable(UNAVAILABLE)
                        else -> UnlockResult.Success
                    },
                    recorder = recorder,
                    success = { error("dojo read failed") },
                    prepare = { if (site == "prepare") error("overlay failed") },
                    wrong = { if (site == "wrong") error("cross failed") else recorder.wrong += 1 },
                    unavailable = { if (site == "unavailable") error("message failed") },
                )
                fail("a throw from $site must propagate")
            } catch (e: IllegalStateException) {
                assertTrue(site, e.message?.isNotEmpty() == true)
            }
            assertFalse(site, recorder.busy)
            assertEquals(site, 1, recorder.clears)
        }
    }

    @Test
    fun `a successful unlock that leaves keeps the latch`() = runBlocking {
        val recorder = Recorder()
        var left = false
        deliver(UnlockResult.Success, recorder, success = {
            left = true
            true
        })
        assertTrue(left)
        assertTrue(recorder.busy)
        assertEquals(0, recorder.clears)
    }

    @Test
    fun `unavailable shows its message and does not take the wrong-pin arm`() = runBlocking {
        val recorder = Recorder()
        deliver(UnlockResult.Unavailable(UNAVAILABLE), recorder)
        assertEquals(UNAVAILABLE, recorder.unavailable)
        assertEquals(0, recorder.wrong)
        assertFalse(recorder.busy)
    }

    @Test
    fun `the debug line names the arm and never the message`() {
        val secret = "pin-99999-key-material"
        val line = unlockResultLogLine(UnlockResult.Unavailable("$UNAVAILABLE $secret"))
        assertEquals("Unlock result: Unavailable", line)
        assertFalse(line.contains(secret))
        assertFalse(line.contains("99999"))
        assertEquals("Unlock result: Success", unlockResultLogLine(UnlockResult.Success))
        assertEquals("Unlock result: WrongPin", unlockResultLogLine(UnlockResult.WrongPin))
        assertEquals("Unlock result: NotConfigured", unlockResultLogLine(UnlockResult.NotConfigured))
    }

    @Test
    fun `the pin screen wires the latch, the verdict, and the task exit`() {
        val main = source("app/src/main/java/com/samourai/sentinel/ui/MainActivity.kt")
        val unlock = functionBody(main, "private fun onUnlockPin(pin: String)")
        assertTrue(unlock.contains("if (unlockBusy)"))
        assertTrue(unlock.contains("unlockBusy = true"))
        assertTrue(unlock.contains("deliverUnlockResult("))
        assertTrue(unlock.contains("clearBusy = { unlockBusy = false }"))
        assertEquals(1, Regex("unlockBusy = false").findAll(unlock).count())
        assertTrue(unlock.contains("dialog.showError()"))
        assertTrue(unlock.contains("dialog.resetPad(message)"))
        assertTrue(unlock.contains("Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()"))
        assertTrue(unlock.contains("navigate()"))
        val debug = unlock.substring(
            unlock.indexOf("if (BuildConfig.DEBUG)"),
            unlock.indexOf("kdfOverlay.dismiss()"),
        )
        assertTrue(debug.contains("unlockResultLogLine(result)"))
        assertFalse(debug.contains("pin"))
        assertFalse(debug.contains("message"))
        assertFalse(debug.contains("dataKey"))
        assertFalse(debug.contains("pinKey"))

        val navigate = functionBody(main, "private fun navigate(): Boolean")
        assertTrue(navigate.contains("accessFactory.admitSession()"))
        assertTrue(navigate.contains("unlockLeavesScreen("))
        assertTrue(navigate.contains("checkMigration()"))
        assertTrue(navigate.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(navigate.contains("return leaves"))
        val migrationAt = navigate.indexOf("MigrationActivity")
        assertTrue(migrationAt > 0)
        assertTrue(navigate.substring(0, migrationAt).contains("finish()"))
        assertFalse(navigate.substring(migrationAt).contains("finish()"))

        val back = functionBody(main, "override fun onBackPressed()")
        assertTrue(back.contains("finishAndRemoveTask()"))
        assertFalse(Regex("(?<!\\w)finish\\(\\)").containsMatchIn(back))
        assertTrue(back.contains("R.string.confirm_exit"))
        assertTrue(back.contains("R.string.ask_you_sure_exit"))
        assertTrue(back.contains("R.string.yes"))
        assertTrue(back.contains("R.string.no"))
        assertFalse(back.contains("super.onBackPressed()"))
        assertFalse(back.contains("RESULT_PIN"))
        assertFalse(back.contains("pinHash"))
        assertFalse(back.contains("pinKeyMaterial"))

        val sentinel = source("app/src/main/java/com/samourai/sentinel/ui/SentinelActivity.kt")
        assertTrue(sentinel.contains("final override fun onCreate(savedInstanceState: Bundle?)"))
        assertTrue(sentinel.contains("shouldBounceUnadmittedSession(accessFactory.isSessionAdmitted)"))
        assertTrue(sentinel.contains("accessFactory.isTimedOut"))
    }

    @Test
    fun `unavailable copy is inline and red and the wrong pin still uses the cross`() {
        val lock = source("app/src/main/java/com/samourai/sentinel/ui/views/LockScreenDialog.kt")
        val created = functionBody(lock, "override fun onViewCreated(")
        assertTrue(created.contains("FLAG_SECURE"))
        val reset = functionBody(lock, "fun resetPad(message: String)")
        assertTrue(reset.contains("binding.lockScreenText.text = message"))
        assertTrue(reset.contains("R.color.red"))
        assertTrue(reset.contains("advisoryShowing = true"))
        assertFalse(reset.contains("flashErrorCross"))
        val error = functionBody(lock, "fun showError()")
        assertTrue(error.contains("flashErrorCross()"))
        assertTrue(error.contains("R.string.pin_incorrect"))
    }

    private suspend fun deliver(
        result: UnlockResult,
        recorder: Recorder,
        success: suspend () -> Boolean = { error("success not expected") },
        prepare: () -> Unit = {},
        wrong: () -> Unit = { recorder.wrong += 1 },
        unavailable: (String) -> Unit = { recorder.unavailable = it },
    ) {
        deliverUnlockResult(
            result = result,
            clearBusy = { recorder.clear() },
            onWrongPin = wrong,
            onUnavailable = unavailable,
            onSuccess = success,
            prepare = prepare,
        )
    }

    private class Recorder {
        var busy = true
        var clears = 0
        var wrong = 0
        var unavailable: String? = null

        fun clear() {
            busy = false
            clears += 1
        }
    }

    private fun pairedFlagCount(relative: String): Int =
        source(relative).lines().count {
            "FLAG_ACTIVITY_CLEAR_TASK" in it && "FLAG_ACTIVITY_NEW_TASK" in it
        }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unclosed $signature")
    }

    private fun sourceRoot(): File =
        sequenceOf(File("app/src/main"), File("src/main")).firstOrNull { it.isDirectory }
            ?: error("main sources not found from ${File(".").absolutePath}")

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        const val UNAVAILABLE = "Could not open saved unlock data. Try again."
    }
}
