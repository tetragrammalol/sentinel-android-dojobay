package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Xpub
import com.samourai.sentinel.data.prevOut
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Own-side mixes from Dojo partials. Amounts are the device Tx0 coins
 * (bb8bea0f 20x2_500_605, bc822e9a 20x250_605) or that headroom applied
 * to the testnet coin. Paths are relative (M/0/N); accounts come from
 * the xpub the way sync builds accountOfXpub.
 */
class WhirlpoolOwnSideAmountTest {

    private val cfg = WhirlpoolConfig.DEFAULT
    private val collectionId = "a060d45a-c03f-481b-9e8e-a000caaf0cf1"

    /** Premix account vpub echoed on the device Tx0 goldens. */
    private val premixVpub =
        "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe"

    /**
     * The goldens have no postmix vpub. Step the fixture premix key's
     * child index from the premix account to the postmix account; sync
     * resolves the account from that child, not from the path.
     */
    private val postmixVpub: String = run {
        val premix = XPUB(premixVpub)
        premix.decode()
        val child = premix.child + (cfg.postmixAccount - cfg.premixAccount).toInt()
        XPUB.makeXPUB(
            premix.version, premix.depth, premix.fingerprint, child,
            premix.chain, premix.pubkey,
        )
    }

    // bb8bea0f premix coin, and the 0.025 output it sits 605 above.
    private val premixMainnet = 2_500_605L
    private val postmixMainnet = 2_500_000L

    // bc822e9a premix coin. Same 605 sat headroom; the postmix output
    // is not a catalogue denomination.
    private val premixTestnet = 250_605L
    private val postmixTestnet = premixTestnet - (premixMainnet - postmixMainnet)

    private val accounts = accountOfXpub(listOf(premixVpub, postmixVpub))

    /**
     * TransactionsRepository: account = xpub.child + 2^31, stored under
     * every serialization so Dojo's echoed `m` still resolves.
     */
    private fun accountOfXpub(pubs: List<String>): Map<String, Long> = buildMap {
        for (pub in pubs) {
            val x = XPUB(pub)
            x.decode()
            val account = x.child + 2_147_483_648L
            listOf(
                XPUB.MAGIC_XPUB, XPUB.MAGIC_TPUB, XPUB.MAGIC_YPUB,
                XPUB.MAGIC_UPUB, XPUB.MAGIC_ZPUB, XPUB.MAGIC_VPUB,
            ).forEach { magic ->
                put(
                    XPUB.makeXPUB(
                        magic, x.depth, x.fingerprint, x.child, x.chain, x.pubkey,
                    ),
                    account,
                )
            }
        }
    }

    private fun partial(txid: String, inputs: List<Inputs>, outputs: List<Out>) = Tx(
        hash = "$txid-$collectionId",
        time = 0L,
        version = 1,
        locktime = 0,
        result = null,
        inputs = inputs,
        out = outputs,
        block_height = null,
    )

    private fun spend(
        value: Long,
        vpub: String,
        path: String,
        addr: String? = null,
        prevTxid: String = "e".repeat(64),
        prevVout: Int = 0,
    ) = Inputs(
        vin = 0,
        sequence = 4294967295L,
        prev_out = prevOut(
            addr = addr,
            txid = prevTxid,
            value = value,
            vout = prevVout,
            xpub = Xpub(m = vpub, path = path),
        ),
    )

    private fun receive(value: Long, vpub: String, path: String, n: Int = 0) = Out(
        n = n,
        value = value,
        addr = null,
        xpub = Xpub(m = vpub, path = path),
    )

    private class FakeSink : WhirlpoolAutoWriter.Sink {
        val writes = mutableListOf<Triple<String, String, String>>()
        override suspend fun findTxLabel(txid: String): ExistingLabel? = null
        override suspend fun writeAutoTxLabel(
            txid: String, label: String, origin: String,
        ) {
            writes += Triple(txid, label, origin)
        }
    }

    private fun mergedView(vararg partials: Tx): WhirlpoolTxView {
        val merged = WhirlpoolBackfill.mergePartialTxs(partials.toList(), collectionId)
        assertEquals(1, merged.size)
        return WhirlpoolTxAdapter.toView(merged[0], collectionId, accounts)
    }

    private fun backfill(vararg partials: Tx): Pair<WhirlpoolBackfill.Result, FakeSink> =
        runBlocking {
            val sink = FakeSink()
            val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
                .run(partials.toList(), collectionId, accounts)
            result to sink
        }

    @Test
    fun relativePathsDoNotResolveAndXpubsDo() {
        assertNull(WhirlpoolDetector.accountFromPath("M/0/106"))
        assertNull(WhirlpoolDetector.accountFromPath("M/0/76"))
        assertEquals(cfg.premixAccount, accounts[premixVpub])
        assertEquals(cfg.postmixAccount, accounts[postmixVpub])
        assertTrue(postmixTestnet !in cfg.denominationsSats)
    }

