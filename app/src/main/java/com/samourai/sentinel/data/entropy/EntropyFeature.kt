package com.samourai.sentinel.data.entropy

/**
 * Kill switch for the per-tx Boltzmann entropy feature (#105).
 *
 * OFF pending the #105 engine-path fix: 5x5-class Whirlpool remix
 * rounds row as tooComplex (should read 1496 interpretations /
 * ~10.55 bits - the pipeline proved it can, on-device, Oct 4), and
 * an honest refusal is still wrong in the dangerous direction on
 * exactly the transactions where privacy matters most.
 *
 * While OFF the feature must not interact with the app at all: no
 * per-tx Dojo fetches, no engine CPU during sync, no DB writes, no
 * DAO read on sheet open, no Privacy row. The gate sits on the two
 * production entries (TransactionsRepository's ingest wiring,
 * TransactionsDetailsBottomSheet's row block); because the ingest
 * is constructed `by lazy` and first touched only at the gated
 * call, the engine object is never built while this is false.
 *
 * Re-enable bar (#105): a real on-device remix renders 1496
 * interpretations / ~10.55 bits. The schema, the existing rows,
 * the vendored engine and the tests are deliberately untouched -
 * they are the fix's material and its proof, still run in CI.
 */
const val ENTROPY_FEATURE_ENABLED = false

/**
 * Tier-1 external analysis link (#107): destination for entropy
 * rows the device declines. The txid rides in the hash fragment
 * (#?tx=...), which is never sent to a server; am-i.exposed is a
 * static site with no backend. The privacy cost is the page's own
 * mempool.space fetch - that server sees the txid and the timing
 * (IP protected by Tor while the app's webview proxy is active).
 * The advisory dialog states this before the link opens.
 */
const val EXTERNAL_ANALYSIS_URL_PREFIX = "https://am-i.exposed/#?tx="
