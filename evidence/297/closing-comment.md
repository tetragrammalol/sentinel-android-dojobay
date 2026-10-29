Landed via upstream PR #327 — "fix: trim public key input before validating it (#297)" — merge commit `6762e55` on master (fix commit `1daa2545b733a5fc55fa04dab99c168ae0ee162a`). This issue is a stale-open duplicate of that landing: the tracker entry outlived its fix by six days.

Root cause
The xpub arm of `AddNewPubKeyBottomSheet.validate()` tested the untrimmed scan: `FormatsUtil.isValidXpub(code)` and `pubKeyString = code` both saw the raw pasted string, while the private-key and address branches trimmed in place (`payload.trim()`). A valid testnet vpub carrying leading/trailing whitespace or a newline — e.g. copied from a document — failed `isValidXpub` and dead-ended on the scanner page with the "Invalid public key or payload" toast. `extractPublicKey()` compounded it: its "bitcoin:" prefix matching uses `startsWith`, which also saw the padded input.

The fix
`AddNewPubKeyBottomSheet.validate()` trims once at the top — `val payload = FormatsUtil.extractPublicKey(code.trim()).trim()` — and every check below (PrivKeyReader, address, xpub, Dojo-pairing payload) plus the stored `pubKeyString` reads the trimmed value. Deliberate shape: single trim point rather than per-branch `.trim()` scattered at each use site.

Verification, in plain words
No test rides along with this fix in this repo — green means compile plus the existing suite, not regression coverage.

- The change shipped on master via `1daa254` (16 insertions / 8 deletions, `AddNewPubKeyBottomSheet.kt` only) and is included in every build since, including the CI runs gating PRs #367 (#361) and #368 (#341) — both of those staging builds compiled and ran the full unit suite against code containing this fix.
- The #297 repro steps (vpub with surrounding whitespace → Home → NEW → Paste → accepted, advancing to Choose collection) exercise exactly the trimmed path now taken unconditionally; the reporter's plan case COL-12 is the acceptance case.
