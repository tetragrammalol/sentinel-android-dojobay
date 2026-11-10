package com.samourai.sentinel.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.ui.views.applyScreenshotPolicy
import com.samourai.sentinel.ui.views.isScreenshotBlocked
import com.samourai.sentinel.ui.views.keepScreenshotsBlocked
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
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #511. Disable Screenshots is PrefsUtil.displaySecure. The PIN pad
 * secures the host window while it is showing and restores the setting
 * when it leaves. Dojo pairing credentials and the pairing QR stay
 * secure on their own window with the setting on or off, and closing
 * that window does not leave the flag on the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplaySecurePolicyTest {

    private companion object {
        const val API_KEY = "synthetic-node-key"
        const val PAIRING_JSON =
            """{"pairing":{"type":"dojo.api","version":"1.0.0","apikey":"synthetic-node-key","url":"http://synthetic.onion/v2"}}"""
    }

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
        window.keepScreenshotsBlocked()
        assertTrue(window.isScreenshotBlocked())
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
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
    fun `generic sheet stores the caller flag`() {
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
    fun `pairing credentials and qr stay secure on their own window with the setting on or off`() {
        val savedEndpoint = prefs.apiEndPoint
        val savedEndpointTor = prefs.apiEndPointTor
        val savedExplorerUrl = prefs.dojoExplorerUrl
        val savedExplorerType = prefs.dojoExplorerType
        armPairing()
        try {
            prefs.displaySecure = false
            val activity = launch()
            assertFalse(activity.window.isScreenshotBlocked())

            val off = showCredentials(activity, "credentials-off")
            assertCredentialsSecure(off, activity)
            assertFalse(activity.window.isScreenshotBlocked())
            dismissCredentials(activity, off)
            assertFalse(
                "dismissing the credentials sheet must not leave the host secure",
                activity.window.isScreenshotBlocked()
            )
            controller!!.pause().resume()
            assertFalse(activity.window.isScreenshotBlocked())

            prefs.displaySecure = true
            controller!!.pause().resume()
            assertTrue(activity.window.isScreenshotBlocked())
            val on = showCredentials(activity, "credentials-on")
            assertCredentialsSecure(on, activity)
            assertTrue(activity.window.isScreenshotBlocked())
            dismissCredentials(activity, on)
            assertTrue(activity.window.isScreenshotBlocked())
        } finally {
            prefs.apiEndPoint = savedEndpoint
            prefs.apiEndPointTor = savedEndpointTor
            prefs.dojoExplorerUrl = savedExplorerUrl
            prefs.dojoExplorerType = savedExplorerType
            SentinelCollectionStore().getDojoStore().deleteAll()
        }
    }

    @Test
    fun `dojobay screens that do not show the api key stay on the setting`() {
        val network = source("app/src/main/java/com/samourai/sentinel/ui/settings/NetworkActivity.kt")
        assertTrue(network.contains("val sheet = DojoCredentialsBottomSheet()"))
        assertFalse(network.contains("DojoCredentialsBottomSheet(secure"))
        assertFalse(network.contains("LayoutParams.FLAG_SECURE"))

        val credentials = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoCredentialsBottomSheet.kt")
        assertTrue(credentials.contains("keepScreenshotsBlocked()"))
        assertTrue(credentials.contains("putBoolean(GenericBottomSheet.ARG_SECURE, true)"))
        assertFalse(credentials.contains("displaySecure"))
        assertFalse(credentials.contains("requireActivity().window"))
        assertFalse(credentials.contains("LayoutParams.FLAG_SECURE"))

        val configure = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoConfigureBottomSheet.kt")
        val community = source("app/src/main/java/com/samourai/sentinel/ui/dojo/CommunityDojoListBottomSheet.kt")
        assertFalse(configure.contains("LayoutParams.FLAG_SECURE"))
        assertFalse(configure.contains("requireActivity().window"))
        assertFalse(community.contains("LayoutParams.FLAG_SECURE"))
        assertFalse(community.contains("requireActivity().window"))

        val qr = source("app/src/main/java/com/samourai/sentinel/ui/fragments/QRBottomSheetDialog.kt")
        assertFalse(qr.contains("keepScreenshotsBlocked"))
        assertFalse(qr.contains("Dojo"))

        val camera = source("app/src/main/java/com/samourai/sentinel/ui/views/codeScanner/CameraFragmentBottomSheet.java")
        assertTrue(camera.contains("getValue(\"displaySecure\", false)"))
        assertFalse(camera.contains("getValue(\"displaySecure\", true)"))

        val policy = source("app/src/main/java/com/samourai/sentinel/ui/views/ScreenshotPolicy.kt")
        assertTrue(policy.contains("DojoCredentialsBottomSheet"))
        assertTrue(policy.contains("DojoConfigureBottomSheet"))
        assertTrue(policy.contains("fun Window.keepScreenshotsBlocked()"))
    }

    private fun armPairing() {
        // DojoUtility.read() builds ApiService, and that touches
        // SentinelState, which asks Koin for this same DojoUtility and
        // later for the collection list. Publish the instance before
        // that read can observe it, and give SentinelState the empty
        // repository it posts onto the main looper.
        val ready = CountDownLatch(1)
        var published: DojoUtility? = null
        loadKoinModules(
            module {
                single { SentinelCollectionStore() }
                single { CollectionRepository() }
                single { ApiService() }
                single {
                    check(ready.await(5, TimeUnit.SECONDS)) { "DojoUtility was not published" }
                    published ?: error("DojoUtility was not published")
                }
            }
        )
        val store = GlobalContext.get().get<SentinelCollectionStore>()
        store.getDojoStore().deleteAll()
        store.getDojoStore().writeToFile(PAIRING_JSON)
        val dojo = DojoUtility()
        published = dojo
        ready.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (dojo.exportDojoPayload().isNullOrBlank() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        check(dojo.exportDojoPayload()?.contains(API_KEY) == true) {
            "pairing payload did not load"
        }
    }

    private fun showCredentials(
        activity: PolicyHostActivity,
        tag: String,
    ): DojoCredentialsBottomSheet {
        val sheet = DojoCredentialsBottomSheet()
        // A restore can still be holding the old caller flag. Showing
        // that sheet must come up secure anyway.
        sheet.arguments = Bundle().apply {
            putBoolean(GenericBottomSheet.ARG_SECURE, false)
        }
        sheet.show(activity.supportFragmentManager, tag)
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        return sheet
    }

    private fun assertCredentialsSecure(
        sheet: DojoCredentialsBottomSheet,
        activity: PolicyHostActivity,
    ) {
        val window = sheet.dialog?.window
        assertNotNull(window)
        assertTrue(window !== activity.window)
        assertTrue(window!!.isScreenshotBlocked())
        assertEquals(
            API_KEY,
            sheet.requireView().findViewById<TextView>(R.id.dojoApiKeyValue).text.toString()
        )
        val qr = sheet.requireView().findViewById<ImageView>(R.id.dojoPairingQr)
        assertEquals(View.VISIBLE, qr.visibility)
        assertNotNull(qr.drawable)
        val payload = sheet.requireView().findViewById<TextView>(R.id.dojoPayloadValue).text.toString()
        assertTrue(payload.contains(API_KEY))
        assertTrue(sheet.requireArguments().getBoolean(GenericBottomSheet.ARG_SECURE, false))
    }

    private fun dismissCredentials(activity: PolicyHostActivity, sheet: DojoCredentialsBottomSheet) {
        sheet.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        assertFalse(sheet.isAdded)
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
