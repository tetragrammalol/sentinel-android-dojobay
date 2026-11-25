package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #646: the shared path renderer, pure JVM. The XPUB leg of
 * receivePathFor (decode -> account child) is compile-gated and
 * device QA'd - no fixture key rides repo artifacts (house rule);
 * the notation is what these tests pin.
 */
class DerivationPathTest {

    @Test
    fun mainnetBip84FirstIndex() {
        assertEquals("m/84'/0'/0'/0/0", receivePathText(purpose = 84, coin = 0, account = 0L, index = 0))
    }

    @Test
    fun mainnetBip84LaterIndex() {
        assertEquals("m/84'/0'/0'/0/24", receivePathText(84, 0, 0L, 24))
    }

    @Test
    fun testnetUsesCoinTypeOne() {
        assertEquals("m/84'/1'/0'/0/7", receivePathText(84, 1, 0L, 7))
    }

    @Test
    fun bip49AndNonzeroAccount() {
        assertEquals("m/49'/0'/2'/0/5", receivePathText(49, 0, 2L, 5))
    }

    @Test
    fun bip44KeepsHardenedMarksOnTheFirstThreeLevels() {
        assertEquals("m/44'/0'/0'/0/1", receivePathText(44, 0, 0L, 1))
    }

    @Test
    fun everyLevelIsHardenedExceptChainAndIndex() {
        val segments = receivePathText(84, 0, 3L, 9).removePrefix("m/").split("/")
        assertEquals(5, segments.size)
        assertEquals(listOf("84'", "0'", "3'", "0", "9"), segments)
    }
}
