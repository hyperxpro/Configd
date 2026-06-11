# C1 Design Review — review-architect gate for Session 3 component C1

> **Scope:** ADR ratification (A1: ADR-0037, A2: ADR-0038), C1 design screen (B),
> Phase V1 machinery review (C). This file gates whether C1 implementation may begin
> (charter §1 rule 2, §2, §4 C1, §6 rules). Read-only on code; one file written.
> **Reviewer:** review-architect. **Date:** 2026-06-11. **Branch:** session-3-data-plane.

Finding severities: **BLOCKING** (must change/resolve before the affected work proceeds),
**REQUIRED** (must land during C1, tracked, not a gate on starting), **NOTE** (advisory).
Each finding carries an explicit prod-blocking flag where it bears on production.

Verification posture: every factual claim below was checked against repo code at the
commits on `session-3-data-plane` (HEAD `ebca440`). No Maven build was run (a probe agent
may be active; reading sufficed for every claim). `grep io.netty` across all `pom.xml`
and all `import` lines: **zero hits** (re-verified).

---

## A1. ADR-0037 — Edge transport reuses the JDK-socket/TlsManager/FrameCodec stack (no Netty)

### Verdict: **RATIFY-WITH-CHANGES**

Every load-bearing factual claim verified true against the code:

- **Zero Netty.** `grep io.netty` across poms + imports → 0. ADR-0010's "Netty" is confirmed
  audit-fiction; this ADR amends the record rather than perpetuating it. ✔
- **mTLS = `setNeedClientAuth(true)`, TLSv1.3, PKCS12.** `TcpRaftTransport.java:512`
  (`serverSocket.setNeedClientAuth(true)`); `TlsManager.createSslContext()` builds a
  `TLSv1.3` SSLContext from PKCS12 key/trust stores. "Identical classes" claim holds:
  C2 reuses `TlsConfig`/`TlsManager` verbatim, so "mTLS consistent with the control plane's"
  is true by construction. ✔
- **FrameCodec discipline.** `FrameCodec`: length-prefix bounds-checked before allocation
  (`peekLength` :324, `decode` :257 range check `[HEADER+TRAILER, MAX_FRAME_SIZE]`),
  version byte (`WIRE_VERSION=0x01`), type byte, CRC32C trailer verified **before** any
  field is read (:268-285), explicit 16 MiB cap (`MAX_FRAME_SIZE :86`), symmetric
  encoder/decoder cap. The "separate codec/version byte for the edge protocol" decision is
  sound and well-justified (decoupling the edge cadence from the Raft golden-fixture gate). ✔
- **RR-002 architecture.** `TcpRaftTransport` post-RR-002: dedicated single connector thread
  (`connectExecutor`, :135), bounded connect (`CONNECT_TIMEOUT_MS`) + bounded handshake
  (`setSoTimeout(HANDSHAKE_TIMEOUT_MS)` :477, cleared :479), virtual-thread-per-connection
  (`Executors.newVirtualThreadPerTaskExecutor()` :184), connect/handshake never on the caller
  (tick) thread. The "reuse inherits RR-002's verification" argument is valid. ✔

**Adversarial attack on the virtual-thread/JDK-socket soundness at the stated scale (§12):**
The ADR's scale argument is **sound at the architecture's own stated tree shape** but the ADR
**misstates the scale envelope and must be corrected**. §12's tree is k=16 at tier 1, k=64 at
tier 2 → a *fan-out node* (relay) has tens to low-hundreds of long-lived downstream streaming
connections. Virtual-thread-per-connection with blocking JDK sockets is comfortably correct
there: each subscriber is one slow streaming consumer, not 10k ephemeral request/response
sockets, so Netty's syscall-amortization advantage is genuinely absent. The reasoning is
correct. **However**, PROMPT §baseline says *10k edge nodes baseline, 1M ceiling*. The ADR's
"tens to low hundreds per node" is true only because the **tree** amortizes 10k edges across
relay tiers — the ADR states the per-node bound as if it were the whole-system bound. That is
the right number for the right reason but the ADR does not show its work, so a future reader
could mistake it for a claim that the system only has hundreds of edges.

#### Findings

1. **[REQUIRED — non-blocking for prod]** The ADR's "tens to low hundreds of connections per
   node" must explicitly state that this is the **per-fan-out-node** subscriber count under the
   §12 tree fan-out (k=16/k=64), NOT the system edge count (10k baseline / 1M ceiling). As
   written, §2 "Scale honesty" reads as if the system has hundreds of edges. The conclusion is
   correct; the derivation must be shown so the §12 amortization is the stated reason. Fix is a
   one-paragraph edit; does not block ratification.

2. **[NOTE — non-blocking]** The ADR's escape hatch ("swap the server socket loop for an
   NIO/Netty endpoint is localized behind the C1 session abstraction") is only true if
   `FanOutSessionCore` is genuinely transport-agnostic (no socket types in its API). The C1
   design (B) does isolate this behind `TransportSink`; the ADR's claim is therefore consistent
   with the design but is **contingent on B's `TransportSink` boundary being honored** — flag
   for the C1 design-note closeout to confirm the seam held.

