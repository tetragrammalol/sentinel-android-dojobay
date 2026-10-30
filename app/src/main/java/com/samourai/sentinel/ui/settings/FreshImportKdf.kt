package com.samourai.sentinel.ui.settings

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * #358: label for the import PIN continuation's Argon2id wait.
 * The same waiting room the other KDF paths use (#355).
 */
internal const val FRESH_IMPORT_PIN_LABEL = "Saving pin\u2026"

/**
 * #358: show the non-cancelable KDF overlay for [protect]
 * ([com.samourai.sentinel.core.access.PinVault.protectFreshImport])
 * and dismiss it on every exit of that call.
 *
 * [show] and [dismiss] run on [main]. [protect] stays on the caller
 * dispatcher (IO at the import screen) so the derive does not freeze
 * the thread that draws the overlay.
 *
 * A plain [withContext] on an already-cancelled job never enters its
 * block, which would leave the window up after the screen is torn
 * down. The dismiss hop is [NonCancellable] for that reason. The host
 * still dismisses from `onDestroy`: the derive is a blocking call and
 * does not observe cancellation until it returns.
 */
internal suspend fun protectImportedPin(
    show: (String) -> Unit,
    dismiss: () -> Unit,
    main: CoroutineContext = Dispatchers.Main,
    protect: suspend () -> Unit,
) {
    try {
        withContext(main) { show(FRESH_IMPORT_PIN_LABEL) }
        protect()
    } finally {
        withContext(main + NonCancellable) { dismiss() }
    }
}
