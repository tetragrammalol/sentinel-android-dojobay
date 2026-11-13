package com.samourai.sentinel.ui.webview

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.views.GenericBottomSheet
import org.koin.java.KoinJavaComponent.inject

/**
 * Settings > Explorer. Four rows, in order: paired Dojo, the onion
 * service, the clearnet site, Custom. Info opens a sheet and does not
 * select. A row Tor cannot carry yet stays on screen, grey, with the
 * reason, and does not select.
 */
class ExplorerOptionsBottomSheet : GenericBottomSheet() {

    private val explorerRepository: ExplorerRepository by inject(ExplorerRepository::class.java)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_explorer_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<LinearLayout>(R.id.explorerOptionsList)
        val bind = { render(list) }
        bind()
        SentinelTorManager.getTorStateLiveData().observe(viewLifecycleOwner) { bind() }
    }

    private fun render(list: LinearLayout) {
        if (!isAdded) return
        list.removeAllViews()
        val facts = explorerRepository.facts()
        val torReady = SentinelTorManager.isTorReady()
        val torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF
        val selected = ExplorerOptions.idOf(facts.savedSelection, facts.customUrl)
        val name = TxFactsResolver.MEMPOOL_DISPLAY_NAME
        val quiet = 0xFF9A9A9A.toInt()
        val titleColor = ContextCompat.getColor(requireContext(), R.color.white)
        val grey = ContextCompat.getColor(requireContext(), R.color.grey_accent)
        ExplorerOptions.rows(facts, torReady).forEach { row ->
            val item = layoutInflater.inflate(R.layout.item_explorer_option, list, false)
            val icon = item.findViewById<ImageView>(R.id.explorerOptionIcon)
            val title = item.findViewById<TextView>(R.id.explorerOptionTitle)
            val subline = item.findViewById<TextView>(R.id.explorerOptionSubline)
            val detail = item.findViewById<TextView>(R.id.explorerOptionDetail)
            val reason = item.findViewById<TextView>(R.id.explorerOptionReason)
            val check = item.findViewById<ImageView>(R.id.explorerOptionSelected)
            val info = item.findViewById<ImageButton>(R.id.explorerOptionInfo)

            title.text = titleFor(row.id, name)
            title.setTextColor(if (row.selectable) titleColor else grey)
            val sub = sublineFor(row.id, facts, torOff, name)
            if (sub.isNullOrEmpty()) {
                subline.visibility = View.GONE
            } else {
                subline.visibility = View.VISIBLE
                subline.text = sub
                subline.setTextColor(if (row.selectable) quiet else grey)
            }
            if (row.addressLine.isNullOrBlank()) {
                detail.visibility = View.GONE
            } else {
                detail.visibility = View.VISIBLE
                detail.text = row.addressLine
                detail.setTextColor(if (row.selectable) quiet else grey)
            }
            reason.visibility = if (row.needsTor) View.VISIBLE else View.GONE

            val iconRes = when (row.icon) {
                ExplorerOptions.Icon.ONION_PAIRED, ExplorerOptions.Icon.ONION -> R.drawable.ic_tor_onion
                ExplorerOptions.Icon.GLOBE -> R.drawable.ic_globe
            }
            val tint = when {
                !row.selectable -> R.color.grey_accent
                row.icon == ExplorerOptions.Icon.ONION_PAIRED -> R.color.green_ui_2
                else -> R.color.white
            }
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
            info.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_ui_2))
            check.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_ui_2))
            check.visibility = if (row.id == selected) View.VISIBLE else View.GONE

            item.setOnClickListener {
                if (!row.selectable) return@setOnClickListener
                deliver(row.id)
            }
            info.setOnClickListener {
                if (parentFragmentManager.findFragmentByTag(TAG_INFO) != null) return@setOnClickListener
                ExplorerInfoBottomSheet.newInstance(row.id).show(parentFragmentManager, TAG_INFO)
            }
            list.addView(item)
        }
    }

    private fun titleFor(id: ExplorerOptions.Id, name: String): String = when (id) {
        ExplorerOptions.Id.PAIRED -> getString(R.string.explorer_choice_paired)
        ExplorerOptions.Id.MEMPOOL_ONION -> getString(R.string.explorer_choice_onion)
        ExplorerOptions.Id.MEMPOOL_SPACE -> name
        ExplorerOptions.Id.CUSTOM -> getString(R.string.explorer_choice_custom)
    }

    private fun sublineFor(
        id: ExplorerOptions.Id,
        facts: ExplorerBinding.ExplorerFacts,
        torOff: Boolean,
        name: String,
    ): String? = when (id) {
        ExplorerOptions.Id.PAIRED -> getString(R.string.explorer_option_paired_subline)
        ExplorerOptions.Id.MEMPOOL_ONION -> getString(R.string.explorer_option_onion_subline, name)
        ExplorerOptions.Id.MEMPOOL_SPACE -> if (ExplorerOptions.mempoolSpaceTorOff(facts, torOff)) {
            getString(R.string.explorer_option_space_subline_off, name)
        } else {
            getString(R.string.explorer_option_space_subline_tor, name)
        }
        ExplorerOptions.Id.CUSTOM -> null
    }

    private fun deliver(id: ExplorerOptions.Id) {
        val action = when (id) {
            ExplorerOptions.Id.CUSTOM -> ACTION_CUSTOM
            ExplorerOptions.Id.MEMPOOL_ONION, ExplorerOptions.Id.MEMPOOL_SPACE -> ACTION_ADVISE
            ExplorerOptions.Id.PAIRED -> ACTION_SELECT
        }
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(EXTRA_ACTION, action)
                putString(EXTRA_ID, id.name)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "explorer_options"
        const val EXTRA_ACTION = "action"
        const val EXTRA_ID = "id"
        const val ACTION_SELECT = "select"
        const val ACTION_ADVISE = "advise"
        const val ACTION_CUSTOM = "custom"
        private const val TAG_INFO = "explorer_option_info"
    }
}