3. **[NOTE — non-blocking]** Hard-rule-5 compliance (no new external runtime deps) is satisfied:
   the ADR's dependency list for `configd-edge-node` is all first-party. Good. The C1-side
   classes land in `configd-distribution-service`, which already depends on config-store +
   transport — confirmed no new third-party dep is introduced.

**Sign-off (A1):** ADR-0037 is factually accurate and the no-Netty decision is correct and
well-priced. **RATIFY-WITH-CHANGES** — finding 1 (scale-envelope wording) must be applied to
the ADR text; it is editorial, not substantive, so C1 is not gated on it landing first. The
review-architect sign-off line in the ADR may be recorded as ratified-with-the-noted-edit.

---

## A2. ADR-0038 — Fan-out streams the verbatim signed delta chain; no server-side coalescing; prefix subscription is an edge-side storage filter

### Verdict: **RATIFY**

Every signature/chain claim verified against code:

- **F-0052 mechanics.** `ConfigDelta.signingPayload()` (:129) = `encodeBatch(mutations) ‖ BE(epoch,8) ‖ nonce`
  for F-0052 deltas, reducing byte-identically to `encodeBatch(mutations)` for legacy
  (epoch 0, empty nonce). `DeltaApplier.offer` (:184) is genuinely **fail-closed**: rejects
  signed deltas when no verifier (`UNSIGNED_REJECTED` :192), rejects unsigned when verifier
  present (:201), `SIGNATURE_INVALID` on bad/erroring verify, `REPLAY_REJECTED` on
  `epoch <= highestSeenEpoch` (:223), with on-disk epoch persistence (SEC-017) so a restart
  cannot accept an older leader-signed delta as fresh. ✔
- **Chain enforcement.** `DeltaApplier.offer` enforces `delta.fromVersion() == currentVersion`
  (:239) → `GAP_DETECTED`; `toVersion <= currentVersion` → `STALE_DELTA`. Exact `seq+1`/gap
  semantics. ✔
- **Leader holds the key, relay does not.** Production `ConfigdServer` listener
  (`ConfigdServer.java:367-389`) forwards `stateMachine.lastSignature()/lastEpoch()/lastNonce()`
  into the `ConfigDelta` on the apply path — the leader signs; the fan-out buffer (`publish`)
  only carries the already-signed bytes. The fan-out service never signs. ✔
- **ADR-0030 Quicksilver premise.** ADR-0030 adopts the Quicksilver shape (centralized write,
  async full fan-out) and records that `PlumtreeNode.broadcast()` was benchmark-only/unwired.
  So "every edge gets everything" is the chosen topology, not an invented one. ✔
- **ADR-0020 prefix model / architecture §7.** §7 specifies prefix (primary)/full-store/per-key;
  ADR-0038's reframing (prefix → edge-side storage filter, full chain on the wire) is a
  documented, justified deviation. ✔

**Adversarial attack on the security argument — is it airtight?**
The core claim is: *a relay cannot rewrite, coalesce, or suppress a single delta without the
edge detecting it.* This holds:

- **Rewrite/coalesce:** any payload the leader did not sign fails `verifier.verify()` →
  `SIGNATURE_INVALID` (fail-closed). Coalescing produces unsigned bytes → rejected. ✔
- **Single-delta suppression:** dropping delta N makes delta N+1's `fromVersion` mismatch the
  edge's `currentVersion` → `GAP_DETECTED` → catch-up. A relay cannot silently skip one delta;
  the chain break is structural. ✔ This is exactly why transport-level prefix filtering is
  correctly **forbidden** — a "nothing here for you" skip-marker would be relay-asserted, not
  leader-signed, reopening the suppression channel (especially for `secure/` keys). The ADR's
  reasoning that prefix-as-transport-filter breaks suppression-detectability is correct.
- **Residual:** wholesale stream stalling (relay stops forwarding entirely). The ADR honestly
  names this and points at the staleness state machine + commit-timestamp clock (ADR-0035) as
  the detector. **This residual is real and is entangled with B's open decision 1** (see B-1):
  a stalled-but-heartbeating relay can mask staleness for suppressed keys, and the HEARTBEAT is
  relay-asserted, not leader-signed. The ADR characterizes this honestly and does not overclaim.

**Is frame-level batching genuinely chain-preserving?** Yes, by construction: a `NOTIFY` frame
carries N *consecutive* `CommitNotification`s, each an unmodified signed delta in seq order
(design §3). Nothing is merged; the edge verifies and applies each link independently. The
"exact coalescing/gap rule the charter demanded" is satisfied as *may-not-collapse* + exact
`fromVersion == currentVersion` per delta. This directly answers charter §4 C1's "define and
test the exact rule." ✔ (CT-17 is the test; currently UNIMPLEMENTED, correctly so.)

**Is the bandwidth-honesty paragraph honest? (Computed at baseline.)**
Baseline (PROMPT): 10k writes/s, 1 KB typical payload. Per-edge egress of the full chain ≈
**10 MB/s ≈ 80 Mbit/s sustained per edge stream**. The ADR's claim "per-edge egress equals the
write stream" is **arithmetically correct and honest** — it does not pretend prefix filtering
buys per-edge savings (it explicitly does not, since the full chain is on the wire). The §12
tree amortizes the *source/relay* fan-out (a relay receives one stream, re-fans to k children),
so the 10k-edge aggregate (≈100 GB/s if naively centralized) is not borne at one node — the ADR
correctly leans on the tree for this. The named upgrade path (leader-signed Merkle skip-evidence)
is the honest escape hatch and is correctly scoped out.

