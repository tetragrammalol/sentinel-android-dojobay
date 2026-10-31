package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.FirstMix
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Remix
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Tier
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Tx0
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Unknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit matrix over synthetic views. Real-tx fixtures
 * land in /whirlpool/sample.jsonl via scripts/fetch_whirlpool_fixtures.sh
 * — the final test fails fast until then (fixture-first: red now,
 * green in the same PR).
 */
class WhirlpoolDetectorTest {

    private val cfg = WhirlpoolConfig.DEFAULT

    private fun io(value: Long, account: Long? = null, script: String? = null) =
        WhirlpoolTxView.IO(value, null, account, script)

    private fun view(ins: List<WhirlpoolTxView.IO>, outs: List<WhirlpoolTxView.IO>) =
        WhirlpoolTxView("aa".repeat(32), ins, outs)

    // --- TX0 ---

    @Test
    fun tx0AccountsAloneClassifyAtL1() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(2_499_999, cfg.premixAccount),   // uneven -> structure unconfirmed
                io(92_400_000, cfg.badbankAccount),
            )
        )
        assertEquals(Tx0(Tier.L1), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun tx0UniformPremixPlusOpReturnClassifiesAtL2() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(92_400_000, cfg.badbankAccount),
                io(0, script = "6a04a1b2c3d4"),
            )
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun tx0WithoutOpReturnWhenScriptsSuppliedStaysAtL1() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount, script = "0014ab"),
                io(2_500_000, cfg.premixAccount, script = "0014cd"),
                io(92_400_000, cfg.badbankAccount, script = "0014ef"),
            )
        )
        assertEquals(Tx0(Tier.L1), WhirlpoolDetector.classify(v, cfg))
    }

    /** 0.025 BTC pool. No badbank output. */
    @Test
    fun tx0Premix0025BtcWithoutBadbankClassifies() {
        val margin = cfg.premixMinerFeeAllowanceSats
        // Device shape: denomination + fee buffer, change unresolved.
        val buffered = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_605, cfg.premixAccount),
                io(2_500_605, cfg.premixAccount),
                io(94_000_000),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(buffered, cfg))

        // Inclusive edges of [denom, denom + premix miner-fee allowance].
        val atDenom = view(
            listOf(io(5_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(atDenom, cfg))

        val atMargin = view(
            listOf(io(6_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000 + margin, cfg.premixAccount),
                io(2_500_000 + margin, cfg.premixAccount),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(atMargin, cfg))
    }

    /** 0.25 BTC pool. No badbank output. */
    @Test
    fun tx0Premix025BtcWithoutBadbankClassifies() {
        val v = view(
            listOf(io(80_000_000, cfg.depositAccount)),
            listOf(
                io(25_000_605, cfg.premixAccount),
                io(25_000_605, cfg.premixAccount),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(v, cfg))
    }

    /** Same 0.25 window. Input account retagged from deposit to badbank. */
    @Test
    fun tx0BadbankInput025BtcWithoutBadbankClassifies() {
        val v = view(
            listOf(io(80_000_000, cfg.badbankAccount)),
            listOf(
                io(25_000_605, cfg.premixAccount),
                io(25_000_605, cfg.premixAccount),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun premixOutputsMixingBothPoolWindowsStayUnclassified() {
        val v = view(
            listOf(io(30_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_605, cfg.premixAccount),
                io(25_000_605, cfg.premixAccount),
            ),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun tx0WithBadbankOutputStillClassifies() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_605, cfg.premixAccount),
                io(2_500_605, cfg.premixAccount),
                io(94_000_000, cfg.badbankAccount),
            ),
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun depositInWithNonPremixOutputIsNotTx0() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.postmixAccount),
                io(97_000_000),
            ),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun depositToPremixOutsidePoolWindowIsNotTx0() {
        // Outside both [denom, denom + premix miner-fee allowance]
        // windows. The same uneven premix with a badbank output stays
        // a Tx0 (tx0AccountsAloneClassifyAtL1).
        val margin = cfg.premixMinerFeeAllowanceSats
        fun premix(value: Long) = view(
            listOf(io(value + 1_000, cfg.depositAccount)),
            listOf(io(value, cfg.premixAccount)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(premix(2_499_999), cfg))
        assertEquals(
            Unknown,
            WhirlpoolDetector.classify(premix(2_500_000 + margin + 1), cfg),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(premix(10_000_000), cfg))
        assertEquals(
            Unknown,
            WhirlpoolDetector.classify(premix(25_000_000 + margin + 1), cfg),
        )

        val mixed = view(
            listOf(io(6_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_499_999, cfg.premixAccount),
            ),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(mixed, cfg))
    }

    // --- Mixes ---

    @Test
    fun firstMixPremixInPostmixOutUniformClassifies() {
        val ins = (1..5).map { io(2_500_300, cfg.premixAccount) }
        val outs = (1..5).map { io(2_500_000, cfg.postmixAccount) }
        assertEquals(
            FirstMix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    @Test
    fun remixPostmixInPostmixOutUniformClassifies() {
        val ins = (1..5).map { io(2_500_200, cfg.postmixAccount) }
        val outs = (1..5).map { io(2_500_000, cfg.postmixAccount) }
        assertEquals(
            Remix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    /**
     * Inputs sit one sat above the premix miner-fee allowance, so the
     * own-side window does not match. The 5/5 path still classifies.
     */
    @Test
    fun fiveByFiveFirstMixAboveAllowanceStaysL2() {
        val allowance = cfg.premixMinerFeeAllowanceSats
        val ins = (1..5).map { io(2_500_000 + allowance + 1, cfg.premixAccount) }
        val outs = (1..5).map { io(2_500_000, cfg.postmixAccount) }
        assertEquals(
            FirstMix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    // --- Own-side mixes (Dojo partial: our input and our output) ---

    @Test
    fun ownSideFirstMix0025OneInOneOut() {
        val v = view(
            listOf(io(2_500_444, cfg.premixAccount)),
            listOf(io(2_500_000, cfg.postmixAccount)),
        )
        assertEquals(
            FirstMix(2_500_000L, Tier.L1),
            WhirlpoolDetector.classify(v, cfg)
        )
    }

    @Test
    fun ownSideFirstMix025OneInOneOut() {
        val v = view(
            listOf(io(25_000_444, cfg.premixAccount)),
            listOf(io(25_000_000, cfg.postmixAccount)),
        )
        assertEquals(
            FirstMix(25_000_000L, Tier.L1),
            WhirlpoolDetector.classify(v, cfg)
        )
    }

    @Test
    fun ownSideFirstMixWindowEdges() {
        val allowance = cfg.premixMinerFeeAllowanceSats
        fun round(input: Long, output: Long) = view(
            listOf(io(input, cfg.premixAccount)),
            listOf(io(output, cfg.postmixAccount)),
        )
        // (denom, denom + allowance]
        assertEquals(
            FirstMix(2_500_000L, Tier.L1),
            WhirlpoolDetector.classify(round(2_500_001, 2_500_000), cfg)
        )
        assertEquals(
            FirstMix(2_500_000L, Tier.L1),
            WhirlpoolDetector.classify(round(2_500_000 + allowance, 2_500_000), cfg)
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(round(2_500_000, 2_500_000), cfg))
        assertEquals(
            Unknown,
            WhirlpoolDetector.classify(round(2_500_000 + allowance + 1, 2_500_000), cfg)
        )
        assertEquals(
            FirstMix(25_000_000L, Tier.L1),
            WhirlpoolDetector.classify(round(25_000_001, 25_000_000), cfg)
        )
        assertEquals(
            FirstMix(25_000_000L, Tier.L1),
            WhirlpoolDetector.classify(round(25_000_000 + allowance, 25_000_000), cfg)
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(round(25_000_000, 25_000_000), cfg))
        assertEquals(
            Unknown,
            WhirlpoolDetector.classify(round(25_000_000 + allowance + 1, 25_000_000), cfg)
        )
    }

    @Test
    fun premixInputWithinWindowOfObservedPostmixIsFirstMix() {
        // The postmix amount is not a catalogue denomination. D is the
        // output, and the premix input sits inside (D, D + allowance].
        val v = view(
            listOf(io(2_500_444, cfg.premixAccount)),
            listOf(io(2_500_100, cfg.postmixAccount)),
        )
        assertEquals(
            FirstMix(2_500_100L, Tier.L1),
            WhirlpoolDetector.classify(v, cfg),
        )
    }

    @Test
    fun premixInputPaidOutsidePostmixIsNotFirstMix() {
        // Exact denomination, but the output is not our postmix account.
        val v = view(
            listOf(io(2_500_444, cfg.premixAccount)),
            listOf(io(2_500_000)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun mismatchedPoolsAreNotFirstMix() {
        val smallInBigOut = view(
            listOf(io(2_500_444, cfg.premixAccount)),
            listOf(io(25_000_000, cfg.postmixAccount)),
        )
        val bigInSmallOut = view(
            listOf(io(25_000_444, cfg.premixAccount)),
            listOf(io(2_500_000, cfg.postmixAccount)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(smallInBigOut, cfg))
        assertEquals(Unknown, WhirlpoolDetector.classify(bigInSmallOut, cfg))
    }

    @Test
    fun ownSideRemixIsNotFirstMix() {
        val small = view(
            listOf(io(2_500_000, cfg.postmixAccount)),
            listOf(io(2_500_000, cfg.postmixAccount)),
        )
        val big = view(
            listOf(io(25_000_000, cfg.postmixAccount)),
            listOf(io(25_000_000, cfg.postmixAccount)),
        )
        assertEquals(Remix(2_500_000L, Tier.L1), WhirlpoolDetector.classify(small, cfg))
        assertEquals(Remix(25_000_000L, Tier.L1), WhirlpoolDetector.classify(big, cfg))
    }

    @Test
    fun postmixSendIsNotRemixOrFirstMix() {
        val toExternal = view(
            listOf(io(2_500_000, cfg.postmixAccount)),
            listOf(io(2_490_000)),
        )
        val offDenom = view(
            listOf(io(2_500_000, cfg.postmixAccount)),
            listOf(io(2_490_000, cfg.postmixAccount)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(toExternal, cfg))
        assertEquals(Unknown, WhirlpoolDetector.classify(offDenom, cfg))
    }

    @Test
    fun depositToPostmixIsNotFirstMix() {
        val v = view(
            listOf(io(2_500_444, cfg.depositAccount)),
            listOf(io(2_500_000, cfg.postmixAccount)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun ownSideMixesDoNotRequireCatalogueDenominations() {
        // Amounts outside the mainnet catalogue, including a
        // testnet-sized 250_000. D is observed; it is not a pool entry.
        for (denom in listOf(100_000L, 250_000L, 1_000_000L, 5_000_000L, 50_000_000L)) {
            val first = view(
                listOf(io(denom + 444, cfg.premixAccount)),
                listOf(io(denom, cfg.postmixAccount)),
            )
            val remix = view(
                listOf(io(denom, cfg.postmixAccount)),
                listOf(io(denom, cfg.postmixAccount)),
            )
            assertEquals(FirstMix(denom, Tier.L1), WhirlpoolDetector.classify(first, cfg))
            assertEquals(Remix(denom, Tier.L1), WhirlpoolDetector.classify(remix, cfg))
        }
    }

    // --- Guards: no label by construction ---

    @Test
    fun foreignUniformRoundIsUnknownByConstruction() {
        // Wasabi/JoinMarket shape: perfect 5/5 uniform, zero accounts.
        val ins = (1..5).map { io(2_500_300) }
        val outs = (1..5).map { io(2_500_000) }
        assertEquals(Unknown, WhirlpoolDetector.classify(view(ins, outs), cfg))
    }

    @Test
    fun arbitraryPremixSpendIsUnknown() {
        val v = view(
            listOf(io(2_500_000, cfg.premixAccount)),
            listOf(io(2_490_000)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun postmixConsolidationIsUnknown() {
        val ins = (1..3).map { io(2_500_000, cfg.postmixAccount) }
        val outs = listOf(io(7_490_000, cfg.postmixAccount))
        assertEquals(Unknown, WhirlpoolDetector.classify(view(ins, outs), cfg))
    }

    @Test
    fun uniformRoundOutsideCatalogueIsOwnSideFirstMix() {
        // 5/5 outputs are not a catalogue denomination, so the structural
        // path does not claim L2. Own-side still sees premix inputs inside
        // the miner-fee window of the postmix output.
        val ins = (1..5).map { io(3_000_300, cfg.premixAccount) }
        val outs = (1..5).map { io(3_000_000, cfg.postmixAccount) }
        assertEquals(
            FirstMix(3_000_000L, Tier.L1),
            WhirlpoolDetector.classify(view(ins, outs), cfg),
        )
    }

    // --- Path parsing ---

    @Test
    fun accountIndicesFromPaths() {
        // SamouraiAccountIndex.java: PREMIX = Int.MAX_VALUE - 2
        val premixIdx = (Int.MAX_VALUE - 2).toLong()
        assertEquals(
            premixIdx,
            WhirlpoolDetector.accountFromPath("m/84'/0'/${Int.MAX_VALUE - 2}'/0/7")
        )
        assertEquals(0L, WhirlpoolDetector.accountFromPath("m/84'/0'/0'/0/0"))
        assertNull(WhirlpoolDetector.accountFromPath("m/84'/0'"))
        assertNull(WhirlpoolDetector.accountFromPath("nonsense"))
    }

    @Test
    fun firstMixWithRemixerAndPremixInputsClassifies() {
        // Observed shape (076a1dcc, 6fd8e282): inputs mix buffered
        // premix UTXOs (two different buffers observed) with exact-
        // denomination remixer UTXOs; outputs all exact denomination.
        // Sparse anchor: only the wallet's IOs carry accounts.
        val premixIdx = (Int.MAX_VALUE - 2).toLong()
        val postmixIdx = (Int.MAX_VALUE - 1).toLong()
        val ins = listOf(
            io(2_500_907, premixIdx),
            io(2_500_000), io(2_500_000), io(2_500_000), io(2_500_605),
        )
        val outs = listOf(
            io(2_500_000, postmixIdx),
            io(2_500_000), io(2_500_000), io(2_500_000), io(2_500_000),
        )
        assertEquals(
            FirstMix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    // --- Real-tx fixtures (fixture-first gate) ---

    @Test
    fun sampleFixtureMatrixPasses() {
        val stream = javaClass.getResourceAsStream("/whirlpool/sample.jsonl")
        if (stream == null) {
            org.junit.Assert.fail(
                "sample.jsonl missing — populate the txid manifest and run " +
                "scripts/fetch_whirlpool_fixtures.sh"
            )
        }
        stream!!.bufferedReader().readLines().filter { it.isNotBlank() }
            .forEach { raw ->
                val obj = org.json.JSONObject(raw)
                val txid = obj.getString("txid")
                val accountsMap = obj.optJSONObject("accounts") ?: org.json.JSONObject()
                val roleIndex = mapOf(
                    "deposit" to cfg.depositAccount,
                    "premix" to cfg.premixAccount,
                    "postmix" to cfg.postmixAccount,
                    "badbank" to cfg.badbankAccount,
                )
                val acct: (String?) -> Long? = { addr ->
                    if (addr != null && accountsMap.has(addr)) {
                        val role = accountsMap.getString(addr)
                        roleIndex[role]
                            ?: throw IllegalStateException("txid $txid: unknown account role '$role'")
                    } else null
                }
                val ins = (0 until obj.getJSONArray("vin").length()).map { i ->
                    val prev = obj.getJSONArray("vin").getJSONObject(i).optJSONObject("prevout")
                    val addr = prev?.optString("scriptpubkey_address", null as String?)
                    WhirlpoolTxView.IO(
                        value = prev?.optLong("value") ?: 0L,
                        addr = addr,
                        account = acct(addr),
                        script = prev?.optString("scriptpubkey"),
                    )
                }
                val outs = (0 until obj.getJSONArray("vout").length()).map { i ->
                    val o = obj.getJSONArray("vout").getJSONObject(i)
                    val addr = o.optString("scriptpubkey_address", null as String?)
                    WhirlpoolTxView.IO(
                        value = o.getLong("value"),
                        addr = addr,
                        account = acct(addr),
                        script = o.optString("scriptpubkey"),
                    )
                }
                val result = WhirlpoolDetector.classify(WhirlpoolTxView(txid, ins, outs), cfg)
                when (obj.getString("expect")) {
                    "tx0" -> assertEquals("txid $txid", Tx0(Tier.L2), result)
                    "first_mix" -> assertEquals(
                        "txid $txid", FirstMix(outs[0].value, Tier.L2), result)
                    "remix" -> assertEquals(
                        "txid $txid", Remix(outs[0].value, Tier.L2), result)
                    "none" -> assertEquals("txid $txid", Unknown, result)
                    else -> org.junit.Assert.fail("txid $txid: unknown expect tag")
                }
            }
    }

    /**
     * sample.jsonl Tx0. The deposit input is retagged to badbank.
     * Amounts, coordinator fee, OP_RETURN, premix outputs and the
     * badbank change are the fixture's own.
     */
    @Test
    fun sampleTx0RetaggedToBadbankClassifies() {
        val view = sampleTx0View(depositRole = "badbank", keepPremix = true)
        assertEquals(46_973_000L, view.inputs.single().value)
        assertEquals(cfg.badbankAccount, view.inputs.single().account)
        assertTrue(view.outputs.any {
            it.value == 2_500_605L && it.account == cfg.premixAccount
        })
        assertTrue(view.outputs.any {
            it.value == 1_836_274L && it.account == cfg.badbankAccount
        })
        assertTrue(view.outputs.any { it.value == 125_000L && it.account == null })
        assertTrue(view.outputs.any {
            it.value == 0L && it.script?.startsWith("6a") == true
        })
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(view, cfg))
    }

    /** Same fixture, premix outputs removed. Badbank input is not a Tx0. */
    @Test
    fun sampleTx0BadbankInputWithoutPremixIsNotTx0() {
        val view = sampleTx0View(depositRole = "badbank", keepPremix = false)
        assertEquals(cfg.badbankAccount, view.inputs.single().account)
        assertTrue(view.outputs.none { it.account == cfg.premixAccount })
        assertTrue(view.outputs.any { it.account == cfg.badbankAccount })
        assertEquals(Unknown, WhirlpoolDetector.classify(view, cfg))
    }

    private fun sampleTx0View(depositRole: String, keepPremix: Boolean): WhirlpoolTxView {
        val raw = javaClass.getResourceAsStream("/whirlpool/sample.jsonl")!!
            .bufferedReader().readLines().first { line ->
                line.isNotBlank() &&
                    org.json.JSONObject(line).optString("expect") == "tx0"
            }
        val obj = org.json.JSONObject(raw)
        val accountsMap = obj.getJSONObject("accounts")
        val roleIndex = mapOf(
            "deposit" to cfg.depositAccount,
            "premix" to cfg.premixAccount,
            "postmix" to cfg.postmixAccount,
            "badbank" to cfg.badbankAccount,
        )
        fun roleOf(addr: String?): String? {
            if (addr == null || !accountsMap.has(addr)) return null
            val original = accountsMap.getString(addr)
            return if (original == "deposit") depositRole else original
        }
        fun accountOf(addr: String?): Long? {
            val role = roleOf(addr) ?: return null
            return roleIndex[role]
                ?: throw IllegalStateException("unknown account role '$role'")
        }
        val ins = (0 until obj.getJSONArray("vin").length()).map { i ->
            val prev = obj.getJSONArray("vin").getJSONObject(i).optJSONObject("prevout")
            val addr = prev?.optString("scriptpubkey_address", null as String?)
            WhirlpoolTxView.IO(
                value = prev?.optLong("value") ?: 0L,
                addr = addr,
                account = accountOf(addr),
                script = prev?.optString("scriptpubkey"),
            )
        }
        val outs = (0 until obj.getJSONArray("vout").length()).mapNotNull { i ->
            val o = obj.getJSONArray("vout").getJSONObject(i)
            val addr = o.optString("scriptpubkey_address", null as String?)
            if (!keepPremix && roleOf(addr) == "premix") return@mapNotNull null
            WhirlpoolTxView.IO(
                value = o.getLong("value"),
                addr = addr,
                account = accountOf(addr),
                script = o.optString("scriptpubkey"),
            )
        }
        return WhirlpoolTxView(obj.getString("txid"), ins, outs)
    }
}
