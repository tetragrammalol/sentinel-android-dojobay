package com.samourai.sentinel.ui

import com.samourai.sentinel.core.access.UnlockResult

/**
 * #528: name of the unlock arm for a debug log. The unavailable
 * message is omitted: it is not a secret today, and this line must
 * stay free of the PIN and of key material if that ever changes.
 */
internal fun unlockResultLogLine(result: UnlockResult): String =
    "Unlock result: ${unlockResultName(result)}"

internal fun unlockResultName(result: UnlockResult): String = when (result) {
    UnlockResult.Success -> "Success"
    UnlockResult.WrongPin -> "WrongPin"
    UnlockResult.NotConfigured -> "NotConfigured"
    is UnlockResult.Unavailable -> "Unavailable"
}

/**
 * #528: Home is started only after a successful unlock when no
 * migration is still in front of this activity. Every other result
 * stays on the PIN screen.
 */
internal fun unlockLeavesScreen(result: UnlockResult, migrationPending: Boolean): Boolean =
    result is UnlockResult.Success && !migrationPending

/**
 * #528: run the verdict for one PIN confirm.
 *
 * [onSuccess] returns whether this activity is leaving the screen
 * (Home started and finished). A migration hand-off returns false.
 * [clearBusy] runs from a finally on every path that does not leave,
 * including a throw from [prepare], [onWrongPin], [onUnavailable],
 * or [onSuccess], so a stuck latch cannot swallow the next confirm.
 * A path that leaves may keep the latch: the activity is finishing.
 */
internal suspend fun deliverUnlockResult(
    result: UnlockResult,
    clearBusy: () -> Unit,
    onWrongPin: () -> Unit,
    onUnavailable: (String) -> Unit,
    onSuccess: suspend () -> Boolean,
    prepare: () -> Unit = {},
) {
    var leaves = false
    try {
        prepare()
        when (result) {
            UnlockResult.Success -> leaves = onSuccess()
            UnlockResult.WrongPin, UnlockResult.NotConfigured -> onWrongPin()
            is UnlockResult.Unavailable -> onUnavailable(result.message)
        }
    } finally {
        if (!leaves) clearBusy()
    }
}
