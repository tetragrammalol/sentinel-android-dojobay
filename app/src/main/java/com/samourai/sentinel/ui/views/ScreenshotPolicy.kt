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
 * #511: windows that stay secure whatever Disable Screenshots says.
 * The flag belongs on the window that is showing the secret. It is
 * not left on the host activity after that window closes, so a normal
 * screen can still be screenshotted when the setting is off.
 *
 * Always secure:
 * - PIN entry. [LockScreenDialog] adds FLAG_SECURE to the host window
 *   for the whole time the pad is showing, and puts that window back
 *   to the setting when the last pad leaves.
 * - Dojo pairing credentials and the pairing QR. [com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet]
 *   is the surface that renders the pairing payload, the pairing QR,
 *   and the node API key. It calls [keepScreenshotsBlocked] on its own
 *   dialog window for the whole time it is showing.
 *
 * Audited and left on the setting, because they do not render the
 * pairing payload, the pairing QR, or the API key: the Network pairing
 * sheet ([com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet],
 * including its confirmation and receipt dialogs), the community Dojo
 * list, and the public-key and unsigned-transaction QR sheet.
 */
internal fun Window.keepScreenshotsBlocked() {
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

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
