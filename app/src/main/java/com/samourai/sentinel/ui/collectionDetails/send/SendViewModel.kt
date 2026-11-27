package com.samourai.sentinel.ui.collectionDetails.send

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.wallet.psbt.PSBT
import org.bouncycastle.util.encoders.Hex
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.BalanceDisplayFormatter
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.repository.LabelRepository
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.collections.set

class SendViewModel : ViewModel() {

    private val utxos: ArrayList<Utxo> = arrayListOf()
    private val selectedUtxos: ArrayList<Utxo> = arrayListOf()
    private var address: String = ""
    private var amount: Double = 0.0
    private var enteredAmount: Double = 0.0
    var job: Job? = null
    private var selectedFee = 0L

    /**
     * #635: the send screen's destination. External is the typed or
     * pasted string (the only kind before #635); Own is one of our own
     * collections with a guaranteed-fresh receive address, verified by
     * re-derivation before any compose.
     */
    @Volatile
    private var destination: Destination = Destination.External("")
    private var ownDestinationJob: Job? = null
    private var destinationGeneration = 0

    /**
     * #77: set while the custom fee field holds no usable rate. Composing
     * must stop then: the last good rate is still in FeeUtil, and building
     * a PSBT with it would not be the fee the user is looking at.
     */
    private var feeInvalid = false
    private var transactionComposer = TransactionComposer()
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private val txDao: TxDao by inject(TxDao::class.java)
    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)
    /**
     * #643: the miner fee readout follows the wallet's display selection;
     * street mode masks it like every other amount on the send screen.
     */
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    /**
     * #644: the in-flight write from the last compose tap's label,
     * if any. [buildTxDiagram] joins it before reading labels, so a
     * diagram named on the same tap never races its own write.
     */
    private var labelWriteJob: Job? = null

    private var receivers: HashMap<String, BigInteger> = hashMapOf()

    private val _psbt = MutableLiveData("")
    val psbtLive: LiveData<String> = _psbt

    /**
     * #635: the pre-QR transaction diagram for the reviewed snapshot.
     * Null when there is no review, or the snapshot did not parse
     * into one.
     */
    private val _txDiagram = MutableLiveData<TxDiagram?>(null)
    internal val txDiagram: LiveData<TxDiagram?> = _txDiagram

    /**
     * #635: bumped on every diagram build and every clear, so a build
     * for a superseded snapshot never paints over a newer one - the
     * same generation discipline as [spendGeneration].
     */
    private var diagramGeneration = 0

    /**
     * #635: the fresh-destination verdict for the screen to say out
     * loud - confirmed or refused. Null once consumed.
     */
    private val _destinationNotice = MutableLiveData<Int?>(null)
    val destinationNotice: LiveData<Int?> = _destinationNotice

    /**
     * #646 leg 4: the index the confirmed walk landed on - the notice's
     * number. Null with any other verdict, so the screen only names it
     * on a confirmed one.
     */
    private var destinationWalkedIndex: Int? = null

    /** #646 leg 4: the index the last confirmed walk landed on, or null. */
    fun destinationWalkIndex(): Int? = destinationWalkedIndex

    /**
     * #566: the PSBT the user reviewed, captured once at [makeTx].
     * QR, text, and both file exports read this. None of them re-read
     * the composer, which [prepareSpend] rebuilds on resume and on
     * every coin-set update.
     */
    private var reviewedPsbt: ReviewedPsbt? = null

    /**
     * #566: why [makeTx] refused to open the review. Consumed by the
     * screen so the message is shown once.
     */
    private var snapshotError: PsbtSnapshotError? = null

    /**
     * #566: a reviewed input was spent or frozen. The value is the
     * string the screen shows as it closes the review. Null when the
     * review still holds.
     */
    private val _freshReview = MutableLiveData<Int?>(null)
    val freshReview: LiveData<Int?> = _freshReview

    private val _minerFee = MutableLiveData("")
    val minerFee: LiveData<String> = _minerFee

    private val _validSpend = MutableLiveData(false)
    val validSpend: LiveData<Boolean> = _validSpend

    /**
     * #669: true while selection validation (the compose) runs.
     * Opened where the compose window opens, closed on every verdict:
     * a refusal through [block], a landed verdict on the publish,
     * a dead job in the outer catch. Back out of the screen cancels
     * the job outright, so the flag lives and dies with the screen.
     */
    private val _composing = MutableLiveData(false)
    val composing: LiveData<Boolean> = _composing

    /**
     * #294: why composing is not possible, so the screen can say so.
     *
     * validSpend on its own only greys the button out. Every reason was
     * computed here and then discarded, leaving the user to guess whether the
     * address was wrong, on the wrong network, or simply more than they hold.
     * Published separately so existing observers of validSpend are unaffected.
     */
    enum class SpendBlocker {
        NONE,
        NO_ADDRESS,
        INVALID_ADDRESS,
        WRONG_NETWORK,
        NO_AMOUNT,
        NO_FUNDS,
        INSUFFICIENT_FUNDS,
        CANNOT_COMPOSE,
        /** #190: the pinned coins do not cover the amount plus the fee. */
        SELECTION_INSUFFICIENT,
        /** #190: a pinned coin has been marked do-not-spend since it was pinned. */
        SELECTION_BLOCKED,
        /** #190: a pinned coin is no longer unspent (spent, or gone after a sync). */
        SELECTION_MISSING,
        /**
         * #77: the custom fee field holds no usable rate (empty, not a
         * number, or out of range). The field says which.
         */
        INVALID_FEE,
        /**
         * #635: an own-collection destination whose fresh address has
         * not been re-derived and matched (walk exhausted, derivation
         * mismatch, or a walk still in flight).
         */
        DESTINATION_UNVERIFIED
    }

    /** #566: [makeTx] did not take a snapshot. */
    enum class PsbtSnapshotError {
        /** A compose is still running, or the previous one is no longer valid. */
        REBUILD_IN_FLIGHT,
        /** The composer held no bytes, or they do not parse as a PSBT. */
        UNUSABLE,
    }

    private val _spendBlocker = MutableLiveData(SpendBlocker.NO_ADDRESS)
    val spendBlocker: LiveData<SpendBlocker> = _spendBlocker

    /**
     * Set while at least one XPUB in this account failed its last sync.
     * Null when every XPUB here synced, so the screen keeps its own figure.
     */
    private val _selectableSpendable = MutableLiveData<Long?>(null)
    val selectableSpendable: LiveData<Long?> = _selectableSpendable

    private val _unsyncedXpubNote = MutableLiveData<String?>(null)
    val unsyncedXpubNote: LiveData<String?> = _unsyncedXpubNote

    private var lastRawUtxos: List<Utxo> = emptyList()

    private fun block(reason: SpendBlocker): Boolean {
        _spendBlocker.postValue(reason)
        _validSpend.postValue(false)
        // #294: a figure computed from input we have just rejected is worse
        // than none - the fee kept reading 0.00000850 BTC next to a disabled
        // button on an amount the wallet could not cover.
        if (reason != SpendBlocker.NONE) {
            _minerFee.postValue("")
        }
        // #669: a refusal is a verdict - the compose window is over.
        _composing.postValue(false)
        return false
    }

    /**
     * #190: the outpoints ("txid:vout") the user pinned in the coin picker.
     * Empty means automatic selection, which stays the default. Cleared when
     * the account being spent from changes, since a pin is only meaningful
     * against that account's coins.
     */
    private val _pinnedOutpoints = MutableLiveData<Set<String>>(emptySet())
    val pinnedOutpoints: LiveData<Set<String>> = _pinnedOutpoints

    /** #190: the utxoDao LiveData currently observed, so a switch can detach it. */
    private var utxoSource: LiveData<List<Utxo>>? = null

    /** #190: the candidate pool the picker lists - the current account's coins. */
    private val _utxoPool = MutableLiveData<List<Utxo>>(emptyList())
    val utxoPool: LiveData<List<Utxo>> = _utxoPool

    /**
     * #190: total of the pinned coins, or null while selection is automatic
     * (or the pin no longer resolves). The send screen shows it in place of
     * the account's spendable balance, and "spend all" spends it.
     */
    private val _pinnedTotal = MutableLiveData<Long?>(null)
    val pinnedTotal: LiveData<Long?> = _pinnedTotal

    /**
     * #190: pins exactly [outpoints] as the input set. Any key that does not
     * resolve to a spendable coin in the current pool is refused here, before
     * it can reach compose; the picker already disables do-not-spend rows,
     * so a refusal means the pool moved under it.
     *
     * @return false (nothing changed) if the selection was refused.
     */
    fun setPinnedSelection(outpoints: Set<String>): Boolean {
        val resolution = CoinSelection.resolve(outpoints, utxos) { UtxoMetaUtil.has(it) }
        if (resolution is CoinSelection.Resolution.Blocked ||
            resolution is CoinSelection.Resolution.Missing
        ) {
            return false
        }
        _pinnedOutpoints.value = outpoints.toSet()
        prepareSpend()
        return true
    }

    /** #190: back to automatic selection. */
    fun clearPinnedSelection() {
        if (_pinnedOutpoints.value.isNullOrEmpty()) return
        _pinnedOutpoints.value = emptySet()
        prepareSpend()
    }

    /** Spendable total, excluding anything marked do-not-spend. */
    private fun spendableSats(): Long =
        utxos.filter { !UtxoMetaUtil.has(it) }.sumOf { it.value ?: 0L }


    init {
        //Listen for the miner fee change from TransactionComposer
        viewModelScope.launch {
            for (value in transactionComposer.getMinerFee()) {
                withContext(Dispatchers.Main) {
                    // #643: the fee follows the wallet's display selection
                    // through the one formatter door - street mode masks it
                    // with the rest of the screen.
                    _minerFee.postValue(
                        BalanceDisplayFormatter.formatForDisplay(
                            value, prefsUtil.balanceDisplayMode, prefsUtil.streetMode == true
                        )
                    )
                }
            }
        }
    }

    fun setPublicKey(
        pubKeyModel: PubKeyModel,
        lifecycleOwner: LifecycleOwner,
        mCollection: PubKeyCollection
    ) {
        // #566: a review belongs to the account it was composed against.
        clearReviewedPsbt()
        // #635: a destination belongs to the account it was selected
        // against - an in-flight walk for another collection is dead.
        ownDestinationJob?.cancel()
        destinationGeneration++
        destination = Destination.External("")
        // #190: a pin belongs to the account it was made against.
        _pinnedOutpoints.value = emptySet()
        _pinnedTotal.value = null
        // #190: each account switch used to add another observer and leave
        // the previous account's attached, so its next emission replaced the
        // pool with the wrong account's coins - which the picker now lists.
        utxoSource?.removeObserver(utxoObserver)
        val source: LiveData<List<Utxo>>
        if (mCollection.isImportFromWallet && pubKeyModel.label.equals("Deposit")) {
            transactionComposer.setPubKey(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection),
                getPubKeyModelByLabel("Deposit BIP49", mCollection),
                getPubKeyModelByLabel("Deposit BIP44", mCollection)
            ))
            source = utxoDao.getUTXObyPubKeys(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP49", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP44", mCollection).pubKey
            ))
        } else {
            transactionComposer.setPubKey(listOf(pubKeyModel))
            source = utxoDao.getUtxoWithPubKey(pubKeyModel.pubKey)
        }
        utxoSource = source
        source.observe(lifecycleOwner, utxoObserver)
    }

    private fun getPubKeyModelByLabel(label: String, mCollection: PubKeyCollection): PubKeyModel {
        mCollection.pubs?.forEach {
            if (it.label == label)
                return it
        }
        return mCollection.pubs[0]
    }

    /**
     * #190: bumped on every prepareSpend (main thread). A compose job only
     * publishes its verdict while it is still the latest one, so a slower
     * compose for an older input set can neither re-enable the button nor
     * overwrite a newer blocker. A cancelled job used to land in the
     * Exception wall below and flash CANNOT_COMPOSE for the same reason.
     */
    private var spendGeneration = 0

    private fun prepareSpend(): Boolean {
        selectedUtxos.clear()
        val generation = ++spendGeneration

        // #190: resolve the pin against the pool as it is now. Done before
        // the address/amount checks so the picker's total stays current
        // while the user is still typing.
        val selection = CoinSelection.resolve(
            _pinnedOutpoints.value ?: emptySet(),
            utxos
        ) { UtxoMetaUtil.has(it) }
        _pinnedTotal.postValue((selection as? CoinSelection.Resolution.Pinned)?.totalSats)

        if (address.isBlank() || address.isEmpty()) {
            return block(SpendBlocker.NO_ADDRESS)
        }
        // #294: the address was previously only judged inside compose(), which
        // reports one undifferentiated failure. Check it here so the three
        // cases the user can actually act on are told apart.
        if (!FormatsUtil.isValidBitcoinAddress(address)) {
            return block(SpendBlocker.INVALID_ADDRESS)
        }
        if (isWrongNetworkAddress(address)) {
            return block(SpendBlocker.WRONG_NETWORK)
        }

        val dAmount: Double = enteredAmount
        amount = (dAmount * 1e8)

        if (dAmount == 0.0) {
            return block(SpendBlocker.NO_AMOUNT)
        }

        if (utxos.isEmpty()) {
            return block(SpendBlocker.NO_FUNDS)
        }
        if (amount <= 0.0) {
            return block(SpendBlocker.NO_AMOUNT)
        }
        // Checked before composing so "more than you hold" is distinguishable
        // from a compose that failed for any other reason. The fee is not
        // known yet, so this does not catch an amount that only fails once the
        // fee is added; compose() still reports that as CANNOT_COMPOSE.
        if (amount.toLong() > spendableSats()) {
            return block(SpendBlocker.INSUFFICIENT_FUNDS)
        }
        if (feeInvalid) {
            return block(SpendBlocker.INVALID_FEE)
        }
        // #635: an own-collection destination composes only once its
        // fresh address has been re-derived and matched byte for byte.
        // Anything less refuses here, loudly - never a silent fallback
        // to the raw string.
        val ownDestination = destination as? Destination.Own
        if (ownDestination != null && !ownDestination.verified) {
            return block(SpendBlocker.DESTINATION_UNVERIFIED)
        }
        // #190: an explicit selection that no longer holds, or cannot cover
        // the amount, is reported - never composed around. A coin frozen
        // after it was pinned is the hard do-not-spend strip; it fails the
        // selection rather than silently composing the remainder.
        val explicitInputs: List<Utxo>? = when (selection) {
            is CoinSelection.Resolution.Auto -> null
            is CoinSelection.Resolution.Blocked -> return block(SpendBlocker.SELECTION_BLOCKED)
            is CoinSelection.Resolution.Missing -> return block(SpendBlocker.SELECTION_MISSING)
            is CoinSelection.Resolution.Pinned -> {
                if (amount.toLong() > selection.totalSats) {
                    return block(SpendBlocker.SELECTION_INSUFFICIENT)
                }
                selection.utxos
            }
        }
        job?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        // #190: the PSBT held by the composer belongs to the previous input
        // set until this compose finishes; makeTx must not hand it out.
        _validSpend.value = false
        // #669: the compose window is open - let the screen say so.
        _composing.value = true
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                receivers = HashMap()
                receivers[address] = BigInteger.valueOf(amount.toLong())
                try {
                    val isValid = transactionComposer.compose(
                        inputAddress = address,
                        inputAmount = amount,
                        inputFee = selectedFee,
                        inputUtxos = ArrayList<Utxo>(
                            utxosSelectableForSend(explicitInputs ?: utxos)
                                .filter { utxo -> !UtxoMetaUtil.has(utxo) }
                        ),
                        explicitSelection = explicitInputs != null
                    )
                    withContext(Dispatchers.Main) {
                        if (generation != spendGeneration) return@withContext
                        _validSpend.postValue(isValid)
                        // #669: the verdict landed - close the window.
                        _composing.postValue(false)
                        _spendBlocker.postValue(
                            if (isValid) SpendBlocker.NONE else SpendBlocker.CANNOT_COMPOSE
                        )
                    }
                } catch (e: TransactionComposer.InsufficientSelectionException) {
                    // #190: the pinned coins cover the amount but not the fee.
                    Timber.i(e.message)
                    viewModelScope.launch(Dispatchers.Main) {
                        if (generation == spendGeneration) block(SpendBlocker.SELECTION_INSUFFICIENT)
                    }
                } catch (e: com.samourai.sentinel.api.ApiService.ApiNotConfigured) {
                    // #125: compose() reaches getTxHex (:351/:375) -> buildClient ->
                    // getAPIUrl, throwing this on an unpaired install. Throwable, so the
                    // Exception wall below is blind and viewModelScope has no CEH: the
                    // throw would have escaped the compose job from the send screen. Same
                    // degrade as :132-136. FQN deliberate: this file has no ApiService
                    // import; simple-name cleanup may follow.
                    Timber.e(e, "Compose aborted: no API endpoint configured")
                    viewModelScope.launch(Dispatchers.Main) {
                        if (generation == spendGeneration) block(SpendBlocker.CANNOT_COMPOSE)
                    }
                } catch (e: Exception) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (generation == spendGeneration) block(SpendBlocker.CANNOT_COMPOSE)
                    }
                    Timber.e(e, "Compose failed")
                }
            } catch (ex: TransactionComposer.ComposeException) {
                // viewModelScope has no handler. Logging leaves the job
                // failed-closed: _validSpend is already false.
                Timber.e(ex, "unable to compose")
                // #669: no verdict lands from a dead job - close the
                // window so the note cannot hang.
                _composing.postValue(false)
            }
        }
        job?.invokeOnCompletion {
            if (it != null) {
                Timber.e("send-job %s", it.javaClass.simpleName)
                viewModelScope.launch(Dispatchers.Main) {
//                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        return true
    }

    /**
     * #294: a bech32 address for the other network.
     *
     * isValidBitcoinAddress() decodes bech32 without comparing the human
     * readable part to the active network, so a mainnet bc1 address passes it
     * on testnet and then fails inside compose() with no explanation. Base58
     * addresses are already rejected by that function, since it builds an
     * Address against the active NetworkParameters.
     */
    internal fun isWrongNetworkAddress(address: String): Boolean {
        val hrp = address.lowercase().take(2)
        if (hrp != "bc" && hrp != "tb") return false
        return if (SentinelState.isTestNet()) hrp == "bc" else hrp == "tb"
    }

    fun setAmount(amount: Double) {
        enteredAmount = amount
        prepareSpend()
    }

    private val utxoObserver: Observer<List<Utxo>> = Observer { raw ->
        applySelectableUtxos(raw)
    }

    /** Re-apply the hide after a sync that did not rewrite the UTXO rows. */
    fun refreshUnsyncedPubs() {
        if (lastRawUtxos.isEmpty()) return
        applySelectableUtxos(lastRawUtxos)
    }

    private fun applySelectableUtxos(raw: List<Utxo>) {
        lastRawUtxos = raw
        val hidden = hiddenXpubCount(raw)
        _unsyncedXpubNote.value = unsyncedXpubNote(hidden)
        val visible = utxosSelectableForSend(raw)
        _selectableSpendable.value = if (hidden == 0) {
            null
        } else {
            visible.filter { !UtxoMetaUtil.has(it) }.sumOf { it.value ?: 0L }
        }
        utxos.clear()
        utxos.addAll(visible)
        _utxoPool.value = visible
        // #566: resume and every coin-set write come through here.
        // Close the review only when a reviewed input itself is gone
        // or frozen. A rebuild of the composer is not that.
        noteReviewedInputs(raw) { UtxoMetaUtil.has(it) }
        if (utxos.isEmpty()) {
            block(SpendBlocker.NO_FUNDS)
        } else {
            prepareSpend()
        }
    }

    fun setDestinationAddress(enteredAddress: String) {
        this.address = enteredAddress
        // #635: a typed or pasted address is an external destination -
        // any pending own-collection walk is superseded.
        ownDestinationJob?.cancel()
        destinationGeneration++
        destination = Destination.External(enteredAddress)
        prepareSpend()
    }

    /**
     * #644: the label typed beside To, written at the Compose tap.
     * External typed destinations only - a fresh own-collection
     * walk address must stay unmarked, so it still counts as unused
     * when handed out later. A blank label writes nothing: a
     * compose is never a delete.
     */
    fun setDestinationLabel(label: String) {
        val external = (destination as? Destination.External)?.address ?: ""
        if (label.isBlank() || external.isBlank()) return
        labelWriteJob = viewModelScope.launch {
            labelRepository.setAddrLabel(external, label.trim())
        }
    }

    fun setFee(fee: Float) {
        Timber.i("setFee: $fee")
        val decFormat = NumberFormat.getInstance(Locale.US)
        decFormat.maximumFractionDigits = 3
        decFormat.minimumFractionDigits = 0
        var customValue = 0.0
        customValue = try {
            fee.toDouble()
        } catch (e: java.lang.Exception) {
            return
        }
        val suggestedFee = SuggestedFee()
        suggestedFee.isStressed = false
        suggestedFee.isOK = true
        suggestedFee.defaultPerKB = BigInteger.valueOf((customValue).toLong())
        FeeUtil.getInstance().suggestedFee = suggestedFee
        selectedFee = fee.toLong()
        feeInvalid = false
        prepareSpend()
    }

    /**
     * #77: the custom fee field holds no usable rate; block composing until
     * [setFee] is called with one.
     */
    fun setFeeInvalid() {
        if (feeInvalid) return
        feeInvalid = true
        prepareSpend()
    }

    /**
     * #566: take the snapshot the review screen will show and export.
     *
     * Called from the Compose tap, on the main thread, so [_psbt] is
     * published with [MutableLiveData.setValue] before the screen
     * opens. Refuses while a compose is still running and when the
     * bytes are missing or do not parse: [PSBT.toBytes] swallows a
     * serialization failure and returns null, and [PSBT.toString]
     * would then crash. Nothing is published in that case.
     */
    fun makeTx(): Boolean {
        snapshotError = null
        if (job?.isActive == true || _validSpend.value != true) {
            snapshotError = PsbtSnapshotError.REBUILD_IN_FLIGHT
            return false
        }
        val params = SentinelState.getNetworkParam()
        val live = transactionComposer.getPSBT()
        val raw = try {
            live?.toBytes()
        } catch (t: Throwable) {
            Timber.e(t, "PSBT bytes were not usable")
            null
        }
        if (params == null || PsbtFileExport.refusal(raw, params) != null || raw == null) {
            snapshotError = PsbtSnapshotError.UNUSABLE
            return false
        }
        val parsed = try {
            PSBT.fromBytes(raw, params)
        } catch (t: Throwable) {
            Timber.e(t, "PSBT bytes did not parse")
            snapshotError = PsbtSnapshotError.UNUSABLE
            return false
        }
        val inputs = parsed.transaction?.inputs.orEmpty()
        if (!parsed.isParseOK || inputs.isEmpty()) {
            snapshotError = PsbtSnapshotError.UNUSABLE
            return false
        }
        // Same "txid:vout" key the coin picker stores, taken from the
        // parsed bytes so a later edit of the live PSBT cannot move them.
        val outpoints = try {
            inputs.map { input ->
                val outpoint = input.outpoint ?: return@map null
                "${outpoint.hash}:${outpoint.index}"
            }.filterNotNull().toSet()
        } catch (t: Throwable) {
            Timber.e(t, "PSBT inputs were not usable")
            snapshotError = PsbtSnapshotError.UNUSABLE
            return false
        }
        if (outpoints.size != inputs.size) {
            snapshotError = PsbtSnapshotError.UNUSABLE
            return false
        }
        reviewedPsbt = ReviewedPsbt(raw.copyOf(), outpoints)
        _psbt.value = Hex.toHexString(raw)
        return true
    }

    /**
     * #635: build the pre-QR diagram for the reviewed snapshot.
     *
     * The snapshot is walked here - inputs and outputs as plain data -
     * and the pure builder in TxDiagram.kt classifies it. Input values
     * come from the pool the snapshot was composed from, captured on
     * the main thread next to the makeTx that verified it; a reviewed
     * input missing from the pool closes the review (#566) before this
     * runs. A snapshot that no longer parses, or a wallet with no
     * network parameters, clears the diagram and says so in the log -
     * never a partial one.
     */
    fun buildTxDiagram() {
        val snapshot = reviewedPsbt
        if (snapshot == null) {
            _txDiagram.value = null
            return
        }
        val params = SentinelState.getNetworkParam()
        if (params == null) {
            Timber.e("tx diagram: no network parameters")
            diagramGeneration++
            _txDiagram.value = null
            return
        }
        val generation = ++diagramGeneration
        val destination = address
        val pool = lastRawUtxos.associate {
            "${it.txHash}:${it.txOutputN}" to (it.value ?: 0L)
        }
        viewModelScope.launch(Dispatchers.Default) {
            // #644: the label write fired at this compose tap joins
            // here, so the label read in diagramFor never races it.
            labelWriteJob?.join()
            val diagram = try {
                val parsed = PSBT.fromBytes(snapshot.bytes.copyOf(), params)
                val tx = parsed.transaction
                    ?: throw IllegalStateException("snapshot held no unsigned tx")
                val inputs = tx.inputs.map { input ->
                    val key = "${input.outpoint.hash}:${input.outpoint.index}"
                    TxDiagramInput(outpoint = key, sats = pool[key] ?: 0L)
                }
                val outputs = tx.outputs.map { output ->
                    TxDiagramOutput(script = output.scriptBytes, sats = output.value.value)
                }
                diagramFor(
                    inputs = inputs,
                    outputs = outputs,
                    destinationAddress = destination,
                    params = params,
                    coinLabels = labelRepository.getAll().associate {
                        "${it.txid}:${it.vout}" to it.label
                    },
                    addressLabels = labelRepository.addrLabels(),
                )
            } catch (t: Throwable) {
                Timber.e(t, "tx diagram build failed")
                null
            }
            withContext(Dispatchers.Main) {
                if (generation != diagramGeneration) return@withContext
                _txDiagram.value = diagram
            }
        }
    }

    /** #566: the bytes [makeTx] captured. Null when there is no review. */
    fun getPsbtBytes(): ByteArray? = reviewedPsbt?.bytes?.copyOf()

    /** #566: why the last [makeTx] returned false. Null after it is read. */
    fun takeSnapshotError(): PsbtSnapshotError? =
        snapshotError.also { snapshotError = null }

    /**
     * #566: directory that holds the per-share cache files. Set by the
     * screen. [clearReviewedPsbt] deletes them.
     */
    private var shareCacheDir: File? = null

    fun bindShareCache(dir: File) {
        shareCacheDir = dir
    }

    /**
     * #566: drop the reviewed PSBT. The screen calls this when the
     * review closes; [setPublicKey] calls it when the account changes.
     * Each share file in [shareCacheDir] is deleted with it.
     */
    fun clearReviewedPsbt() {
        reviewedPsbt = null
        snapshotError = null
        _psbt.value = ""
        diagramGeneration++
        _txDiagram.value = null
        shareCacheDir?.let { PsbtShareFiles.deleteAll(it) }
    }

    fun consumeFreshReview() {
        _freshReview.value = null
    }

    /** The outpoints [makeTx] froze into the snapshot. Empty when there is no review. */
    internal fun reviewedOutpoints(): Set<String> = reviewedPsbt?.outpoints ?: emptySet()

    /**
     * #566: a reviewed input spent elsewhere is absent from [raw]; a
     * reviewed input frozen since the review is [isBlocked]. Either
     * one closes the review. Any other coin arriving, leaving, or
     * being frozen does not.
     *
     * @return the fresh-review string, or null when the review still holds.
     */
    internal fun noteReviewedInputs(
        raw: List<Utxo>,
        isBlocked: (Utxo) -> Boolean,
    ): Int? {
        val reviewed = reviewedPsbt ?: return null
        val resolution = CoinSelection.resolve(reviewed.outpoints, raw, isBlocked)
        if (resolution is CoinSelection.Resolution.Missing ||
            resolution is CoinSelection.Resolution.Blocked
        ) {
            clearReviewedPsbt()
            _freshReview.value = R.string.send_review_inputs_changed
            return R.string.send_review_inputs_changed
        }
        return null
    }

    /**
     * #566: a do-not-spend mark does not rewrite the UTXO rows, so the
     * table observer never runs. The screen calls this when the marks
     * change. No reviewed snapshot, or no coin set yet, is a no-op:
     * an empty pool must not look like every input was spent.
     */
    fun recheckReviewedInputs(): Boolean {
        val raw = lastRawUtxos
        if (raw.isEmpty() || reviewedPsbt == null) return true
        // #635: null back means the review still holds. False means
        // noteReviewedInputs closed the review and posted its toast
        // (#566) - the Broadcast door aborts on it.
        return noteReviewedInputs(raw) { UtxoMetaUtil.has(it) } == null
    }

    /**
     * #635: destination becomes one of our own collections. Walks that
     * pub's receive chain to the first unused address - unused meaning
     * no output in the synced history pays it and no addr label marks
     * it as handed out - then re-derives the same index from the
     * wallet's own keys and requires a byte-for-byte match before the
     * destination is verified. A walk that exhausts, a derivation that
     * comes back null, or a mismatch all refuse the destination
     * loudly; none of them ever fall back to a raw string.
     *
     * Generation-guarded like prepareSpend: a walk for a superseded
     * selection (account switch, or a typed address arriving) never
     * publishes.
     */
    fun setOwnDestination(collectionId: String, pub: PubKeyModel) {
        ownDestinationJob?.cancel()
        val generation = ++destinationGeneration
        // #654: classify the pub by its OWN xpub serialization before
        // anything walks - the walk (firstFresh/derivedReceiveAddress)
        // derives on the ACTIVE network parameters, so a cross-network
        // key derives an address that is valid on the wrong network
        // and belongs to no wallet the recipient tracks
        // (DestinationNetworkGuard.kt). The WRONG_NETWORK blocker cannot
        // catch this class: it compares the DERIVED address's hrp
        // against the active network, which the derivation already
        // used, so the derived address always looks right. Refused
        // here the walk never launches, so no index is consumed
        // (#646 leg 4) - and any future caller of this door inherits
        // the refusal.
        val activeIsTestnet = SentinelState.isTestNet()
        if (pubIsCrossNetwork(pub, activeIsTestnet)) {
            destination = Destination.External("")
            address = ""
            destinationWalkedIndex = null
            // #654 QA: the refusal names both networks - the single
            // generic sentence ran past the system toast's width and
            // was cut on device.
            _destinationNotice.value =
                if (activeIsTestnet) R.string.send_destination_cross_network_testnet
                else R.string.send_destination_cross_network_mainnet
            prepareSpend()
            return
        }
        ownDestinationJob = viewModelScope.launch(Dispatchers.Default) {
            val historyAddresses = txDao.getAllForPub(collectionId, pub.pubKey)
                .flatMap { tx -> tx.out }
                .mapNotNull { it.addr }
                .toSet()
            val labelled = labelRepository.labelledAddrRefs()
            val walk = firstFresh(
                start = pub.account_index,
                ceiling = RECEIVE_WALK_CEILING,
                usedAddresses = historyAddresses,
                isLabelled = { it in labelled },
                derive = { index -> derivedReceiveAddress(pub.pubKey, pub.type, index) },
            )
            val rederived = (walk as? FreshWalk.Found)
                ?.let { derivedReceiveAddress(pub.pubKey, pub.type, it.index) }
            val verified = walk is FreshWalk.Found && rederived == walk.address
            withContext(Dispatchers.Main) {
                if (generation != destinationGeneration) return@withContext
                when {
                    verified -> {
                        val found = walk as FreshWalk.Found
                        destination = Destination.Own(collectionId, pub.pubKey, found.address, true)
                        address = found.address
                        destinationWalkedIndex = found.index
                        _destinationNotice.value = R.string.send_destination_fresh_confirmed
                    }
                    walk is FreshWalk.Found -> {
                        destinationWalkedIndex = null
                        // The walk found an address the re-derivation does
                        // not reproduce. Refuse - never hand out an
                        // unmatched pair.
                        destination = Destination.Own(collectionId, pub.pubKey, walk.address, false)
                        _destinationNotice.value = R.string.send_destination_unverified
                    }
                    else -> {
                        destinationWalkedIndex = null
                        destination = Destination.External("")
                        // #635: the walk refused - the stale typed address must not
                        // survive as the compose destination behind a cleared field.
                        address = ""
                        _destinationNotice.value = R.string.send_destination_none_fresh
                    }
                }
                prepareSpend()
            }
        }
    }

    /** #635: the notice is shown once; consuming clears it. */
    fun consumeDestinationNotice() {
        _destinationNotice.value = null
    }

    /**
     * #635: the address the destination resolves to right now - the
     * fresh receive address for an own destination, the typed string
     * for an external one, null when the field should stay clear. The
     * screen's To field reads it when the verdict notice lands.
     */
    fun destinationAddress(): String? = when (val d = destination) {
        is Destination.Own -> d.freshAddress
        is Destination.External -> d.address.ifBlank { null }
    }

    private class ReviewedPsbt(
        val bytes: ByteArray,
        val outpoints: Set<String>,
    )
}
