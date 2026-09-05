package com.samourai.sentinel.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.flow.Flow

@Dao
interface UtxoLabelDao {

    @Query("SELECT * FROM utxo_labels WHERE network=:network AND txid=:txid AND vout=:vout")
    suspend fun find(network: String, txid: String, vout: Int): UtxoLabel?

    @Query("SELECT * FROM utxo_labels WHERE network=:network AND txid=:txid AND vout=:vout")
    fun observe(network: String, txid: String, vout: Int): LiveData<UtxoLabel?>

    @Query("SELECT * FROM utxo_labels WHERE network=:network")
    fun observeAll(network: String): LiveData<List<UtxoLabel>>

    @Query("SELECT * FROM utxo_labels WHERE network=:network")
    suspend fun getAll(network: String): List<UtxoLabel>

    @Query("SELECT * FROM utxo_labels")
    suspend fun getAllNetworks(): List<UtxoLabel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(label: UtxoLabel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(labels: List<UtxoLabel>)

    @Query("DELETE FROM utxo_labels WHERE network=:network AND txid=:txid AND vout=:vout")
    suspend fun delete(network: String, txid: String, vout: Int)

    @Query("DELETE FROM utxo_labels WHERE network=:network AND txid=:txid AND vout=:vout AND label=:label")
    suspend fun deleteIfLabelEquals(network: String, txid: String, vout: Int, label: String)

    @Query("DELETE FROM utxo_labels WHERE network=:network")
    suspend fun deleteAll(network: String)

    @Query("DELETE FROM utxo_labels")
    suspend fun deleteAllNetworks()

    /**
     * Atomic commit for a fully resolved BIP329 import plan: all inserts,
     * updates and deletions become visible together or not at all.
     */
    @Transaction
    suspend fun applyPlan(upserts: List<UtxoLabel>, deletions: List<Triple<String, String, Int>>) {
        deletions.forEach { (network, txid, vout) ->
            delete(network, txid, vout)
        }
        if (upserts.isNotEmpty()) {
            upsertAll(upserts)
        }
    }

    // Flow variants for coroutine-based consumers (BIP329 export).
    @Query("SELECT * FROM utxo_labels WHERE network=:network")
    fun observeAllFlow(network: String): Flow<List<UtxoLabel>>
}
