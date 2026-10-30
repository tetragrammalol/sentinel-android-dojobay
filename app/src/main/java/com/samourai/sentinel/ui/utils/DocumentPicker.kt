package com.samourai.sentinel.ui.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.samourai.sentinel.R
import timber.log.Timber

/**
 * Launches a Storage Access Framework picker, surviving a device that has
 * none.
 *
 * `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT` are not guaranteed to
 * resolve. A stripped ROM, a managed profile with DocumentsUI disabled, or
 * an emulator image built without it leave nothing to handle the intent, and
 * `startActivityForResult` then throws [ActivityNotFoundException] - which, on
 * an unguarded call, takes the whole process down (#292).
 *
 * The throw is caught rather than pre-empted with `resolveActivity()`: under
 * the package-visibility rules that apply from API 30, `resolveActivity()` can
 * return null for an activity that would in fact have handled the intent, so
 * using it as the gate would refuse the picker on devices that have a perfectly
 * good one. Attempting the launch is the only reliable test.
 */
fun Fragment.startDocumentPickerForResult(intent: Intent, requestCode: Int) {
    try {
        startActivityForResult(intent, requestCode)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No activity handles ${intent.action}")
        // context is null once the fragment is detached; there is no UI left
        // to tell in that case, and the log above is the record.
        context?.let {
            Toast.makeText(it, R.string.no_file_picker_available, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Activity-side twin of [Fragment.startDocumentPickerForResult].
 *
 * The file-import screens are activities, so the fragment extension is
 * not a drop-in (#366). Launching the document intent itself throws
 * [ActivityNotFoundException] when nothing handles it; this catch shows
 * [R.string.no_file_picker_available] instead.
 *
 * Do not wrap the intent in [Intent.createChooser]. The launched action
 * would be `ACTION_CHOOSER`, which the framework resolves to its own
 * chooser whether or not anything handles the wrapped intent, so this
 * catch never runs and a pickerless device gets an empty chooser (#366).
 * Callers launch `ACTION_OPEN_DOCUMENT` itself. Pre-checking with
 * `resolveActivity()` is the wrong gate for the same reason as the
 * fragment overload: under the package-visibility rules from API 30 it
 * can return null for a picker that would have handled the launch.
 */
fun Activity.startDocumentPickerForResult(intent: Intent, requestCode: Int) {
    try {
        startActivityForResult(intent, requestCode)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No activity handles ${intent.action}")
        Toast.makeText(this, R.string.no_file_picker_available, Toast.LENGTH_LONG).show()
    }
}
