#351 — Chain-level warning at pairing + an honest sweep empty-answer (testnet4/5)

## Root cause

Sentinel's "testnet" means testnet3 everywhere: `SentinelState` resolves `NetworkParameters.fromID(ID_TESTNET)` once at class-init, `isTestNet()` is literally `getNetworkParam() is TestNet3Params`, `DerivedNetwork` has two members plus UNKNOWN, and `normalizeDeclaredNetwork` nulls anything but "mainnet"/"testnet". Testnet4 (BIP94) and testnet5 (BIP95, deployed 2026-11-17) reuse testnet3's address formats — same tb HRP, same script version bytes, same WIF prefix — so Sentinel interoperates with a testnet4/5 Dojo **by accident** and cannot tell the chains apart. The keychain vote can never separate them either: tpub-family keys vote identically on every testnet generation. Pointed at the wrong index the user gets silent emptiness — the #348 incident: a funded testnet4 key swept against a testnet3-indexed Dojo answered "This private key doesn't have any UTXOs", repeatedly, while two public explorers confirmed the coin on testnet4.

No Dojo API field exposes the chain (census: every endpoint the app hits — auth/login, xpub, tx, txs, wallet, fees, pushtx, the /inv socket — returns wallet/tx/fee data with no network/chain key; the admin API that does is never contacted). The only in-tree chain evidence is convention: the mount segment (`/test` for testnet3, `/test4` per the dojobay convention).

## The fix — options 1+3, NOT option 2 (a verdict the issue left open)

**Vendoring `TestNet4Params` (option 2) is deliberately rejected**: testnet5's launch proves the family churns on a schedule no app release can chase, and bitcoinj's ten `instanceof TestNet3Params` ternaries (extlibj HRP picks) would each need family surgery — for a watch-only wallet where genesis/magic are inert. The durable shape is convention detection plus honest copy, which also covers testnet5 with zero further code.

- `core/DojoChain.kt` — `DojoChain` enum (TESTNET_3/4/5) + `DojoChainWhisper.fromUrl(url)`: segment-equality whisper over the mount path (`test`/`testnet`/`testnet3` → 3, `test4`/`testnet4` → 4, `testnet5`/`test5` → 5), root mount → null (absence of evidence, never a verdict), query/fragment outside the parser, `java.net.URI` like `NetworkClassifier`. Deliberately OUTSIDE `DerivedNetwork`: a TESTNET_4 member there would lie about what the keychain vote can see.
- `ui/dojo/PairingChainDecision.kt` — pure decision: warn (Pair stays available — convention evidence could false-positive via a relabelled mount, so the user decides, never a refuse) exactly when the app is in testnet mode and the whisper names a different generation. Mainnet-mode mismatches stay with the #250 network guard — one warning per dialog. The refuse gate remains driven by the network decision only.
- `DojoConfigureBottomSheet.showPairingConfirmation()` — the chain warning appends to the pre-pair dialog message (`dojo_pairing_chain_mismatch` string), driven by the same `pairingUrl` the network guard reads.
- `SweepPrivKeyFragment.findUTXOs()` — the empty answer names the risk when the paired Dojo (via `ApiService.getAPIUrl()`) is mounted on a different testnet generation: `sweep_no_utxos_wrong_chain` ("your Dojo is mounted for testnet4 while Sentinel tracks testnet3..."), plain `sweep_no_utxos` otherwise. The silent half of #348 was this toast reading the same for wrong-chain as empty.

## Verification, in plain words

- `:app:compileStagingDebugKotlin` + `:app:compileStagingDebugUnitTestKotlin` pass.
- New `DojoChainWhisperTest` (11 legs, pure JVM): every mount segment maps (test/testnet/testnet3 → 3; test4/testnet4 → 4; testnet5/test5 → 5), root/garbage/null stay null, attest/contest stay silent (segment equality, never substring), query/fragment outside, case/whitespace insensitive; decision table — null whisper proceeds both modes, testnet3 whisper proceeds on a testnet app, testnet4/5 whisper warns on a testnet app (the #348 shape), any testnet whisper proceeds on a mainnet app (no double warning). The `test5` gap was caught by its own leg before landing.
- Full `:app:testStagingDebugUnitTest` suite pass (exit 0).
- Pairing receipts/flows: the #250 network guard paths are untouched except the appended message line — `PairingNetworkDecision` table, gate, and commit are unchanged and their tests pass unchanged.

## Device legs — deliberately not re-run here

A real testnet4/5-mounted Dojo pairing: the whisper strings are pinned by unit legs; the on-device rendering rides the existing pre-pair dialog (MaterialAlertDialog message append, the same seam as the network warning since #250).

## Explicitly out of scope

- A `TestNet4Params`/`TestNet5Params` vendored class and a chain-selectable testnet family (option 2 — rejected above; revisit only if a Dojo release ever exposes chain in contract data).
- Explorer base URLs for testnet4/5 (`TxFactsResolver` stays on mempool's `/testnet` = testnet3; `ExplorerBinding.TESTNET_SEGMENTS` already recognizes `testnet4` as a path hint, unchanged).
- Label-DB scoping strings ("testnet"/"mainnet") — per-chain scoping only becomes real with a params family.