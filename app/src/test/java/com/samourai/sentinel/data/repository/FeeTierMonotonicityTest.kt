package com.samourai.sentinel.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #348. The Dojo fee oracle on the regression rig serves
 * {"2":1,"4":588236,"6":21,"12":4,"24":588077}. parse() stored the
 * tiers positionally (high=1, normal=21, low=588077), setUpFee()
 * derived the slider ceiling from High alone and the starting value
 * from Normal alone, and the send page died at first layout in
 * BaseSlider.validateValues: value 200001 above valueTo 10000. Tiers
 * are fee data only when the fee falls as the block delay grows:
 * fee("2") >= fee("6") >= fee("24").
 */
class FeeTierMonotonicityTest {

    @Test
    fun `the live broken-oracle payload is rejected`() {
        assertFalse(feeTiersAreMonotonic(fee2 = 1L, fee6 = 21L, fee24 = 588077L))
    }

    @Test
    fun `falling tiers are fee data`() {
        assertTrue(feeTiersAreMonotonic(fee2 = 100L, fee6 = 50L, fee24 = 10L))
    }

    @Test
    fun `collapsed tiers are still monotonic`() {
        assertTrue(feeTiersAreMonotonic(fee2 = 5L, fee6 = 5L, fee24 = 5L))
        assertTrue(feeTiersAreMonotonic(fee2 = 8L, fee6 = 8L, fee24 = 2L))
    }

    @Test
    fun `absent keys impose no constraint`() {
        assertTrue(feeTiersAreMonotonic(fee2 = 7L, fee6 = null, fee24 = null))
        assertTrue(feeTiersAreMonotonic(fee2 = null, fee6 = 3L, fee24 = null))
    }

    @Test
    fun `the two-and-twenty-four pair needs the outer comparison`() {
        // setFees() stores a two-tier payload positionally: fee("2") as
        // High, fee("24") as Low. 1/9 violates no adjacent pair; only
        // the direct 2-vs-24 comparison catches it.
        assertFalse(feeTiersAreMonotonic(fee2 = 1L, fee6 = null, fee24 = 9L))
        assertTrue(feeTiersAreMonotonic(fee2 = 9L, fee6 = null, fee24 = 1L))
    }

    @Test
    fun `an inverted middle tier is rejected`() {
        assertFalse(feeTiersAreMonotonic(fee2 = 50L, fee6 = 10L, fee24 = 20L))
    }

    @Test
    fun `guard sits at ingestion and both fee selectors clamp`() {
        val fee = source(
            "src/main/java/com/samourai/sentinel/data/repository/FeeRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/FeeRepository.kt",
        )
        val guard = fee.indexOf("if (!feeTiersAreMonotonic(fee2, fee6, fee24))")
        val reject = fee.indexOf("rejected non-monotonic fee tiers")
        val commit = fee.indexOf("this.fees.postValue(feeParsed)")
        assert(guard >= 0)
        assert(reject > guard)
        assert(commit > guard)

        val send = source(
            "src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt",
        )
        val sweep = source(
            "src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt",
        )
        for (src in listOf(send, sweep)) {
            assert(src.contains("coerceAtLeast(2F)"))
            assert(src.contains("coerceIn(1F, sliderCeiling)"))
            assertFalse(src.contains("valueTo = if (valueTo <= 0)"))
        }
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
