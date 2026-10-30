package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #301: the pure core of the single balance derivation. Every display
 * surface renders from balanceFigures(total, blockedAmounts); these legs
 * pin the shared arithmetic. The UtxoMetaUtil-backed figuresFor helpers
 * are not JVM-testable - they touch the Koin-injected store - and ride
 * the device leg.
 *
 * Semantics kept identical to the five in-tree derivations replaced:
 * frozen amounts are the sums captured at mark time, and nothing is
 * clamped - a stale mark on a spent coin drives spendable negative
 * (stale-mark hygiene is #287's ground, not this fix's).
 */
class SpendableBalanceTest {

    @Test
    fun noMarksSpendableEqualsTotal() {
        val f = balanceFigures(150_000L, emptyList())
        assertEquals(150_000L, f.total)
        assertEquals(150_000L, f.spendable)
        assertEquals(0L, f.frozen)
        assertFalse(f.hasFrozen)
    }

    @Test
    fun marksSubtractFromTotal() {
        val f = balanceFigures(150_000L, listOf(50_000L))
        assertEquals(100_000L, f.spendable)
        assertEquals(50_000L, f.frozen)
        assertTrue(f.hasFrozen)
    }

    @Test
    fun multipleMarksSumBeforeSubtracting() {
        val f = balanceFigures(1_000_000L, listOf(100_000L, 250_000L, 50_000L))
        assertEquals(400_000L, f.frozen)
        assertEquals(600_000L, f.spendable)
    }

    @Test
    fun zeroValueMarkFreezesNothing() {
        val f = balanceFigures(150_000L, listOf(0L))
        assertEquals(150_000L, f.spendable)
        assertFalse(f.hasFrozen)
    }

    @Test
    fun wholeWalletFrozenGoesToZero() {
        val f = balanceFigures(150_000L, listOf(150_000L))
        assertEquals(0L, f.spendable)
        assertTrue(f.hasFrozen)
    }

    @Test
    fun staleMarkPastTotalStaysNegativeNotClamped() {
        val f = balanceFigures(100_000L, listOf(130_000L))
        assertEquals(-30_000L, f.spendable)
        assertTrue(f.hasFrozen)
    }

    @Test
    fun emptyWalletWithNoMarksIsZero() {
        val f = balanceFigures(0L, emptyList())
        assertEquals(0L, f.spendable)
        assertFalse(f.hasFrozen)
    }
}
