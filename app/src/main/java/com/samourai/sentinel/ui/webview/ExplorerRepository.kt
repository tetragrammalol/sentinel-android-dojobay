package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.koin.java.KoinJavaComponent.inject

/**
 * The explorer every in-app lookup goes through. Reads the pairing
 * and the saved choice, migrates a legacy selection on the way, and
 * delegates the decision to [ExplorerBinding].
 */
class ExplorerRepository {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    /**
     * Last health check for the bound explorer. Lives here, not on
     * the Network screen, so leaving and coming back paints the same
     * result instead of flashing amber. Not stored in prefs: a new
     * process checks again.
     */
    @Volatile
    private var probeRecord: ExplorerProbeMemory.Record? = null

    fun rememberedProbe(): ExplorerProbeMemory.Record? = probeRecord

    fun rememberProbe(
        key: String,
        outcome: ExplorerProbeOutcome,
        atMillis: Long,
        socksGeneration: Int,
        socksEndpoint: String,
    ) {
        if (outcome == ExplorerProbeOutcome.PENDING || key.isBlank()) return
        probeRecord = ExplorerProbeMemory.Record(
            key,
            outcome,
            atMillis,
            socksGeneration,
            socksEndpoint,
        )
    }

    /** Drops a result from a Tor session that is no longer the live one. */
    fun forgetProbe() {
        probeRecord = null
    }

    fun facts(): ExplorerBinding.ExplorerFacts = ExplorerBinding.ExplorerFacts(
        savedSelection = prefsUtil.selectedExplorer,
        customUrl = prefsUtil.customExplorerUrl,
        publishedUrl = prefsUtil.dojoExplorerUrl,
        appIsTestnet = prefsUtil.testnet == true,
        dojoDeclaredNetwork = prefsUtil.dojoDisplayNetwork,
        dojoApiUrl = prefsUtil.apiEndPoint,
    )

    /** Rewrites a legacy saved choice, then resolves it. */
    fun resolution(): ExplorerBinding.ExplorerResolution {
        val current = facts()
        val migrated = ExplorerBinding.migratedSelection(current.savedSelection, current.customUrl)
        if (migrated != current.savedSelection) {
            prefsUtil.selectedExplorer = migrated
        }
        return ExplorerBinding.resolve(facts())
    }

    fun pageUrl(page: ExplorerBinding.ExplorerPage, id: String): String? =
        ExplorerBinding.pageUrl(resolution(), page, id)

    /**
     * Label for the paired row in Settings. The truncated published
     * URL when that explorer would actually be used; null when the
     * row should say there is no paired explorer.
     */
    fun pairedChoiceLabel(): String? {
        val paired = ExplorerBinding.resolve(facts().copy(savedSelection = ExplorerBinding.CHOICE_PAIRED))
        val bound = paired as? ExplorerBinding.ExplorerResolution.Bound ?: return null
        if (bound.kind != ExplorerBinding.ExplorerKind.PAIRED) return null
        return ExplorerBinding.truncateExplorerLabel(bound.displayUrl)
    }

    companion object {
        const val CUSTOM_EXPLORER_NAME = ExplorerBinding.CHOICE_CUSTOM
        const val TXID_PLACEHOLDER = ExplorerBinding.TXID_PLACEHOLDER

        /** External analysis site. Its page fetches from the public explorer. */
        const val AM_I_EXPOSED_NAME = "am-i.exposed"
        const val AM_I_EXPOSED_TX_PREFIX = "https://$AM_I_EXPOSED_NAME/#?tx="

        /** Visible name of the explicit third-party explorer choice. */
        const val MEMPOOL_CHOICE = TxFactsResolver.MEMPOOL_DISPLAY_NAME
    }
}
