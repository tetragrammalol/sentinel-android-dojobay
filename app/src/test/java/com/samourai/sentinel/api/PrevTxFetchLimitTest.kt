package com.samourai.sentinel.api

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.tools.sweep.SweepUtxoVerificationException
import com.samourai.sentinel.ui.tools.sweep.fetchPrevTxHexViaApi
import com.samourai.sentinel.ui.tools.sweep.verifySweepUtxos
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.ImportTooLargeException
import com.samourai.wallet.api.backend.beans.UnspentOutput
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.koin.java.KoinJavaComponent
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #503. [ApiService.getTxHex] is the previous-transaction read for both
 * sweep ([fetchPrevTxHexViaApi]) and compose (`TransactionComposer.verifyPrevTx`).
 * The ceiling has to be on that call: both callers only see the body
 * after [com.samourai.sentinel.api.okHttp.await] has already buffered it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrevTxFetchLimitTest {

    private lateinit var stall: StallingChunkServer

    @Before
    fun setUp() {
        val limit = ImportLimits.PREV_TX_RESPONSE_MAX_BYTES
        stall = StallingChunkServer(limit.toLong() + 64 * 1024)
        stall.start()
        ensureRuntime("http://127.0.0.1:${stall.port}")
    }

    @After
    fun tearDown() {
        stall.close()
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    @Test(timeout = 20_000)
    fun getTxHexRejectsAStreamedOversizedEnvelope() {
        val api = KoinJavaComponent.get(ApiService::class.java)
        val started = System.nanoTime()
        try {
            runBlocking { api.getTxHex("ab".repeat(32)) }
            fail("oversized prev-tx body should be refused")
        } catch (e: ImportTooLargeException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(ImportLimits.PREV_TX_RESPONSE_MAX_BYTES.toLong(), e.limit)
            assertEquals("bytes", e.unit)
            assertTrue(
                "getTxHex returned in ${elapsedMs}ms; the read timeout is 90s",
                elapsedMs < 8_000,
            )
            assertTrue(stall.payloadSent.get() > ImportLimits.PREV_TX_RESPONSE_MAX_BYTES)
            assertFalse(stall.wroteTerminalChunk.get())
        }
    }

    @Test(timeout = 20_000)
    fun sweepRefusesTheOversizedFetchBeforeBuildSignOrBroadcast() {
        val api = KoinJavaComponent.get(ApiService::class.java)
        val utxo = UnspentOutput().apply {
            tx_hash = "ab".repeat(32)
            tx_output_n = 0
            value = 100_000L
            script = "51"
            confirmations = 1
        }
        var built = 0
        var signed = 0
        var broadcast = 0
        try {
            runBlocking {
                verifySweepUtxos(listOf(utxo), MainNetParams.get()) { txid ->
                    fetchPrevTxHexViaApi(api, txid)
                }
                built++
                signed++
                broadcast++
            }
            fail("expected a SweepUtxoVerificationException")
        } catch (expected: SweepUtxoVerificationException) {
            assertEquals(0, built)
            assertEquals(0, signed)
            assertEquals(0, broadcast)
            assertTrue(expected.message!!.contains("No prev tx data"))
        } catch (other: Exception) {
            fail("oversized fetch escaped as ${other.javaClass.name}: ${other.message}")
        }
    }

    private fun ensureRuntime(baseUrl: String) {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        prefs.testnet = false
        prefs.enableTor = false
        prefs.apiEndPoint = baseUrl
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { CollectionRepository() }
                    single { ApiService() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                }
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
