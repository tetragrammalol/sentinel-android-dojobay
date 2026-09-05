package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * User-defined UTXO labels (BIP329 "output" records).
 *
 * All access is scoped to the active network ("mainnet"/"testnet") so labels
 * can never leak across networks. Labels are stored independently of the
 * sync-managed utxos table and therefore survive backend refreshes.
 */
class LabelRepository {

    private val dao: UtxoLabelDao by inject(UtxoLabelDao::class.java)

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    fun observe(txid: String, vout: Int): LiveData<UtxoLabel?> =
        dao.observe(currentNetwork(), txid, vout)

    /** Live map of "txid:vout" -> label for the active network. */
    fun observeLabelMap(): LiveData<Map<String, String>> =
        Transformations.map(dao.observeAll(currentNetwork())) { labels ->
            labels.associate { "${it.txid}:${it.vout}" to it.label }
        }

    suspend fun getAll(): List<UtxoLabel> =
        withContext(Dispatchers.IO) { dao.getAll(currentNetwork()) }

    /**
     * Sets or updates a label. A blank label deletes the record
     * (agreed BIP329 semantics: empty label = remove).
     */
    suspend fun setLabel(txid: String, vout: Int, label: String) =
        withContext(Dispatchers.IO) {
            val network = currentNetwork()
            val trimmed = label.trim()
            if (trimmed.isEmpty()) {
                dao.delete(network, txid, vout)
            } else {
                val now = System.currentTimeMillis()
                val existing = dao.find(network, txid, vout)
                dao.upsert(
                    UtxoLabel(
                        network = network,
                        txid = txid,
                        vout = vout,
                        label = trimmed,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            }
        }
}
