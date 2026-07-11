# ADR-0038: Fan-out streams the verbatim signed delta chain - no server-side coalescing; prefix subscription is an edge-side storage filter

- **Status:** Accepted (2026-06-11). The security argument was verified against rewrite, coalesce, and
  single-delta-suppression attacks; the burst-bandwidth honesty note below was widened as part of this
  ratification.
- **Date:** 2026-06-11
- **Interacts with:** ADR-0034 (boundary semantics: contiguous signed deltas or GAP), the per-delta
  Ed25519 signature plus epoch/nonce replay protection verified at the edge by `DeltaApplier`, ADR-0020
  (prefix subscription model), ADR-0030 (Quicksilver-shaped topology: centralized write, async full
  fan-out), and the subscription/coalescing model described in the architecture docs

> **Amendment (2026-07-02, ADR-0045).** This ADR's two legs are now split by trust. **Leg (a) - no
> coalescing or rewrite - stands unconditionally** (a trust-independent authenticity requirement).
> **Leg (b) - no server-side prefix filtering - is relaxed under a posture flag**
> (`configd.edge.fanout.filter`, default on) for the co-located trusted deployment: the drain may drop
> **whole** signed deltas to a prefix-scoped edge, with the edge trusting the server's covered-through
> assertion on the heartbeat. The no-suppression guarantee must be restored (posture off, or a future
> leader-signed Merkle skip-evidence design this ADR names) the moment a separate or untrusted relay
> tier is deployed. Independently, the signed payload now covers the version position (ADR-0045),
> closing the gap this ADR's anti-suppression claim previously leaned on TLS for. See ADR-0045.

## Context

Three requirements collide here:

1. **The original design** allows coalescing (multiple in-flight updates to one key
   may collapse to the latest) and specifies prefix-based subscription as the primary model
   (an edge receives only keys matching its prefixes).
2. **ADR-0034** delivers each mutation as a `ConfigDelta` carrying an Ed25519
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
  so a coalescing relay forces the edge to either accept unsigned data (breaks the edge's
  fail-closed signature check) or reject the stream entirely.
- **Prefix filtering breaks the chain.** Dropping a delta whose mutations all fall outside
  a subscriber's prefixes makes the next delivered delta's `fromVersion` mismatch the
  edge's `currentVersion` - indistinguishable from data loss. Any "this range contained
  nothing for you" skip-marker would be asserted by the **relay**, not signed by the
  leader, so a compromised or buggy relay could silently suppress arbitrary keys
  (including `secure/` strong-read keys) - a freshness/suppression attack that per-delta
  signatures exist to make detectable.

## Decision

**The fan-out service streams the verbatim, leader-signed delta chain to every subscriber - every
delta, unmodified, in seq order.** Specifically:

1. **No server-side coalescing of signed payloads.** The original design's "may collapse"
   option is exercised as *may not*, by this ADR, for the security reasons above. The
   throughput concern coalescing addressed is met by **frame-level batching** instead:
   one `NOTIFY` wire frame carries N consecutive notifications (chain intact, signatures
   intact). Batching parameters are named configs with metrics.
   - **The coalescing/gap rule is therefore:** *the stream is
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
   (negative caching still applies). This preserves: end-to-end signature verification on
   every chain link, exact gap detection, and suppression-detectability (a relay cannot
   drop a single delta without the edge seeing a chain break).
   - The "per-key: supported but discouraged" subscription mode is N/A-by-construction at
     the transport (it is a storage filter choice), and "full-store" is the universal
     transport behavior.
3. **Bandwidth honesty.** At a baseline of 10k writes/s x ~1 KB typical payload, the full
   chain is about 80 Mbit/s per subscriber - the same stream the control plane already
   replicates; per-edge egress equals the write stream. At a 100k writes/s burst that is
   about 800 Mbit/s per subscriber: sustainable on datacenter links for burst durations,
   but it makes the bound explicit - a deployment that runs sustained burst-rate writes to
   large edge fleets is outside this design's envelope. If a future deployment needs
   transport-level prefix filtering to cut that, it requires a leader-signed skip-evidence
   design (e.g., signed per-range Merkle summaries) - out of scope, priced here, and
   recorded as the explicit upgrade path.

## Consequences

- `DeltaApplier`/`LocalConfigStore` keep their existing chain validation unchanged; the edge node
  adds the prefix apply-filter between verification and storage (a named, tested step).
- The simulator's invariant "snapshot-delta equivalence" compares full-store state for
  full-store subscribers and subscribed-subset state for prefix subscribers.
- Slow-consumer math keys off frames and bytes, not coalesced logical updates.
- The property tests that previously asserted coalescing behavior are updated to assert
  the frame-batching behavior instead, per this ADR.
- A relay cannot make an edge skip a delta undetected; the residual freshness attack is
  wholesale stream stalling, which the staleness state machine (section 2) + commit-timestamp
  clock (ADR-0035) detect and surface.
