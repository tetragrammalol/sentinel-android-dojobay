package com.samourai.sentinel.ui.home

import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
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
import org.koin.java.KoinJavaComponent.inject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The underlying-change flag: reset() (clear-existing import) must force
 * the next sync round to bypass the signature diff once - ids/keys can
 * reappear identical after a restore while the tx/utxo rows are gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnderlyingChangeFlagTest {

    private var startedKoin = false

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(
                    module {
                        single { PrefsUtil(ApplicationProvider.getApplicationContext()) }
                        single { CollectionRepository() }
                    }
                )
            }
            startedKoin = true
        }
    }

    @After
    fun tearDown() {
        if (startedKoin) {
            stopKoin()
            startedKoin = false
        }
    }

    @Test
    fun resetMarksAndTheFirstTakeConsumesIt() {
        val repository = inject(CollectionRepository::class.java).value
        assertFalse(repository.takeUnderlyingChange())
        repository.reset()
        assertTrue(repository.takeUnderlyingChange())
        // Consumed once - a second take is false, so only ONE full round.
        assertFalse(repository.takeUnderlyingChange())
    }

    @Test
    fun markUnderlyingChangeWithoutResetAlsoForcesOneRound() {
        val repository = inject(CollectionRepository::class.java).value
        repository.markUnderlyingChange()
        assertTrue(repository.takeUnderlyingChange())
        assertFalse(repository.takeUnderlyingChange())
    }
}
