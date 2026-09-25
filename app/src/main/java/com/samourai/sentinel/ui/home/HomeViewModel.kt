package com.samourai.sentinel.ui.home

import androidx.lifecycle.*
import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

class HomeViewModel : ViewModel() {

    companion object {
        /**
         * How long a sync may run before we tell the user it's taking a while.
         *
         * This does NOT cancel anything - it only annotates the banner. Tor
         * bootstrapping plus onion circuit setup can legitimately exceed a minute,
         * so cancelling on a timer produced false failures.
         */
        private const val SLOW_SYNC_NOTICE_MS = 30_000L

        /** Auto rounds that fail totally (circuit not yet usable) get at
         *  most this many spaced retries before showing Failed. */
        private const val AUTO_SYNC_RETRIES = 2
        private const val AUTO_RETRY_DELAY_MS = 3_000L
    }

    val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)

    private val errorMessage: MutableLiveData<String> = MutableLiveData()
    private val balance: MutableLiveData<Long> = MutableLiveData()
    private var netWorkJobs: ArrayList<Job?> = arrayListOf()
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    /** Single source of truth for the sync lifecycle. */
    private val syncState: MutableLiveData<SyncState> = MutableLiveData(SyncState.Idle)

    fun syncState(): LiveData<SyncState> = syncState

    /**
     * Coalescing guard (#47): at most ONE sync round in flight at a time -
     * whether a user-initiated fetchBalance round or an auto-armed
     * syncCollections round - plus a single trailing re-run if the collection
     * list changed mid-round. Overlapping rounds (each with its own
     * completed-counter, each posting its own terminal state) were the banner
     * flap, the backwards "n of m" counter, and the dojo call storm.
     */
    private var syncRound: Job? = null

    @Volatile
    private var syncRerunPending = false

    /**
     * Content signature (collection ids + pubkeys) of the last round's list.
     * ALL auto re-arms - MediatorLiveData emissions, trailing re-runs,
     * stragglers delivered just after round end - are skipped when the
     * signature is unchanged. Ids alone are too coarse: adding a pubkey to
     * an existing collection keeps the id but must re-sync (#47).
     */
    @Volatile
    private var lastRoundSignature: String? = null

    private fun collectionSignature(list: List<PubKeyCollection>): String =
        list.joinToString("|") { c ->
            c.id + ":" + c.pubs.joinToString(",") { it.pubKey }
        }

    /** Bounded auto-retry budget; reset on success or user refresh. */
    private var autoRetryBudget = 0

    @Volatile
    private var autoRetryPending = false

    private fun launchSyncRound(collections: ArrayList<PubKeyCollection>) {
        if (syncRound?.isActive == true) {
            // Drop the re-arm; the in-flight round is already fetching this
            // data. Re-run at most once when it ends so genuine collection
            // changes mid-round are still picked up.
            syncRerunPending = true
            Timber.i("sync round suppressed: round in flight, trailing re-run armed")
            return
        }
        val signature = collectionSignature(collections)
        if (lastRoundSignature != null && signature == lastRoundSignature) {
            Timber.i("sync round skipped: signature unchanged since last round")
            return
        }
        lastRoundSignature = signature
        Timber.i("sync round begin: syncCollections collections=${collections.size}")
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                syncCollections(collections)
            } finally {
                // Identity check: a cancelled/replaced round must never clear
                // the guard state of its successor.
                if (syncRound === self) {
                    syncRound = null
                    if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    }
                } else {
                    Timber.i("sync round end: superseded, cleanup skipped")
                }
            }
        }
    }

    /**
     * Surfaces a corrupt-payload condition so the UI can warn the user instead of
     * silently showing an empty wallet list.
     */
    fun payloadReadFailure(): LiveData<String?> = repository.readFailure()

    init {
        load()
        observeTor()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                repository.read()
            } catch (e: Exception) {
                Timber.e(e)
                // Do NOT report this as a generic network error; it means the
                // on-disk payload could not be decoded.
                syncState.postValue(
                    SyncState.Failed(
                        reason = "Could not load your collections.",
                        retryable = true
                    )
                )
            }
        }
    }

    /**
     * Reflects Tor bootstrap progress into the sync state so the indicator can
     * show real progress rather than an indefinite spinner.
     */
    private fun observeTor() {
        if (!SentinelState.isTorRequired()) return
        SentinelTorManager.getTorStateLiveData().observeForever { torState ->
            when (torState.state) {
                EnumTorState.STARTING -> {
                    if (syncState.value !is SyncState.Syncing) {
                        syncState.postValue(SyncState.WaitingForTor(torState.progressIndicator))
                    }
                }
                EnumTorState.OFF -> {
                    if (syncState.value is SyncState.WaitingForTor) {
                        syncState.postValue(
                            SyncState.Failed("Tor is not connected.", retryable = true)
                        )
                    }
                }
                EnumTorState.ON -> Unit
                EnumTorState.STOPPING -> Unit
            }
        }
    }

    fun getCollections(): LiveData<ArrayList<PubKeyCollection>> {
        val collectionsLiveData = repository.collectionsLiveData
        val resultLiveData = MediatorLiveData<ArrayList<PubKeyCollection>>()

        resultLiveData.addSource(collectionsLiveData) { collections ->
            // Publish cached collections IMMEDIATELY so the user sees their
            // wallets while any network work happens in the background.
            resultLiveData.value = collections

            val torReady = !SentinelState.isTorRequired() ||
                SentinelTorManager.getTorState().state == EnumTorState.ON

            if (!torReady) {
                // Previously the loading counter was incremented here even though
                // no fetch was started, which is what left the spinner running
                // forever on first launch after PIN entry.
                syncState.postValue(
                    SyncState.WaitingForTor(SentinelTorManager.getTorState().progressIndicator)
                )
                SentinelState.hasAppJustStarted = false
                return@addSource
            }

            // MUST be Dispatchers.IO: fetchUTXOS/fetchFromServer touch Room, and
            // viewModelScope defaults to Dispatchers.Main, which makes Room throw
            // "Cannot access database on the main thread".
            launchSyncRound(collections)
        }
        return resultLiveData
    }

    /**
     * Fetches UTXOs for each collection, reporting determinate progress.
     *
     * There is deliberately NO timeout on the network work. Tor circuits can take
     * minutes to build on a poor connection, and cancelling a healthy-but-slow
     * sync produced a false "timed out" error. Instead a watchdog flips the state
     * to `Syncing(slow = true)` so the UI can explain the delay while the request
     * keeps running. The `finally` block still guarantees a terminal state.
     */
    private suspend fun syncCollections(collections: ArrayList<PubKeyCollection>) = coroutineScope {
        if (collections.isEmpty()) {
            syncState.postValue(SyncState.Success(System.currentTimeMillis()))
            updateBalance()
            SentinelState.hasAppJustStarted = false
            return@coroutineScope
        }

        var completed = 0
        var failed = 0
        // Tracked locally because postValue() is asynchronous - reading
        // syncState.value in the finally block would still see "Syncing" and
        // clobber the real terminal state.
        var resolved = false
        syncState.postValue(SyncState.Syncing(done = 0, total = collections.size))

        // Informational only: annotates the banner, never cancels the sync.
        val slowWatchdog = launch {
            delay(SLOW_SYNC_NOTICE_MS)
            syncState.postValue(
                SyncState.Syncing(done = completed, total = collections.size, slow = true)
            )
        }

        try {
            collections.forEach { collection ->
                try {
                    transactionsRepository.fetchUTXOS(collection.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Timber.e(e, "Failed to sync collection ${collection.id}")
                }
                completed++
                syncState.postValue(
                    SyncState.Syncing(
                        done = completed,
                        total = collections.size,
                        slow = !slowWatchdog.isActive
                    )
                )
                updateBalance()
            }

            updateBalance()

            syncState.postValue(
                when {
                    failed == collections.size -> SyncState.Failed(
                        "Could not reach the server.",
                        retryable = true
                    )
                    failed > 0 -> SyncState.Failed(
                        "$failed of ${collections.size} collections failed to sync.",
                        retryable = true
                    )
                    else -> SyncState.Success(System.currentTimeMillis())
                }
            )
            resolved = true
        } finally {
            Timber.i("sync round end: syncCollections")
            slowWatchdog.cancel()
            // Guaranteed to run, so the UI can never be left mid-sync.
            SentinelState.hasAppJustStarted = false
            if (!resolved) {
                syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
            }
        }
    }

    /**
     * Trailing re-run with id-diff discipline: the finished round already
     * synced exactly these collections if the id sets match, so its own
     * emissions are discarded. Only a genuinely changed list re-arms (#47).
     */
    private fun maybeTrailingRerun() {
        // launchSyncRound applies the signature gate; an unchanged list is
        // skipped there (#47).
        repository.collectionsLiveData.value?.let { launchSyncRound(it) }
    }

    fun getBalance(): LiveData<Long> {
        return balance
    }

    /**
     * Derives the toolbar traffic-light from Tor state + sync state.
     *
     * GREEN only when Tor is connected AND the last sync succeeded.
     */
    fun connectionIndicator(): LiveData<ConnectionIndicator> {
        val mediator = MediatorLiveData<ConnectionIndicator>()

        fun recompute() {
            val torRequired = SentinelState.isTorRequired()
            val torState = SentinelTorManager.getTorState().state
            val sync = syncState.value ?: SyncState.Idle

            mediator.value = when {
                sync is SyncState.Failed -> ConnectionIndicator.RED
                torRequired && torState == EnumTorState.OFF -> ConnectionIndicator.RED
                torRequired && torState == EnumTorState.STARTING -> ConnectionIndicator.YELLOW
                sync is SyncState.WaitingForTor -> ConnectionIndicator.YELLOW
                sync is SyncState.Syncing -> ConnectionIndicator.YELLOW
                // Tor up (or not needed) and the last sync completed cleanly.
                sync is SyncState.Success -> ConnectionIndicator.GREEN
                else -> ConnectionIndicator.NEUTRAL
            }
        }

        mediator.addSource(syncState) { recompute() }
        if (SentinelState.isTorRequired()) {
            mediator.addSource(SentinelTorManager.getTorStateLiveData()) { recompute() }
        }
        return mediator
    }

    fun fetchBalance(userInitiated: Boolean = false) {
        if (prefsUtil.apiEndPoint == null) {
            syncState.postValue(
                SyncState.Failed("No server configured.", retryable = false)
            )
            return
        }

        if (userInitiated) {
            if (netWorkJobs.isNotEmpty()) {
                netWorkJobs.forEach { it?.cancel() }
                netWorkJobs.clear()
            }

            // A human refresh REPLACES any in-flight round (#47).
            syncRound?.cancel()
            syncRound = null
            syncRerunPending = false
            autoRetryBudget = 0
        } else if (syncRound?.isActive == true) {
            // Auto triggers never stack or replace: the in-flight round is
            // already fetching this data. Robust to any number of auto
            // trigger sources (#47).
            Timber.i("sync round skipped: auto fetchBalance while round in flight")
            return
        }

        val collections = ArrayList(repository.pubKeyCollections)

        // MUST be Dispatchers.IO: fetchFromServer writes to Room.
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            Timber.i("sync round begin: fetchBalance userInitiated=$userInitiated collections=${collections.size}")
            try {
                exchangeRateRepository.fetch()
            } catch (e: Exception) {
                Timber.e(e)
            }

            if (collections.isEmpty()) {
                syncState.postValue(SyncState.Success(System.currentTimeMillis()))
                return@launch
            }

            lastRoundSignature = collectionSignature(collections)
            var completed = 0
            var failed = 0
            var resolved = false
            syncState.postValue(SyncState.Syncing(done = 0, total = collections.size))

            // Informational only - no timeout, since Tor can legitimately be slow.
            val slowWatchdog = launch {
                delay(SLOW_SYNC_NOTICE_MS)
                syncState.postValue(
                    SyncState.Syncing(done = completed, total = collections.size, slow = true)
                )
            }

            try {
                collections.forEach { collection ->
                    try {
                        transactionsRepository.fetchFromServer(collection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        Timber.e(e)
                    }
                    completed++
                    syncState.postValue(
                        SyncState.Syncing(
                            done = completed,
                            total = collections.size,
                            slow = !slowWatchdog.isActive
                        )
                    )
                }

                try {
                    feeRepository.getDynamicFees()
                } catch (e: Exception) {
                    Timber.e(e)
                }

                updateBalance()

                if (failed == collections.size) {
                    if (!userInitiated && autoRetryBudget < AUTO_SYNC_RETRIES) {
                        // Tor state ON does not mean the SOCKS circuit is
                        // usable yet. Stay in Syncing (no Failed flash) and
                        // retry shortly instead of churning rounds (#47).
                        autoRetryBudget++
                        autoRetryPending = true
                        Timber.i(
                            "sync auto-retry armed: $autoRetryBudget/$AUTO_SYNC_RETRIES"
                        )
                    } else {
                        syncState.postValue(
                            SyncState.Failed("Could not reach the server.", retryable = true)
                        )
                    }
                } else {
                    autoRetryBudget = 0
                    prefsUtil.lastSynced = System.currentTimeMillis()
                    syncState.postValue(SyncState.Success(System.currentTimeMillis()))
                }
                resolved = true
            } finally {
                if (syncRound === self) {
                    syncRound = null
                    if (autoRetryPending) {
                        autoRetryPending = false
                        syncRerunPending = false
                        // Give the circuit a moment; the retry re-fetches
                        // everything, so a pending re-run is subsumed.
                        delay(AUTO_RETRY_DELAY_MS)
                        Timber.i("sync round end: fetchBalance, auto-retry relaunching")
                        fetchBalance(userInitiated = false)
                    } else if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    } else {
                        Timber.i("sync round end: fetchBalance")
                    }
                } else {
                    Timber.i("sync round end: fetchBalance superseded, cleanup skipped")
                }
                slowWatchdog.cancel()
                // postValue() is async, so syncState.value cannot be trusted here.
                if (!resolved) {
                    syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
                }
            }
        }
    }

    fun getErrorMessage(): LiveData<String> {
        return errorMessage
    }

    fun getFiatBalance(): LiveData<String> {

        val mediatorLiveData = MediatorLiveData<String>()

        mediatorLiveData.addSource(exchangeRateRepository.getRateLive()) {
            mediatorLiveData.value = getFiatBalance(balance.value, it)
        }
        mediatorLiveData.addSource(balance) {
            mediatorLiveData.value = getFiatBalance(it, exchangeRateRepository.getRateLive().value)
        }
        return mediatorLiveData
    }

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate == null) return "00.00"
        balance ?: return "00.00"
        return try {
            val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                .format((balance / 1e8) * rate.rate)
            "$fiatRate ${rate.currency}"
        } catch (e: Exception) {
            "00.00 ${rate.currency}"
        }
    }


    fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }


    private fun updateBalance() {
        val collections = repository.collectionsLiveData.value ?: return

        try {
            val pubkeysList: MutableList<String> = mutableListOf()
            collections.forEach { collection ->
                collection.pubs.forEach { pubkey ->
                    pubkeysList.add(pubkey.pubKey)
                }
            }

            val blockedUtxos = UtxoMetaUtil.getBlockedAssociatedWithPubKeyList(pubkeysList)

            // sumOf is empty-safe; reduce() threw UnsupportedOperationException
            // on an empty collection list.
            val totalAmount = collections.sumOf { it.balance }
            val totalBlocked = blockedUtxos.sumOf { it.amount }

            balance.postValue(totalAmount - totalBlocked)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}
