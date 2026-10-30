package com.samourai.sentinel.util

import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * sentinel-android
 *
 * @author Sarath
 */
object UtxoMetaUtil {

    data class UtxoState(val hash: String,
                         val txOutputN: Int,
                         val associatedPub: String,
                         val amount: Long)

    data class BlockPayload(val utxoBlockState: MutableMap<String, UtxoState>)

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val dbHandler: SentinelCollectionStore by inject(SentinelCollectionStore::class.java)

    private var utxoBlockState: MutableMap<String, UtxoState> = mutableMapOf()

    /**
     * #301: bumps on every change to the do-not-spend marks - put, remove,
     * clearAll, and any read() that replaces the in-memory state (the
     * initial load plus later re-reads). Balance surfaces observe this and
     * re-derive, so spendable figures repaint the moment a mark changes
     * instead of at the next sync emission. postValue: callers arrive on
     * the main thread (action mode) and IO coroutines (read) alike.
     */
    val blockedChanges = MutableLiveData<Unit>()

    private fun announceChange() {
        blockedChanges.postValue(Unit)
    }

    init {
        read()
    }

    fun put(utxo: Utxo) {
        val key = "${utxo.txHash}-${utxo.txOutputN}"
        utxoBlockState[key] = UtxoState(
                utxo.txHash!!,
                utxo.txOutputN!!,
                utxo.pubKey,
                if (utxo.value != null) utxo.value!! else 0
        )
        announceChange()
        write()
    }

    fun has(hash: String, index: Int): Boolean {
        val key = "${hash}-${index}"
        return utxoBlockState.values.find { utxo -> (utxo.hash == hash && utxo.txOutputN == index) } != null
    }

    fun has(utxo: Utxo): Boolean {
        val key = "${utxo.txHash}-${utxo.txOutputN}"
        return utxoBlockState.containsKey(key)
    }

    fun getBlockedAssociatedWithPubKey(pubKey: String): Collection<UtxoState> {
        return utxoBlockState.values.filter { it.associatedPub == pubKey }
    }

    fun getBlockedAssociatedWithPubKeyList(pubKeys: List<String>): Collection<UtxoState> {
        return utxoBlockState.values.filter { it.associatedPub in pubKeys }
    }

    fun remove(txHash: String, txOutputN: Int) {
        val toRemove = utxoBlockState.filterValues { it.hash == txHash && it.txOutputN == txOutputN }.keys
        if (toRemove.size == 1) {
            utxoBlockState.remove(toRemove.first())
            announceChange()
            write()
        }
    }


    @Synchronized
    fun read() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val items = dbHandler.getUtxoMetaData().read<BlockPayload>()
                    items?.let {
                        if (it.utxoBlockState != null) {
                            utxoBlockState = it.utxoBlockState
                            announceChange()
                        }
                    }
                }
            } catch (e: Exception) {
                throw CancellationException(e.message)
            }
        }
    }

    fun clearAll() {
        utxoBlockState.clear()
        announceChange()
        write()
    }

    @Synchronized
    private fun write() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val payload = BlockPayload(utxoBlockState)
                    dbHandler.getUtxoMetaData().write(payload, replace = true)
                }
            } catch (e: Exception) {
                Timber.e(e)
                throw CancellationException(e.message)
            }
        }
    }

    fun dispose() {
        if (scope.isActive)
            scope.cancel()
    }
}
