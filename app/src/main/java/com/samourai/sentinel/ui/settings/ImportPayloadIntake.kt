package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.ImportTooLargeException
import org.json.JSONObject

/**
 * What PASTE and SCAN both do with a candidate import string before
 * [ImportBackUpActivity] calls validatePayload.
 *
 * Blank is ignored. Over [ImportLimits.BACKUP_MAX_BYTES] is the #471
 * cap paste already applied, counted in characters the same way.
 * Anything else is the exact text validatePayload receives.
 */
internal sealed class ImportPayloadIntake {
    data object Ignore : ImportPayloadIntake()
    data object TooLarge : ImportPayloadIntake()
    data class Accept(val text: String) : ImportPayloadIntake()
}

internal fun intakeImportPayload(text: String): ImportPayloadIntake {
    if (text.isBlank()) return ImportPayloadIntake.Ignore
    if (text.length > ImportLimits.BACKUP_MAX_BYTES) return ImportPayloadIntake.TooLarge
    return ImportPayloadIntake.Accept(text)
}

/**
 * True when validatePayload would arm an import.
 *
 * The three shapes are that method's classification: an Ashigaru
 * pairing envelope (`external` and `payload`), a Sentinel backup
 * (`time` and `payload`), and a legacy backup (`payload`). A URL, an
 * empty object, or a pairing object without `payload` is not accepted.
 * The text is never logged; a parse failure is not reported.
 */
internal fun importPayloadAccepted(text: String): Boolean {
    val json = try {
        JSONObject(text)
    } catch (_: Exception) {
        return false
    }
    val hasPayload = json.has("payload")
    return when {
        json.has("external") && hasPayload -> true
        json.has("time") && hasPayload -> true
        hasPayload -> true
        else -> false
    }
}

/**
 * SCAN's decision on one admitted decode. Blank and over-cap match
 * [intakeImportPayload]. An [ImportScanDecision.Accept] carries the
 * same string paste would hand to validatePayload. Anything else is
 * unrecognized: the sheet closes and one neutral message is shown.
 */
internal sealed class ImportScanDecision {
    data object Ignore : ImportScanDecision()
    data object TooLarge : ImportScanDecision()
    data object Unrecognized : ImportScanDecision()
    data class Accept(val text: String) : ImportScanDecision()
}

internal fun decideImportScan(text: String): ImportScanDecision {
    return when (val intake = intakeImportPayload(text)) {
        ImportPayloadIntake.Ignore -> ImportScanDecision.Ignore
        ImportPayloadIntake.TooLarge -> ImportScanDecision.TooLarge
        is ImportPayloadIntake.Accept ->
            if (importPayloadAccepted(intake.text)) ImportScanDecision.Accept(intake.text)
            else ImportScanDecision.Unrecognized
    }
}

/** Same sentence paste shows for an over-cap clipboard (#471). */
internal fun importTooLargeMessage(): String =
    ImportTooLargeException(
        ImportLimits.BACKUP_MAX_BYTES.toLong(),
        "characters",
    ).message.orEmpty()

internal const val IMPORT_SCAN_UNRECOGNIZED =
    "That QR isn't a Sentinel backup or Ashigaru pairing"
