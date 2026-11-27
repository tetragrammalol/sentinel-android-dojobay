package com.samourai.sentinel.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #643: formatForDisplay is the one door the send surfaces read their
 * amounts through. Street mode masks regardless of the preference, no
 * street mode follows the preference, and a missing or unknown
 * preference falls back to BTC.
 */
class BalanceDisplayFormatterTest {

    @Before
    fun pinUsNumberFormat() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun streetModeMasksEveryPreference() {
        for (pref in listOf("BTC", "SATS", "MASKED", "garbage", null)) {
            assertEquals(
                BalanceDisplayFormatter.MASKED_TEXT,
                BalanceDisplayFormatter.formatForDisplay(123_456_789L, pref, true)
            )
        }
    }

    @Test
    fun noStreetModeFollowsThePreference() {
        assertEquals(
            "1.23456789 BTC",
            BalanceDisplayFormatter.formatForDisplay(123_456_789L, "BTC", false)
        )
        assertEquals(
            "123,456,789 sats",
            BalanceDisplayFormatter.formatForDisplay(123_456_789L, "SATS", false)
        )
        assertEquals(
            BalanceDisplayFormatter.MASKED_TEXT,
            BalanceDisplayFormatter.formatForDisplay(123_456_789L, "MASKED", false)
        )
    }

    @Test
    fun missingOrUnknownPreferenceFallsBackToBtc() {
        assertEquals(
            "1.23456789 BTC",
            BalanceDisplayFormatter.formatForDisplay(123_456_789L, null, false)
        )
        assertEquals(
            "1.23456789 BTC",
            BalanceDisplayFormatter.formatForDisplay(123_456_789L, "garbage", false)
        )
    }
}
