# ADR-0040: Narrow poison-pill policy; descope the edge-cache circuit breaker and negative caching

- **Status:** Accepted
- **Date:** 2026-06-11
- **Amends:** the architecture doc's edge-caching section ("Negative Caching", "Poison-Pill
  Handling")

## Context

The architecture doc specifies three edge-cache defenses written for a system that does not
exist in this codebase:

1. **Poison-pill circuit breaker** - "version format checks: validate value schema before
   deserialization; if value fails validation, serve previous known-good version" with
   dual-TTL refresh. Configd stores **opaque bytes** and never deserializes values; there
   is no schema, no validation layer, and no TTL-based refresh loop. "Fails validation"
   has no referent.
2. **Negative caching** - a key-only index + Bloom filter to reject lookups for
   non-existent keys cheaply, motivated by prefix-subscribed edges that cannot
   distinguish "doesn't exist" from "not subscribed", and by 10^9-key scale.
3. The genuinely real hazard neither of those addresses: a delta that **repeatedly throws
   during apply** (e.g., a mutation-decode defect) halts the chain - the edge can never
   advance past seq S, staleness grows, and without a policy the failure is a silent wedge.

## Decision

### 1. Poison-pill: implement the narrow policy that matches the real system

- An **invalid-signature** delta is *not* a poison pill: `DeltaApplier` rejects it
  fail-closed (the per-delta signature check), the chain halts deliberately, and the
  ADR-0039 staleness frontier surfaces the stall (STALE -> DEGRADED). Already correct; no
  new mechanism.
- An **apply-throwing** delta (decode/apply exception on an otherwise signature-valid
  frame) is the poison pill. Policy, on the `EdgeClientCore` apply path:
  1. Bounded retries per seq (`PoisonPillDetector`, the existing tested class, re-pointed
     at apply exceptions keyed by seq; default max 3).
  2. **Skipping is forbidden** - a skipped seq is a silent chain break (divergence, the
     unforgivable outcome). On quarantine: emit `configd.edge.poison_pill` (keeping the
     existing metric name) plus a structured log event, then **re-bootstrap via snapshot
     past the poison seq** (`RECONNECT_RESUBSCRIBE(cursor=0, force snapshot)`): the
     snapshot carries cumulative state, so the poisoned delta is never re-applied -
     recovery without divergence.
  3. If the **snapshot itself** fails to apply: terminal fail-loud - the process exits
     non-zero (`configd.edge.poison_pill_terminal` metric emitted before exit). An edge
     that cannot advance and cannot re-bootstrap must die visibly, not serve an
     ever-staler cache behind a green health check (the lying-dashboard anti-pattern).
- **Descoped (the circuit breaker described in the architecture doc):** dual-TTL, schema
  validation, serve-previous-known-good. Rationale: they presume a validation layer that
  does not exist; "serve known-good forever" on a halted chain is exactly the
  silent-stale failure ADR-0039 exists to surface. If a future change adds value-schema
  awareness, it prices a circuit breaker then.

### 2. Negative caching: descope

Under ADR-0038 (full signed chain; prefix subscription is an edge-side **storage
filter**), within its subscription an edge's store is complete - a HAMT miss *is*
authoritative non-existence, served by the existing lock-free, zero-allocation miss path
(verified). Outside the subscription, reads are refused by policy with a
distinct reason (`404` + `X-Configd-Refused: not-subscribed`) - no ambiguity for a
cache to resolve. The 10^9-key motivation is likewise mooted by the storage filter (an
edge stores its slice). No correctness or measured performance need remains.

- The `BloomFilter` class (real, unit-tested, zero `src/main` consumers) is retained as
  shelfware pending a cleanup pass to delete-or-revive unused classes like it; it is not
  wired.
- The narrow poison-pill policy is covered by `PoisonPillRebootstrapTest`, including the
  terminal fail-loud case; negative caching has no test surface to build since it isn't
  built.

## Consequences

- The implementation is exactly: bounded-retry detection, snapshot re-bootstrap recovery,
  terminal fail-loud, two metrics, one structured log event. Nothing else.
- The architecture doc's edge-caching text is amended by this decision and should be
  updated to match.
- Honesty note: the circuit-breaker prose in the architecture doc was another instance of
  documentation written for a system that doesn't exist here - this descoping is the
  correction, not lost functionality.
