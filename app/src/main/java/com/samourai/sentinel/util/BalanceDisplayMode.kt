package com.samourai.sentinel.util

/**
 * Global preference controlling how balances are rendered. Tapping a
 * balance display cycles through the modes; the choice is persisted via
 * PrefsUtil and therefore included in Sentinel's settings backup.
 *
 * Street mode (Settings) always overrides this to [MASKED].
 */
enum class BalanceDisplayMode {
    BTC,
    SATS,
    MASKED;

    fun next(): BalanceDisplayMode = when (this) {
        BTC -> SATS
        SATS -> MASKED
        MASKED -> BTC
    }

    companion object {
        fun fromString(value: String?): BalanceDisplayMode =
            values().firstOrNull { it.name == value } ?: BTC
    }
}

object BalanceDisplayFormatter {

    const val MASKED_TEXT = "********"

    /**
     * Whether the fiat line under a balance is shown. Fiat follows BTC and
     * sats alike; it is hidden only by street mode, the hide-fiat setting,
     * or the masked display mode.
     */
    fun showsFiat(mode: BalanceDisplayMode, streetMode: Boolean, fiatDisabled: Boolean): Boolean =
        !streetMode && !fiatDisabled && mode != BalanceDisplayMode.MASKED

    /**
     * #643: street mode is the global override, so the send surfaces read
     * their amounts through this one door instead of each caller
     * re-deriving the mode. Masked whenever street mode is on, whatever
     * the display preference says; otherwise the preference decides, and
     * a missing or unknown value falls back to BTC.
     */
    fun formatForDisplay(sats: Long, pref: String?, streetMode: Boolean): String =
        if (streetMode) {
            format(sats, BalanceDisplayMode.MASKED)
        } else {
            format(sats, BalanceDisplayMode.fromString(pref))
        }

    /**
     * Renders a satoshi amount according to [mode]. Callers enforcing street
     * mode should pass [BalanceDisplayMode.MASKED].
     */
    fun format(sats: Long, mode: BalanceDisplayMode): String = when (mode) {
        BalanceDisplayMode.MASKED -> MASKED_TEXT
        BalanceDisplayMode.SATS -> String.format(java.util.Locale.US, "%,d sats", sats)
        BalanceDisplayMode.BTC -> formatBtc(sats)
    }

    /** Same 8-decimal rendering the app already used for BTC amounts. */
    fun formatBtc(sats: Long): String {
        val df = java.text.DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8
        return "${df.format(sats / 1e8)} BTC"
    }
}
