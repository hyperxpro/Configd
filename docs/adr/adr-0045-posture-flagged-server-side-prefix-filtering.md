# ADR-0045: Posture-flagged server-side prefix filtering on the SUBSCRIBE drain (trusted domain)

- **Status:** Accepted
- **Date:** 2026-07-02
- **Interacts with:** ADR-0038 (signed-chain streaming, no coalescing - this ADR relaxes leg (b) under a posture flag and preserves leg (a)), ADR-0030 (centralized write + async full fan-out topology), ADR-0034 (edge boundary: contiguous signed deltas or GAP), F-0052 (per-delta Ed25519 + epoch/nonce replay protection). Grounded in `docs/investigation/edge-fanout-efficiency-2026-07-02.md`.

## Context

The edge SUBSCRIBE plane streams the **verbatim, leader-signed delta chain to every subscriber** and filters at the receiver (`PrefixStorageFilter`, after Ed25519 verification). That is O(edges x total-writes) in both network egress and edge verify-CPU, versus an ideal O(edges x f x writes) where `f` is the keyspace fraction an edge actually wants. The waste ratio is ~1/f, and the efficiency investigation found that **network egress is the binding ceiling** (a single fan-out node tops out at ~1-2k edges at the measured write rate), not edge verify-CPU.

ADR-0038 forbids server-side prefix filtering. Its rule has two legs:

- **Leg (a) - no coalescing.** Collapsing signed deltas produces bytes the leader never signed, breaking per-delta Ed25519 verification. This is a HARD, trust-independent authenticity requirement.
- **Leg (b) - no prefix filtering (suppression-detectability).** A relay-asserted skip is not leader-signed, so a compromised/buggy relay could silently suppress arbitrary keys. **This leg depends on the relay being untrusted.**

The efficiency investigation established that v1 is a **trusted, operator-run, mTLS-both-ends, single-hop** deployment where the fan-out relay and the signer are the **same in-process entity**. The Plumtree/HyParView untrusted-relay tier that leg (b) defends against is constructed but dormant (`broadcast()` is never on the data path). The **watch plane already ships server-side filtering under exactly this trust model** (`WatchMultiplexSink` + `FilteringReplaySource`, dropping the signed chain and trusting the server). The full-chain SUBSCRIBE plane is the outlier.

A load-bearing structural fact reframed the crypto question: **the signature covered only `mutations || epoch || nonce`, not the version position** (`fromVersion`/`toVersion`/`seq`). So today's anti-suppression property actually leaned on TLS, not the signature - a key-less relay terminating TLS could drop a delta and rewrite the next `fromVersion` undetectably.

## Decision

**Server-side prefix filtering on the SUBSCRIBE drain, as a configurable posture, default ON for the co-located trusted deployment.** This is the etcd-shaped model (server-side filter + dense cursor + progress notify) the investigation recommended (Track 1). Alongside it we close the signature-position gap (Track 0).

### Non-negotiable carve-outs

1. **Leg (a) stands - never coalesce/rewrite.** Filtering drops **whole signed deltas** only; a delivered NOTIFY still carries verbatim leader-signed bytes. Per-delta Ed25519 authenticity and epoch/nonce replay protection are preserved exactly.
2. **The trusted cursor-advance is where trust is spent.** When the drain advances its cursor past filtered-out deltas, the edge learns the covered-through position from the HEARTBEAT: on a filtered session `HEARTBEAT.latestSeq` carries the **drained-through cursor** (clamped, never the raw buffer tip). The edge advances a dense covered-S cursor and relaxes gap detection to forward-only. The edge trusts the server's assertion "everything matching your prefixes through S has been delivered or filtered."
3. **Strong-read keys are always shipped.** A delta touching any `secure/` (strong-read) key is delivered regardless of the edge's prefix set, so a narrow edge still holds them - and strong-read keys are read linearizably from the root anyway (never from the bounded-stale edge copy), so filtering the bounded-stale plane cannot affect a security decision.

### Track 0 - sign the position (do regardless)

The signed payload for a signed (`epoch > 0`) delta becomes:

```
encodeBatch(mutations) || BE(fromVersion,8) || BE(toVersion,8) || BE(epoch,8) || nonce
```

