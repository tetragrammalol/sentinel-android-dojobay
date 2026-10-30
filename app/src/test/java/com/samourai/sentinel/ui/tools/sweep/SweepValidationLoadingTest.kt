package com.samourai.sentinel.ui.tools.sweep

import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.util.PrivKeyReader
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.junit.After
import org.junit.AfterClass
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.math.roundToInt

/**
 * #353. Pressing Next used to draw the validation spinner on top of the
 * Next label. The wait now uses the backup-import loading card, and the
 * label stays clear of it. The click goes through [SweepPrivKeyFragment]'s
 * own Next callback (validate), not a text match of the layout source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h800dp-mdpi")
class SweepValidationLoadingTest {

    @Before
    fun setUp() {
        ensureKoin()
    }

    @After
    fun drainMainThread() {
        // validate() and SentinelState both post main-thread work. Finish it
        // before Koin is torn down.
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun nextShowsTheLoadingCardWithoutCoveringTheLabel_andAnInvalidKeyHidesIt() {
        // Recognized on mainnet and testnet, so the suite's network pref
        // cannot bounce the click into the invalid-key branch.
        val key = "11".repeat(32)
        assertNotNull(PrivKeyReader(key, MainNetParams.get()).format)
        assertNotNull(PrivKeyReader(key, TestNet3Params.get()).format)

        val (sheet, page) = showKeyEntry()
        val root = page.requireView()
        val button = root.findViewById<Button>(R.id.sweepStartBtn)
        val card = root.findViewById<CardView>(R.id.sweepLoadingCard)
        val progress = root.findViewById<CircularProgressIndicator>(R.id.sweepProgress)

        assertEquals("Next", button.text.toString())
        assertEquals(View.GONE, card.visibility)
        assertEquals(View.GONE, progress.visibility)
        assertCardMatchesBackupImport(root, card, progress)

        layout(root)
        val buttonAtRest = boundsIn(root, button)

        button.alpha = 1f
        val input = root.findViewById<TextInputLayout>(R.id.textPrivateKey)
        input.editText!!.setText(key)
        button.performClick()

        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(View.VISIBLE, progress.visibility)
        assertTrue(card.isShown)
        assertTrue(progress.isShown)
        assertEquals("Next", button.text.toString())
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(1f, button.alpha)

        layout(root)
        val buttonWhileLoading = boundsIn(root, button)
        val cardBounds = boundsIn(root, card)
        val progressBounds = boundsIn(root, progress)

        assertEquals(buttonAtRest, buttonWhileLoading)
        assertFalse("loading card overlaps the Next button", Rect.intersects(cardBounds, buttonWhileLoading))
        assertFalse("progress indicator overlaps the Next button", Rect.intersects(progressBounds, buttonWhileLoading))
        assertTrue(
            "loading card should sit above the Next label",
            cardBounds.bottom <= buttonWhileLoading.top
        )
        assertTrue(cardBounds.contains(progressBounds))
        assertEquals(dp(root, 100f).roundToInt(), card.width)
        assertEquals(dp(root, 100f).roundToInt(), card.height)
        assertEquals(dp(root, 30f).roundToInt(), progress.indicatorSize)
        assertEquals(dp(root, 250f), card.radius, 1f)
        assertEquals(ContextCompat.getColor(root.context, R.color.green_ui_2), card.cardBackgroundColor.defaultColor)
        assertEquals(ContextCompat.getColor(root.context, R.color.white), progress.indicatorColor[0])
        // The sheet's own view is what validate() searches. Both ids have to
        // resolve from there, or a later page would hide the wait.
        assertEquals(View.VISIBLE, sheet.requireView().findViewById<View>(R.id.sweepLoadingCard).visibility)
        assertEquals(View.VISIBLE, sheet.requireView().findViewById<View>(R.id.sweepProgress).visibility)

        input.editText!!.setText("not-a-private-key")
        button.performClick()

        assertEquals(View.GONE, card.visibility)
        assertEquals(View.GONE, progress.visibility)
        assertFalse(card.isShown)
        assertFalse(progress.isShown)
        assertEquals("Next", button.text.toString())
        assertEquals("Invalid private key", ShadowToast.getTextOfLatestToast())
    }

    private fun showKeyEntry(): Pair<SweepPrivKeyFragment, SweepKeyEntryFragment> {
        val activity = Robolectric.buildActivity(SweepLoadingHostActivity::class.java).setup().get()
        val sheet = SweepPrivKeyFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, sheet, "sweep")
            .commitNow()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val page = sheet.childFragmentManager.findFragmentByTag("f0") as? SweepKeyEntryFragment
            ?: error("sweep key entry was not created: ${sheet.childFragmentManager.fragments.map { it.javaClass.simpleName }}")
        // Touch the network once the page exists so the posted observer is
        // drained here, not between the show and hide asserts.
        SentinelState.getNetworkParam()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return sheet to page
    }

    private fun assertCardMatchesBackupImport(
        sweepRoot: View,
        sweepCard: CardView,
        sweepProgress: CircularProgressIndicator
    ) {
        val loading = LayoutInflater.from(sweepRoot.context).inflate(R.layout.layout_loading_bottom, null)
        val referenceProgress = loading.findViewById<CircularProgressIndicator>(R.id.broadcastProgress)
        val referenceCard = referenceProgress.parent.parent as CardView
        assertEquals(referenceCard.layoutParams.width, sweepCard.layoutParams.width)
        assertEquals(referenceCard.layoutParams.height, sweepCard.layoutParams.height)
        assertEquals(referenceCard.radius, sweepCard.radius, 0.5f)
        assertEquals(referenceCard.cardBackgroundColor.defaultColor, sweepCard.cardBackgroundColor.defaultColor)
        assertEquals(referenceProgress.indicatorSize, sweepProgress.indicatorSize)
        assertEquals(referenceProgress.indicatorColor[0], sweepProgress.indicatorColor[0])
    }

    private fun layout(root: View) {
        val width = root.resources.displayMetrics.widthPixels
        val height = root.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun boundsIn(ancestor: View, view: View): Rect {
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        return Rect(x, y, x + view.width, y + view.height)
    }

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density

    companion object {
        @Volatile
        private var startedKoin = false

        private fun ensureKoin() {
            if (GlobalContext.getOrNull() != null) return
            val app = ApplicationProvider.getApplicationContext<Application>()
            val prefs = PrefsUtil(app)
            prefs.testnet = false
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { prefs }
                        single { CollectionRepository() }
                    }
                )
            }
            startedKoin = true
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin && GlobalContext.getOrNull() != null) {
                stopKoin()
                startedKoin = false
            }
        }
    }
}

/** Theme the sweep page the way Tools does, without booting SentinelApplication. */
class SweepLoadingHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppThemeV3)
        super.onCreate(savedInstanceState)
    }
}
