package com.samourai.sentinel.ui.views

import android.view.Window
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

/**
 * #511: Disable Screenshots, applied both ways. On adds FLAG_SECURE.
 * Off clears it. Other window flags are left alone.
 */
internal fun Window.applyScreenshotPolicy(displaySecure: Boolean) {
    if (displaySecure) {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

internal fun Window.isScreenshotBlocked(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

/**
 * A showing PIN pad owns the host window's FLAG_SECURE until it
 * leaves, including when Disable Screenshots is off. Re-applying the
 * setting must not clear the flag out from under the pad.
 */
internal fun FragmentActivity.hasShowingLockScreen(except: LockScreenDialog? = null): Boolean =
    supportFragmentManager.fragments.any { fragment ->
        fragment !== except &&
            fragment is LockScreenDialog &&
            fragment.isAdded &&
            fragment.view != null
    }