**The one honesty gap:** the ADR computes only the *baseline*. PROMPT also specifies a **100k/s
burst**. At 100k/s × 1 KB = **800 Mbit/s per edge stream** — that saturates most edge uplinks
and makes "full chain to every edge" a real operational constraint under burst, not just a
baseline footnote. The ADR's "bandwidth honesty" section should state the burst number too, so
the upgrade-path trigger (Merkle skip-evidence) is anchored to a concrete pain threshold.

#### Findings

1. **[REQUIRED — non-blocking for prod, blocking-for-honesty]** The "Bandwidth honesty" section
   (§3) computes only the 10k/s baseline (≈80 Mbit/s/edge). Add the 100k/s burst figure
   (≈800 Mbit/s/edge) and state explicitly that full-chain-to-every-edge is the design's known
   bandwidth cost under burst, with the Merkle-skip-evidence upgrade path as the mitigation when
   a deployment's edge links cannot carry the burst. This makes the honesty paragraph honest at
   both load points. Does not block ratification or C1 (the decision stands; only the stated
   envelope widens).

2. **[NOTE — prod-relevant]** The residual freshness attack (stalled-but-heartbeating relay
   masking staleness for suppressed keys) is shared between this ADR and B's open decision 1.
   It is acceptable **only because** the next real delta breaks the chain and the staleness
   clock surfaces the stall. This coupling must be stated once, canonically — recommend B-1's
   ADR-0039 owns the canonical characterization and ADR-0038 cross-references it (rather than
   each restating the trust boundary slightly differently). See B-1.

3. **[NOTE — non-blocking]** ADR-0038 §Consequences correctly notes the V1 invariant
   "snapshot–delta equivalence" compares full-store state for full-store subscribers and
   subscribed-subset state for prefix subscribers. The V1 machinery (C) currently only models
   full-store edges (every edge applies every delta) — the prefix-subset convergence variant is
   a C2 concern and is correctly not in V1. No action for C1; flag for C2's design note.

**Sign-off (A2):** The security argument is airtight on the attacks that per-delta signatures
exist to defeat (rewrite, coalesce, single-delta suppression). The bandwidth paragraph is honest
at baseline and must add the burst figure (finding 1). The residual (wholesale stall) is honestly
characterized. **RATIFY.** The review-architect + contract-qa sign-off lines may be recorded;
contract-qa flips CT-17 and CT-25 to ADR-RENEGOTIATED(adr-0038) on this ratification. Finding 1
is an editorial widening, not a re-decision, so it does not gate C1.

---

## B. C1 Design Screen — `c1-fanout-design-draft.md`

### Verdict: **CLEARED-WITH-CONDITIONS** (C1 may implement the wire path, codec, session core,
### backpressure, and full-chain delivery now; idle-staleness measurement and the HEARTBEAT
### frame semantics are DEFERRED to C2 behind ADR-0039 — see B-1).

#### Performance-disqualifying-design screen (charter §6 rule 4) — **PASS**

Verified against code, not just the design's own claim:

- **No unbounded queue.** Per-session bounded outbound queue (`edge.fanout.session.queueFrames`
  = 256), overflow → demotion to CATCHUP with cursor evidence + metric + structured log. Never
  an unbounded queue; never a silent drop. ✔
- **No per-update full-snapshot shipping.** NOTIFY ships verbatim deltas (frame-batched); full
  snapshot only on Gap/bootstrap, chunked at 1 MiB with per-chunk CRC (RR-019 lesson). ✔
- **No O(subscribers) work under a global lock on the publish path.** Confirmed by reading the
  real wiring: `ConfigdServer.java:367-389` — the apply thread's only fan-out interaction is
  `fanOutBuffer.publish(...)`, which is `FanOutBuffer.publish` (`FanOutBuffer.java:112`):
  lock-free, allocation-free (wraps the notification reference into an `AtomicReferenceArray`
  ring; single-writer; RR-096-corrected eviction order). Sessions **pull** via `readSince`; no
  per-subscriber work, no signaling primitive, and no global lock touches the apply path. The
  subscriber registry is touched only on connect/disconnect. The publish-path-isolation claim
  is **true as verified**, not merely asserted. ✔

One screen-level caveat: the drain wake-up is "poll with adaptive backoff, idle parkNanos capped
at 5 ms." Polling a lock-free ring is allocation-free and off the apply path, so this passes rule
4. But N sessions each polling every ≤5 ms is O(subscribers) *poll* work on the **drain** side
(not the publish side). At the §12 per-node subscriber count (tens-to-hundreds) this is trivial;
it is flagged (B-4) only so C1 measures it rather than assumes it.

#### Layering — **SOUND**

