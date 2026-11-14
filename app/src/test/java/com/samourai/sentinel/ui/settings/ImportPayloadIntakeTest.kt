package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.ImportTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCAN and PASTE share [intakeImportPayload]. SCAN then keeps only the
 * text validatePayload would arm; a blank decode is ignored and anything
 * else is one unrecognized result.
 */
class ImportPayloadIntakeTest {

    @Test
    fun `blank text is ignored by paste and scan`() {
        listOf("", " ", "   ", "\n", "\t\n  ").forEach { text ->
            assertEquals(text, ImportPayloadIntake.Ignore, intakeImportPayload(text))
            assertEquals(text, ImportScanDecision.Ignore, decideImportScan(text))
            assertFalse(importPayloadAccepted(text))
        }
    }

    @Test
    fun `the size cap is the paste character cap`() {
        val atCap = "x".repeat(ImportLimits.BACKUP_MAX_BYTES)
        val over = atCap + "y"
        assertTrue(intakeImportPayload(atCap) is ImportPayloadIntake.Accept)
        assertEquals(ImportPayloadIntake.TooLarge, intakeImportPayload(over))
        assertEquals(ImportScanDecision.TooLarge, decideImportScan(over))
        assertEquals(ImportScanDecision.Unrecognized, decideImportScan(atCap))
        assertEquals(
            ImportTooLargeException(ImportLimits.BACKUP_MAX_BYTES.toLong(), "characters").message,
            importTooLargeMessage(),
        )
    }

    @Test
    fun `an accepted scan is the same text paste would validate`() {
        val samples = listOf(
            """{"external":true,"payload":"pairing-content"}""",
            """{"time":1700000000,"payload":"cipher"}""",
            """{"payload":"legacy"}""",
            """{"external":false,"time":1,"payload":""}""",
            "  {\"external\":true,\"payload\":\"pairing-content\"}",
        )
        samples.forEach { text ->
            assertTrue(text, importPayloadAccepted(text))
            val paste = intakeImportPayload(text)
            val scan = decideImportScan(text)
            assertEquals(text, ImportPayloadIntake.Accept(text), paste)
            assertEquals(text, ImportScanDecision.Accept(text), scan)
            assertEquals(
                (paste as ImportPayloadIntake.Accept).text,
                (scan as ImportScanDecision.Accept).text,
            )
        }
    }

    @Test
    fun `a decode validatePayload would not arm is unrecognized`() {
        val rejected = listOf(
            "https://example.com/not-a-backup",
            "not json",
            "{}",
            """{"foo":"bar"}""",
            """{"external":true}""",
            """{"time":1}""",
            """{"pairing":{"apikey":"synthetic","url":"http://synthetic.onion"}}""",
            "[{ \"payload\": \"no\" }]",
        )
        rejected.forEach { text ->
            assertFalse(text, importPayloadAccepted(text))
            assertEquals(text, ImportPayloadIntake.Accept(text), intakeImportPayload(text))
            assertEquals(text, ImportScanDecision.Unrecognized, decideImportScan(text))
        }
    }

    @Test
    fun `paste and scan meet in deliverImportPayload and the decode is not logged`() {
        val activity = source("app/src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt")
        val paste = activity.substringAfter("importPastePayloadBtn.setOnClickListener")
            .substringBefore("importScanPayloadBtn.setOnClickListener")
        assertTrue(paste.contains("deliverImportPayload(clipped)"))
        assertFalse(paste.contains("validatePayload"))

        val scanClick = activity.substringAfter("importScanPayloadBtn.setOnClickListener")
            .substringBefore("binding.importStartBtn.isEnabled")
        assertTrue(scanClick.contains("askCameraPermission()"))
        assertTrue(scanClick.contains("showImportScanSheet()"))
        assertEquals(1, Regex("askCameraPermission\\(\\)").findAll(activity).count())

        val deliver = activity.substringAfter("private fun deliverImportPayload")
            .substringBefore("internal fun onScannedImportPayload")
        assertTrue(deliver.contains("intakeImportPayload(text)"))
        assertTrue(deliver.contains("validatePayload(intake.text)"))
        assertTrue(deliver.contains("importTooLargeMessage()"))

        val scanned = activity.substringAfter("fun onScannedImportPayload")
            .substringBefore("private fun disarmImportPayload")
        assertTrue(scanned.contains("decideImportScan(text)"))
        assertTrue(scanned.contains("deliverImportPayload(text)"))
        assertTrue(scanned.contains("deliverImportPayload(decision.text)"))
        assertTrue(scanned.contains("IMPORT_SCAN_UNRECOGNIZED"))
        assertFalse(scanned.contains("Timber"))
        assertFalse(scanned.contains("Log."))
        assertFalse(scanned.contains("println"))
        assertFalse(activity.contains("AddNewPubKeyBottomSheet"))

        val sheet = source("app/src/main/java/com/samourai/sentinel/ui/settings/ImportPayloadScanBottomSheet.kt")
        assertTrue(sheet.contains("setPasteVisible(false)"))
        assertTrue(sheet.contains("onScannedImportPayload(payload)"))
        assertTrue(sheet.contains("applyScreenshotPolicy("))
        assertFalse(sheet.contains("keepScreenshotsBlocked"))
        assertFalse(sheet.contains("pairingScan = true"))
        assertFalse(sheet.contains("Timber"))
        assertFalse(sheet.contains("Log."))
        assertFalse(sheet.contains("println"))

        val decision = source("app/src/main/java/com/samourai/sentinel/ui/settings/ImportPayloadIntake.kt")
        assertFalse(decision.contains("Timber"))
        assertFalse(decision.contains("Log."))
        assertFalse(decision.contains("println"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
