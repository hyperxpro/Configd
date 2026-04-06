# Iter-3 Scope Decision

**Authored:** 2026-04-25 by code-level production-readiness pass (Opus 4.7).
**Baseline:** `./mvnw test` on JDK 25 + `--enable-preview` →
**21,346 tests, 0 failures, 0 errors, BUILD SUCCESS in ~57 s.** This
matches the iter-2 verify.md "BUILD SUCCESS" claim. (The first
measurement in this session reported 21,406 because it included
stale `TEST-*.xml` files from a prior iter-2 run; after `rm -rf
*/target/surefire-reports` the true clean count was 21,346.) Iter-3
will add tests for the wire-format changes; success criterion is
final reactor green at ≥ baseline + new tests, zero failures.

## Open carry-overs from iter-2 (per `docs/loop-state.json` `open_items`)

| ID | Carry-over | Touch points |
|----|------------|--------------|
| **W1** | Wire-protocol rev: version byte + CRC32C trailer | `FrameCodec`, `TcpRaftTransport` (re-validation), `WireFixtureGenerator`, ADR-0029 |
| W2 | Same wire-format dependency | `RaftMessageCodec` consumers (no own change — moves with W1) |
| W3 | Thread `MetricsRegistry` + `SloTracker` through `HttpApiServer` read path | `HttpApiServer`, `HttpApiServerMetricsTest` |
| W4 | Extend `SimulatedNetwork` chaos APIs (`addLinkSlowness`, `freezeNode`, `simulateTlsReload`, `clearChaos`, `setDropEveryNth`) | `SimulatedNetwork`, `ChaosScenariosTest` |

## Scope chosen for this pass: **W1 + W2 only**

### Why this scope

1. **Highest production-safety value.** A versioned, checksummed wire
   format is the foundation for any future rolling-upgrade story. Once
   v0.1 ships with `WIRE_VERSION=0x01`, v0.2 can negotiate a v2 format
   with N–1 ↔ N coexistence. Without the version byte, every future
   wire change becomes a hard break.
2. **Most code-local.** Touches `FrameCodec` (159 lines today) plus
   one length sanity check in `TcpRaftTransport`. No cross-module
   surgery; no ABI break to consensus / storage.
3. **Most testable.** Property tests (`FrameCodecPropertyTest`) and
   golden-bytes fixtures (`WireCompatGoldenBytesTest` + the 16
   pre-generated `wire-fixtures/v1/*.bin`) already exist in
   `.iter3-deferred-tests/`. Implementation must match them; that is
   the verification rubric — no test-author dispute possible because
   the fixtures predate the implementation.
4. **Unblocks two of three deferred property suites in one pass.**
   `FrameCodecPropertyTest` and `RaftMessageCodecPropertyTest` both
   compile against the new API. `WireCompatGoldenBytesTest` flips
   from "missing API" to byte-equality green.
5. **Discrete enough for one autonomous pass with adversarial review.**
   Estimated total surface: ~80 lines of `FrameCodec` rewrite plus 3
   test files moved back into `src/test/java`.

### What is explicitly NOT in scope this pass

| Carry-over | Reason for deferral |
|------------|--------------------|
| **W3 (HttpApiServerMetricsTest)** | Requires API-shape change in `HttpApiServer.handleRead` to thread `MetricsRegistry`/`SloTracker` through the request path. The test references at least 5 metric names (`configd_api_read_total`, `configd_api_read_errors_total`, `configd_api_read_duration_seconds`, plus SLO counters) that need wiring at every call site. Belongs in a separate observability pass. Carries over with same severity (P1 carry-over). |
| **W4 (ChaosScenariosTest)** | Five new chaos primitives in `SimulatedNetwork` are independent of wire format. Belongs in a chaos / test-infra pass. Carries over with same severity. |
| All v0.2-backlog S1/S2/S3 from `loop-state.json` | Per the iter-2 termination rationale (§5 stable_two_consecutive), these are not GA-blocking. Re-confirming the deferral; no change. |
| Calendar-bounded gates (C1–C4, runbook drills, on-call rotation) | Per §4.7 honesty invariant. Already documented in `docs/automation-prerequisites.md`. |

## Definition of done for this pass

1. **Wire format implemented to byte-equality with the
   pre-generated `wire-fixtures/v1/*.bin` fixtures.** All 16 fixtures
   (one per `MessageType`) match the live `FrameCodec.encode` output.
2. **`FrameCodec` exposes the new public API** consumed by
   `FrameCodecPropertyTest`:
   - `byte WIRE_VERSION`, `int HEADER_SIZE = 18`, `int TRAILER_SIZE = 4`
   - `class UnsupportedWireVersionException` with `int observedVersion()`
