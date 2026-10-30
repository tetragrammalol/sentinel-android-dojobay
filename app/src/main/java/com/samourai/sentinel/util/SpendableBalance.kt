package com.samourai.sentinel.util

import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel

/**
 * #301: the one do-not-spend balance derivation.
 *
 * Every balance surface renders spendable figures from here - Home rows,
 * the Home total, the collection details header (all tabs, deposit tab
 * included), pubkey rows and the send screen's available balance. Before
 * this, five call sites each subtracted blocked UTXOs their own way at
 * their own moment (bind time, sync emission, LiveData race), which is
 * how the same wallet showed three different totals at once.
 *
 * Semantics deliberately IDENTICAL to the derivations replaced:
 *  - frozen is the sum of the amounts captured when each mark was set
 *    (UtxoMetaUtil's stored UtxoState.amount), not a live UTXO lookup;
 *  - nothing is clamped - stale marks on spent coins can drive spendable
 *    below zero (stale-mark hygiene is #287, out of scope here);
 *  - the total side comes from the collection/pub balances the sync
 *    already maintains.
 *
 * [balanceFigures] is the pure core (plain-JVM testable, no Koin); the
 * figuresFor helpers add the UtxoMetaUtil lookup and are covered by the
 * device leg. Reactivity is UtxoMetaUtil.blockedChanges - surfaces
 * observe it and re-derive; nothing polls.
 */
data class BalanceFigures(val total: Long, val frozen: Long) {
    /** total minus frozen; NOT clamped - see class doc. */
    val spendable: Long get() = total - frozen

    /** True when any blocked amount is positive - drives the frozen caption. */
    val hasFrozen: Boolean get() = frozen > 0L
}

/** Pure core: a total minus a blocked-amount list. */
fun balanceFigures(total: Long, blockedAmounts: Collection<Long>): BalanceFigures =
        BalanceFigures(total, blockedAmounts.sum())

/** One pubkey's row: its balance minus the marks associated with it. */
fun figuresFor(pub: PubKeyModel): BalanceFigures =
        balanceFigures(pub.balance, blockedAmountsFor(listOf(pub.pubKey)))

/** A pubkey subset (e.g. the deposit tab's keychains). */
fun figuresFor(pubs: List<PubKeyModel>): BalanceFigures =
        balanceFigures(pubs.sumOf { it.balance }, blockedAmountsFor(pubs.map { it.pubKey }))

/** A whole collection (Home row, details "All" tab). */
fun figuresFor(collection: PubKeyCollection): BalanceFigures =
        balanceFigures(collection.balance, blockedAmountsFor(collection.pubs.map { it.pubKey }))

/** Every collection (the Home total). */
fun figuresForAll(collections: List<PubKeyCollection>): BalanceFigures =
        balanceFigures(
                collections.sumOf { it.balance },
                blockedAmountsFor(collections.flatMap { c -> c.pubs.map { it.pubKey } })
        )

private fun blockedAmountsFor(pubKeys: List<String>): List<Long> =
        UtxoMetaUtil.getBlockedAssociatedWithPubKeyList(pubKeys).map { it.amount }
