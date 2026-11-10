package com.samourai.sentinel.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.ui.views.applyScreenshotPolicy
import com.samourai.sentinel.ui.views.isScreenshotBlocked
import org.junit.After
import org.junit.Assert.assertFalse
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * #511. Disable Screenshots is PrefsUtil.displaySecure. The Dojobay
 * credentials sheet used to force FLAG_SECURE, and the PIN pad set
 * the same flag on the host window without ever clearing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplaySecurePolicyTest {

    private lateinit var prefs: PrefsUtil
    private var savedSecure: Boolean? = null
    private var savedPinHash: String? = null
    private var savedPinKey: String? = null
    private var savedPinEnabled: Boolean? = null
    private var savedAdmitted = false
    private var controller: ActivityController<PolicyHostActivity>? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        savedSecure = prefs.displaySecure
        savedPinHash = prefs.pinHash
        savedPinKey = prefs.pinKeyMaterial
        savedPinEnabled = prefs.pinEnabled
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        prefs.displaySecure = false
        savedAdmitted = sessionAdmitted()
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        access.updatePin()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                }
            )
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        prefs.displaySecure = savedSecure
        prefs.pinHash = savedPinHash
        prefs.pinKeyMaterial = savedPinKey
        prefs.pinEnabled = savedPinEnabled
        sessionAdmitted(savedAdmitted)
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun `policy sets and clears only the secure flag`() {
        val window = Robolectric.buildActivity(Activity::class.java).setup().get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.applyScreenshotPolicy(true)
        assertTrue(window.isScreenshotBlocked())
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        window.applyScreenshotPolicy(false)
        assertFalse(window.isScreenshotBlocked())
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        window.applyScreenshotPolicy(true)
        assertTrue(window.isScreenshotBlocked())
    }

    @Test
    fun `resume follows the setting both ways`() {
        val activity = launch()
        assertFalse(activity.window.isScreenshotBlocked())

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        controller!!.pause().resume()
        assertFalse(
            "a leftover FLAG_SECURE must clear when Disable Screenshots is off",
            activity.window.isScreenshotBlocked()
        )

        prefs.displaySecure = true
        controller!!.pause().resume()
        assertTrue(activity.window.isScreenshotBlocked())
    }

    @Test
    fun `pin pad secures the host while showing and restores the setting when it closes`() {
        prefs.displaySecure = false
        val activity = launch()
        assertFalse(activity.window.isScreenshotBlocked())

        val pad = showPad(activity, "pad")
        assertTrue(activity.window.isScreenshotBlocked())

        controller!!.pause().resume()
        assertTrue(
            "a resume while the pad is up must not clear the flag",
            activity.window.isScreenshotBlocked()
        )

        activity.supportFragmentManager.beginTransaction().remove(pad).commitNow()
        assertFalse(activity.window.isScreenshotBlocked())
    }

    @Test
    fun `closing the top pin pad keeps the host secure while another pad is showing`() {
        prefs.displaySecure = false
        val activity = launch()
        val lower = showPad(activity, "lower")
        val upper = showPad(activity, "upper")
        assertTrue(activity.window.isScreenshotBlocked())

        activity.supportFragmentManager.beginTransaction().remove(upper).commitNow()
        assertTrue(activity.window.isScreenshotBlocked())

        activity.supportFragmentManager.beginTransaction().remove(lower).commitNow()
        assertFalse(activity.window.isScreenshotBlocked())
    }

    @Test
    fun `pin pad leaves the host secure when Disable Screenshots is on`() {
        prefs.displaySecure = true
        val activity = launch()
        assertTrue(activity.window.isScreenshotBlocked())

        val pad = showPad(activity, "pad")
        assertTrue(activity.window.isScreenshotBlocked())

        activity.supportFragmentManager.beginTransaction().remove(pad).commitNow()
        assertTrue(activity.window.isScreenshotBlocked())
    }

    @Test
    fun `credentials sheet stores the caller flag and defaults to off`() {
        assertFalse(
            DojoCredentialsBottomSheet().requireArguments()
                .getBoolean(GenericBottomSheet.ARG_SECURE, true)
        )
        assertFalse(
            DojoCredentialsBottomSheet(secure = false).requireArguments()
                .getBoolean(GenericBottomSheet.ARG_SECURE, true)
        )
        assertTrue(
            DojoCredentialsBottomSheet(secure = true).requireArguments()
                .getBoolean(GenericBottomSheet.ARG_SECURE, false)
        )
        assertTrue(
            GenericBottomSheet(true).requireArguments()
                .getBoolean(GenericBottomSheet.ARG_SECURE, false)
        )
        assertFalse(
            GenericBottomSheet(false).requireArguments()
                .getBoolean(GenericBottomSheet.ARG_SECURE, true)
        )
    }

    @Test
    fun `dojobay call site follows the setting and does not flag the host window`() {
        val network = source("app/src/main/java/com/samourai/sentinel/ui/settings/NetworkActivity.kt")
        assertTrue(network.contains("DojoCredentialsBottomSheet(secure = prefsUtil.displaySecure == true)"))
        assertFalse(network.contains("LayoutParams.FLAG_SECURE"))

        val credentials = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoCredentialsBottomSheet.kt")
        assertTrue(credentials.contains("secure: Boolean = false"))
        assertFalse(credentials.contains("secure: Boolean = true"))
        assertFalse(credentials.contains("requireActivity().window"))
        assertFalse(credentials.contains("LayoutParams.FLAG_SECURE"))

        val configure = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoConfigureBottomSheet.kt")
        val community = source("app/src/main/java/com/samourai/sentinel/ui/dojo/CommunityDojoListBottomSheet.kt")
        assertFalse(configure.contains("LayoutParams.FLAG_SECURE"))
        assertFalse(configure.contains("requireActivity().window"))
        assertFalse(community.contains("LayoutParams.FLAG_SECURE"))
        assertFalse(community.contains("requireActivity().window"))

        val camera = source("app/src/main/java/com/samourai/sentinel/ui/views/codeScanner/CameraFragmentBottomSheet.java")
        assertTrue(camera.contains("getValue(\"displaySecure\", false)"))
        assertFalse(camera.contains("getValue(\"displaySecure\", true)"))
    }

    private fun launch(): PolicyHostActivity {
        val created = Robolectric.buildActivity(PolicyHostActivity::class.java)
        // Not in the manifest, so it does not pick up AppThemeV3 on its own.
        created.get().setTheme(R.style.AppThemeV3)
        controller = created.setup()
        return created.get()
    }

    private fun showPad(activity: PolicyHostActivity, tag: String): LockScreenDialog {
        val pad = LockScreenDialog.newInstance(
            message = "Enter pin code",
            requestKey = "pin_$tag",
            purpose = LockScreenDialog.PURPOSE_UNLOCK,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, pad, tag)
            .commitNow()
        return pad
    }

    private fun sessionAdmitted(): Boolean {
        val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
        field.isAccessible = true
        return field.getBoolean(null)
    }

    private fun sessionAdmitted(value: Boolean) {
        val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
        field.isAccessible = true
        field.setBoolean(null, value)
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}

/** Host with no screen of its own, so the screenshot policy is the only window flag in play. */
class PolicyHostActivity : SentinelActivity() {
    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        setContentView(View(this))
    }
}
