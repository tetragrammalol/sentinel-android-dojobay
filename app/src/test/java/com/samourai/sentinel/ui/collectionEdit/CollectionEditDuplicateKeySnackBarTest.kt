package com.samourai.sentinel.ui.collectionEdit

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.snackbar.Snackbar
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowLooper

/**
 * #618: the duplicate-key refusal Snackbar used to dock at the window
 * bottom, underneath the ADD NEW ExtendedFloatingActionButton, so the
 * "Public key already exists in this collection" message was easy to
 * miss. Both refusal paths (a duplicate handed through the Intent, and a
 * duplicate typed into the add sheet) share one helper that anchors the
 * bar to the ADD NEW button, so it rides above the button instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionEditDuplicateKeySnackBarTest {

    private lateinit var repository: CollectionRepository
    private var savedAdmitted = false

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        savedAdmitted = AccessFactory.getInstance(app).isSessionAdmitted
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        val prefs = PrefsUtil(app)
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        repository = CollectionRepository()
        PubKeyRequests.clear()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { repository }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ApiService() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        PubKeyRequests.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    @Test
    fun `an intent duplicate shows a snackbar anchored above the add new button`() {
        val id = storedCollection()
        val key = PubKeyModel(pubKey = "zpub-1", label = "Dup", type = AddressTypes.BIP84)
        val token = PubKeyRequests.put(key)
        val intent = Intent()
            .putExtra("collection", id)
            .putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token)
        val activity = Robolectric.buildActivity(CollectionEditActivity::class.java, intent).setup().get()
        ShadowLooper.idleMainLooper()

        val bar = visibleFloatingSnackBar(activity)
        assertNotNull(bar)
        assertEquals(R.id.addNewPubFab, bar!!.anchorView?.id)
        assertEquals(
            "Public key already exists in this collection",
            bar.view.findViewById<TextView>(R.id.mainText)?.text?.toString()
        )
        // #243: the refusal keeps the existing keys on screen.
        val keys = ViewModelProvider(activity)[CollectionEditViewModel::class.java]
            .getPubKeys().value!!.map { it.pubKey }
        assertEquals(listOf("zpub-1", "zpub-2"), keys)
    }

    @Test
    fun `the sheet-path refusal shares the same anchored helper`() {
        val id = storedCollection()
        val activity = launch(id)
        val viewModel = ViewModelProvider(activity)[CollectionEditViewModel::class.java]

        // The sheet listener runs on the activity after the sheet
        // dismisses; drive the shared helper the way that branch does.
        activity.showDuplicateKeySnackBar()
        ShadowLooper.idleMainLooper()

        val bar = visibleFloatingSnackBar(activity)
        assertNotNull(bar)
        assertEquals(R.id.addNewPubFab, bar!!.anchorView?.id)
        assertEquals(
            "Public key already exists in this collection",
            bar.view.findViewById<TextView>(R.id.mainText)?.text?.toString()
        )
        // Refusing a duplicate never touches the key list.
        assertEquals(
            listOf("zpub-1", "zpub-2"),
            viewModel.getPubKeys().value!!.map { it.pubKey }
        )
    }

    private fun launch(id: String): CollectionEditActivity {
        val intent = Intent().putExtra("collection", id)
        val activity = Robolectric.buildActivity(CollectionEditActivity::class.java, intent).setup().get()
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun storedCollection(): String {
        val collection = PubKeyCollection(
            collectionLabel = "Cold storage",
            pubs = arrayListOf(
                PubKeyModel(pubKey = "zpub-1", label = "Savings", type = AddressTypes.BIP84),
                PubKeyModel(pubKey = "zpub-2", label = "Travel", type = AddressTypes.BIP84)
            )
        )
        repository.addNew(collection)
        return collection.id
    }

    private fun visibleFloatingSnackBar(activity: CollectionEditActivity): Snackbar? {
        val found = ArrayList<Snackbar>()
        fun walk(view: View) {
            val tagged = view.getTag(R.id.floating_snackbar) as? Snackbar
            if (tagged != null && tagged.isShown && tagged.view.isShown) {
                found.add(tagged)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
        return found.firstOrNull()
    }
}