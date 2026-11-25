package com.samourai.sentinel.ui.collectionDetails.send

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.receivePathFor
import com.samourai.sentinel.ui.adapters.CollectionRowNetworkState
import com.samourai.sentinel.ui.adapters.collectionRowNetworkState
import org.koin.java.KoinJavaComponent.inject

/**
 * #635: the destination selector - one row per own collection.
 *
 * Shown by [SendFragment] on its childFragmentManager, so it shares the
 * send screen's [SendViewModel]. A tap resolves the collection's receive
 * pub ([resolveDestinationPubIndex] - the deposit key, never a
 * standalone address) and hands it to [SendViewModel.setOwnDestination],
 * which walks it to the first unused receive address and re-derives the
 * same index from the wallet's own keys before anything composes.
 *
 * The sheet decides nothing about freshness: the walk runs in the
 * ViewModel and its verdict lands on the screen's destination notice,
 * said out loud either way. A collection with no eligible pub refuses
 * its row loudly - the sheet never falls back to pubs[0].
 */
class DestinationPickerBottomSheet : GenericBottomSheet() {

    private val viewModel: SendViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)

    private var collections: List<PubKeyCollection> = emptyList()
    private val adapter = CollectionAdapter()

    // #654/#429: grey-out constants, the collections list's values
    // (CollectionsAdapter). The dim rides the row's CONTENT views,
    // never itemView.alpha - #429 device leg 2: a bind-time itemView
    // dim did not survive a data pass under an item animator.
    private val rowAlphaNormal = 1f
    private val rowAlphaGreyed = 0.38f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container, false)
        view.findViewById<TextView>(R.id.dialogTitle).text =
            getString(R.string.send_destination_picker_title)
        val content = inflater.inflate(R.layout.content_bottom_sheet_destination_picker, null)
        view.findViewById<FrameLayout>(R.id.contentContainer).addView(content)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        collections = collectionRepository.collectionsLiveData.value ?: emptyList()
        val empty = view.findViewById<TextView>(R.id.destinationPickerEmpty)
        val list = view.findViewById<RecyclerView>(R.id.destinationPickerList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        collectionRepository.collectionsLiveData.observe(viewLifecycleOwner) {
            collections = it
            empty.visibility = if (collections.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
        empty.visibility = if (collections.isEmpty()) View.VISIBLE else View.GONE
    }

    /** #635: hand the collection's receive pub to the fresh-address walk. */
    private fun confirm(collection: PubKeyCollection) {
        val index = resolveDestinationPubIndex(
            collection.isImportFromWallet,
            collection.pubs.map { it.label },
            collection.pubs.map { it.type },
        )
        if (index == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.send_destination_no_receive_pub),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // #654: classify the pub a tap would walk by its OWN xpub
        // serialization, not by the active network - the walk derives
        // on the active parameters, so a cross-network key derives a
        // valid address on the WRONG network that belongs to no wallet
        // the recipient tracks (DestinationNetworkGuard.kt). The
        // collection-level grey is informative, not a gate: a mixed
        // collection renders Normal while its resolved pub can still
        // be cross-network. Refuse the tap loudly and keep the sheet
        // open - never hand off, never fall back.
        val activeIsTestnet = SentinelState.isTestNet()
        if (pubIsCrossNetwork(collection.pubs[index], activeIsTestnet)) {
            Toast.makeText(
                requireContext(),
                getString(
                    if (activeIsTestnet) R.string.send_destination_cross_network_testnet
                    else R.string.send_destination_cross_network_mainnet
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        viewModel.setOwnDestination(collection.id, collection.pubs[index])
        dismiss()
    }

    /**
     * #654: the greyed badge text - the same template pair the
     * collections list renders (#429 networkBadgeText; that one is
     * private in CollectionsAdapter, so this resolves the same pair
     * here). Reached only in a greyed state, where the collection's
     * network is by definition the opposite of the active one - no
     * re-derivation needed. Network names reuse the #250 labels.
     */
    private fun networkBadgeText(activeIsTestnet: Boolean, paired: Boolean): String {
        val collectionNetwork = getString(
            if (!activeIsTestnet) R.string.network_testnet else R.string.network_mainnet
        )
        val activeNetwork = getString(
            if (activeIsTestnet) R.string.network_testnet else R.string.network_mainnet
        )
        return getString(
            if (paired) R.string.collection_network_badge_paired
            else R.string.collection_network_badge_unpaired,
            collectionNetwork,
            activeNetwork
        )
    }

    private inner class CollectionAdapter : RecyclerView.Adapter<CollectionAdapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.destinationPickerLabel)
            val balance: TextView = itemView.findViewById(R.id.destinationPickerBalance)
            val path: TextView = itemView.findViewById(R.id.destinationPickerPath)
            // #654: the off-network badge line.
            val badge: TextView = itemView.findViewById(R.id.destinationPickerBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_destination_picker, parent, false)
            )

        override fun getItemCount(): Int = collections.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val collection = collections[position]
            holder.label.text = collection.collectionLabel.ifBlank { collection.id }
            holder.balance.text =
                "${MonetaryUtil.getInstance().getBTCDecimalFormat(collection.balance)} BTC"
            // #646 leg 1: the row shows the same path+index line the
            // receive advanced view renders, off the pub a tap would
            // resolve - the two screens are finally comparable from
            // the device. Stored index only (locked with the user):
            // the walk's honest preview stays the verdict notice's
            // job, and no Room read rides a sheet render.
            val pathLine = resolveDestinationPubIndex(
                collection.isImportFromWallet,
                collection.pubs.map { it.label },
                collection.pubs.map { it.type },
            )?.let { idx ->
                receivePathFor(collection.pubs[idx], SentinelState.isTestNet())
            }
            holder.path.text = pathLine.orEmpty()
            holder.path.visibility = if (pathLine == null) View.GONE else View.VISIBLE
            // #654: the collections list's cross-network render
            // (#429), on the picker's rows: grey is informative - the
            // row stays visible and named - but a tap is a spend
            // decision, so confirm() refuses it loudly. The dim rides
            // the CONTENT views, never itemView.alpha (#429), and both
            // arms set the badge unconditionally - a recycled row
            // inherits nothing.
            val activeIsTestnet = SentinelState.isTestNet()
            val rowState = collectionRowNetworkState(
                collection,
                activeIsTestnet = activeIsTestnet,
                dojoEnabled = SentinelState.isDojoEnabled()
            )
            val greyed = rowState !is CollectionRowNetworkState.Normal
            val dim = if (greyed) rowAlphaGreyed else rowAlphaNormal
            holder.label.alpha = dim
            holder.balance.alpha = dim
            holder.path.alpha = dim
            holder.badge.alpha = dim
            if (greyed) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = networkBadgeText(
                    activeIsTestnet,
                    paired = rowState is CollectionRowNetworkState.GreyedPaired
                )
            } else {
                holder.badge.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { confirm(collection) }
        }
    }

    companion object {
        const val TAG = "destination_picker"
    }
}
