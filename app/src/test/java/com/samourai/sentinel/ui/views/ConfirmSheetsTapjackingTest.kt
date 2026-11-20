package com.samourai.sentinel.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #565, JVM side: the destructive confirms and the PIN pads refuse
 * obscured taps. `filterTouchesWhenObscured` drops any touch whose
 * window is partly or fully covered, so an app holding
 * SYSTEM_ALERT_WINDOW cannot decoy the user into tapping Yes /
 * Pair / the PIN confirm through its overlay.
 *
 * The window hardening is the same gate as ScreenshotPolicy's
 * FLAG_SECURE: `setHideOverlayWindows(true)` on the sheet window
 * (API 31+) and on the pre-pair dialog window, so untrusted
 * overlays cannot draw over the sheet at all on recent Android.
 * A Robolectric inflate would not read the flag - it is a
 * ViewGroup attribute resolved at XML parse time - so the source
 * shape is pinned, the same idiom CollectionRowGreyRenderTest
 * uses for its layouts.
 */
class ConfirmSheetsTapjackingTest {

    @Test
    fun `every confirm arm layout refuses obscured taps`() {
        // The shared confirm sheet: Erase all Sentinel data, Remove
        // key, Delete collection, Remove Dojo.
        val confirm = source("app/src/main/res/layout/content_bottom_sheet_confirm.xml")
        val positive = confirm.substringAfter("bottomSheetConfirmPositiveBtn")
            .substringBefore("</com.google.android.material.button.MaterialButton>")
        assertTrue(positive.contains("""android:filterTouchesWhenObscured="true""""))

        // The input sheet's confirming arm (set Dojo manually).
        val input = source("app/src/main/res/layout/content_bottom_sheet_input.xml")
        val inputPositive = input.substringAfter("bottomSheetConfirmPositiveBtn")
            .substringBefore("</com.google.android.material.button.MaterialButton>")
        assertTrue(inputPositive.contains("""android:filterTouchesWhenObscured="true""""))

        // The pairing-password decrypt arm.
        val pairing = source("app/src/main/res/layout/content_pairing_password.xml")
        val decrypt = pairing.substringAfter("pairingPasswordDecryptBtn")
            .substringBefore("</com.google.android.material.button.MaterialButton>")
        assertTrue(decrypt.contains("""android:filterTouchesWhenObscured="true""""))
    }

    @Test
    fun `every pin pad key and the confirm refuse obscured taps`() {
        val keypad = source("app/src/main/res/layout/fragment_keypad_view.xml")
        // 10 digits + back + confirm: 12 tappable keys.
        assertEquals(12, Regex("""android:id="@\+id/t[a-z]+"""").findAll(keypad).count())
        assertEquals(
            12,
            Regex("""android:filterTouchesWhenObscured="true"""").findAll(keypad).count(),
        )
    }

    @Test
    fun `the sheet and pre-pair dialog windows refuse overlay drawing`() {
        val sheets = source("app/src/main/java/com/samourai/sentinel/ui/views/BottomSheetDialogs.kt")
        val onCreateDialog = sheets.substringAfter("override fun onCreateDialog")
            .substringBefore("companion object")
        assertTrue(onCreateDialog.contains("setHideOverlayWindows(true)"))
        assertTrue(onCreateDialog.contains("Build.VERSION.SDK_INT >= 31"))

        val prePair = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoConfigureBottomSheet.kt")
        val afterShow = prePair.substringAfter("dialog.show()")
            .substringBefore("if (signer != null)")
        assertTrue(afterShow.contains("setHideOverlayWindows(true)"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}