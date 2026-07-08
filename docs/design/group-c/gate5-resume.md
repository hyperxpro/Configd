# Gate 5 (Conformance Suite) — Resume Handoff

**Status: CHECKPOINT.** The conformance-suite *machinery* + both-direction *pattern* are built, proven, and
green; the remaining work is *volume* — applying the proven pattern to the **130 catalog clauses** the coverage
audit still lists as unmapped. This doc is the resume brief so the fresh effort picks up efficiently.

Everything below is on the WIP branch snapshot (uncommitted at checkpoint). Do **not** touch `docs/rfc/`
(the lead owns RFC fixes). Serialize builds on the 2-vCPU box; iterate on `-pl configd-conformance`.

---

## 1. What is DONE (green, leaf-verified)

`configd-conformance` runs **26 tests, all green EXCEPT `CoverageAuditTest`**, which is **red *by design*** until
every catalog clause is covered-or-skipped (the strict gate working as intended — not a bug).

- **Runner I — wire conformance (COMPLETE).** `wire/WireCases.java` = 62 cases: 46 golden round-trips (every
  `EdgeFrameGoldenBytes` fixture V1–V4 decodes→re-encodes byte-for-byte via the reused `EdgeFrameCodec`) + 16
  poison frames (10 frame-level + 6 inner-payload bounds). `wire/WireConformanceRatchetTest.java` asserts the
  actual outcome set EQUALS the checked-in `resources/conformance/wire-manifest.txt` — an unexpected FAIL *or*
  PASS both break the build (the `--failure_list` bidirectional ratchet).
- **No-version-negotiation invariant (COMPLETE).** `wire/NoVersionNegotiationTest.java` — 4 cases proving no
  hello/downgrade, fail-closed on unknown version/path.
- **The coverage framework (COMPLETE + strict).** `CoverageAuditTest.java` discovers every `@Tag("clause:<id>")`
  on this module's own test-classes (via the JUnit Platform launcher, discovery-only), maps them against
  `resources/conformance/catalog-clauses.txt` (all 244 clauses, normalized tag-safe), requires every clause to
  be COVERED xor listed in `resources/conformance/coverage-skips.txt` (else the build fails), and emits +
  golden-compares `configd-conformance/conformance-coverage.md` (the checked-in coverage record; regenerate +
  commit on a deliberate change).
- **Runner II — started (both directions demonstrated).**
  - *client-conforms:* `io.configd.client.http.ClientConformsHttpTest` (12 genuine cases) drives
    `ConfigdHttpClient` vs `MockControlPlane`.
  - *server-obeys:* the 4 seeded `RealServer{SubscribeHydrate,AuthModes,Watch,Http}Test` drive the real client
    vs a live `FanOutServer` / `HttpApiServer`, now `@Tag`-ed.

**Tally: 244 = 67 COVERED + 47 SKIP + 130 REMAINING.**

Test-jars added for fixture reuse (one source of truth, no re-declaration): `configd-wire` (EdgeFrameGoldenBytes),
`configd-client-edge` (MockEdgeServer), `configd-client-http` (MockControlPlane).

---

## 2. Case-authoring conventions

**Tagging.** Every conformance test method (or class) carries `@Tag("clause:<id>")` for each clause it
**genuinely asserts**. `<id>` is the **normalized** catalog id (see the header of `catalog-clauses.txt`:
whitespace removed, `/`→`_`, `(`→`_`, `)` dropped — e.g. `F6-2 / F6-2a` → `F6-2_F6-2a`, `OV7-4(3)` → `OV7-4_3`).
Grouping is encouraged: one well-asserted test MAY carry several clause tags. **DEPTH RULE (non-negotiable):** a
tag means the test *asserts the specified behavior* (the right status / ErrorCode / frame / state transition) —
never a token call that merely references the id. If a clause is not meaningfully live-testable at v1, SKIP it
honestly (below) rather than a weak pass.

