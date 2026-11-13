package com.samourai.sentinel.ui.webview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.samourai.sentinel.R
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.views.GenericBottomSheet
import org.koin.java.KoinJavaComponent.inject

/**
 * Educational note for one explorer option. It never selects and
 * never confirms a load. The onion service shows its full address
 * so the user can check it. That address is not written to a log.
 */
class ExplorerInfoBottomSheet : GenericBottomSheet() {

    private val explorerRepository: ExplorerRepository by inject(ExplorerRepository::class.java)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_explorer_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getString(ARG_ID)
            ?.let { runCatching { ExplorerOptions.Id.valueOf(it) }.getOrNull() }
        if (id == null) {
            dismiss()
            return
        }
        val name = TxFactsResolver.MEMPOOL_DISPLAY_NAME
        view.findViewById<TextView>(R.id.explorerInfoTitle).text = when (id) {
            ExplorerOptions.Id.PAIRED -> getString(R.string.explorer_choice_paired)
            ExplorerOptions.Id.MEMPOOL_ONION -> getString(R.string.explorer_choice_onion)
            ExplorerOptions.Id.MEMPOOL_SPACE -> name
            ExplorerOptions.Id.CUSTOM -> getString(R.string.explorer_choice_custom)
        }
        view.findViewById<TextView>(R.id.explorerInfoBody).text = body(id, name)
        val address = view.findViewById<TextView>(R.id.explorerInfoAddress)
        if (id == ExplorerOptions.Id.MEMPOOL_ONION) {
            address.visibility = View.VISIBLE
            address.text = getString(
                R.string.explorer_info_onion_address,
                TxFactsResolver.MEMPOOL_ONION_HOST,
            )
        } else {
            address.visibility = View.GONE
        }
        view.findViewById<View>(R.id.explorerInfoClose).setOnClickListener { dismiss() }
    }

    private fun body(id: ExplorerOptions.Id, name: String): String {
        val facts = explorerRepository.facts()
        val torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF
        return when (id) {
            ExplorerOptions.Id.PAIRED -> getString(R.string.explorer_info_paired)
            ExplorerOptions.Id.MEMPOOL_ONION -> getString(R.string.explorer_info_onion, name)
            ExplorerOptions.Id.MEMPOOL_SPACE -> if (ExplorerOptions.spaceInfo(facts, torOff) == ExplorerOptions.SpaceInfo.TOR_OFF) {
                getString(R.string.explorer_info_space_off, name)
            } else {
                getString(R.string.explorer_info_space, name)
            }
            ExplorerOptions.Id.CUSTOM -> getString(R.string.explorer_info_custom)
        }
    }

    companion object {
        private const val ARG_ID = "id"

        fun newInstance(id: ExplorerOptions.Id): ExplorerInfoBottomSheet =
            ExplorerInfoBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, id.name) }
            }
    }
}