3. **`FrameCodec.decode` enforces** version equality, CRC32C
   trailer integrity, length prefix consistency, and known type code,
   in that order.
4. **The 4 deferred test files** move from `.iter3-deferred-tests/`
   back into `configd-transport/src/test/java/io/configd/transport/`
   (FrameCodecPropertyTest, plus `wirecompat/` subpackage with
   WireCompatGoldenBytesTest and WireFixtureGenerator) and
   `configd-server/src/test/java/io/configd/server/`
   (RaftMessageCodecPropertyTest).
5. **Existing `FrameCodecTest` continues to pass** (the API-surface
   tests are wire-format-agnostic; the one assertion on the literal
   constant `HEADER_SIZE` is updated to the new value with `frameSize`
   tested for both header AND trailer accounting).
6. **`TcpRaftTransport` length-sanity check is updated** to
   `HEADER_SIZE + TRAILER_SIZE` minimum (small fix; the existing
   constant reference still compiles but minimum-frame size grew by 5
   bytes — 1 byte header + 4 bytes trailer).
7. **`ADR-0029-wire-format-v1.md`** authored, documenting the v1
   layout, version byte semantics, CRC32C polynomial (Castagnoli per
   `java.util.zip.CRC32C`), the forward-compat contract for v2's
   negotiation, and the §8.10 fixture-bump enforcement loop.
8. **Full reactor `./mvnw test` passes** with test count ≥ 21,346
   (clean baseline) + delta from new property tests. Zero failures,
   zero errors.
9. **Adversarial reviewer subagent finds no new S0/S1** in the diff.

## Out-of-scope (revisit explicitly)

- **Backwards compatibility with v0 wire format.** v0.1 has not
  shipped (`docs/ga-review.md` Phase 9 still YELLOW: "release pipeline
  never exercised end-to-end"). No deployed peer speaks the
  current header-only format, so the new format is the *first* wire
  format. ADR-0029 captures this so the next wire change (v0.2 →
  v0.3 or v0.1 → v0.2 if the decision changes) does *not* assume the
  same green-field freedom.
- **Encryption, compression, or fragmentation at the frame layer.**
  TLS handles confidentiality + integrity at the connection layer
  already; the CRC32C is a defense-in-depth check against bit-flip /
  bug corruption inside a TLS session, not against a
  cryptographically-active attacker. ADR-0029 will say so.
- **Streaming / chunked frames.** Out of scope for v1; the 16 MB
  per-frame cap stays.

## Risk register for this pass

| Risk | Mitigation |
|------|------------|
| `TcpRaftTransport`'s loose 16 MB upper bound passes garbage past the length check, and the new CRC32C + version checks fire on the first byte — could mask a real network issue as "checksum bad" instead of "framing bad". | Order the validation: length sanity → version → CRC32C → type. Surface distinct exception types so the error log is unambiguous. |
| Outbound `TcpRaftTransport.PeerConnection` writes raw `encoded` bytes; if the encoder allocates a per-frame buffer with the wrong size, we get an `IndexOutOfBoundsException` at write time. | Property test `byteBufferAndArrayEncodeProduceIdenticalBytes` covers this. Plus the `frameSize(payloadLength)` formula is the single source of truth — every consumer goes through it. |
| `WireFixtureGenerator.main(--update-fixtures)` is now a checked-in maintenance tool; if a careless run flips the bytes without bumping `WIRE_VERSION`, we silently break N–1 ↔ N forever. | The CI `wire-compat` job greps the PR diff for a `WIRE_VERSION` change and fails if fixture bytes changed without it (per the WireCompatGoldenBytesTest Javadoc). Verify this CI step exists and works before declaring done; if missing, add it. |
| `CRC32C` is JDK 9+ (`java.util.zip.CRC32C`); we run JDK 25 so this is fine, but worth noting the dependency. | None needed; documented in ADR-0029. |

## What success looks like to the reader

A future engineer running `git log` sees a single commit titled along
the lines of "Iter-3 W1+W2: wire-format v1 (version byte + CRC32C trailer)
+ ADR-0029 + property tests" with:

- Diff under 500 lines net additions.
- `FrameCodec.java` ~60 lines longer than before.
- `ADR-0029-wire-format-v1.md` ~120 lines.
- 4 test files moved (no edits) from `.iter3-deferred-tests/` to
  `src/test/java/`.
- 16 wire fixtures committed (already present, not generated this
  pass).
- Reactor green at ≥ 21,346 tests (clean baseline), no flake re-runs.
