package com.samourai.sentinel.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.settings.ImportBackUpActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/**
 * #366. Broadcast and backup file import used to open a chooser around
 * ACTION_GET_CONTENT. The chooser always resolves, so a device with no
 * document picker got an empty sheet and no explanation.
 *
 * Robolectric records startActivityForResult and does not throw unless
 * ShadowInstrumentation.checkActivities is set. With it set and nothing
 * registered for ACTION_OPEN_DOCUMENT, the launch throws
 * ActivityNotFoundException the way a pickerless device does. Registering
 * a handler is the device that has a picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileImportPickerTest {

    private val picker = ComponentName(
        "com.android.documentsui",
        "com.android.documentsui.picker.PickActivity"
    )

    private var prefs: PrefsUtil? = null
    private var savedPinHash: String? = null
    private var savedPinKey: String? = null
    private var savedPinEnabled: Boolean? = null
    private var savedSecure: Boolean? = null
    private var savedAdmitted = false
    private var savedLastPin = 0L

    @Before
    fun setUp() {
        removePicker()
        ShadowToast.reset()
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        savedAdmitted = staticBoolean("sessionAdmitted")
        savedLastPin = staticLong("lastPin")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        savedPinHash = prefs.pinHash
        savedPinKey = prefs.pinKeyMaterial
        savedPinEnabled = prefs.pinEnabled
        savedSecure = prefs.displaySecure
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        prefs.displaySecure = false
        this.prefs = prefs
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
        setCheckActivities(true)
    }

    @After
    fun tearDown() {
        setCheckActivities(false)
        removePicker()
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        prefs?.let {
            it.pinHash = savedPinHash
            it.pinKeyMaterial = savedPinKey
            it.pinEnabled = savedPinEnabled
            it.displaySecure = savedSecure
        }
        staticBoolean("sessionAdmitted", savedAdmitted)
        staticLong("lastPin", savedLastPin)
        ShadowToast.reset()
    }

    @Test
    fun `broadcast file import toasts when no picker resolves`() {
        val activity = launch(BroadcastTx::class.java)
        assertNull(activity.packageManager.resolveActivity(openDocumentIntent(), PackageManager.MATCH_DEFAULT_ONLY))
        click(activity, R.id.fileImportBtn)
        assertPickerless(activity)
    }

    @Test
    fun `broadcast file import launches open document when a picker resolves`() {
        installPicker()
        val activity = launch(BroadcastTx::class.java)
        assertNotNull(activity.packageManager.resolveActivity(openDocumentIntent(), PackageManager.MATCH_DEFAULT_ONLY))
        click(activity, R.id.fileImportBtn)
        assertOpenedDocument(activity, BroadcastTx.REQUEST_TX_FILE)
        assertNotEquals(ImportBackUpActivity.REQUEST_FILE_CODE, BroadcastTx.REQUEST_TX_FILE)
    }

    @Test
    fun `backup file import toasts when no picker resolves`() {
        val activity = launch(ImportBackUpActivity::class.java)
        assertNull(activity.packageManager.resolveActivity(openDocumentIntent(), PackageManager.MATCH_DEFAULT_ONLY))
        click(activity, R.id.importChoosePayloadBtn)
        assertPickerless(activity)
    }

    @Test
    fun `backup file import launches open document when a picker resolves`() {
        installPicker()
        val activity = launch(ImportBackUpActivity::class.java)
        assertNotNull(activity.packageManager.resolveActivity(openDocumentIntent(), PackageManager.MATCH_DEFAULT_ONLY))
        click(activity, R.id.importChoosePayloadBtn)
        assertOpenedDocument(activity, ImportBackUpActivity.REQUEST_FILE_CODE)
    }

    private fun openDocumentIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }

    private fun <T : Activity> launch(clazz: Class<T>): T {
        val activity = Robolectric.buildActivity(clazz).setup().get()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun click(activity: Activity, viewId: Int) {
        val button = activity.findViewById<View>(viewId)
        assertNotNull(button)
        val shadow = shadowOf(activity)
        while (shadow.peekNextStartedActivityForResult() != null) {
            shadow.nextStartedActivityForResult
        }
        ShadowToast.reset()
        assertTrue(button.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPickerless(activity: Activity) {
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(
            activity.getString(R.string.no_file_picker_available),
            ShadowToast.getTextOfLatestToast()?.toString()
        )
        assertEquals(1, ShadowToast.shownToastCount())
        assertFalse(activity.isFinishing)
    }

    private fun assertOpenedDocument(activity: Activity, requestCode: Int) {
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertEquals("*/*", started.intent.type)
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(started.intent.hasExtra(Intent.EXTRA_INTENT))
        assertEquals(requestCode, started.requestCode)
        assertEquals(0, ShadowToast.shownToastCount())
        assertFalse(activity.isFinishing)
    }

    private fun installPicker() {
        val info = ActivityInfo().apply {
            packageName = picker.packageName
            name = picker.className
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo().apply {
                packageName = picker.packageName
                flags = ApplicationInfo.FLAG_INSTALLED
                enabled = true
            }
        }
        val pm = shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager)
        pm.addOrUpdateActivity(info)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        pm.addIntentFilterForActivity(picker, filter)
    }

    private fun removePicker() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager)
        pm.removeActivity(picker)
        pm.removePackage(picker.packageName)
    }

    private fun setCheckActivities(check: Boolean) {
        val shadow = Shadow.extract<Any>(InstrumentationRegistry.getInstrumentation())
        ReflectionHelpers.callInstanceMethod<Any>(
            shadow,
            "checkActivities",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, check)
        )
    }

    private fun staticBoolean(name: String): Boolean {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(null)
    }

    private fun staticBoolean(name: String, value: Boolean) {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(null, value)
    }

    private fun staticLong(name: String): Long {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(null)
    }

    private fun staticLong(name: String, value: Long) {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(null, value)
    }
}