`FanOutSessionCore` as the sim's `StreamDriver` is the right structural countermeasure to
Session 1's "documentation of another system" failure. Verified the V1 `StreamDriver` interface
(`configd-testkit/.../StreamDriver.java`): its `Context` exposes exactly `source(cpNode)` /
`replaySource(cpNode)` / `send(edge, msg)` / `nowMs()` — i.e. the ADR-0034 seams and the edge
network. A real C1 driver consuming `source(cpNode).readSince(cursor)` and pushing
`EdgeStream.Notify` / on-Gap `EdgeStream.Snapshot` is exactly what `StreamDriver.NONE`'s Javadoc
says C1 must supply. The design's claim "the same code the live endpoint runs" is achievable
**iff** `FanOutSessionCore` depends only on `CommitNotificationSource`/`ReplaySource` + a
`TransportSink`, never on a socket type. That is the design's stated intent; the C1 closeout must
prove the seam held (also A1-finding-2).

#### Protocol table — gaps for C2/C3/C4/C5

The v1 frame table (design §3) is good for C1 (SUBSCRIBE/SUBSCRIBE_OK/NOTIFY/SNAPSHOT_*/
CURSOR_ACK/HEARTBEAT/ERROR-CLOSE) and carries the §6-open-decision-1 HEARTBEAT either way. Gaps
the C1 codec/protocol-version should reserve space for now (cheaper than a v2 bump later):