**Discovery scope.** `CoverageAuditTest` scans `target/test-classes` (this module's own compiled tests), NOT the
reused test-jar deps — so a client-conforms case may live in a plane package (e.g. `io.configd.client.http`) to
reach a package-private mock and still be discovered.

**The two directions (author BOTH where the clause binds both):**

- **client-conforms** — drive the reference client against a scriptable / hostile mock, assert the client obeys.
  - HTTP: `ConfigdHttpClient` vs `MockControlPlane` (enqueue scripted responses; inspect `recorded()` requests).
  - edge: `ConfigdEdgeClient` / `Watch` / `Subscription` vs `MockEdgeServer` (`startPlaintext(conn -> …)`;
    the mock reads the client's frame and sends server frames — remember a 0x02 watch connection needs
    `conn.send(frame, EDGE_WIRE_VERSION_V2)`).
- **server-obeys** — drive the real client against a live in-process server, assert the server behaves.
  - HTTP: a live `HttpApiServer` + `AdminApiHandler` wired with a stub `ConfigWriteService` proposer returning
    `Committed(seq)` + `AuthInterceptor` + `AclService` (all main classes; no full Raft). See `RealServerHttpTest`.
  - edge: a live `FanOutServer` (+ `EdgeAuthConfig` for auth). See `RealServerAuthModesTest` / `RealServerWatchTest`.

### Worked example — client-conforms (HTTP), from `ClientConformsHttpTest`

```java
@Test
@Tag("clause:D4-2")   // seq is parsed from the BODY, not a header
@Tag("clause:D4-7")   // a 200 is committed-and-applied
void writeParsesSeqFromTheBodyNotAHeader() throws Exception {
    try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
        s.enqueue(Response.committed(42));                       // server scripts: 200 "Committed: seq=42"
        WriteOutcome w = c.blocking().put("k", "v".getBytes(UTF_8), WriteOptions.defaults());
        assertEquals(42L, w.seq());                              // GENUINE assertion of the clause
    }
}
```

### Worked example — server-obeys (HTTP), from `RealServerHttpTest`

```java
// class @Tag("clause:D3-7") ... — the reserved-prefix ADMIN gate, driven against the real handler:
try (ConfigdHttpClient writer = client(base, "writer-tok")) {          // a non-ADMIN principal
    assertThrows(ForbiddenException.class,
            () -> writer.blocking().get("_acl/roles/x", GetOptions.defaults()));  // real server → 403
}
try (ConfigdHttpClient admin = client(base, "admin-tok")) {            // an ADMIN principal
    GetResult r = admin.blocking().get("_acl/roles/x", GetOptions.defaults());
    assertFalse(r.found());                                            // gate passes → 404 (absent), not 403
}
```

The edge equivalents: client-conforms via `MockEdgeServer` (see the Gate-3 `EdgeWatchTest` patterns for dedup /
order / progress / catch-up / full-chain-verify / cancel), server-obeys via `FanOutServer` (see `RealServerWatchTest`).

---

## 3. The 130 remaining clauses — grouped, with harness + direction

Get the live list any time: run `CoverageAuditTest`; its failure message prints the exact UNMAPPED ids. Groups:

| Group | Clauses (normalized) | Harness + direction |
|---|---|---|
| **A — paths / access / authz** (~17) | A2-1, A2-3, A3-1..A3-3, A3-4, A3-5, A4-2, A4-7, A5-2, A6-1, A6-2, A6-3, A6-4_INV-WATCH-READ, A6-5, A8-2, A9-3, A9-4 | A3-x path/scope grammar → **client-conforms** (client rejects malformed path/scope before the wire; server accepts per D8-2 — assert both). A5-2/A6-x watch-authz + A6-5 (unauthorized sub ⇒ terminal, ZERO data frames first — a *required* negative case) → **server-obeys** vs live `FanOutServer` with an authorizer that denies. |
| **AU — authentication lifecycle** (~19) | AU3-3(codec), AU4-1..AU4-7, AU5-1..AU5-4, AU5-6, AU6-1, AU6-2, AU7-1..AU7-3, AU8-1..8-4 | AU4-x connect/auth/pipeline + AU5-x expiry/refresh → **both**: client-conforms vs `MockEdgeServer` (AUTH frame, REFRESH_AUTH, CREDENTIAL_EXPIRED handling — reuse the Gate-1 `EdgeConnectionAuthTest` scenarios) + server-obeys vs live `EdgeAuthGateHandler`. AU3-3 is codec → tag the wire runner. |
| **D — data-plane details** (~23) | D1-1_D1-2, D2-2, D2-4, D2-5_D2-5a, D2-6, D3-2a, D3-5_D3-5a, D3-6, D3-8, D4-4, D4-5, D4-6, D8-1..D8-4, D9-1, D10-1, D10-2, D11-1..D11-4 | Mostly **client-conforms** vs `MockControlPlane` (extend `ClientConformsHttpTest`): D3-5a strong-read header-not-name, D3-6 ordinary-key-linearizable-503, D4-4/D4-5 scope + `_acl/` policy-400, D4-6 outcome→status table, D8-x limits, D10 health-JSON, D11 no-CAS/replay-guard. D9-1 (no list wire) → **SKIP:not-in-v1**. |
| **R — routing / leader-following** (~9) | R2-1, R2-2, R3-2, R4-1_R4-2, R4-4, R5-1..R5-4, R7-1_R7-2, R8-4 | **client-conforms** vs `MockControlPlane` returning `X-Leader-Hint` (follow-once, hintless-backoff, numeric-NodeId, no-client-sharding, unresolvable→hintless — the `ConfigdHttpClientTest` leader-follow cases, re-expressed here). R3-2 (no discovery endpoint) + R8-4 (hint authz-gated) → **server-obeys** (404 / 401-before-hint). |
| **W — watches** (~32) | W1-1(codec), W1-3, W2-1..W2-8, W3-1/W3-3/W3-5(codec), W4-1..W4-5, W5-1..W5-12 (+W5-4a/W5-9a), W6-1/W6-3/W6-5, W7-1..W7-7, W8-2/W8-3/W8-4, W10-2..W10-8 | The big cluster. **client-conforms** vs `MockEdgeServer` (dedup by (gid,S), per-key/per-shard order, WATCH_PROGRESS advance, resume-from-vector, per-(watch,gid) catch-up, full_chain_verify, cancel-no-reuse — all already exercised in Gate-3 `EdgeWatchTest`/`EdgeWatchMultiplexTest`; re-express + tag here) + **server-obeys** vs live `FanOutServer` (RealServerWatch covers a few). W7-x watch-authz → server-obeys. The codec W-fields (W1-1, W3-1/3/5, W5-1..) → tag the wire runner. |
| **E — error taxonomy** (~5) | E2-1, E3-2, E3-3, E4-1, E7-1 | **client-conforms**: E2-1 HTTP status table (vs `MockControlPlane` — most already asserted in `ClientConformsHttpTest`, add tags), E3-2 catch-up-ladder / E3-3 (code,carrier) scope / E4-1 401-vs-403 both planes (vs `MockEdgeServer` sending ERROR_CLOSE / WATCH_CANCELED), E7-1 retry classes. |
| **OV — overview / architecture** (~5) | OV2-1, OV2-3, OV4-1, OV5-4, OV7-3 | **client-conforms**, structural: OV2-1/OV2-3 (no write frame on edge, no watch route on HTTP — assert the absence), OV4-1 (two version mechanisms), OV5-4 (vector cursor + leader-follow at N=1), OV7-3 (fail-closed on anything unknown). |
| **F — wire framing** (~20) | F4-1, F4-2, F5-1, F5-2, F5-3, F6-9, F6A-5, F9-2, F9-3, F10-1..F10-4 (+F10-1a..1e), F13-1..F13-9 | F4-1/F4-2 pin + F5-x u64-encoding + F6-9 message-sanitize + F6A-5 → **tag the wire runner** (add a u64-high-bit poison case for F5; a message-with-control-bytes fixture for F6-9). F9-2/F9-3 (TLS profile) → **client-conforms** (the F9 TLS tests exist in `EdgeTlsTest`) or **SKIP:model**. F10-x flow-control (DEMOTED_TO_CATCHUP non-fatal, QUARANTINED, slow-consumer) → **client-conforms** vs `MockEdgeServer` sending those terminal/notice frames (F10-1b refuse-cursored-share is already proven in `RealServerWatchTest`/`EdgeWatchMultiplexTest` — tag it). **F13-1..F13-9 (the §13 Raft plane) → SKIP:not-in-v1** (explicitly out of driver conformance scope, catalog §7.11). |

---

## 4. SKIP conventions

Add a line to `resources/conformance/coverage-skips.txt`: `<clause-id> | SKIP:<category> (<reason>)`. Categories:

- `SKIP:not-testable-v1` — dormant at v1 static-N=1 (single shard / one topology epoch); codec-only where a
  vector exists. The §7.7 set: STALE_TOPOLOGY(12), WATCH_CANCELED.oldest `has_oldest=1`, `prev_value`,
  cross-shard ordering. **Never a false live-pass** — exercise the codec byte-vector where one exists + annotate.
- `SKIP:not-in-v1` — no v1 wire surface (no `list` wire — D9-1; the §13 Raft plane — F13; N>1-only semantics).
- `SKIP:model` — a design / model / trust / presentation statement with no wire-observable behavior.
- `SKIP:operator` — a deployment / operator obligation (not a client or server wire behavior).
- `SKIP:guidance` — non-normative guidance / suite-scope prose.

A clause must be COVERED **xor** SKIP (never both). The 47 current skips were derived from the catalog's own
Testable column; extend the same way.

---

## 5. Resume-to-green steps

1. Install the fixture test-jars once: `./mvnw -q -pl configd-wire,configd-client-edge,configd-client-http install -DskipTests`.
2. Iterate: `./mvnw -pl configd-conformance test -Dtest=CoverageAuditTest` — read the UNMAPPED list.
3. For each unmapped clause: either write/extend a genuinely-asserting case (`@Tag("clause:<id>")`, both
   directions where it binds both) **or** add an honest `coverage-skips.txt` line. Work by group (§3). Reuse the
   Gate-1/2/3/4 client tests' scenarios as the client-conforms bodies; reuse the RealServer tests as the
   server-obeys bodies.
4. When the audit stops reporting UNMAPPED, it will fail once more asking to refresh the golden
   `conformance-coverage.md` — review the tally (covered / skip-by-category) for honesty, then copy the
   generated `target/conformance/conformance-coverage.generated.md` to `configd-conformance/conformance-coverage.md`.
5. Re-run — `CoverageAuditTest` green ⇒ the whole module green.
6. Run the authoritative reactor SOLO (ping the lead; hold the box):
   `./mvnw -pl configd-transport,configd-distribution-service,configd-conformance install -DskipITs`
   (the two module-source guards + the suite). Report BUILD SUCCESS + both guards green + the coverage tally.
7. The lead commits Gate 5 (Runner I + II + coverage + the test-jar pom changes + the
   `EdgeFrameGoldenBytes.forVersion` visibility change).

**Depth check before declaring done:** the lead spot-checks case depth against `conformance-coverage.md`. Every
`COVERED` clause must trace to a test that genuinely asserts the behavior; every `SKIP` must have a legitimate,
honest reason. A green audit with shallow tags is worse than an honest smaller covered-count.
