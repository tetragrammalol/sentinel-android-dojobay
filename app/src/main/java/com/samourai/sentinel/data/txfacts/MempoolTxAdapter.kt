package com.samourai.sentinel.data.txfacts

import org.json.JSONObject

/**
 * #119 Route A: /tx facts JSON -> LiveFacts, for the sheet's live-lookup
 * render. Accepts both observed dialects:
 *  - mempool.space /api/tx: fee, status{confirmed, block_height},
 *    size, vsize; confirmations derived against the caller-supplied
 *    tip height (SentinelState.blockHeight).
 *  - dojo/esplora /tx?fees=1 (the #72 on-device census shape): fees,
 *    vfeerate, size, confirmations, block.
 *
 * Tolerance: non-JSON, missing txid, or nothing fee-shaped maps to
 * null - that is the "incompatible" failure class, surfaced honestly
 * by the self-rung failure dialog.
 */
object MempoolTxAdapter {

    data class LiveFacts(
        val fee: Long,
        val feeRate: Long, // sat/vbyte
        val size: Long,
        val vsize: Long,
        val confirmations: Long,
        val blockHeight: Long?,
        val confirmed: Boolean,
    )

    fun fromJson(body: String, tipHeight: Long?): LiveFacts? {
        return try {
            val root = JSONObject(body)
            if (root.optString("txid", "").isEmpty()) return null
            val size = root.optLong("size", 0L)
            val vsize = if (root.has("vsize")) root.optLong("vsize") else size
            var fee = -1L
            var feeRate = -1L
            var confirmations = -1L
            var blockHeight: Long? = null
            var confirmed = false
            if (root.has("fee")) {
                // mempool.space dialect
                fee = root.optLong("fee")
                val st = root.optJSONObject("status")
                confirmed = st?.optBoolean("confirmed", false) ?: false
                blockHeight =
                    if (st != null && st.has("block_height")) st.optLong("block_height")
                    else null
                val bh = blockHeight
                val tip = tipHeight
                confirmations =
                    if (confirmed && bh != null && tip != null && tip > 0) tip - bh + 1
                    else 0L
            } else if (root.has("fees")) {
                // dojo/esplora dialect
                fee = root.optLong("fees")
                if (root.has("vfeerate")) feeRate = root.optLong("vfeerate")
                if (root.has("confirmations")) confirmations = root.optLong("confirmations")
                confirmed = confirmations > 0
                if (root.has("block") && !root.isNull("block")) blockHeight = root.optLong("block")
            } else {
                return null
            }
            if (feeRate < 0 && vsize > 0) feeRate = fee / vsize
            if (fee < 0) return null
            LiveFacts(fee, feeRate, size, vsize, confirmations, blockHeight, confirmed)
        } catch (e: Exception) {
            null
        }
    }
}
