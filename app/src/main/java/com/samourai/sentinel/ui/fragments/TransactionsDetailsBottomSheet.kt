package com.samourai.sentinel.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.db.dao.TxEntropyDao
import com.samourai.sentinel.data.entropy.ENTROPY_FEATURE_ENABLED
import com.samourai.sentinel.data.entropy.EXTERNAL_ANALYSIS_URL_PREFIX
import com.samourai.sentinel.data.entropy.EntropyBand
import com.samourai.sentinel.data.entropy.EntropyBands
import com.samourai.sentinel.data.txfacts.MempoolTxAdapter
import com.samourai.sentinel.data.txfacts.TxFactsClient
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.databinding.ContentTransactionsDetailsBinding
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.InputBottomSheet
import com.samourai.sentinel.ui.webview.ExplorerWebViewActivity
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * sentinel-android
 *
 * @author Sarath
 *
 */


class TransactionsDetailsBottomSheet(private var tx: Tx, val secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    data class TxFeeData(val fee: Long?, val feeRate: Long?, val size: Long?)

    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)
    private val txEntropyDao: TxEntropyDao by inject(TxEntropyDao::class.java)
    var job: Job? = null
    private var liveFactsJob: Job? = null

    private var currentLabel: String? = null

    private var _binding: ContentTransactionsDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = ContentTransactionsDetailsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.txDetailsOpenInExplorerBtn.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        binding.txDetailsOpenInExplorerBtn2.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        setTx(tx)
        fetchFee()

        // Boltzmann entropy row (issue #7): reads the ingest-time cache
        // by bare txid (same expression as the label row — the two can
        // never disagree about which tx this is). States are engine
        // truth only: honest numbers, "too complex to analyze" (engine
        // declined), zero entropy (nbCmbn = 1). No cached row (flag
        // off / not yet computed) hides the row — never a guess. Secure
        // mode (street mode) hides the entropy row too.
        // #105: kill switch. While ENTROPY_FEATURE_ENABLED is false the
        // row is GONE unconditionally - no DAO read on sheet open.
        if (secure || !ENTROPY_FEATURE_ENABLED) {
            binding.txDetailsEntropyRow.visibility = View.GONE
        } else {
            // #118: the row itself is the tap target (ripple + trailing
            // external-link icon live in the layout); ONE listener for
            // all three row classes - the #117-shipped advisory path,
            // unchanged.
            binding.txDetailsEntropyRow.setOnClickListener {
                showExternalAnalysisAdvisory()
            }
            apiScope.launch {
                val entry = txEntropyDao.findByTxid(tx.hash.split("-")[0])
                withContext(Dispatchers.Main) {
                    if (entry == null) {
                        binding.txDetailsEntropyRow.visibility = View.GONE
                        return@withContext
                    }
                    when {
                        entry.tooComplex -> {
                            // #115 grey band: no verdict - track only,
                            // nothing filled. Red no longer means
                            // "declined"; refusal plus a pointer.
                            binding.txDetailsEntropy.text = "too complex · analyze externally"
                            binding.txDetailsEntropy.alpha = 0.5f
                            binding.txDetailsEntropyBar.setDeclined()
                        }
                        entry.nbCmbn == 1 -> {
                            // Zero entropy: red band, 1 rung (nbCmbn=1
                            // <= 2 per the ladder).
                            binding.txDetailsEntropy.text = "0 bits · 1 interpretation"
                            binding.txDetailsEntropyBar.setState(EntropyBand.RED, 1)
                        }
                        else -> {
                            // #115 ladder: color = tier (red <=2,
                            // amber 3-4, green >=5 = the Stonewall
                            // pin), fill = min(nbCmbn, 5) rungs.
                            binding.txDetailsEntropy.text =
                                "%.2f bits · %d interpretations"
                                    .format(entry.entropyBits, entry.nbCmbn)
                            binding.txDetailsEntropyBar.setState(
                                EntropyBands.band(entry.nbCmbn),
                                EntropyBands.filled(entry.nbCmbn),
                            )
                        }
                    }
                }
            }
        }

        // #119 Route A: live tx-facts row. Destination resolves per
        // tap - the payload-declared Dojo explorer rung first
        // (captured at pairing, see DojoUtil), public mempool.space
        // over Tor second. No auto-fetch: third-party disclosure
        // happens only on user action, matching this sheet's advisory
        // asymmetry.
        binding.txDetailsLiveDestination.text = liveDestinationLabel()
        binding.txDetailsLiveRow.setOnClickListener { onLiveLookupTap() }

        // Label row: shows the label, or a dimmed "Add label" prompt.
        // Click edits (blank save = remove, BIP329 semantics), long-press
        // copies. The list and sheet re-render themselves via Room
        // LiveData when the write lands.
        labelRepository.observeTxLabel(tx.hash.split("-")[0]).observe(viewLifecycleOwner) { entry ->
            currentLabel = entry?.label
            if (entry != null) {
                binding.txDetailsLabel.text = entry.label
                binding.txDetailsLabel.alpha = 1f
            } else {
                binding.txDetailsLabel.text = "Add label"
                binding.txDetailsLabel.alpha = 0.5f
            }
        }
        binding.txDetailsLabel.setOnClickListener { showLabelEditor() }
        binding.txDetailsLabel.setOnLongClickListener {
            copyToClipBoard(binding.txDetailsLabel)
            true
        }

        binding.txDetailsBlockId.setOnClickListener { copyToClipBoard(binding.txDetailsBlockId) }
        binding.txDetailsConfirmation.setOnClickListener { copyToClipBoard(binding.txDetailsConfirmation) }
        binding.txDetailsFees.setOnClickListener { copyToClipBoard(binding.txDetailsFees) }
        binding.txDetailsHash.setOnClickListener { copyToClipBoard(binding.txDetailsHash) }
        binding.txDetailsFeeRate.setOnClickListener { copyToClipBoard(binding.txDetailsFeeRate) }
        binding.txDetailsAmount.setOnClickListener { copyToClipBoard(binding.txDetailsAmount) }
    }

    /**
     * #107 tier 1 / #116: consent gate before the only exit from
     * the app's Tor-only path - now on every entropy row (declined
     * or honest). Wording per #114: names am-i.exposed, states it
     * is backend-less (static page; the txid rides the link
     * fragment, never sent to a server), names the page's own
     * mempool.space fetch as the privacy cost (that server sees
     * the txid and the timing, not the IP - Tor-routed webview),
     * and names self-hosting as the escape hatch.
     */
    private fun showExternalAnalysisAdvisory() {
        val txid = tx.hash.split("-")[0]
        // #111: the Continue tap can land after the sheet (and
        // this fragment) is dismissed - requireContext() in the button
        // lambda crashed at :210 (device leg 1 of #110). Capture the
        // host context at dialog build (attachment proven here - the
        // builder needs it); the captured context outlives the detach
        // and the confirmed intent still launches.
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("External privacy analysis")
            .setMessage(
                "This opens the public am-i.exposed website - a static " +
                    "page with no backend; it sees nothing, and the txid " +
                    "stays in the link fragment, which is never sent to " +
                    "a server. To analyze the transaction, the page " +
                    "fetches it from mempool.space in your browser: " +
                    "mempool.space sees the txid and the timing, but " +
                    "not your IP - the webview routes over Tor. " +
                    "Self-hosters can run the tool beside their own " +
                    "mempool instance instead."
            )
            .setPositiveButton("Continue") { _, _ ->
                SentinelState.selectedTx = tx
                ctx.startActivity(
                    Intent(ctx, ExplorerWebViewActivity::class.java)
                        .putExtra(
                            ExplorerWebViewActivity.EXTRA_URL,
                            EXTERNAL_ANALYSIS_URL_PREFIX + txid,
                        )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLabelEditor() {
        val txid = tx.hash.split("-")[0]
        val editor = InputBottomSheet("Transaction label", onViewReady = { bottomSheet ->
            val view = bottomSheet.view
            val input = view?.findViewById<TextInputEditText>(R.id.bottomSheetInputField)
            // alertWithInput's default maxLen (34) is address-length; labels
            // need more room.
            input?.filters = arrayOf(InputFilter.LengthFilter(64))
            input?.setText(currentLabel ?: "")
            view?.findViewById<MaterialButton>(R.id.bottomSheetConfirmPositiveBtn)?.apply {
                text = "Save"
                setOnClickListener {
                    val text = input?.text?.toString().orEmpty()
                    // Blank save deletes the record (BIP329: empty = remove)
                    apiScope.launch { labelRepository.setTxLabel(txid, text) }
                    bottomSheet.dismiss()
                }
            }
        })
        editor.isCancelable = true
        editor.show(childFragmentManager, editor.tag)
    }

    private fun liveDestinationLabel(): String =
        when (TxFactsResolver.classify(prefsUtil.dojoExplorerUrl)) {
            TxFactsResolver.Destination.SELF -> "Dojo explorer"
            TxFactsResolver.Destination.PUBLIC -> "mempool.space \u00b7 Tor"
        }

    private fun onLiveLookupTap() {
        if (SentinelTorManager.getTorState().state != EnumTorState.ON ||
            SentinelTorManager.getProxy() == null
        ) {
            Toast.makeText(requireContext(), "Enable Tor first", Toast.LENGTH_SHORT).show()
            return
        }
        val resolved = TxFactsResolver.factsUrl(
            tx.hash.split("-")[0], prefsUtil.dojoExplorerUrl,
            SentinelState.isTestNet(),
        )
        when (resolved.destination) {
            TxFactsResolver.Destination.SELF -> fetchLiveFacts(resolved.url, fromSelf = true)
            TxFactsResolver.Destination.PUBLIC -> showMempoolAdvisory {
                fetchLiveFacts(resolved.url, fromSelf = false)
            }
        }
    }

    /**
     * #119 Route A / #114 grammar: consent gate for the public rung.
     * Names the destination, what it learns (txid + timing), what it
     * never sees (the IP - the fetch rides the app's Tor circuit),
     * that the facts render in-sheet with no page opening, and the
     * escape hatch (pair a Dojo that declares its own explorer).
     */
    private fun showMempoolAdvisory(onContinue: () -> Unit) {
        val ctx = requireContext() // #111: survive dismiss-then-continue
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Live facts via mempool.space")
            .setMessage(
                "Sentinel will fetch this transaction's current state " +
                    "from mempool.space - not your Dojo, which serves no " +
                    "mempool data - over the app's Tor circuit. " +
                    "mempool.space sees the txid and the timing, but not " +
                    "your IP. The facts render here in the sheet; no web " +
                    "page opens. Pairing a Dojo that declares its own " +
                    "explorer avoids this lookup."
            )
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * User-directed failure handling for the self rung - no silent
     * auto-fallback. Retry stays offered even on "incompatible": an
     * operator reconfiguring the Dojo's explorer mid-session makes a
     * later retry genuinely succeed.
     */
    private fun showSelfExplorerFailure(incompatible: Boolean, detail: String) {
        val ctx = requireContext() // #111: survive dismiss-then-continue
        val message = if (incompatible)
            "Your Dojo's explorer answered, but not in a compatible API " +
                "format ($detail)."
        else
            "Your Dojo's explorer didn't respond ($detail)."
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Live lookup failed")
            .setMessage(message)
            .setPositiveButton("Retry") { _, _ ->
                val resolved = TxFactsResolver.factsUrl(
                    tx.hash.split("-")[0], prefsUtil.dojoExplorerUrl,
                    SentinelState.isTestNet(),
                )
                if (resolved.destination == TxFactsResolver.Destination.SELF)
                    fetchLiveFacts(resolved.url, fromSelf = true)
                else
                    showMempoolAdvisory { fetchLiveFacts(resolved.url, fromSelf = false) }
            }
            .setNeutralButton("Use mempool.space") { _, _ ->
                showMempoolAdvisory {
                    val resolved = TxFactsResolver.factsUrl(
                        tx.hash.split("-")[0], null, SentinelState.isTestNet(),
                    )
                    fetchLiveFacts(resolved.url, fromSelf = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchLiveFacts(url: String, fromSelf: Boolean) {
        binding.txDetailsLiveDestination.text = "Fetching\u2026"
        liveFactsJob?.cancel()
        liveFactsJob = apiScope.launch {
            val result = TxFactsClient.fetch(
                url, fromSelf, SentinelState.blockHeight?.height,
            )
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.txDetailsLiveDestination.text = liveDestinationLabel()
                when (result) {
                    is TxFactsClient.FetchResult.Ok -> setLiveFacts(result.facts)
                    is TxFactsClient.FetchResult.Unreachable ->
                        if (fromSelf) showSelfExplorerFailure(false, result.detail)
                        else Toast.makeText(
                            requireContext(), "Live lookup failed: ${result.detail}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    is TxFactsClient.FetchResult.Incompatible ->
                        if (fromSelf) showSelfExplorerFailure(true, result.detail)
                        else Toast.makeText(
                            requireContext(), "Live lookup failed: ${result.detail}",
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    private fun setLiveFacts(f: MempoolTxAdapter.LiveFacts) {
        // Live values overwrite the Dojo-synced rows only on success.
        binding.txDetailsFees.text = f.fee.toString()
        binding.txDetailsFeeRate.text = f.feeRate.toString()
        binding.txDetailsSize.text = f.size.toString()
        binding.txDetailsConfirmation.text =
            if (f.confirmed) f.confirmations.toString() else "in mempool"
        binding.txDetailsFeesProgress.visibility = View.GONE
        binding.txDetailsFeesRateProgress.visibility = View.GONE
    }

    private fun setTx(tx: Tx) {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        fmt.timeZone = TimeZone.getDefault()
        binding.txDetailsAmount.text = "${MonetaryUtil.getInstance().formatToBtc(tx.result)} BTC | ${getFiatBalance(tx.result, exchangeRateRepository.getRateLive().value)} "
        binding.txDetailsBlockId.text = "${tx.block_height ?: "__"}"
        var latestBlockHeight = 1L
        if (SentinelState.blockHeight != null)
            latestBlockHeight = SentinelState.blockHeight?.height!!
        val txBlockHeight = tx.block_height ?: 0
        binding.txDetailsConfirmation.text = tx.confirmations.toString() ?: "0"
        if (tx.result != null)
            binding.txDetailsTime.text = "${fmt.format(Date(tx.time * 1000))} "
        binding.txDetailsHash.text = tx.hash.split("-")[0]

    }


    private fun setFeeDetails(txFeeData: TxFeeData) {
        binding.txDetailsFeesProgress.visibility = View.GONE
        binding.txDetailsFeesRateProgress.visibility = View.GONE
        binding.txDetailsFees.text = txFeeData.fee.toString()
        binding.txDetailsFeeRate.text = txFeeData.feeRate.toString()
        binding.txDetailsSize.text = txFeeData.size.toString()
    }

    private fun fetchFee() {
        job = apiScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getTx(tx.hash.split("-")[0])
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val jsonObject = JSONObject(body)
                        var fee = 0L
                        var feeRate = 0L
                        var size = 0L
                        if (jsonObject.has("fees")) {
                            fee = jsonObject.getLong("fees")
                        }
                        if (jsonObject.has("vfeerate")) {
                            feeRate = jsonObject.getLong("vfeerate")
                        }
                        if (jsonObject.has("size")) {
                            size = jsonObject.getLong("size")
                        }
                        val txFeeData = TxFeeData(fee, feeRate, size)
                        withContext(Dispatchers.Main) {
                            setFeeDetails(txFeeData)
                        }
                    }
                }
            } catch (_: Exception) {
            } catch (e: ApiNotConfigured) {
                Timber.e(e)
            }
        }
        job?.invokeOnCompletion {
            if (it != null)
                CoroutineScope(Dispatchers.Main).launch {
                    if (!(it is CancellationException))
                        Toast.makeText(requireContext(), "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate != null) {
            balance?.let {
                return try {
                    val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                            .format((balance / 1e8) * rate.rate)
                    "$fiatRate ${rate.currency}"
                } catch (e: Exception) {
                    "00.00 ${rate.currency}"
                }
            }
            return "00.00"
        } else {
            return "00.00"
        }
    }


    private fun copyToClipBoard(txtView: TextView) {
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", (txtView).text)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        job?.let {
            if (it.isActive) it.cancel()
        }
        liveFactsJob?.let {
            if (it.isActive) it.cancel()
        }
        super.onDestroy()
    }


}
