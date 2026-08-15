package com.samourai.sentinel.ui.dojo

import com.google.gson.annotations.SerializedName
import com.samourai.sentinel.helpers.toJSON

/**
 * Response shape of the Dojo Bay community directory
 * (http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/dojos.json).
 */
data class CommunityDojoDirectory(
    @SerializedName("generated_at")
    val generatedAt: String? = null,
    @SerializedName("nodes")
    val nodes: List<CommunityDojoNode>? = null
)

data class CommunityDojoNode(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("network")
    val network: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("jurisdiction")
    val jurisdiction: String? = null,
    @SerializedName("country")
    val country: String? = null,
    @SerializedName("version")
    val version: String? = null,
    @SerializedName("checked_at")
    val checkedAt: String? = null,
    @SerializedName("payload")
    val payload: CommunityDojoPayload? = null
) {
    val isOnline: Boolean
        get() = status.equals("active", ignoreCase = true)

    /**
     * The pairing payload as the exact `{"pairing":{...}}` JSON string that
     * [DojoUtility.validate] and the rest of the existing pairing flow expect,
     * built from the same [Pairing] model used for manual/QR pairing.
     */
    fun pairingPayloadJson(): String? {
        val pairing = payload?.pairing ?: return null
        return DojoPairing(pairing = pairing).toJSON()
    }
}

data class CommunityDojoPayload(
    @SerializedName("pairing")
    val pairing: Pairing? = null
)
