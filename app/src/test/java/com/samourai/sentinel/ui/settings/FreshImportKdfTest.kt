package com.samourai.sentinel.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #358: the import PIN continuation's Argon2id wait. The overlay is
 * up only while [protectImportedPin]'s protect block runs, and it is
 * dropped on success, failure, and cancellation.
 */
class FreshImportKdfTest {

    @Test
    fun overlayIsUpOnlyWhileThePinIsSaved() = runBlocking {
        val ui = RecordingWait()
        val events = mutableListOf<String>()
        protectImportedPin(
            show = {
                events += "show"
                ui.show(it)
            },
            dismiss = {
                events += "dismiss"
                ui.dismiss()
            },
            main = Dispatchers.Unconfined,
        ) {
            events += "protect"
            assertTrue(ui.showing)
            assertEquals(FRESH_IMPORT_PIN_LABEL, ui.label)
            assertEquals(0, ui.dismisses)
        }
        assertEquals(listOf("show", "protect", "dismiss"), events)
        assertFalse(ui.showing)
        assertEquals(1, ui.shows)
        assertEquals(1, ui.dismisses)
    }

    @Test
    fun overlayIsDismissedWhenSavingThePinFails() = runBlocking {
        val ui = RecordingWait()
        try {
            protectImportedPin(
                show = { ui.show(it) },
                dismiss = { ui.dismiss() },
                main = Dispatchers.Unconfined,
            ) {
                assertTrue(ui.showing)
                throw IllegalStateException("wrap failed")
            }
            fail("protect failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("wrap failed", e.message)
        }
        assertFalse(ui.showing)
        assertEquals(FRESH_IMPORT_PIN_LABEL, ui.label)
        assertEquals(1, ui.shows)
        assertEquals(1, ui.dismisses)
    }

    @Test
    fun overlayIsDismissedWhenTheScreenGoesAwayDuringTheDerive() = runBlocking {
        val ui = RecordingWait()
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            protectImportedPin(
                show = { ui.show(it) },
                dismiss = { ui.dismiss() },
                main = Dispatchers.Unconfined,
            ) {
                assertEquals(FRESH_IMPORT_PIN_LABEL, ui.label)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertTrue(ui.showing)
        job.cancelAndJoin()
        assertFalse(ui.showing)
        assertEquals(1, ui.dismisses)
    }

    private class RecordingWait {
        var label: String? = null
        var showing: Boolean = false
        var shows: Int = 0
        var dismisses: Int = 0

        fun show(label: String) {
            this.label = label
            showing = true
            shows += 1
        }

        fun dismiss() {
            showing = false
            dismisses += 1
        }
    }
}
