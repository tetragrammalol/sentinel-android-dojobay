package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #635 slice 1: the fresh-address walk, pure JVM - no Room, no Dojo,
 * no network parameters. The derivation itself is the composer's
 * proven read-only calls and is covered by device QA, not here.
 */
class FreshDestinationTest {

    @Test
    fun historyMarksACandidateUsed() {
        assertEquals(FreshnessReason.HISTORY, freshnessOf("c", setOf("c")) { false })
    }

    @Test
    fun aLabelMarksACandidateUsedEvenWithNoHistory() {
        assertEquals(FreshnessReason.LABEL, freshnessOf("c", emptySet()) { true })
    }

    @Test
    fun anUnknownCandidateIsFresh() {
        assertEquals(null, freshnessOf("c", emptySet()) { false })
    }

    @Test
    fun theWalkStopsAtTheFirstFreshIndexAndCarriesTheSkips() {
        val walk = firstFresh(
            ceiling = 5,
            usedAddresses = setOf("d0", "d2"),
            isLabelled = { it == "d1" },
            derive = { i -> "d$i" },
        )
        val found = walk as FreshWalk.Found
        assertEquals("d3", found.address)
        assertEquals(3, found.index)
        assertEquals(
            listOf(
                FreshSkip(0, FreshnessReason.HISTORY),
                FreshSkip(1, FreshnessReason.LABEL),
                FreshSkip(2, FreshnessReason.HISTORY),
            ),
            found.skips,
        )
    }

    @Test
    fun theWalkCarriesNoAddressWhenEveryCandidateIsUsed() {
        val walk = firstFresh(
            ceiling = 3,
            usedAddresses = setOf("d0", "d1", "d2"),
            isLabelled = { false },
            derive = { i -> "d$i" },
        )
        assertTrue(walk is FreshWalk.Exhausted)
        assertEquals(3, (walk as FreshWalk.Exhausted).skips.size)
    }

    @Test
    fun aNullDerivationFailsTheWalkClosed() {
        val walk = firstFresh(
            ceiling = 3,
            usedAddresses = emptySet(),
            isLabelled = { false },
            derive = { i -> if (i == 0) null else "d$i" },
        )
        assertTrue(walk is FreshWalk.Exhausted)
        assertTrue((walk as FreshWalk.Exhausted).skips.isEmpty())
    }

    // #646 leg 4: the walk starts at the stored counter, never below it.

    // The device-QA shape on wallet X: the counter slot itself is fresh,
    // so the walk lands exactly at the counter - never on a lower slot
    // the counter already handed out.
    @Test
    fun aFreshCounterSlotLandsExactlyAtTheCounter() {
        val walk = firstFresh(
            start = 521,
            ceiling = 3,
            usedAddresses = emptySet(),
            isLabelled = { false },
            derive = { i -> "d$i" },
        )
        val found = walk as FreshWalk.Found
        assertEquals("d521", found.address)
        assertEquals(521, found.index)
        assertTrue(found.skips.isEmpty())
    }

    // A used counter slot is skipped - with local history paying it - and
    // the walk proceeds upward, carrying every skip.
    @Test
    fun aUsedCounterSlotIsSkippedAndTheWalkProceedsUpward() {
        val walk = firstFresh(
            start = 521,
            ceiling = 3,
            usedAddresses = setOf("d521", "d522"),
            isLabelled = { false },
            derive = { i -> "d$i" },
        )
        val found = walk as FreshWalk.Found
        assertEquals("d523", found.address)
        assertEquals(523, found.index)
        assertEquals(
            listOf(
                FreshSkip(521, FreshnessReason.HISTORY),
                FreshSkip(522, FreshnessReason.HISTORY),
            ),
            found.skips,
        )
    }

    @Test
    fun aNegativeStartClampsToZero() {
        val walk = firstFresh(
            start = -7,
            ceiling = 2,
            usedAddresses = emptySet(),
            isLabelled = { false },
            derive = { i -> "d$i" },
        )
        val found = walk as FreshWalk.Found
        assertEquals("d0", found.address)
        assertEquals(0, found.index)
        assertTrue(found.skips.isEmpty())
    }

    @Test
    fun aStartNearIntMaxClampsItsEndInsteadOfOverflowing() {
        val walk = firstFresh(
            start = Int.MAX_VALUE - 1,
            ceiling = RECEIVE_WALK_CEILING,
            usedAddresses = emptySet(),
            isLabelled = { false },
            derive = { i -> "d$i" },
        )
        val found = walk as FreshWalk.Found
        assertEquals("d${Int.MAX_VALUE - 1}", found.address)
        assertEquals(Int.MAX_VALUE - 1, found.index)
        assertTrue(found.skips.isEmpty())
    }
}
