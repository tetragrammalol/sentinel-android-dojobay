package com.samourai.sentinel.ui.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import org.koin.java.KoinJavaComponent.get

/**
 * Opens a tx or address on the bound explorer. A third-party page
 * (the explicit mempool choice, or the external analysis site) is
 * shown only after the one advisory. An unbound lookup stays on
 * this screen until the user asks.
 */
class ExplorerOpener(
    private val context: Context,
    private val repository: ExplorerRepository = get(ExplorerRepository::class.java),
    private val launch: (url: String, title: String?) -> Unit = { url, title ->
        startExplorer(context, url, title)
    },
) {

    fun open(page: ExplorerBinding.ExplorerPage, id: String, offerAnalysis: Boolean = false) {
        val clean = id.trim()
        if (clean.isEmpty() || !canShowDialog()) return
        when (val resolution = repository.resolution()) {
            is ExplorerBinding.ExplorerResolution.Unbound ->
                showUnbound(page, clean, offerAnalysis)
            is ExplorerBinding.ExplorerResolution.Bound ->
                openBound(resolution, page, clean)
        }
    }

    /** Explicit ask for the external analysis site. Always advisory-gated. */
    fun openExternalAnalysis(txid: String) {
        val clean = txid.trim()
        if (clean.isEmpty() || !canShowDialog()) return
        confirmThirdParty(ExplorerRepository.AM_I_EXPOSED_NAME) {
            launch(ExplorerRepository.AM_I_EXPOSED_TX_PREFIX + clean, clean)
        }
    }

    /**
     * [party] is the site this confirmation will open. The IP sentence
     * follows [currentLoadDecision], the same gate the WebView uses.
     */
    fun confirmThirdParty(
        party: String = TxFactsResolver.MEMPOOL_DISPLAY_NAME,
        onContinue: () -> Unit,
    ) {
        if (!canShowDialog()) return
        val message = ExplorerAdvisory.message(
            party = party,
            decision = currentLoadDecision(),
            base = context.getString(R.string.explorer_third_party_base),
            tor = context.getString(R.string.explorer_third_party_tor),
            direct = context.getString(R.string.explorer_third_party_direct),
            unsent = context.getString(R.string.explorer_third_party_unsent),
            guidance = context.getString(R.string.explorer_third_party_guidance),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.explorer_third_party_title)
            .setMessage(message)
            .setPositiveButton(R.string.explorer_third_party_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.explorer_close, null)
            .show()
    }

    private fun currentLoadDecision(): ExplorerLoadDecision = explorerLoadDecision(
        proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
        torReady = SentinelTorManager.isTorReady(),
        torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF,
        torRequired = SentinelState.isTorRequired(),
    )

    private fun openBound(
        resolution: ExplorerBinding.ExplorerResolution.Bound,
        page: ExplorerBinding.ExplorerPage,
        id: String,
    ) {
        val url = ExplorerBinding.pageUrl(resolution, page, id) ?: return
        if (resolution.kind == ExplorerBinding.ExplorerKind.MEMPOOL) {
            confirmThirdParty { launch(url, id) }
        } else {
            launch(url, id)
        }
    }

    private fun showUnbound(
        page: ExplorerBinding.ExplorerPage,
        id: String,
        offerAnalysis: Boolean,
    ) {
        val mempool = ExplorerBinding.resolve(
            repository.facts().copy(savedSelection = TxFactsResolver.MEMPOOL_DISPLAY_NAME)
        )
        val mempoolUrl = ExplorerBinding.pageUrl(mempool, page, id)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.explorer_unbound_title)
            .setMessage(R.string.explorer_unbound_message)
            .setNegativeButton(R.string.explorer_close, null)
        if (mempoolUrl != null) {
            builder.setPositiveButton(
                context.getString(
                    R.string.explorer_use_third_party,
                    TxFactsResolver.MEMPOOL_DISPLAY_NAME,
                )
            ) { _, _ ->
                confirmThirdParty { launch(mempoolUrl, id) }
            }
        }
        if (offerAnalysis && page == ExplorerBinding.ExplorerPage.TX) {
            builder.setNeutralButton(
                context.getString(
                    R.string.explorer_analyze_externally,
                    ExplorerRepository.AM_I_EXPOSED_NAME,
                )
            ) { _, _ ->
                openExternalAnalysis(id)
            }
        }
        builder.show()
    }

    private fun canShowDialog(): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    companion object {
        fun startExplorer(context: Context, url: String, title: String?) {
            val intent = Intent(context, ExplorerWebViewActivity::class.java)
                .putExtra(ExplorerWebViewActivity.EXTRA_URL, url)
            if (!title.isNullOrBlank()) {
                intent.putExtra(ExplorerWebViewActivity.EXTRA_TITLE, title)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