    /** bb8bea0f coin 2_500_605 spent into a 2_500_000 postmix output. */
    @Test
    fun premixSpend2_500_605PostmixReceive2_500_000IsFirstMix() {
        val txid = "1".repeat(64)
        val rows = arrayOf(
            partial(
                txid,
                inputs = listOf(
                    spend(
                        premixMainnet, premixVpub, "M/0/106",
                        addr = "tb1qp2v6wy4954c78kcqva95zl5v0ysslstnh7a3a2",
                        prevTxid = "bb8bea0f3ad694bf6ec7a6548186d92c15314274bb75a3c1cd04eea38f89c261",
                        prevVout = 2,
                    ),
                ),
                outputs = emptyList(),
            ),
            partial(
                txid,
                inputs = emptyList(),
                outputs = listOf(receive(postmixMainnet, postmixVpub, "M/0/0")),
            ),
        )
        val view = mergedView(*rows)
        assertEquals(cfg.premixAccount, view.inputs.single().account)
        assertEquals(cfg.postmixAccount, view.outputs.single().account)
        assertEquals(
            WhirlpoolClassification.FirstMix(postmixMainnet, WhirlpoolClassification.Tier.L1),
            WhirlpoolDetector.classify(view),
        )
        val (result, sink) = backfill(*rows)
        assertEquals(1, result.processed)
        assertEquals(1, result.written)
        assertEquals(0, result.handsOff)
        assertEquals(0, result.unclassified)
        assertEquals(
            listOf(Triple(txid, "Whirlpool Mix", "auto:whirlpool:v1")),
            sink.writes,
        )
    }

    /**
     * bc822e9a coin 250_605. D = 250_605 - (2_500_605 - 2_500_000) is
     * not in the pool catalogue, and the premix input is inside the
     * miner-fee window above it.
     */
    @Test
    fun testnetSizedPostmixOutsideCatalogueIsFirstMix() {
        val txid = "2".repeat(64)
        val rows = arrayOf(
            partial(
                txid,
                inputs = listOf(
                    spend(
                        premixTestnet, premixVpub, "M/0/76",
                        addr = "tb1qpv473ukfxmtucf4x7ptqs0q9efhlj3pc3tlur9",
                        prevTxid = "bc822e9a3af8f56da3710ca66987b7cb7e1b704b1ef63d02ac875ee8161ba7fd",
                        prevVout = 2,
                    ),
                ),
                outputs = emptyList(),
            ),
            partial(
                txid,
                inputs = emptyList(),
                outputs = listOf(receive(postmixTestnet, postmixVpub, "M/0/0")),
            ),
        )
        val view = mergedView(*rows)
        assertEquals(premixTestnet, view.inputs.single().value)
        assertEquals(postmixTestnet, view.outputs.single().value)
        assertTrue(postmixTestnet !in cfg.denominationsSats)
        assertEquals(
            WhirlpoolClassification.FirstMix(postmixTestnet, WhirlpoolClassification.Tier.L1),
            WhirlpoolDetector.classify(view),
        )
        val (result, sink) = backfill(*rows)
        assertEquals(1, result.written)
        assertEquals(0, result.handsOff)
        assertEquals(txid, sink.writes.single().first)
        assertEquals("Whirlpool Mix", sink.writes.single().second)
    }

    /** Premix input one sat above D + the premix miner-fee allowance. */
    @Test
    fun premixInputAboveMinerFeeAllowanceIsNotFirstMix() {
        val txid = "3".repeat(64)
        val over = postmixMainnet + cfg.premixMinerFeeAllowanceSats + 1
        val rows = arrayOf(
            partial(
                txid,
                inputs = listOf(spend(over, premixVpub, "M/0/106")),
                outputs = emptyList(),
            ),
            partial(
                txid,
                inputs = emptyList(),
                outputs = listOf(receive(postmixMainnet, postmixVpub, "M/0/0")),
            ),
        )
        assertEquals(
            WhirlpoolClassification.Unknown,
            WhirlpoolDetector.classify(mergedView(*rows)),
        )
        val (result, sink) = backfill(*rows)
        assertEquals(1, result.unclassified)
        assertEquals(0, result.written)
        assertEquals(0, result.handsOff)
        assertTrue(sink.writes.isEmpty())
    }

    /** Equal postmix values at the bc822e9a coin size, not a catalogue denom. */
    @Test
    fun equalPostmixAtNonCatalogueValueIsRemixHandsOff() {
        val txid = "4".repeat(64)
        assertTrue(premixTestnet !in cfg.denominationsSats)
        val rows = arrayOf(
            partial(
                txid,
                inputs = listOf(spend(premixTestnet, postmixVpub, "M/0/76")),
                outputs = emptyList(),
            ),
            partial(
                txid,
                inputs = emptyList(),
                outputs = listOf(receive(premixTestnet, postmixVpub, "M/0/0", n = 1)),
            ),
        )
        assertEquals(
            WhirlpoolClassification.Remix(premixTestnet, WhirlpoolClassification.Tier.L1),
            WhirlpoolDetector.classify(mergedView(*rows)),
        )
        val (result, sink) = backfill(*rows)
        assertEquals(1, result.processed)
        assertEquals(1, result.handsOff)
        assertEquals(0, result.written)
        assertEquals(0, result.unclassified)
        assertTrue(sink.writes.isEmpty())
    }

    /** Postmix in and out at the two golden premix coin sizes. Not a remix. */
    @Test
    fun unequalPostmixValuesAreNotRemix() {
        val txid = "5".repeat(64)
        val rows = arrayOf(
            partial(
                txid,
                inputs = listOf(spend(premixTestnet, postmixVpub, "M/0/76")),
                outputs = emptyList(),
            ),
            partial(
                txid,
                inputs = emptyList(),
                outputs = listOf(receive(premixMainnet, postmixVpub, "M/0/106")),
            ),
        )
        val cls = WhirlpoolDetector.classify(mergedView(*rows))
        assertTrue(cls !is WhirlpoolClassification.Remix)
        assertEquals(WhirlpoolClassification.Unknown, cls)
        val (result, sink) = backfill(*rows)
        assertEquals(1, result.unclassified)
        assertEquals(0, result.handsOff)
        assertEquals(0, result.written)
        assertTrue(sink.writes.isEmpty())
    }
}
