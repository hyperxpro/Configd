# ADR-0038: Fan-out streams the verbatim signed delta chain - no server-side coalescing; prefix subscription is an edge-side storage filter

- **Status:** Accepted (review-architect RATIFY 2026-06-11 - security argument verified airtight against rewrite/coalesce/single-delta-suppression; the burst-bandwidth honesty widening is applied below; contract-map rows CT-17/CT-25 flip to ADR-RENEGOTIATED on this ratification)
- **Date:** 2026-06-11
- **Interacts with:** ADR-0034 (boundary semantics: contiguous signed deltas or GAP), F-0052 (per-delta Ed25519 signature + epoch/nonce replay protection, verified at the edge by `DeltaApplier`), ADR-0020 (prefix subscription model), ADR-0030 (Quicksilver-shaped topology: centralized write, async full fan-out), architecture section 7 (subscription model + coalescing)

> **Amendment (2026-07-02, ADR-0045).** This ADR's two legs are now split by trust. **Leg (a) - no coalescing/rewrite - STANDS unconditionally** (a trust-independent authenticity requirement). **Leg (b) - no server-side prefix filtering - is RELAXED under a posture flag** (`configd.edge.fanout.filter`, default on) for the co-located trusted deployment: the drain may drop **whole** signed deltas to a prefix-scoped edge, with the edge trusting the server's covered-through assertion on the HEARTBEAT. The no-suppression guarantee must be restored (posture off, or the v2 leader-signed Merkle skip-evidence this ADR names) the moment a separate/untrusted relay tier is deployed. Independently, the signed payload now covers the version position (ADR-0045 Track 0), closing the §3.1 gap this ADR's anti-suppression claim previously leaned on TLS for. See ADR-0045.

## Context

Three requirements collide at C1:

1. **Architecture section 7 / charter C1** allow coalescing (multiple in-flight updates to one key
   may collapse to the latest) and specify prefix-based subscription as the primary model
   (an edge receives only keys matching its prefixes).
2. **F-0052 / ADR-0034** deliver each mutation as a `ConfigDelta` carrying an Ed25519
   signature over `mutations || epoch || nonce`, **signed by the leader at apply time**.
   The edge (`DeltaApplier.offer`) verifies the signature and rejects unsigned/invalid/
   replayed deltas - fail-closed. The fan-out service does not (and must never) hold the
   signing key.
3. **Contract section 4** gap detection: the edge applies `received == last_applied + 1`,
   catch-up on a higher seq, discard on a lower one. `DeltaApplier` enforces the chain via
   `delta.fromVersion() == currentVersion`.

(1) is incompatible with (2)+(3) as stated:

- **Coalescing rewrites payloads.** Collapsing two signed deltas into one produces bytes
  the leader never signed. The fan-out service cannot re-sign them (no key, by design),
  so a coalescing relay forces the edge to either accept unsigned data (breaks F-0052
  fail-closed) or reject the stream entirely.
- **Prefix filtering breaks the chain.** Dropping a delta whose mutations all fall outside
  a subscriber's prefixes makes the next delivered delta's `fromVersion` mismatch the
  edge's `currentVersion` - indistinguishable from data loss. Any "this range contained
  nothing for you" skip-marker would be asserted by the **relay**, not signed by the
  leader, so a compromised or buggy relay could silently suppress arbitrary keys
  (including `secure/` strong-read keys) - a freshness/suppression attack that per-delta
  signatures exist to make detectable.

## Decision

**C1 streams the verbatim, leader-signed delta chain to every subscriber - every delta,
unmodified, in seq order.** Specifically:

1. **No server-side coalescing of signed payloads.** The charter's "may collapse" option
   is exercised as *may not*, by this ADR, for the security reasons above. The
   throughput concern coalescing addressed is met by **frame-level batching** instead:
   one `NOTIFY` wire frame carries N consecutive notifications (chain intact, signatures
   intact). Batching parameters are named configs with metrics.
   - **The exact coalescing/gap rule the charter demands is therefore:** *the stream is
     the contiguous applied-mutation seq chain; nothing is ever collapsed or skipped;
     gap detection remains exact `fromVersion == currentVersion` per delta; any observed
     skip is a real gap and triggers catch-up.* This rule is trivially
     coalescing-safe because coalescing is forbidden.
2. **Prefix subscription becomes an edge-side storage/serving filter, not a transport
   filter.** Every edge receives the full signed chain (ADR-0030's Quicksilver-shaped
   "every edge gets everything" - config-write volumes, not data-plane volumes). After
   signature verification, the edge applies only mutations matching its subscribed
   prefixes; non-matching mutations advance the version chain without storing payloads
   (the applied-version cursor is global; per-key values exist only for subscribed
   prefixes). Reads outside the subscription are NOT_FOUND by subscription semantics
   (negative caching per section 8 still applies). This preserves: end-to-end signature
   verification on every chain link, exact gap detection, and suppression-detectability
   (a relay cannot drop a single delta without the edge seeing a chain break).
   - Architecture section 7's "per-key: supported but discouraged" is N/A-by-construction at the
     transport (it is a storage filter choice), and "full-store" is the universal
     transport behavior.
3. **Bandwidth honesty.** At the section 0.1 baseline (10k writes/s x ~1 KB typical payload) the
   full chain is ~ 80 Mbit/s per subscriber - the same stream the control plane already
   replicates; per-edge egress equals the write stream. At the section 0.1 **burst** envelope
   (100k writes/s) that is ~ 800 Mbit/s per subscriber: sustainable on datacenter links
   for burst durations, but it makes the bound explicit - a deployment that runs sustained
   burst-rate writes to large edge fleets is outside this design's envelope. If a future
   deployment needs transport-level prefix filtering to cut that, it requires a
   leader-signed skip-evidence design (e.g., signed per-range Merkle summaries) - out of
   scope, priced here, and recorded as the explicit upgrade path.

## Consequences

- `DeltaApplier`/`LocalConfigStore` keep their existing chain validation unchanged; C2
  adds the prefix apply-filter between verification and storage (a named, tested step).
- The V1 simulator invariant "snapshot-delta equivalence" compares full-store state for
  full-store subscribers and subscribed-subset state for prefix subscribers.
- Slow-consumer math (C4) keys off frames and bytes, not coalesced logical updates.
- The contract-test map rows on coalescing (architecture section 7) flip to
  ADR-RENEGOTIATED(adr-0038) with the frame-batching tests as their evidence.
- A relay cannot make an edge skip a delta undetected; the residual freshness attack is
  wholesale stream stalling, which the staleness state machine (section 2) + commit-timestamp
  clock (ADR-0035) detect and surface.

## Sign-off

- review-architect: _pending - C1 design review_
- contract-qa-engineer: _pending - map rows updated on ratification_
