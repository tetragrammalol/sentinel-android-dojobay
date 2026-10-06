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
