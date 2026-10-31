package com.samourai.sentinel.data.whirlpool

import com.google.gson.reflect.TypeToken
import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.collectionDetails.transactions.TxPerspectiveLabel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

/**
 * Badbank deduction goldens (issue #46): the two real testnet TX0s
 * from the device DB, as verbatim Dojo partial rows. Payloads are
 * never hand-edited; expect/badbankVout/badbankValue are the manifest
 * knobs. Double-parse (string -> Gson list) mirrors Room's
 * TxInputConverter read path.
 */
class BadbankDeductionTest {

    private val inputsType: Type = object : TypeToken<List<Inputs>>() {}.type
    private val outsType: Type = object : TypeToken<List<Out>>() {}.type

    // The account vpubs the payload echoes, mapped to the fixed
    // Samourai indices (WhirlpoolConfig). The sync wiring builds the
    // same map via x.child + 2^31 across all six serializations
    // (TransactionsRepository); the test maps the literal `m` strings.
    private val accountOfXpub = mapOf(
        "vpub5Yig39b82QaiLtyWHh1xQUUD8fdQNwoNvGEHzjWNLZHhw41DcXPMP4AQZ2oj3RQWdVGE27STV11qxXxvb7HSWbuyF4zeEPHMY8GLk5Vc8nd" to
            WhirlpoolConfig.DEFAULT.depositAccount,
        "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe" to
            WhirlpoolConfig.DEFAULT.premixAccount,
        "vpub5Yig39bGN57gLvDjA3S9JjXE7KRueWiNRUGZUA5tzcFQ6m5EAziMCi9aFaySGvvrkCpTjqKyDJdsShHQRwyVnNMwupJhNkLYx69n4erkSyp" to
            WhirlpoolConfig.DEFAULT.badbankAccount,
    )

    private data class Row(val tx: Tx, val meta: JSONObject)

    private fun rows(): List<Row> =
        javaClass.getResourceAsStream("/whirlpool/dojo_tx0_goldens.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .map { raw ->
                val obj = JSONObject(raw)
                Row(
                    Tx(
                        // DB rows carry the save-time row index
                        // (txid-collectionId-N); the backfill consumes the
                        // PRE-mangle in-memory list where hashes are
                        // txid-collectionId (TransactionsRepository: the
                        // whirlpool block runs BEFORE
                        // keepTransactionWithVariousPubkeys mangles hashes).
                        // Strip the index here; everything else verbatim.
                        hash = obj.getString("hash")
                            .replaceFirst("-\\d+$".toRegex(), ""),
                        time = obj.getLong("time"),
                        associatedPubKey = obj.getString("associatedPubKey"),
                        collectionId = obj.getString("collectionId"),
                        version = obj.getInt("version"),
                        locktime = obj.getInt("locktime"),
                        result = if (obj.isNull("result")) null
                                 else obj.getLong("result"),
                        inputs = fromJSON(obj.getString("inputs"), inputsType)!!,
                        out = fromJSON(obj.getString("out"), outsType)!!,
                        block_height = if (obj.isNull("block_height")) null
                                       else obj.getLong("block_height"),
                        confirmations = obj.getLong("confirmations"),
                    ),
                    obj,
                )
            }

    @Test fun deduction_goldens() = runBlocking {
        val all = rows()
        assertEquals(6, all.size)                    // 3 partial rows x 2 txids
        val collectionId = all.first().tx.collectionId
        val merged = WhirlpoolBackfill.mergePartialTxs(
            all.map { it.tx }, collectionId)
        assertEquals(2, merged.size)                 // 3 rows per txid -> 1

        val metaByTxid = all.associate {
            it.tx.hash.removeSuffix("-$collectionId") to it.meta
        }
        merged.forEach { tx ->
            val bareTxid = tx.hash.removeSuffix("-$collectionId")
            val meta = metaByTxid[bareTxid]!!
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            val cls = WhirlpoolDetector.classify(view)
            // App Tx model carries no scripts: OP_RETURN tri-state is
            // "skipped" — NOT disconfirming — so uniform premix classifies
            // at L2 (detector's own matrix: L1 is the scripts-supplied-and-
            // no-null-data case). The golden caught a wrong L1 prediction
            // here; the fixture doing its job.
            assertEquals(
                "txid $bareTxid",
                WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
                cls,
            )
            val deduced = BadbankDeducer.deduce(
                view, cls as WhirlpoolClassification.Tx0)
            assertNotNull("txid $bareTxid: expected a badbank deduction", deduced)
            deduced as BadbankDeducer.Result.Badbank
            assertEquals("txid $bareTxid", meta.getInt("badbankVout"), deduced.vout)
            assertEquals("txid $bareTxid", meta.getLong("badbankValue"), deduced.valueSats)
        }
    }

    // --- Branch oracles (synthetic; deduce's contract given Tx0) ---

