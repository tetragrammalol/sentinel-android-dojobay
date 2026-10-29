Landed via PR #368 — "ux: fixed-width network switch button matches the Connections rows (#341)" — merge commit `c1350c0a2a93b8a51dc4889c6680cb26fd46ce96` on master. The fix commit `caac3b43b4363f340446dea626aeee2939db927d` carries `Closes #341`.

Root cause
The Bitcoin Network row's "Switch to Testnet" button (`networking_network_switch_btn`) in `activity_network.xml` was `wrap_content` with `marginEnd="4dp"` — its box hugged the label, so only the borderless button's default padding sat between the text and the 4dp margin. Every Connections row button is a fixed-width box with centered text (`networking_tor_btn`/`networking_dojo_btn` 116dp, `networking_tor_renew`/`networking_dojo_details_btn` 84dp, all `marginEnd="4dp"`), which leaves visible space before the screen edge. The row landed with #222 (#208) and inherited `wrap_content` while the older rows use fixed widths — the margins already matched, so the width grammar was the sole delta.

The fix
One attribute in `app/src/main/res/layout/activity_network.xml`: `layout_width` `wrap_content` → `116dp`, matching the `networking_tor_btn` treatment exactly (same borderless style, same `marginEnd`, same `#6eb8c5` text color, centered text in a fixed box). Render-only; no code or logic touched, no shared style extracted — a one-button change did not warrant the broader refactor the issue offered as the alternative.

Verification, in plain words
No test rides along with this fix — it is a single layout attribute; green here means compile plus the existing suite, not regression coverage.

- Local: `:app:processStagingDebugResources` pass, then `:app:assembleStagingDebug` + full `:app:testStagingDebugUnitTest` pass (55s, exit 0).
- CI: PR #368, run `40806817370` (`pull_request`, head `caac3b4`, workflow "Build and test staging APK") — success in 6m13s.
- Landing: the landing delta equals the fix commit's numstat exactly — 1 file, 1 insertion / 1 deletion (`activity_network.xml`). Nothing else rode along onto master.

Device leg — deliberately not re-run
The change is a width attribute on a borderless button rendered identically by every device class; no staging APK was cut for this close. The layout render is deterministic — 116dp box, centered text, 4dp end margin now reads as the same visual grammar as the Disable/Change buttons below it.
