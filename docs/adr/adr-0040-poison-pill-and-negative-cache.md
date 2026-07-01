# ADR-0040: Narrow poison-pill policy; descope architecture §8's circuit breaker and negative caching

- **Status:** Accepted (content pre-ratified by review-architect at the C2–C5 design screen,
  `docs/session-3/reviews/c2-c5-design-screen.md` §C3, 2026-06-11 — authored before C3
  implementation per that review's hard sequencing gate)
- **Date:** 2026-06-11
- **Session:** 3 (Edge Data Plane) — C3 scope decision (charter §4 C3: "implement or
  explicitly descope by ADR — do not leave it ambiguous")
- **Amends:** `docs/architecture.md` §8 ("Negative Caching", "Poison-Pill Handling" —
  amended by reference; consolidated doc pass at session close)
- **Affects contract-test-map rows:** CT-32 (negative caching), CT-33 (poison-pill)

## Context

Architecture §8 specifies three edge-cache defenses written for a system that does not
exist in this codebase:

1. **Poison-pill circuit breaker** — "version format checks: validate value schema before
   deserialization; if value fails validation, serve previous known-good version" with
   dual-TTL refresh. Configd stores **opaque bytes** and never deserializes values; there
   is no schema, no validation layer, and no TTL-based refresh loop. "Fails validation"
   has no referent.
2. **Negative caching** — a key-only index + Bloom filter to reject lookups for
   non-existent keys cheaply, motivated by prefix-subscribed edges that cannot
   distinguish "doesn't exist" from "not subscribed", and by 10^9-key scale.
3. The genuinely real hazard neither of those addresses: a delta that **repeatedly throws
   during apply** (e.g., a mutation-decode defect) halts the chain — the edge can never
   advance past seq S, staleness grows, and without a policy the failure is a silent wedge.

## Decision

### 1. Poison-pill: implement the NARROW policy that matches the real system

- An **invalid-signature** delta is NOT a poison pill: `DeltaApplier` rejects it
  fail-closed (F-0052), the chain halts deliberately, and the ADR-0039 staleness frontier
  surfaces the stall (STALE → DEGRADED). Already correct; no new mechanism.
- An **apply-throwing** delta (decode/apply exception on an otherwise signature-valid
  frame) is the poison pill. Policy, implemented in C3 on the `EdgeClientCore` apply path:
  1. Bounded retries per seq (`PoisonPillDetector`, the existing tested class, re-pointed
     at apply exceptions keyed by seq; default max 3).
  2. **Skipping is forbidden** — a skipped seq is a silent chain break (divergence, the
     unforgivable outcome). On quarantine: emit `configd.edge.poison_pill` (the §8 metric
     name, kept) + a structured log event, then **re-bootstrap via snapshot past the
     poison seq** (`RECONNECT_RESUBSCRIBE(cursor=0, force snapshot)`): the snapshot
     carries cumulative state, so the poisoned delta is never re-applied — recovery
     without divergence.
  3. If the **snapshot itself** fails to apply: terminal fail-loud — the process exits
     non-zero (`configd.edge.poison_pill_terminal` metric emitted before exit). An edge
     that cannot advance and cannot re-bootstrap must die visibly, not serve an
     ever-staler cache behind a green health check (the Session-1 lying-dashboard lesson).
- **Descoped (the §8 circuit breaker):** dual-TTL, schema validation,
  serve-previous-known-good. Rationale: they presume a validation layer that does not
  exist; "serve known-good forever" on a halted chain is exactly the silent-stale failure
  ADR-0039 exists to surface. If a future session adds value-schema awareness, it prices
  a circuit breaker then.

### 2. Negative caching: descope

Under ADR-0038 (full signed chain; prefix subscription is an edge-side **storage
filter**), within its subscription an edge's store is complete — a HAMT miss IS
authoritative non-existence, served by the existing lock-free, zero-allocation miss path
(Session-1-verified). Outside the subscription, reads are refused by policy with a
distinct reason (`404` + `X-Configd-Refused: not-subscribed`, C2) — no ambiguity for a
cache to resolve. The 10^9-key motivation is likewise mooted by the storage filter (an
edge stores its slice). No correctness or measured performance need remains.

- The `BloomFilter` class (real, unit-tested, zero `src/main` consumers) is retained as
  shelfware pending Session 7's orphan sweep (delete-or-revive is S7's call across all
  RR-034-class rows); it is NOT wired.
- Map row CT-32 → ADR-RENEGOTIATED(adr-0040); CT-33 → the narrow policy's tests
  (`PoisonPillRebootstrapTest` incl. the terminal fail-loud case, required by the screen).

## Consequences

- C3 implements exactly: bounded-retry detection, snapshot re-bootstrap recovery,
  terminal fail-loud, two metrics, one structured log event. Nothing else.
- The §8 text is amended by reference; the consolidated architecture/contract doc pass at
  session close updates the section to point here.
- Honesty note for the register: §8's circuit-breaker prose was another instance of the
  Session-1 "documentation of another system" pattern — the descope is recorded as the
  correction, not as lost functionality.

## Sign-off

- review-architect: **pre-ratified** at the C2–C5 design screen (hard gate: this ADR
  authored before C3 implementation — satisfied; see
  `docs/session-3/reviews/c2-c5-design-screen.md` §C3).
