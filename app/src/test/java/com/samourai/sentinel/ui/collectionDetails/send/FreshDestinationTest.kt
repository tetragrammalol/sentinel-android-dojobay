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
}
