package com.samourai.sentinel.ui.settings

import android.app.Activity
import android.content.Intent
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil

/**
 * #208's confirmed network switch, shared by the Network page and the
 * first-run setup recovery sheet (#223) so both switch the same way.
 *
 * Order matters: flag first, then Dojo removal, then Tor stop, then the
 * task relaunch - erase-all's recipe (MainSettingsFragment.clearWallet)
 * minus the nuke. The process survives the relaunch, so the async pref
 * write and the async payload delete both land - no sync-commit dance
 * needed. enableTor is deliberately untouched: SentinelApplication.setUpTor
 * re-starts Tor on the next launch whenever the user's own toggle is on.
 *
 * The caller decides whether the switch is allowed (NetworkSwitch.rowState)
 * and confirms it; [extras] can tell the relaunched Home what to do next.
 */
fun Activity.switchNetworkAndRelaunch(
    prefsUtil: PrefsUtil,
    dojoUtility: DojoUtility,
    targetIsTestnet: Boolean,
    extras: Intent.() -> Unit = {},
) {
    prefsUtil.testnet = targetIsTestnet
    dojoUtility.clearDojo()
    SentinelTorManager.stop()
    startActivity(Intent(applicationContext, HomeActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        extras()
    })
    finish()
}
