package com.samourai.sentinel.data.txfacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MempoolTxAdapterTest {

    private val txid = "EF".repeat(16)

    @Test
    fun `mempool dialect confirmed derives confirmations from tip`() {
        val body = """
            {"txid":"$txid","fee":300,"size":200,"vsize":141,
             "status":{"confirmed":true,"block_height":800000}}
        """.trimIndent()
        val f = MempoolTxAdapter.fromJson(body, tipHeight = 800012L)
        assertNotNull(f)
        f!!
        assertEquals(300L, f.fee)
        assertEquals(2L, f.feeRate) // 300 / 141 vsize
        assertEquals(13L, f.confirmations) // tip - height + 1
        assertEquals(800000L, f.blockHeight!!)
        assertTrue(f.confirmed)
    }

    @Test
    fun `mempool dialect unconfirmed is zero conf`() {
        val body =
            """{"txid":"$txid","fee":300,"size":200,"vsize":141,"status":{"confirmed":false}}"""
        val f = MempoolTxAdapter.fromJson(body, tipHeight = 800012L)
        assertNotNull(f)
        assertEquals(0L, f!!.confirmations)
        assertFalse(f.confirmed)
    }

    @Test
    fun `dojo esplora dialect uses fees vfeerate confirmations`() {
        // Shape from the #72 on-device census of dojo /tx?fees=1.
        val body = """
            {"txid":"$txid","fees":300,"size":200,"vsize":141,
             "vfeerate":2,"confirmations":13,"block":800000}
        """.trimIndent()
        val f = MempoolTxAdapter.fromJson(body, tipHeight = null)
        assertNotNull(f)
        f!!
        assertEquals(300L, f.fee)
        assertEquals(2L, f.feeRate)
        assertEquals(13L, f.confirmations)
        assertEquals(800000L, f.blockHeight!!)
        assertTrue(f.confirmed)
    }

    @Test
    fun `incompatible bodies map to null`() {
        assertNull(MempoolTxAdapter.fromJson("""{"foo":"bar"}""", null))
        assertNull(MempoolTxAdapter.fromJson("not json", null))
        assertNull(MempoolTxAdapter.fromJson("""{"txid":"$txid"}""", null))
    }
}
