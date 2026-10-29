Landed via PR #372 — "ux: white 13sp Dojo name on the pairing receipt (#344)" — merge commit `7d311ebc15c731cc7400d68165c312affd35f4fc` on master. The fix commit `124b83945844583ab0987f22e341e13c80d67d60` carries `Closes #344`.

Root cause
The receipt (`showPairingReceipt()` in `DojoConfigureBottomSheet.kt`, landed #256/#259) is a plain MaterialAlertDialog: title + message + identity line + OK, with no explicit text color on the identity line — so it inherits the dialog's default grey message color. The pre-pair detail box (`dialog_dojo_pairing_details.xml`, same landing) uses the credentials-sheet idiom: grey 12sp row labels over white 13sp wrapped values. The same Dojo name therefore rendered white before Pair and grey after — one flow, two colors.

The fix
The plain-dialog shape stays (no custom layout — deliberately, per the issue's census and the dialog's KDoc contract). `setMessage()` now receives a `SpannableString` spanning only the identity line — `ForegroundColorSpan(R.color.white)` + `AbsoluteSizeSpan(13, true)` — the exact value grammar the pre-pair rows use. The message text, the non-cancelable contract, and the OK-gated #159 dismissal seam are untouched; span start is `message.length + 2` (past the `\n\n` separator), end is `length`.

Verification, in plain words
No test rides along — the change is presentation-only inside a dialog; green means compile plus the existing suite, not regression coverage.

- Local: `:app:assembleStagingDebug` + full `:app:testStagingDebugUnitTest` pass (exit 0).
- CI: PR #372, run `40809006992` (`pull_request`, head `124b839`, workflow "Build and test staging APK") — success in 4m55s.
- Landing: the landing delta equals the fix commit's numstat exactly — 1 file, `DojoConfigureBottomSheet.kt` (imports + the spanned setMessage; the `pairingIdentityLine()` ladder itself unchanged, still covered by `PairingAckGateTest` cases at L183–L246). Nothing else rode along onto master.

Device leg — deliberately not re-run
A spanned TextView color/size is deterministic rendering; the receipt requires a live successful pairing to reach. No staging APK was cut for this close; the visual proof is the span math (white 13sp identity line over the default grey message) matching the pre-pair XML grammar line-for-line.