- **Resume semantics (C3/C5):** SUBSCRIBE carries a "resume cursor" and SUBSCRIBE_OK picks
  TAIL vs SNAPSHOT_FIRST — good. But there is no explicit **resume-after-reconnect-to-a-different
  -endpoint** field for the monotonic-read failover clause (contract §3 "Edge Failover" /
  CT-? failover). The edge-failover cursor (client's last `VersionCursor` passed on reconnect) is
  a C2 concern, but the wire must be able to carry it. **Reserve the field in SUBSCRIBE now.**
- **Auth (C2):** the table has no auth/identity frame beyond mTLS. mTLS pins the *connection*
  peer; if subscriptions are ever authorized per-prefix (e.g. `secure/` access), an identity →
  authorized-prefix-set decision needs a home. ADR-0038 makes the full chain go to every edge
  regardless, so per-prefix transport auth is N/A-by-construction *today* — but the edge id in
  SUBSCRIBE should be bound to the mTLS cert identity, not self-asserted. **Note for C2.**
- **Max frame sizes / error taxonomy:** SNAPSHOT_CHUNK caps at 1 MiB and NOTIFY batch caps at
  256 KiB / 64 notifications — good, named configs. The **error taxonomy** (ERROR/CLOSE "code,
  message") is underspecified: C1 must enumerate the codes (GAP-unrecoverable, version-mismatch,
  demotion, quarantine, frame-too-large, bad-wire-version, auth-fail) so C2/C3/C4 map to a fixed
  set rather than inventing strings. **REQUIRED in the C1 codec (CT-41 golden fixture should pin
  the error codes too).**

#### Backpressure design — **SOUND**, one reconciliation owed

Bounded queue + overflow→CATCHUP demotion with cursor evidence is exactly charter C1 / §11. The
named configs each have a metric (charter §6 rule 8). **One documented conflict (already flagged
in the contract-test map CT-26):** architecture §7 credit numbers (100 credits, 1000-entry buffer,
80%/100% disconnect) predate ADR-0034's ring-10,000 and ADR-0038's frame/byte accounting. The C1
design note **must state which numbers govern** (the design's `queueFrames=256` etc.) and that
the §7 credit model is superseded for the edge streaming path. Without this the slow-consumer math
(C4) inherits two conflicting threshold sets.

#### Open decisions — arbitration

**B-1. [BLOCKING for the staleness-measurement sub-feature; NON-BLOCKING for C1 start]
Open decision 1 (idle staleness).** The claim is verified true against code: `StalenessTracker`
(`StalenessTracker.java`) measures `now − lastUpdateNanos` (pure idle time), `recordUpdate` is the
only reset and is called only on an applied delta, and `currentState()` walks
CURRENT→STALE→DEGRADED→DISCONNECTED purely on elapsed idle time. Therefore an idle-but-healthy edge
(no commits for 30 s — normal config workload) **does** march to DISCONNECTED and trigger
re-bootstrap, exactly as the draft asserts. Contract §2/INV-S1 as written
(`staleness = wall_now − commit_ts(last_applied_notification)`) is genuinely unsound for the idle
case (it is the staleness of the *data*, conflated with the staleness of the *connection*).

  *Arbitration of the proposed frontier-based fix*
  (`staleness = wall_now − max(commitTs(lastApplied), serverNowMillis(last HEARTBEAT where
  latestSeq == cursor))`): this is the **correct** decomposition — it separates "my data is old
  because nothing changed" (healthy, HEARTBEAT-covered frontier) from "my data is old because I'm
  cut off" (unhealthy, no fresh HEARTBEAT). The residual trust is real and **acceptably
  characterized**: a stalled-but-heartbeating relay can mask staleness *for the keys it
  suppresses*, but only until the next real delta breaks the chain (the ADR-0038 residual). The
  HEARTBEAT is relay-asserted, not leader-signed — so this measurement trusts the relay's liveness
  assertion. That trust is **bounded and honest**: it cannot manufacture a *newer* version (the
  chain + signatures prevent that), only assert "nothing new through seq=cursor," which a
  malicious relay could lie about — but the same relay could simply stop forwarding, which is the
  already-accepted wholesale-stall residual. The frontier measure does not *expand* the trust
  surface beyond what ADR-0038 already concedes. **Decision: adopt the frontier-based measure.**

  *Should ADR-0039 be written before C2 rather than before C1?* **Yes — ADR-0039 is a C2
  prerequisite, NOT a C1 prerequisite.** The contract-test map confirms the split: CT-01 (make
  `StalenessTracker.recordUpdate`'s timestamp load-bearing), CT-02, CT-07 are all **C2-owned**;
  the producer half (CT-21, commit timestamp on the notification) is already DONE and PASSING. C1
  only needs to *carry the HEARTBEAT frame* (latestSeq + serverNowMillis), which the design §3
  table already does. C1 does **not** implement the staleness measurement — that lives in the edge
  (`StalenessTracker`, configd-edge-cache, C2). **Therefore: C1 ships the HEARTBEAT frame with
  (latestSeq, serverNowMillis) and the named cadence config; the *interpretation* of that frame
  into a frontier-based staleness measure is specified by ADR-0039 and implemented in C2.** Write
  ADR-0039 before C2, not before C1. This unblocks C1 immediately while keeping the unsound idle
  measurement from shipping. **Prod-blocking:** the *current* idle-time `StalenessTracker` must
  NOT be wired as the production staleness signal — it is a proxy (contract §2 already labels it
  so). That is a C2 gate, recorded here.

**B-2. [REQUIRED — non-blocking] Open decision 2 (ADR-0037/0038 ratification).** Resolved by A1
(RATIFY-WITH-CHANGES) + A2 (RATIFY) above. The design's dependency on both is satisfied.

**B-3. [NOTE] Open decision 3 (SNAPSHOT format).** Reusing ADR-0028 `serializeSnapshot` opaque
state-machine `data`, chunked at the frame layer, is the right call — no second serialization
format to version, and the chunking (1 MiB + per-chunk CRC) addresses the RR-019 InstallSnapshot
cliff. **Confirmed: reuse ADR-0028.** One caveat for the C1 note: the snapshot the fan-out ships
must be the *opaque* state-machine bytes the edge's `LocalConfigStore.loadSnapshot` consumes —
confirm the edge deserializer and the CP `serializeSnapshot` are the same format (a C2/C3
convergence test, but C1's SNAPSHOT framing must not assume a key-value layout).

**B-4. [NOTE] Open decision 4 (`secure/` keys at the edge).** Confirmed: ADR-0038 delivers
`secure/` on the chain for suppression-detectability; C1 does **no** key-class filtering (the full
chain is universal). The store-and-never-serve vs store-nothing+route decision is C2's
(CT-37, C2-owned). **Confirmed: C1 carries `secure/` deltas with no C1-level filtering.** The C1
design note must state this explicitly so a later reviewer does not "helpfully" add a filter.

**B-5. [NOTE] Open decision 5 (heartbeat cadence vs staleness thresholds).** 250 ms heartbeat vs
500 ms STALE threshold = 2× margin on an idle stream. **Confirmed reasonable** — one missed
heartbeat still leaves the edge inside CURRENT. Tie this to B-1: the margin is only meaningful
*after* the frontier-based measure (ADR-0039) makes HEARTBEAT load-bearing; under today's idle
clock the cadence is irrelevant. Keep 250 ms as the named default; revisit if C2 measures jitter.

Additional drain-side note: **B-4-perf [NOTE]** the 5 ms idle-poll backoff is O(subscribers) poll
work on the drain side (not publish side). Trivial at §12 scale; C1 should emit a drain-loop
wakeup metric so Session 5 can confirm it stays trivial. Not a rule-4 disqualifier.

#### What C1 may implement NOW vs what is BLOCKED

**C1 may implement now:** `EdgeFrameCodec` + `EDGE_WIRE_VERSION=0x01` + golden fixture (CT-41,
including the batched NOTIFY form and the enumerated error codes); `FanOutSessionCore` (cursor,
bounded outbound queue, drain loop `readSince`→NOTIFY batches, Gap→chunked SNAPSHOT→resume tail,
overflow/ack-lag demotion *events*); `FanOutServer` mTLS endpoint; full-chain verbatim delivery
(CT-17, CT-25 C1-half, CT-18 already PASSING substrate); the HEARTBEAT frame as a *carrier*
(latestSeq, serverNowMillis); all named configs + metrics; the V1 `StreamDriver` wiring to
re-enable `EdgePropagationBacklogTest`.

**Blocked / deferred out of C1:** the *interpretation* of HEARTBEAT into a frontier-based
staleness measure (→ ADR-0039 + C2); making `StalenessTracker.recordUpdate`'s timestamp
load-bearing (CT-01, C2); the edge-side prefix storage filter (CT-25 C2-half); `secure/`
store/serve policy (CT-37, C2); gap recovery on the edge (C3); the full slow-consumer state
machine beyond C1's transition events (C4).

**Sign-off (B):** The design passes the performance-disqualifying screen (verified against the
real publish path, not the claim). Layering is the correct anti-fiction structure. **CLEARED-WITH-
CONDITIONS:** (i) C1 codec enumerates the error taxonomy and reserves the failover-resume field
(protocol-table findings); (ii) the C1 design note states which backpressure numbers govern (§7
credit model superseded — CT-26); (iii) ADR-0039 is written before C2, and C1 ships HEARTBEAT only
as a carrier — the idle-time `StalenessTracker` is NOT wired as the production staleness signal
(B-1, prod-blocking at the C2 gate); (iv) A2 finding 1 (burst bandwidth figure) applied.

---

## C. Phase V1 Machinery Review — commit `6e2b31a` (configd-testkit edge classes)

### Verdict: **ACCEPTED-WITH-ONE-REQUIRED-CHANGE** (the machinery deserves the charter's bar; one
### deviation must be fixed or formally tracked before C2's staleness tests rely on it).

#### `EdgeFanOutSim` — is the FanOutBuffer listener wiring a faithful mirror of ConfigdServer's?

**Mostly faithful, with two deviations — one benign, one C2-load-bearing.**

Verified side-by-side:
- Production (`ConfigdServer.java:367-389`): listener builds `ConfigDelta(fromVersion, version,
  mutations, signature, epoch, nonce)` from `stateMachine.lastSignature()/lastEpoch()/lastNonce()`,
  captures `commitTimestampMillis = clock.currentTimeMillis()` **on the apply thread**, and
  `fanOutBuffer.publish(new CommitNotification(version, ts, delta))`.
- Sim (`EdgeFanOutSim.java:132-141`): listener builds `new ConfigDelta(fromVersion, version,
  mutations)` (**unsigned legacy**, signature null) and captures
  `commitTimestampMillis = cpSim.currentTime()` (**global sim time**).

**Deviation 1 (benign): unsigned delta in the sim.** The sim's `ConfigStateMachine` has no
`ConfigSigner` (`AdversarialSim.java:88` constructs `new ConfigStateMachine(store, clock)` — no
signer), so `lastSignature()` would be null regardless. The sim is faithful *to its own
unsigned configuration*. Signature handling is a C2 concern (the edge `DeltaApplier` verifier is
C2-wired). **Severity: NOTE.** No change required for V1; C2's sim variant that exercises signed
deltas + verifier rejection must add a signer to the sim's state machine — flag for C2.

**Deviation 2 (C2-load-bearing): `commitTimestampMillis = cpSim.currentTime()` (global), not the
per-node SkewedClock.** This is the charter's named deviation, and it is **real and correctly
flagged**. `AdversarialSim.java:84-88` wires each node a `SkewedClock(() -> currentTimeMs, skewMs)`
with `skewMs ∈ [-50, +50]`. Production captures the *leader's* (skewed) clock as the commit
timestamp. The sim captures the *global unskewed* time. **Severity assessment for C2's staleness
tests:** this is **material**. The whole point of CT-08 (the ≤50 ms NTP-skew tripwire) and the
B-1/ADR-0039 frontier measure is that the leader→edge timestamp carries a bounded skew error term
(contract §2: "inter-node clock skew … bounded operationally by NTP … the only residual error
term"). A sim that publishes *unskewed* commit timestamps cannot exercise the skew error term —
it would let a buggy or absent skew-tripwire pass green. The deviation is acceptable for **V1**
(which checks the delivery/convergence *mechanism*, not the staleness *distribution*), but it is a
**blocker for the C2 staleness machinery** and must be fixed there. The cleanest fix: the sim
listener should read the publishing node's clock — `cpSim.stateMachine(cpNode)` is accessible, and
the node's `SkewedClock` is reachable; capture *that* node's `currentTimeMillis()` as the commit
timestamp, mirroring production's "leader's clock on the apply thread."

  Note the listener-signature constraint that makes this non-trivial: `ConfigChangeListener` is
  `(mutations, version)` only — it does NOT pass a commit timestamp, so both production and sim
  capture the clock *inside* the listener. Production captures the leader's clock because the
  listener runs on the leader's apply thread holding the leader's `clock`. The sim's listener,
  however, captures `cpSim.currentTime()` (the harness global), not the per-node clock it could
  reach via `cpSim` — so the fix is a real code change in the sim, not a config tweak.

**Finding C-1. [REQUIRED — blocking for C2's staleness tests, non-blocking for C1]** The
`EdgeFanOutSim` listener must publish each node's *skewed* commit timestamp (the publishing CP
node's `SkewedClock`), not the global sim time, before C2's staleness/skew-tripwire tests
(CT-01/CT-02/CT-07/CT-08) run against it. As-is, those tests cannot exercise the ±50 ms skew error
term that the contract names as the *only* residual error. Acceptable for V1 (mechanism check);
must be fixed at the C2 machinery gate. Track as a Session-3 register row against C2.

#### `EdgeInvariants` — are the four checks real and non-vacuous?

All four verified against the code:
- **(a) per-edge version monotonicity** — throws on `v < prev` within an incarnation; resets on
  crash (incarnation bump). Real. ✔
- **(b) no stale overwrite** — per edge, per key, throws on a key version decrease within an
  incarnation (full-store diff per tick). Real. ✔
- **(c) snapshot–delta convergence** — `finalCheck` byte-equals every live edge's store to the CP
  leader's after heal-all + drain, with a precise first-divergence diff. Real. ✔
- **(d) eventual delivery** — recorded (never thrown), per-(cpNode, seq) outstanding tracking,
  with excusal for crashed/lagging/disconnected edges. Real. ✔

**Is the eventual-delivery excusal logic sound — can it excuse a violation it shouldn't?**
There is a narrow **false-negative (under-counting) risk**, not a false-positive risk (it never
records a spurious violation, which is the dangerous direction). `checkEventualDelivery`
(`EdgeInvariants.java:185-224`): when `nowMs - publishedAtMs > boundMs`, it records a violation for
each still-owing edge that is **eligible at the deadline tick** (`alive && !lagging &&
connected`), then **retires the entire outstanding entry** (drops eligible-recorded AND
ineligible-excused edges, `it.remove()`). Consequence: an edge that was eligible for the *entire*
delivery window but happens to be `lagging()`/partitioned/crashed *on the exact tick the deadline
is evaluated* is **excused** for that seq, even though a correct fan-out should have delivered it
earlier in the window. Because the entry is then retired, that seq is never re-checked against that
edge. This can mask a real late-delivery if the fault timing aligns with the deadline tick.

  Mitigating factors: (1) the comment notes "an edge that becomes eligible again would be
  re-obligated by a later publication," so a *persistent* delivery failure surfaces on subsequent
  seqs — a single masked seq does not hide a systemic failure; (2) the no-fault backlog test
  (`EdgePropagationBacklogTest`) runs with `edgeFaults=false`, so under the primary gate this edge
  case cannot arise (no edge is ever lagging/partitioned). The risk is confined to fault-injected
  seeds. **Severity: NOTE (non-blocking).** Recommend a hardening: when retiring an outstanding
  entry past the deadline, record a *separate* "excused-at-deadline" counter (not a violation) so
  excused-vs-delivered is observable and a reviewer can see whether excusals correlate with a
  fan-out bug. This keeps the liveness checker honest without making it throw.

#### `EdgeSeedCompatTest` — does byte-identical CP digest actually prove non-perturbation?

**It proves the strong, load-bearing form of non-perturbation, with a stated scope limit.** The
digest (`foldCp`) folds, per tick per node: `role.ordinal()`, `currentTerm`, `leaderId`,
`log.lastIndex/commitIndex/lastApplied`, and `store.currentVersion()`. Asserting this is
byte-identical between a plain `AdversarialSim` and an `EdgeFanOutSim` (0 edges and 3 edges +
`StreamDriver.NONE`) across 3 seeds × 1200 ticks proves the edge listener wiring + the second
`AdversarialNetwork` (new mixSeed tag) + the edge fault sub-stream **do not alter the CP
consensus trajectory** — role transitions, term progression, leadership, log/commit/apply
indices, and the applied version. That is exactly the "reuse, never fork; the committed 507-seed
gate stays valid" guarantee the charter demands.

  **Scope limit (must be stated, not a defect):** the digest covers CP *control-flow and
  versions*, NOT the store *value bytes* per key, NOT the commit *timestamps*, NOT the edge-network
  message schedule. So it proves "the edge plane does not change *what the CP decides/commits*"; it
  does not (and need not) prove "the edge plane does not change a value byte" — but since the edge
  plane is a pure *consumer* of the CP (it only reads `source(cpNode)` and never calls into the CP
  state machine), control-flow + version identity is sufficient: if the CP trajectory is identical,
  the committed values are identical (they are a deterministic function of the identical command
  stream). **Finding C-2. [NOTE]** The test's Javadoc should state the digest's scope (CP
  control-flow + versions, deterministically implying value identity) so a future reader does not
  over- or under-read what byte-identical proves. The proof is sound; the documentation of its
  scope is thin.

#### `EdgeInvariantsTestTheTesterTest` — does each invariant genuinely fire?

**Each invariant is observed firing on a real violation, and each has a non-vacuity counter-case.**
Verified all six tests:
- (a) `versionMonotonicityCheckerFiresOnDecreasingStoreVersion` — drives store to v10 then v5 via
  doctored Snapshots; asserts `SafetyViolation` naming the invariant + seed. Genuine fire. ✔
- (a-read) `monitorWiredReadStoreFiresInvM1OnCursorAheadRead` — a cursor-ahead read trips the
  **real** test-mode `InvariantMonitor` wired into the edge's `LocalConfigStore` (AssertionError),
  with a clean read on an at/below cursor. Proves the `monotonic_read` seam is live. ✔
- (b) `noStaleOverwriteCheckerFiresOnDecreasingKeyVersion` — store version rises (so (a) passes)
  while key 'k' regresses; (b) fires. Genuine, and it specifically defeats the "(a) would have
  caught it anyway" objection by raising the store version. ✔
- (b-guard) `productionDeltaApplierGuardRefusesStaleDeltaSoTheStoreNeverRegresses` — shows the
  **real** `DeltaApplier` returns `STALE_DELTA` and the store never regresses, so the checker
  correctly does NOT fire. This is the right complement: it proves the production guard, not the
  checker, and asserts the checker is silent (not a "test testing the test"). ✔
- (c) `convergenceFinalCheckFiresOnDivergentEdgeState` — edge@7 vs leader@9 fires with a precise
  diff; a converged edge passes. ✔
- (d) `eventualDeliveryViolationIsRecordedWithCorrectLateness` + `...NotRecordedWhenEdgeObservesInTime`
  — fires at deadline+30 with lateness == 30; at-bound and observed-in-time cases record nothing.
  The exact-lateness assertion is good (it would catch an off-by-one in the bound math). ✔

None of these are testing-the-test: each drives a genuine state and asserts the checker's
observable output (throw / recorded violation), and each pairs with a counter-case proving the
checker is silent when it should be. **This meets contract §4.5 (an assertion never seen firing is
unverified).** No finding.

#### `phase-v-backlog-failures.txt` vs the `@Disabled` test

Verified consistent. The capture records `EdgePropagationBacklogTest#noFaultScheduleDeliversAndConverges`
failing with "recorded 30 violation(s), maxLateness={100=1,101=1,102=1}" under `StreamDriver.NONE`
— i.e. the producer side (FanOutBuffer listener) publishes 30 notifications, none delivered, all 3
edges starve. The test file (`EdgePropagationBacklogTest.java`) carries the matching
`@Disabled("S3-BACKLOG(C1) … enable when C1 lands")` marker and the assertions are the verbatim
ones the capture shows failing (`assertEquals(0, deliveryViolationCount)` + `finalCheck()`). The
re-enable criterion (swap `StreamDriver.NONE` for the C1 driver, remove `@Disabled`, assertions
pass unchanged) is exactly what B's "C1 may implement now" list enables. The backlog is honestly
executable and correctly disabled. **No finding** — this is the model the rest of the session
should follow.

#### Phase V ordering (charter §1 rule 1)

Confirmed by commit history: `6e2b31a` (Phase V1 machinery) and `ebca440` (Phase V3 contract-test
map) precede any C1 implementation. No C1 code exists in `src/main` (`FanOutSessionCore`,
`EdgeFrameCodec`, `FanOutServer` all absent; `configd-edge-node` module absent). The only `src/main`
consumers of `readSince`/`commitNotificationSource` are: the producer wiring (`ConfigdServer`),
the boundary types, and `LivePropagationProbeMain` (V2 probe — boundary mode drains to a
`PropagationProbe`, edge mode exits `EXIT_EDGE_NOT_BUILT`; it does **not** push to edges, so it is
not a smuggled C1 drain). The "machinery before components" prime directive is honored. ✔

**Sign-off (C):** The V1 machinery deserves the bar. The four invariants are real and non-vacuous,
the tester-tests genuinely fire, the seed-compat digest proves the load-bearing non-perturbation
guarantee, and the backlog is honestly executable and correctly disabled. **ACCEPTED-WITH-ONE-
REQUIRED-CHANGE:** C-1 (skewed commit timestamp) must be fixed before C2's staleness machinery
relies on the sim; it is non-blocking for C1. C-2 (digest scope doc) and the (d) excused-at-
deadline counter are NOTE-level hardening.

---

## GATE DECISION

### **C1 IMPLEMENTATION: CLEARED-WITH-CONDITIONS**

C1 (the fan-out distribution service: `EdgeFrameCodec` + golden fixture, `FanOutSessionCore`,
`FanOutServer` mTLS endpoint, bounded per-session backpressure, full verbatim signed-chain
delivery, HEARTBEAT-as-carrier, named configs + metrics, and the V1 `StreamDriver` wiring to
re-enable `EdgePropagationBacklogTest`) may begin implementation now. Conditions, all enforced at
C1 design-note closeout or the named downstream gate:

1. **(A1)** Apply the ADR-0037 scale-envelope wording fix (per-fan-out-node vs system edge count
   under the §12 tree). Editorial; ADR ratified.
2. **(A2)** Add the 100k/s burst bandwidth figure (≈800 Mbit/s/edge) to ADR-0038's honesty
   section. Editorial; ADR ratified.
3. **(B, protocol)** The C1 `EdgeFrameCodec`/protocol-v1 must enumerate a fixed ERROR/CLOSE code
   taxonomy (pinned in the CT-41 golden fixture) and reserve the failover-resume cursor field in
   SUBSCRIBE.
4. **(B, backpressure)** The C1 design note must state that the design's frame/byte thresholds
   govern and that architecture §7's credit model (100 credits / 1000-entry buffer) is superseded
   for the edge streaming path (resolves CT-26's conflicting threshold sets).
5. **(B-1, prod-blocking at the C2 gate)** Write **ADR-0039 before C2** (frontier-based staleness
   = `wall_now − max(commitTs(lastApplied), serverNow(last HEARTBEAT where latestSeq==cursor))`,
   adopted as arbitrated). C1 ships HEARTBEAT **only as a carrier**; the idle-time
   `StalenessTracker` must NOT be wired as the production staleness signal. ADR-0039 is NOT a C1
   prerequisite.
6. **(C-1, blocking for C2's staleness tests)** Fix `EdgeFanOutSim` to publish each CP node's
   *skewed* commit timestamp (not global sim time) before CT-01/02/07/08 run against the sim.
   Register a Session-3 row against C2. Non-blocking for C1.

ADR-0037: **RATIFY-WITH-CHANGES** (finding A1-1). ADR-0038: **RATIFY** (finding A2-1 widens the
honesty envelope only). C1 design screen: **CLEARED-WITH-CONDITIONS** (passes the rule-4
performance-disqualifying screen as verified against the real publish path). V1 machinery:
**ACCEPTED-WITH-ONE-REQUIRED-CHANGE** (C-1).

— review-architect, 2026-06-11
