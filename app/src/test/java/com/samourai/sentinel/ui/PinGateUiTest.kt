package com.samourai.sentinel.ui

import android.app.Application
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.LockScreenDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * #528. The unavailable arm is a red prompt and not the wrong-PIN
 * cross. Confirming exit removes the task. The pad stays FLAG_SECURE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinGateUiTest {

    private lateinit var prefs: PrefsUtil
    private var savedPinHash: String? = null
    private var savedPinKey: String? = null
    private var savedPinEnabled: Boolean? = null
    private var savedScramble: Boolean? = null
    private var savedHaptics: Boolean? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        savedPinHash = prefs.pinHash
        savedPinKey = prefs.pinKeyMaterial
        savedPinEnabled = prefs.pinEnabled
        savedScramble = prefs.scramblePin
        savedHaptics = prefs.haptics
        prefs.pinHash = PIN_MARKER
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = true
        prefs.scramblePin = false
        prefs.haptics = false
        startKoin {
            androidContext(app)
            modules(module { single { prefs } })
        }
    }

    @After
    fun tearDown() {
        prefs.pinHash = savedPinHash
        prefs.pinKeyMaterial = savedPinKey
        prefs.pinEnabled = savedPinEnabled
        prefs.scramblePin = savedScramble
        prefs.haptics = savedHaptics
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun `unavailable shows an inline red message and not the wrong-pin cross`() {
        val activity = launch()
        val pad = pad(activity)
        assertTrue(activity.window.isSecure())
        val prompt = pad.requireView().findViewById<TextView>(R.id.lockScreenText)
        val promptColor = prompt.currentTextColor
        assertEquals("Enter pin code", prompt.text.toString())

        repeat(5) {
            pad.requireView().findViewById<View>(R.id.ta).performClick()
        }
        val mask = pad.requireView().findViewById<android.widget.LinearLayout>(R.id.pinEntryMaskLayout)
        val confirm = pad.requireView().findViewById<View>(R.id.tconfirm)
        assertTrue(mask.childCount >= 5)
        assertEquals(View.VISIBLE, confirm.visibility)

        pad.resetPad(UNAVAILABLE)
        assertEquals(0, mask.childCount)
        assertEquals(View.GONE, confirm.visibility)
        assertEquals(UNAVAILABLE, prompt.text.toString())
        assertEquals(activity.getColor(R.color.red), prompt.currentTextColor)
        assertTrue(activity.window.isSecure())

        pad.requireView().findViewById<View>(R.id.ta).performClick()
        assertEquals("Enter pin code", prompt.text.toString())
        assertEquals(promptColor, prompt.currentTextColor)
        assertFalse(prompt.text.toString().contains(PIN_MARKER))
    }

    @Test
    fun `a wrong pin still flashes the cross`() {
        val activity = launch()
        val pad = pad(activity)
        val prompt = pad.requireView().findViewById<TextView>(R.id.lockScreenText)
        val confirm = pad.requireView().findViewById<View>(R.id.tconfirm)
        pad.showError()
        assertEquals(View.VISIBLE, confirm.visibility)
        assertEquals(activity.getString(R.string.pin_incorrect), prompt.text.toString())
        assertEquals(activity.getColor(R.color.red), prompt.currentTextColor)
        assertTrue(activity.window.isSecure())
    }

    @Test
    fun `confirming exit removes the task and the dialog shows nothing sensitive`() {
        val activity = launch()
        assertTrue(activity.window.isSecure())
        assertFalse(activity.isFinishing)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        ShadowLooper.idleMainLooper()
        val declined = ShadowDialog.getLatestDialog() as AlertDialog
        val declinedMessage = declined.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertEquals(activity.getString(R.string.ask_you_sure_exit), declinedMessage)
        assertFalse(declinedMessage.contains(PIN_MARKER))
        declined.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(activity.isFinishing)
        assertTrue(activity.window.isSecure())

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        ShadowLooper.idleMainLooper()
        val confirmed = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            activity.getString(R.string.yes),
            confirmed.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("confirming exit must finish and remove the task", activity.isFinishing)
        assertTrue(activity.window.isSecure())
    }

    private fun launch(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun pad(activity: MainActivity): LockScreenDialog {
        val pad = activity.supportFragmentManager
            .findFragmentByTag(MainActivity.TAG_UNLOCK_PIN) as LockScreenDialog
        assertNotNull(pad.view)
        return pad
    }

    private fun android.view.Window.isSecure(): Boolean =
        attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private companion object {
        const val PIN_MARKER = "exit-dialog-must-not-show-this"
        const val UNAVAILABLE = "Could not open saved unlock data. Try again."
    }
}
