package com.samourai.sentinel.data.entropy

/**
 * Kill switch for the per-tx Boltzmann entropy feature.
 *
 * ON again - the #107 re-enable flip. The #105 premise ("5x5-class
 * remix rounds row as tooComplex") was census-falsified: none of
 * the 47 refused txs is a 5x5 (28x 8x8, 10x 6x6, 9x 7x7 - every
 * row a wide round), and the wallet's real 5x5 remixes are the 35
 * rows at nbCmbn=1496 / 10.5469 bits computed on-device Oct 4. The
 * re-enable bar - a real on-device remix rendering 1496 / ~10.55
 * bits - is met by banked evidence.
 *
 * What stays refused is the wide-round class, and the arm split is
 * stated honestly: 28 capRefused (8 inputs > SURGE_CAP=7, refused
 * pre-engine, by design); 19 timebox-or-engineFailed (6x6/7x7 pass
 * the cap and die on the 500ms caller box - the landing's
 * five-counter line was wiped, so the split between those two arms
 * is unverifiable; both are contained, both row tooComplex).
 * Declined rows carry the tier-1 external analysis link (#108):
 * refusal plus a pointer, never a derived number. The engine's
 * duration bailout is guarded (#109) - a trip rows tooComplex
 * instead of crashing.
 *
 * While ON: the ingest computes on full txs during sync (bounded
 * by the surge cap and the time box), rows cache permanently, and
 * the sheet renders engine truth - honest numbers, or the
 * declined-row link. Secure mode (street mode) still hides the
 * row. The gate sits on the two production entries
 * (TransactionsRepository's ingest wiring,
 * TransactionsDetailsBottomSheet's row block).
 */
const val ENTROPY_FEATURE_ENABLED = true

/**
 * Tier-1 external analysis (#107 / #116). The site is static and
 * has no backend of its own; its page then fetches the transaction
 * from a public explorer. The web hash router accepts tx, addr,
 * check, and xpub only - there is no backend or API parameter a
 * deep link can set (Copexit/am-i-exposed src/hooks/useHashRouting.ts).
 * The Privacy row's visible action opens it, and only after the
 * third-party advisory, including when a paired explorer is bound.
 * Every other lookup stays on the bound explorer.
 *
 * The engine's counts (1496 / 426,833 / 9,934,563 / 277,006,192)
 * are what that site's local entropy reports too. Agreement
 * verifies; disagreement is a finding.
 */
