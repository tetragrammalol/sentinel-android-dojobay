package com.samourai.sentinel.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.isScreenshotBlocked
import com.samourai.sentinel.util.ImportLimits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * SCAN asks for the camera only when tapped, a denial leaves the
 * Import buttons usable, and the sheet follows Disable Screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPayloadScanTest {

    private lateinit var prefs: PrefsUtil
    private var savedSecure: Boolean? = null
    private var savedPinHash: String? = null
    private var savedPinKey: String? = null
    private var savedPinEnabled: Boolean? = null
    private var savedAdmitted = false
    private var savedLastPin = 0L
    private var controller: ActivityController<ImportBackUpActivity>? = null

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
        savedAdmitted = staticBoolean("sessionAdmitted")
        savedLastPin = staticLong("lastPin")
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        access.updatePin()
        startKoin {
            androidContext(app)
            modules(module {
                single { prefs }
                single { access }
            })
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        if (GlobalContext.getOrNull() != null) stopKoin()
        prefs.displaySecure = savedSecure
        prefs.pinHash = savedPinHash
        prefs.pinKeyMaterial = savedPinKey
        prefs.pinEnabled = savedPinEnabled
        staticBoolean("sessionAdmitted", savedAdmitted)
        staticLong("lastPin", savedLastPin)
    }

    @Test
    fun `declining the camera ask snackbars and leaves import usable`() {
        val activity = launch()
        val scan = activity.findViewById<View>(R.id.importScanPayloadBtn)
        val paste = activity.findViewById<View>(R.id.importPastePayloadBtn)
        val choose = activity.findViewById<View>(R.id.importChoosePayloadBtn)
        assertEquals("Scan", (scan as TextView).text.toString())
        assertEquals("Paste", (paste as TextView).text.toString())
        assertEquals("Choose file", (choose as TextView).text.toString())

        assertTrue(scan.performClick())
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(
            listOf(activity.getString(R.string.camera_permission_denied)),
            snackbarTexts(activity),
        )
        assertTrue(scan.isEnabled)
        assertTrue(paste.isEnabled)
        assertTrue(choose.isEnabled)
        assertEquals(View.VISIBLE, scan.visibility)
        assertFalse(activity.isFinishing)
        assertTrue(activity.supportFragmentManager.fragments.none { it is AddNewPubKeyBottomSheet })
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportPayloadScanBottomSheet.TAG))
        assertTrue(paste.performClick())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `a cancelled or denied camera result does not open the scanner`() {
        val activity = launch()
        activity.onRequestPermissionsResult(
            SentinelActivity.CAMERA_PERMISSION,
            emptyArray(),
            intArrayOf(),
        )
        ShadowLooper.idleMainLooper()
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportPayloadScanBottomSheet.TAG))
        assertTrue(snackbarTexts(activity).isEmpty())
        assertFalse(activity.isFinishing)

        activity.onRequestPermissionsResult(
            SentinelActivity.CAMERA_PERMISSION,
            arrayOf(Manifest.permission.CAMERA),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        ShadowLooper.idleMainLooper()
        assertEquals(
            listOf(activity.getString(R.string.camera_permission_denied)),
            snackbarTexts(activity),
        )
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportPayloadScanBottomSheet.TAG))
        assertTrue(activity.findViewById<View>(R.id.importScanPayloadBtn).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.importPastePayloadBtn).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.importChoosePayloadBtn).isEnabled)
    }

    @Test
    fun `a blank decode is ignored and a url closes with one message`() {
        val activity = launch()
        val preview = activity.findViewById<TextView>(R.id.importPayloadTextView)
        preview.text = "kept"
        assertFalse(activity.onScannedImportPayload(" \n "))
        assertEquals("kept", preview.text.toString())
        assertTrue(snackbarTexts(activity).isEmpty())

        assertTrue(activity.onScannedImportPayload("https://example.com/qr"))
        ShadowLooper.idleMainLooper()
        assertEquals("", preview.text.toString())
        assertEquals(listOf(IMPORT_SCAN_UNRECOGNIZED), snackbarTexts(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importStartBtn).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility)
    }

    @Test
    fun `an over cap scan uses the paste size message`() {
        val activity = launch()
        val preview = activity.findViewById<TextView>(R.id.importPayloadTextView)
        preview.text = "stale"
        val over = "z".repeat(ImportLimits.BACKUP_MAX_BYTES + 1)
        assertTrue(activity.onScannedImportPayload(over))
        ShadowLooper.idleMainLooper()
        assertEquals("", preview.text.toString())
        assertEquals(listOf(importTooLargeMessage()), snackbarTexts(activity))
    }

    @Test
    fun `the scan sheet follows Disable Screenshots and hides paste`() {
        prefs.displaySecure = false
        val activity = launch()
        assertFalse(activity.window.isScreenshotBlocked())
        val off = showScan(activity)
        val offWindow = off.dialog?.window
        assertNotNull(offWindow)
        assertTrue(offWindow !== activity.window)
        assertFalse(offWindow!!.isScreenshotBlocked())
        assertFalse(activity.window.isScreenshotBlocked())
        assertPasteHidden(off)
        off.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.window.isScreenshotBlocked())

        prefs.displaySecure = true
        controller!!.pause().resume()
        assertTrue(activity.window.isScreenshotBlocked())
        val on = showScan(activity)
        val onWindow = on.dialog?.window
        assertNotNull(onWindow)
        assertTrue(onWindow !== activity.window)
        assertTrue(onWindow!!.isScreenshotBlocked())
        assertTrue(activity.window.isScreenshotBlocked())
        on.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        assertTrue(activity.window.isScreenshotBlocked())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp")
    fun `choose file scan and paste fit on a narrow screen`() {
        val activity = launch()
        val row = activity.findViewById<View>(R.id.importPayloadActions)
        val choose = activity.findViewById<TextView>(R.id.importChoosePayloadBtn)
        val scan = activity.findViewById<TextView>(R.id.importScanPayloadBtn)
        val paste = activity.findViewById<TextView>(R.id.importPastePayloadBtn)
        assertTrue(row.width > 0)
        assertTrue(choose.width > 0)
        assertTrue(scan.width > 0)
        assertTrue(paste.width > 0)
        assertTrue(choose.right <= scan.left)
        assertTrue(scan.right <= paste.left)
        assertTrue(paste.right <= row.width)
        assertEquals(0, choose.layout.getEllipsisCount(0))
        assertEquals(0, scan.layout.getEllipsisCount(0))
        assertEquals(0, paste.layout.getEllipsisCount(0))
    }

    private fun showScan(activity: ImportBackUpActivity): ImportPayloadScanBottomSheet {
        activity.showImportScanSheet()
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        val sheet = activity.supportFragmentManager
            .findFragmentByTag(ImportPayloadScanBottomSheet.TAG) as ImportPayloadScanBottomSheet
        sheet.childFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        assertTrue(sheet.isAdded)
        assertNotNull(sheet.dialog?.window)
        return sheet
    }

    private fun assertPasteHidden(sheet: ImportPayloadScanBottomSheet) {
        val paste = sheet.requireView().findViewById<View>(R.id.pastePubKey)
        assertNotNull(paste)
        assertEquals(View.GONE, paste.visibility)
        val instructions = sheet.requireView().findViewById<TextView>(R.id.scanInstructions)
        assertEquals(ImportPayloadScanBottomSheet.SCAN_INSTRUCTION, instructions.text.toString())
    }

    private fun snackbarTexts(activity: ImportBackUpActivity): List<String> {
        val found = ArrayList<String>()
        fun walk(view: View) {
            if (view.id == R.id.mainText && view is TextView && view.isShown) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotEmpty()) found.add(text)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
        return found
    }

    private fun launch(): ImportBackUpActivity {
        val created = Robolectric.buildActivity(ImportBackUpActivity::class.java).setup()
        controller = created
        return created.get()
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
