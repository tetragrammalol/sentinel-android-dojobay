package com.samourai.sentinel.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #444: drag-to-reorder persistence legs.
 *
 * move() must re-sequence the ArrayList under the same #89 monitor the
 * other mutators hold, and sync() must persist the new order - the
 * ArrayList order IS the payload order, so the restart reads back in
 * the dragged sequence. Out-of-range and no-op moves must never write a
 * changed order.
 *
 * Real store under Robolectric (the CollectionsRestoreAfterImportTest
 * idiom): the readback legs decode the actual payload file, so a move
 * that corrupted the list shape would fail here, not on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionRepositoryMoveTest {

    private lateinit var db: SentinelRoomDb
    private var startedKoin = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(koinModule())
            }
            startedKoin = true
        }
    }

    @After
    fun tearDown() {
        db.close()
        if (startedKoin) {
            stopKoin()
        }
    }

    private fun koinModule(): Module = module {
        single { PrefsUtil(ApplicationProvider.getApplicationContext()) }
        single { SentinelCollectionStore() }
        single<TxDao> { db.txDao() }
        single<UtxoDao> { db.utxoDao() }
        single { CollectionRepository() }
    }

    private fun collection(label: String): PubKeyCollection = PubKeyCollection(
        collectionLabel = label,
        pubs = arrayListOf(
            PubKeyModel(
                pubKey = "tpub-$label",
                label = "$label key",
                type = com.samourai.sentinel.data.AddressTypes.BIP84
            )
        )
    )


    private fun payloadFile() =
        (org.koin.java.KoinJavaComponent.inject(SentinelCollectionStore::class.java).value
            as SentinelCollectionStore).getCollectionStore().file

    /** Labels decoded straight from the payload file, in file order. */
    private fun decodedLabels(): List<String> {
        // sync() is fire-and-forget: the file legitimately does not exist
        // until the IO write lands. Poll legs treat that as "not yet".
        val file = payloadFile()
        if (!file.exists()) return emptyList()
        val parsed = com.samourai.sentinel.helpers.fromJSON<ArrayList<PubKeyCollection>>(
            file.readText(),
            object : com.google.gson.reflect.TypeToken<ArrayList<PubKeyCollection?>>() {}.type
        ) ?: return emptyList()
        return parsed.map { it.collectionLabel }
    }

    /** Waits until the payload file's labels equal [labels], in order. */
    private suspend fun waitForOrder(vararg labels: String) {
        withTimeout(10_000) {
            while (decodedLabels() != labels.toList()) delay(25)
        }
    }

    @Test
    fun movePersistsDraggedOrderToPayloadAndRestart() = runBlocking {
        val repository = org.koin.java.KoinJavaComponent.inject(
            CollectionRepository::class.java
        ).value as CollectionRepository
        repository.reset()
        repository.addNew(collection("A"))
        repository.addNew(collection("B"))
        repository.addNew(collection("C"))
        waitForOrder("A", "B", "C")

        // The drag: A pulled below C (two crossings).
        repository.move(0, 2)

        // The payload - and a fresh repository read (the restart) - both
        // carry the dragged order.
        waitForOrder("B", "C", "A")
        val fresh = CollectionRepository()
        fresh.read()
        assertEquals(
            listOf("B", "C", "A"),
            fresh.collectionsSnapshot().map { it.collectionLabel }
        )
    }

    @Test
    fun outOfRangeAndNoOpMovesNeverReorderOrPersist() = runBlocking {
        val repository = org.koin.java.KoinJavaComponent.inject(
            CollectionRepository::class.java
        ).value as CollectionRepository
        repository.reset()
        repository.addNew(collection("A"))
        repository.addNew(collection("B"))
        waitForOrder("A", "B")

        // (from, to) pairs that must all be silent no-ops: out of range
        // on either side, and the identity move.
        listOf(0 to 2, 2 to 0, -1 to 1, 1 to -1, 0 to 0, 1 to 1).forEach { (from, to) ->
            repository.move(from, to)
        }

        // Order unchanged in memory AND on disk (the settle is the
        // sync from the last legal no-op; give it a beat).
        delay(250)
        assertEquals(listOf("A", "B"), repository.collectionsSnapshot().map { it.collectionLabel })
        assertEquals(listOf("A", "B"), decodedLabels())
    }

    @Test
    fun repeatedMovesComposeToAnyPermutation() = runBlocking {
        val repository = org.koin.java.KoinJavaComponent.inject(
            CollectionRepository::class.java
        ).value as CollectionRepository
        repository.reset()
        repository.addNew(collection("A"))
        repository.addNew(collection("B"))
        repository.addNew(collection("C"))
        repository.addNew(collection("D"))
        waitForOrder("A", "B", "C", "D")

        // Drag D to the top: one long drag's crossings, persisted per
        // crossing (3, 2, 1) - the composition every ItemTouchHelper
        // sequence reduces to.
        repository.move(3, 0)
        assertEquals(
            listOf("D", "A", "B", "C"),
            repository.collectionsSnapshot().map { it.collectionLabel }
        )
        // The exact inverse: D from top back to bottom - move(0, 3).
        repository.move(0, 3)
        assertEquals(
            listOf("A", "B", "C", "D"),
            repository.collectionsSnapshot().map { it.collectionLabel }
        )
        waitForOrder("A", "B", "C", "D")
    }
}