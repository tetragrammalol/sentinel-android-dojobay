package com.samourai.sentinel.ui.views

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R

/**
 * #355: non-cancelable progress overlay for the Argon2id waits.
 *
 * PIN unlock, backup encrypt and backup decrypt each run the KDF for
 * seconds while the screen otherwise reads as dead. This is the waiting
 * room, not the crypto: the KDF parameters and every derive site stay
 * untouched.
 *
 * The overlay is held by its host and dies with the host's scope -
 * never a retained fragment (the #220 rule, in reverse: a restored
 * overlay above a dead job is a lie). Show and dismiss are called on
 * the main thread; the waits themselves stay on Dispatchers.IO.
 */
class KdfProgressOverlay(private val activity: Activity) {

    private var dialog: Dialog? = null

    /**
     * Shows the overlay with [label]. A no-op when the host is already
     * going away or the overlay is already up.
     */
    fun show(label: String) {
        if (dialog != null) return
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_kdf_progress, null)
        view.findViewById<TextView>(R.id.kdfProgressLabel).text = label
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .show()
    }

    /**
     * Dismisses the overlay if it is up. Safe on every exit path and
     * safe to call twice; a teardown race with the host window is
     * swallowed, not propagated.
     */
    fun dismiss() {
        val d = dialog ?: return
        dialog = null
        try {
            if (d.isShowing) {
                d.dismiss()
            }
        } catch (e: IllegalArgumentException) {
            // Host window already gone - nothing left to clean up.
        }
    }
}
