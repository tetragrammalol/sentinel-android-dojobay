# Sentinel — Dojo Bay Edition

A watch-only Bitcoin wallet for Android: track xpubs, addresses and cold-storage
wallets, derive receive addresses on the go, and broadcast pre-signed
transactions — entirely over Tor, paired exclusively with a Bitcoin Dojo you
control (or one from the community directory).

This is a community continuation, not a new project. Credit where it belongs:

- **Samourai Wallet** — original Sentinel (code preserved in the [Samourai-Wallet GitHub org](https://github.com/Samourai-Wallet); their web domains were seized in 2024 and later hosted phishing — treat any samouraiwallet.* site as hostile)
- [btcwrestle/sentinel-android](https://github.com/btcwrestle/sentinel-android) —
  the Dojo Bay integration that gave the project its post-2024 purpose, and the
  Dojo-or-nothing direction after upstream infrastructure was seized
- fixes adopted from wanderingking072's fork (authored by @MightyMercurian)

## What this fork maintains

| Area | Status |
|---|---|
| kmp-tor 2.x migration (in-process TorRuntime) | done (#10) |
| Tor watchdog: auto-recovery from boot=0 wedges | done (#14, #11) |
| Connected-dojo name + country flag in details | in flight (#12) |
| Copy/title-case sweep, string resources | in flight (#13) |
| Dead Samourai infrastructure removal | queued (#3) |
| Dependency hygiene, only-on-green | queued (#4) |

## For testers

The staging flavor installs as **Sentinel Beta** (applicationId
`com.samourai.sentinel.staging`) and coexists with a production Sentinel
install. Grab the APK from the
[Actions](https://github.com/tetragrammalol/sentinel-android-dojobay/actions)
build-staging-apk artifacts, or build it:

    ./gradlew :app:assembleStagingDebug

Requires JDK 25+ compatible toolchain (Room runs via KSP; kapt is gone).

## Privacy stance

Directory and Dojo traffic goes only through the app's embedded Tor (SOCKS)
proxy to onion services — the Dojo Bay directory itself is an onion service.
No clearnet Samourai endpoints are contacted (and removing the leftovers is
tracked). Sentinel never holds your private keys.

## Disclaimer

Experimental community software, provided as-is, with no affiliation to
Samourai Technologies or Dojo Bay operators. Read the code; that is the point
of a wallet like this.
