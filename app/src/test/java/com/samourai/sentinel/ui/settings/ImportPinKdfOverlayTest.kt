package com.samourai.sentinel.ui.settings

import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.KdfProgressOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

/**
 * #358: the real non-cancelable overlay, driven through
 * [protectImportedPin], plus the import screen dropping that window
 * when the activity is destroyed mid-derive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPinKdfOverlayTest {

    private var startedKoin = false

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() == null) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { PrefsUtil(app) }
                        single { AccessFactory.getInstance(null) }
                    }
                )
            }
            startedKoin = true
        }
        setSessionAdmitted(true)
    }

    @After
    fun tearDown() {
        setSessionAdmitted(false)
        if (startedKoin && GlobalContext.getOrNull() != null) {
            stopKoin()
            startedKoin = false
        }
    }

    @Test
    fun overlayStaysUpUntilTheDeriveReturnsAndIgnoresBack() = runBlocking {
        val overlay = shownHost()
        var dialog: Dialog? = null
        protectImportedPin(
            show = { overlay.show(it) },
            dismiss = { overlay.dismiss() },
            main = Dispatchers.Unconfined,
        ) {
            dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertTrue(dialog!!.isShowing)
            assertEquals(
                FRESH_IMPORT_PIN_LABEL,
                dialog!!.findViewById<TextView>(R.id.kdfProgressLabel).text.toString()
            )
            @Suppress("DEPRECATION")
            dialog!!.onBackPressed()
            assertTrue(dialog!!.isShowing)
        }
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun overlayIsDismissedWhenTheDeriveThrows() = runBlocking {
        val overlay = shownHost()
        try {
            protectImportedPin(
                show = { overlay.show(it) },
                dismiss = { overlay.dismiss() },
                main = Dispatchers.Unconfined,
            ) {
                assertTrue(ShadowDialog.getLatestDialog().isShowing)
                throw IllegalStateException("wrap failed")
            }
            fail("protect failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("wrap failed", e.message)
        }
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun overlayIsDismissedWhenTheDeriveIsCancelled() = runBlocking {
        val overlay = shownHost()
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            protectImportedPin(
                show = { overlay.show(it) },
                dismiss = { overlay.dismiss() },
                main = Dispatchers.Unconfined,
            ) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        job.cancelAndJoin()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun overlayCanBeShownAgainOnTheNextImport() = runBlocking {
        val overlay = shownHost()
        repeat(2) {
            var during = false
            protectImportedPin(
                show = { overlay.show(it) },
                dismiss = { overlay.dismiss() },
                main = Dispatchers.Unconfined,
            ) {
                during = ShadowDialog.getLatestDialog().isShowing
            }
            assertTrue(during)
            assertFalse(ShadowDialog.getLatestDialog().isShowing)
        }
    }

    @Test
    fun destroyingTheImportScreenDismissesAShowingOverlay() {
        val controller = Robolectric.buildActivity(ImportBackUpActivity::class.java)
        val activity = controller.create().start().resume().get()
        val overlay = kdfOverlay(activity)
        overlay.show(FRESH_IMPORT_PIN_LABEL)
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        controller.pause().stop().destroy()
        assertFalse(dialog.isShowing)
    }

    private fun shownHost(): KdfProgressOverlay {
        val controller = Robolectric.buildActivity(KdfHostActivity::class.java)
        return controller.create().start().resume().get().overlay
    }

    private fun kdfOverlay(activity: ImportBackUpActivity): KdfProgressOverlay {
        val getter = ImportBackUpActivity::class.java.getDeclaredMethod("getKdfOverlay")
        getter.isAccessible = true
        return getter.invoke(activity) as KdfProgressOverlay
    }

    private fun setSessionAdmitted(admitted: Boolean) {
        val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
        field.isAccessible = true
        field.setBoolean(null, admitted)
    }

    class KdfHostActivity : AppCompatActivity() {
        lateinit var overlay: KdfProgressOverlay

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppThemeV3)
            super.onCreate(savedInstanceState)
            overlay = KdfProgressOverlay(this)
        }
    }
}
