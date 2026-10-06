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
import com.samourai.sentinel.helpers.fromJSON
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
            apiScope.launch {
                val entry = txEntropyDao.findByTxid(tx.hash.split("-")[0])
                withContext(Dispatchers.Main) {
                    if (entry == null) {
                        binding.txDetailsEntropyRow.visibility = View.GONE
                        return@withContext
                    }
                    when {
                        entry.tooComplex -> {
                            // #107 tier 1: an honest refusal plus a pointer,
                            // never a derived number. Tap prompts the privacy
                            // advisory; confirm opens the app's Tor-routed
                            // webview on am-i.exposed with the txid in the
                            // hash fragment (never sent to a server).
                            binding.txDetailsEntropy.text = "too complex · analyze externally"
                            binding.txDetailsEntropy.alpha = 0.5f
                            binding.txDetailsEntropyBar.disable()
                            binding.txDetailsEntropy.setOnClickListener {
                                showExternalAnalysisAdvisory()
                            }
                        }
                        entry.nbCmbn == 1 -> {
                            binding.txDetailsEntropy.text = "0 bits · 1 interpretation"
                            binding.txDetailsEntropyBar.disable()
                        }
                        else -> {
                            binding.txDetailsEntropy.text =
                                "%.2f bits · %d interpretations"
                                    .format(entry.entropyBits, entry.nbCmbn)
                            val bars = entropyBars(entry.linkabilityJson)
                            if (bars == 0) binding.txDetailsEntropyBar.disable()
                            else binding.txDetailsEntropyBar.setRange(bars)
                        }
                    }
                }
            }
        }

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
     * EntropyBar range from the cached linkability matrix: cells at
     * 1.0 are deterministic links (engine output read back, nothing
     * recomputed). Thresholds mirror EntropyBar.setRange(TxProcessor-
     * Result): 0 -> disabled, <=25% -> 1, <=50% -> 2, else 3.
     */
    private fun entropyBars(linkabilityJson: String): Int {
        val matrix = fromJSON<List<List<Double>>>(linkabilityJson) ?: return 3
        if (matrix.isEmpty() || matrix.any { it.isEmpty() }) return 3
        val nbLinks = matrix.size * matrix[0].size
        val nbDtrm = matrix.sumOf { row -> row.count { it > 0.999 } }
        val pct = (100 * (nbLinks - nbDtrm)) / nbLinks
        return when {
            pct <= 0 -> 0
            pct <= 25 -> 1
            pct <= 50 -> 2
            else -> 3
        }
    }

    /**
     * #107 tier 1: consent gate before the only exit from the
     * app's Tor-only path. States the disclosure in the tool's
     * own terms (am-i.exposed README): the analysis page fetches
     * this tx from mempool.space, which sees the txid and the
     * timing; the IP is protected by Tor while the webview
     * proxy is active; self-hosting against your own mempool
     * instance is the escape hatch.
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
                "The analysis page fetches this transaction from " +
                    "mempool.space. Your IP is protected by Tor, but " +
                    "mempool.space sees the txid and the timing. " +
                    "Self-hosters can point the tool at their own " +
                    "mempool instance."
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
        super.onDestroy()
    }


}