    @Test fun noBadbankOutput_whenTx0LacksBadbankAccountOutput() {
        // Classified Tx0 with no badbank-account output. Classification
        // is passed as given: this tests deduce's contract.
        val view = WhirlpoolTxView(
            "aa".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 0),
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 1),
            ),
        )
        assertEquals(
            BadbankDeducer.Result.NoBadbankOutput,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    @Test fun ambiguous_multipleBadbankOutputs_sayNothing() {
        val view = WhirlpoolTxView(
            "bb".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 0),
                WhirlpoolTxView.IO(90_000_000, account = 2_147_483_644L, vout = 1),
                WhirlpoolTxView.IO(5_000_000, account = 2_147_483_644L, vout = 2),
            ),
        )
        assertEquals(
            null,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    @Test fun missingVout_sayNothing() {
        // vout lost (e.g. a source that drops output positions):
        // cannot address a utxo label, so no deduction.
        val view = WhirlpoolTxView(
            "cc".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L),
                WhirlpoolTxView.IO(90_000_000, account = 2_147_483_644L),
            ),
        )
        assertEquals(
            null,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    /** Golden round-trip through the full labeler write path: both
     *  device TX0s label exactly (txid, 22) with our origin, converge
     *  on a second run, and write nothing else. */
    @Test fun labeler_goldenRoundTrip() = runBlocking {
        class FakeSink(
            var rows: MutableMap<Pair<String, Int>, ExistingLabel> = mutableMapOf()
        ) : UtxoLabelSink {
            override suspend fun findUtxoLabel(txid: String, vout: Int) =
                rows[txid to vout]
            override suspend fun writeAutoUtxoLabel(
                txid: String, vout: Int, label: String, origin: String,
            ) { rows[txid to vout] = ExistingLabel(label, origin) }
            override suspend fun deleteAutoUtxoLabel(
                txid: String, vout: Int, ourLabel: String) {}
        }
        val all = rows()
        val collectionId = all.first().tx.collectionId
        val merged = WhirlpoolBackfill.mergePartialTxs(
            all.map { it.tx }, collectionId)
        val sink = FakeSink()
        val labeler = BadbankLabeler(sink)
        val expected = mutableMapOf<Pair<String, Int>, ExistingLabel>()
        merged.forEach { tx ->
            val bareTxid = tx.hash.removeSuffix("-$collectionId")
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            assertEquals(
                BadbankLabeler.Outcome.TX0_LABELLED, labeler.process(view))
            expected[bareTxid to 22] =
                ExistingLabel(WhirlpoolConfig.DEFAULT.labels.badbank,
                    WhirlpoolConfig.DEFAULT.origin)
        }
        assertEquals(expected, sink.rows)   // exactly (txid, 22) x 2
        merged.forEach { tx ->
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            labeler.process(view)           // idempotent convergence
        }
        assertEquals(expected, sink.rows)
    }

    /**
     * Same Dojo partials as the deposit-funded goldens. Only the input
     * xpub is retagged from the deposit account to badbank. Amounts,
     * premix outputs and the badbank change are untouched.
     */
    @Test fun badbankFundedGoldens_areTx0AndLabelChangeOnly() = runBlocking {
        val cfg = WhirlpoolConfig.DEFAULT
        val all = rows()
        val collectionId = all.first().tx.collectionId
        val metaByTxid = all.associate {
            it.tx.hash.removeSuffix("-$collectionId") to it.meta
        }
        val views = viewsOf(all.map { it.tx.inputsOnBadbank() })
        assertEquals(2, views.size)

        val utxo = RecordingUtxoSink()
        val txSink = RecordingTxSink()
        val labeler = BadbankLabeler(utxo)
        val writer = WhirlpoolAutoWriter(txSink)
        val badbankLabel = ExistingLabel(cfg.labels.badbank, cfg.origin)
        views.forEach { view ->
            assertEquals(
                setOf(cfg.badbankAccount),
                view.inputs.mapNotNull { it.account }.toSet(),
            )
            assertTrue(view.outputs.any { it.account == cfg.premixAccount })
            assertEquals(
                WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
                WhirlpoolDetector.classify(view),
            )
            assertEquals(BadbankLabeler.Outcome.TX0_LABELLED, labeler.process(view))
            assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, writer.process(view))

            val meta = metaByTxid[view.txid]!!
            val vout = meta.getInt("badbankVout")
            assertEquals(badbankLabel, utxo.rows[view.txid to vout])
            assertEquals(
                meta.getLong("badbankValue"),
                view.outputs.single { it.account == cfg.badbankAccount }.value,
            )
            view.outputs.filter { it.account == cfg.premixAccount }.forEach { out ->
                assertNull(utxo.rows[view.txid to out.vout])
            }
        }
        assertEquals(views.size, utxo.rows.size)
        assertEquals(views.map { it.txid }.toSet(), txSink.writes.map { it.first }.toSet())
        txSink.writes.forEach { write ->
            assertEquals(cfg.labels.tx0, write.second)
            assertEquals(cfg.origin, write.third)
            // Stored label is Whirlpool Tx0. Premix rows display that
            // as Whirlpool Premix; the badbank row displays Bad Bank.
            assertEquals(
                TxPerspectiveLabel.PREMIX_DISPLAY,
                TxPerspectiveLabel.display(
                    write.second, write.third, TxPerspectiveLabel.Role.PREMIX,
                ),
            )
            assertEquals(
                cfg.labels.badbank,
                TxPerspectiveLabel.display(
                    write.second, write.third, TxPerspectiveLabel.Role.BAD_BANK,
                ),
            )
        }
        views.forEach { view ->
            assertEquals(BadbankLabeler.Outcome.TX0_LABELLED, labeler.process(view))
        }
        assertEquals(views.size, utxo.rows.size)
    }

    /** 0.025 premix coins, badbank input, badbank change partial omitted. */
    @Test fun badbankFunded0025WithoutChange_isTx0ViaPoolWindow() = runBlocking {
        val cfg = WhirlpoolConfig.DEFAULT
        val view = viewsOf(
            txsWithPremixValue(2_500_605L).map {
                it.inputsOnBadbank().withoutAccountOutputs(cfg.badbankAccount)
            },
        ).single()
        assertEquals(setOf(cfg.badbankAccount), view.inputs.mapNotNull { it.account }.toSet())
        assertTrue(view.outputs.none { it.account == cfg.badbankAccount })
        assertTrue(view.outputs.all {
            it.account == cfg.premixAccount && it.value == 2_500_605L
        })
        assertEquals(
            WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
            WhirlpoolDetector.classify(view),
        )
        val utxo = RecordingUtxoSink()
        assertEquals(
            BadbankLabeler.Outcome.TX0_NO_BADBANK,
            BadbankLabeler(utxo).process(view),
        )
        assertTrue(utxo.rows.isEmpty())
        val txSink = RecordingTxSink()
        assertEquals(
            WhirlpoolAutoWriter.Outcome.WRITTEN,
            WhirlpoolAutoWriter(txSink).process(view),
        )
        assertEquals(cfg.labels.tx0, txSink.writes.single().second)
    }

    /**
     * Smaller golden premix coin (250_605) sits outside both pool
     * windows. Without the badbank change it is not a Tx0, and the
     * premix outputs block propagation.
     */
    @Test fun badbankPremixOutsideWindowWithoutChange_isNotTx0() = runBlocking {
        val cfg = WhirlpoolConfig.DEFAULT
        val view = viewsOf(
            txsWithPremixValue(250_605L).map {
                it.inputsOnBadbank().withoutAccountOutputs(cfg.badbankAccount)
            },
        ).single()
        assertEquals(
            WhirlpoolClassification.Unknown,
            WhirlpoolDetector.classify(view),
        )
        val input = view.inputs.single()
        val our = ExistingLabel(cfg.labels.badbank, cfg.origin)
        val key = input.spentTxid!! to input.spentVout!!
        val utxo = RecordingUtxoSink(mapOf(key to our))
        assertEquals(
            BadbankLabeler.Outcome.WHIRLPOOL_TERRITORY,
            BadbankLabeler(utxo).process(view),
        )
        assertEquals(mapOf(key to our), utxo.rows)
        val txSink = RecordingTxSink()
        assertEquals(
            WhirlpoolAutoWriter.Outcome.UNCLASSIFIED,
            WhirlpoolAutoWriter(txSink).process(view),
        )
        assertTrue(txSink.writes.isEmpty())
    }

    /** Badbank inputs and the badbank change only. Premix partials dropped. */
    @Test fun badbankInputsWithoutPremixOutput_areNotTx0() = runBlocking {
        val cfg = WhirlpoolConfig.DEFAULT
        val views = viewsOf(
            rows().map {
                it.tx.inputsOnBadbank().withoutAccountOutputs(cfg.premixAccount)
            },
        )
        assertEquals(2, views.size)
        val our = ExistingLabel(cfg.labels.badbank, cfg.origin)
        val initial = views.associate { view ->
            val input = view.inputs.single()
            (input.spentTxid!! to input.spentVout!!) to our
        }
        val utxo = RecordingUtxoSink(initial)
        val labeler = BadbankLabeler(utxo)
        val txSink = RecordingTxSink()
        val writer = WhirlpoolAutoWriter(txSink)
        views.forEach { view ->
            assertTrue(view.outputs.none { it.account == cfg.premixAccount })
            assertEquals(
                WhirlpoolClassification.Unknown,
                WhirlpoolDetector.classify(view),
            )
            assertEquals(WhirlpoolAutoWriter.Outcome.UNCLASSIFIED, writer.process(view))
            assertEquals(BadbankLabeler.Outcome.PROPAGATED, labeler.process(view))
            val change = view.outputs.single()
            assertEquals(cfg.badbankAccount, change.account)
            assertEquals(our, utxo.rows[view.txid to change.vout])
        }
        assertTrue(txSink.writes.isEmpty())
        assertEquals(initial.size + views.size, utxo.rows.size)
    }

    /**
     * Each golden keeps its deposit input and gains a second input of
     * the same value tagged badbank. Outputs are unchanged.
     */
    @Test fun mixedDepositAndBadbankInputs_classifyAsTx0() = runBlocking {
        val cfg = WhirlpoolConfig.DEFAULT
        val views = viewsOf(rows().map { it.tx.withAddedBadbankInput() })
        assertEquals(2, views.size)
        val utxo = RecordingUtxoSink()
        val txSink = RecordingTxSink()
        val labeler = BadbankLabeler(utxo)
        val writer = WhirlpoolAutoWriter(txSink)
        views.forEach { view ->
            assertEquals(cfg.depositAccount, view.inputs[0].account)
            assertEquals(cfg.badbankAccount, view.inputs[1].account)
            assertEquals(view.inputs[0].value, view.inputs[1].value)
            assertEquals(
                WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
                WhirlpoolDetector.classify(view),
            )
            assertEquals(BadbankLabeler.Outcome.TX0_LABELLED, labeler.process(view))
            assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, writer.process(view))
            view.outputs.filter { it.account == cfg.premixAccount }.forEach { out ->
                assertNull(utxo.rows[view.txid to out.vout])
            }
        }
        assertEquals(views.size, utxo.rows.size)
        txSink.writes.forEach { assertEquals(cfg.labels.tx0, it.second) }
    }

    private fun vpub(account: Long) =
        accountOfXpub.entries.first { it.value == account }.key

    /** Deposit-account input xpub retagged to the badbank account. */
    private fun Tx.inputsOnBadbank(): Tx {
        val from = vpub(WhirlpoolConfig.DEFAULT.depositAccount)
        val to = vpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        return copy(inputs = inputs.map { input ->
            val prev = input.prev_out ?: return@map input
            val xpub = prev.xpub ?: return@map input
            if (xpub.m != from) input
            else input.copy(prev_out = prev.copy(xpub = xpub.copy(m = to)))
        })
    }

    private fun Tx.withoutAccountOutputs(account: Long): Tx {
        val pub = vpub(account)
        return copy(out = out.filter { it.xpub?.m != pub })
    }

    /** Second input, same value as the golden input, tagged badbank. */
    private fun Tx.withAddedBadbankInput(): Tx {
        if (inputs.isEmpty()) return this
        val to = vpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        val template = inputs.single()
        val prev = template.prev_out ?: return this
        val xpub = prev.xpub ?: return this
        val extra = template.copy(
            vin = template.vin + 1,
            prev_out = prev.copy(
                txid = "e".repeat(64),
                vout = 0,
                xpub = xpub.copy(m = to),
            ),
        )
        return copy(inputs = inputs + extra)
    }

    private fun txsWithPremixValue(value: Long): List<Tx> {
        val all = rows()
        val collectionId = all.first().tx.collectionId
        return all.groupBy { it.tx.hash.removeSuffix("-$collectionId") }
            .values.first { group ->
                group.any { it.tx.out.any { out -> out.value == value } }
            }
            .map { it.tx }
    }

    private fun viewsOf(txs: List<Tx>): List<WhirlpoolTxView> {
        val collectionId = txs.first().collectionId
        return WhirlpoolBackfill.mergePartialTxs(txs, collectionId).map {
            WhirlpoolTxAdapter.toView(it, collectionId, accountOfXpub)
        }
    }

    private class RecordingUtxoSink(
        initial: Map<Pair<String, Int>, ExistingLabel> = emptyMap(),
    ) : UtxoLabelSink {
        val rows = initial.toMutableMap()
        override suspend fun findUtxoLabel(txid: String, vout: Int) =
            rows[txid to vout]
        override suspend fun writeAutoUtxoLabel(
            txid: String, vout: Int, label: String, origin: String,
        ) { rows[txid to vout] = ExistingLabel(label, origin) }
        override suspend fun deleteAutoUtxoLabel(
            txid: String, vout: Int, ourLabel: String,
        ) {}
    }

    private class RecordingTxSink : WhirlpoolAutoWriter.Sink {
        val writes = mutableListOf<Triple<String, String, String>>()
        override suspend fun findTxLabel(txid: String): ExistingLabel? = null
        override suspend fun writeAutoTxLabel(
            txid: String, label: String, origin: String,
        ) { writes += Triple(txid, label, origin) }
    }
}
