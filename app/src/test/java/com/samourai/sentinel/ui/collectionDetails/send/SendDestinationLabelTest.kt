package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.segwit.SegwitAddress
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.ECKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #644 slice 2: the label typed beside To is written at the Compose
 * tap, for a typed external destination only. A blank label writes
 * nothing - a compose is never a delete - and a fresh own-collection
 * walk destination must stay unmarked so it still counts as unused
 * when handed out later. The write is what the diagram then reads
 * back through addrLabels().
 *
 * The Own no-write case is not fixtured here: building a verified
 * Own needs the full fresh-destination walk, far past the two-line
 * guard it covers. Guard covered by reading, device leg on the PR.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendDestinationLabelTest {

    private lateinit var db: SentinelRoomDb
    private lateinit var labels: LabelRepository

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .build()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { db }
                    single { db.txDao() }
                    single { db.utxoDao() }
                    single { db.utxoLabelDao() }
                    single { db.labelEntryDao() }
                    single { db.txEntropyDao() }
                    single { LabelRepository() }
                },
            )
        }
        labels = KoinJavaComponent.get(LabelRepository::class.java)
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    /**
     * #644: the destination is derived from a throwaway test key,
     * never a literal - no addresses in the public tree.
     */
    private fun destinationAddress(): String = SegwitAddress(
        ECKey.fromPrivate(ByteArray(32) { 0x24 }).pubKey,
        SentinelState.getNetworkParam()!!,
    ).bech32AsString

    /**
     * The write runs in viewModelScope on the main dispatcher and
     * suspends on the DAO's IO. Each round idles the looper so posted
     * continuations run, then gives the IO leg time to land; the read
     * itself goes through its own IO hop. Gives up loudly, no bare
     * sleep-only wait.
     */
    private fun drainMainUntil(attempts: Int, ready: suspend () -> Boolean) {
        repeat(attempts) {
            if (runBlocking { ready() }) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun composeTapWritesTheTypedDestinationLabelTrimmed() {
        val vm = SendViewModel()
        val addr = destinationAddress()
        vm.setDestinationAddress(addr)
        vm.setDestinationLabel("  Hardware wallet  ")
        drainMainUntil(attempts = 50) { labels.addrLabels().containsKey(addr) }
        assertEquals(
            "Hardware wallet",
            runBlocking { labels.addrLabels()[addr] },
        )
    }

    @Test
    fun blankLabelWritesNothingEvenForATypedDestination() {
        val vm = SendViewModel()
        vm.setDestinationAddress(destinationAddress())
        vm.setDestinationLabel("   ")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(runBlocking { labels.addrLabels().isEmpty() })
    }

    @Test
    fun labelWithoutATypedDestinationWritesNothing() {
        val vm = SendViewModel()
        vm.setDestinationLabel("Hardware wallet")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(runBlocking { labels.addrLabels().isEmpty() })
    }

    @Test
    fun relabelUpdatesTheSameAddressEntryNotANewOne() {
        val vm = SendViewModel()
        val addr = destinationAddress()
        vm.setDestinationAddress(addr)
        vm.setDestinationLabel("First")
        drainMainUntil(attempts = 50) { labels.addrLabels()[addr] == "First" }
        vm.setDestinationLabel("Second")
        drainMainUntil(attempts = 50) { labels.addrLabels()[addr] == "Second" }
        val map = runBlocking { labels.addrLabels() }
        assertEquals(setOf(addr), map.keys)
        assertEquals("Second", map[addr])
    }
}