Binding both versions defeats the drop-and-relink attack: a relay rewriting `fromVersion` breaks verification because the position is now inside the signature. This is a signing-payload-composition change, **not a wire-layout change** (`fromVersion`/`toVersion` are already on the wire, the signature is opaque length-prefixed bytes), so **no golden fixture rebaselines**. Legacy `epoch == 0` deltas keep the batch-only payload. The edge additionally rejects a signature carried on an `epoch == 0` delta (production never emits that shape).

Track 0 authenticates what is delivered; Track 1 confines trust to what is skipped - a clean synergy.

### Posture flags (two-way door)

- Server: `configd.edge.fanout.filter` = `on`/`off`, default **on**, fail-loud on other values. Set **off** to restore the full-chain feed when a separate/untrusted relay tier terminates the fan-out.
- Edge: `configd.edge.accept_filtered` = `on`/`off`, default **off**, fail-loud. A prefix-scoped edge with this on negotiates the 0x03 wire and advertises `acceptsFiltered`; an unconfigured or full-store edge stays on the byte-identical 0x01 wire.

Filtering is active for a session iff the server posture is on AND the edge advertised `acceptsFiltered` AND the subscription is a non-empty prefix set. When inactive the drain is byte-identical to the legacy full-chain path.

### Wire

A new `EDGE_WIRE_VERSION_V3 = 0x03` carries the two new fields (SUBSCRIBE `acceptsFiltered`, SUBSCRIBE_OK `filtered`), appended only under 0x03; the 0x01/0x02 golden images are unchanged. A 0x03 SUBSCRIBE to an old server fails LOUD as `BAD_WIRE_VERSION`. See RFC section 06. The cursor-advance mechanism reuses the existing HEARTBEAT frame (no new frame type, no NOTIFY change).

## Consequences

- **Egress relief.** For a narrow subscription (f = 1%) filtering is a ~100x egress reduction on the binding ceiling (E x W -> E x f x W). It does not help the all-edges-want-everything case, which only the (un-built) distribution tree addresses.
- **Suppression-detectability downgrades from cryptographic to operational within the co-located mTLS domain.** A well-formed suppression of a matching delta behind a correct covered-S is NOT edge-detectable under Track 1 - the documented trusted-server boundary. A genuine data-loss gap (ring eviction) is still detected **server-side** (`readSince` -> GAP -> demote -> snapshot, unchanged) and healed by a re-snapshot; a **delivered `NOTIFY` whose position regresses below the applied version** IS detected edge-side (the forward-only gap check) and triggers resync. A **regressed covered-S on the HEARTBEAT is safely ignored** - the edge advances its covered cursor monotonically and never regresses it. See `docs/operations/known-limitations.md`.
- **Guardrail.** The moment a separate distribution tier or edge-to-edge forwarding is deployed, the untrusted-relay adversary becomes real and the no-suppression guarantee must be restored - set the posture off, or build the v2 hardening. ADR-0038 leg (b) is marked "relaxed-by-posture, see ADR-0045."
- **v2 upgrade path (deferred, gated on a real untrusted tier):** leader-signed per-range Merkle skip-evidence (ADR-0038's own named path), a multi-month chain redesign. Track 0 (position signing) is a prerequisite for it and is landed now.
- **Lockstep upgrade.** A 0x03 edge requires a 0x03 server; an old edge to a new server stays 0x01 (legacy). Pre-v1-tag, so acceptable; no silent-downgrade window is offered by design.

## Alternatives considered

- **Leave the rule as-is (full chain to every edge).** Rejected: it is the exact binding-egress problem the investigation quantified, and it keeps the SUBSCRIBE plane inconsistent with the already-shipped watch plane's trust model.
- **Fan-out-signed skip markers.** Theater - the fan-out tier holds no key, and if it did, the filtering party certifying "I filtered nothing" proves nothing.
- **Leader-signed Merkle skip-evidence now (v2 path).** Over-engineering against an adversary that does not exist in v1 - exactly the BIP-37 -> BIP-158 mistake the industry unwound. Deferred, gated on an actually-deployed untrusted tier.
