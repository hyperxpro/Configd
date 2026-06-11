# Review — RR-002 fix (timeout-less blocking connect freeze) + ADR-0035 sign-off

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **Scope:** independent review of the RR-002 (P0) fix — commits `e91242f` (fix + tests + drill),
  `1e92462` (register pin). Plus ADR-0035 (HLC reconciliation) sign-off.
- **Authoritative artifacts:** `docs/session-2/captures/rr-002-prefix-failure.txt`,
  `gates/rr-002-blackhole-drill.sh`, readiness-register RR-002 row.

---

## Verdict: **APPROVE.** Nothing blocks the RESOLVED flip.

The fix correctly moves all socket establishment off the caller (tick) thread onto a single
dedicated connector, bounds connect (1000 ms) and handshake (2000 ms), preserves R-01 and mTLS,
and restores `soTimeout` to 0 after the handshake so long-lived idle connections are not broken.
The new lock-free-ish per-peer state has no permanent-wedge, double-writer, or lost-frame-forever
defect that I could construct. The discriminating tests are strong; the live drill passes with
bounded latencies and a clean iptables baseline. Notes (none blocking) and the jcstress-must-cover
list for B6 are below.

---

## 1. Concurrency design (hardest scrutiny)

Per-peer shared state: `queue` (`ArrayBlockingQueue(1024)`), `connectInFlight`/`closed`
(`AtomicBoolean`), `socket`/`out` (`volatile`), `framesDropped` (`AtomicLong`); `connectionManager`
(plain `HashMap`, always accessed under `synchronized (connectionManager)`). I verified every
ConnectionManager call site (`markConnected` :666, `markDisconnected` :671/:742, `backoffRemainingMs`
:619) is under that monitor; `addPeer` is construction-time only. Good.

**(A) Lost-wakeup — frame offered after the writer observed empty and exited?** No. The writer never
exits on empty: it blocks in `queue.poll(1, SECONDS)` and on timeout `continue`s, re-checking
liveness. It exits only on `!running/closed/s.isClosed()` or an IOException. A frame offered while
the writer is parked in `poll` wakes it; a frame offered in the ≤1 s gap between a poll-timeout and
the next poll is picked up on the next poll. No permanent lost-wakeup (≤1 s worst-case pickup
latency, fine for Raft heartbeats).

**(B) Permanent wedge — frame queued but no connect ever scheduled / peer never reconnects?** No
construction found. The liveness invariant: every frame in the queue either (a) gets a writer that
drains it, or (b) triggers a reschedule via the `connectAndStartWriter` finally / `teardown`
`!queue.isEmpty()` check, or (c) is evicted by a newer send that itself calls `scheduleConnect`. I
traced the tightest race — `scheduleConnect` CAS vs `connectAndStartWriter`'s `finally`
`connectInFlight.set(false)` then `!queue.isEmpty()` reschedule: if a concurrent send grabs the flag
between the reset and the check, the finally's own reschedule CASes-fail and returns, but the
concurrent send's scheduled attempt covers delivery — no double-schedule, no dropped reschedule.
The only way the queue is empty after a failed connect is that no writer ever drained it (none
exists on failure) and a concurrent send evicted the last frame via drop-oldest — and that send
itself scheduled a connect. So no queued frame is wedged.

**(C) Teardown-vs-publish — live writer on a dead socket, or two writers?** No. `connectInFlight`
(single-in-flight CAS) guarantees at most one `connectAndStartWriter` runs per peer at a time, and
each successful connect submits exactly one writer task for its own stream `s`/`o`. `teardown(s)`
clears the published fields only `if (this.socket == s)` (identity guard), so a stale teardown from
an OLD socket cannot clobber a NEWER connection's fields. The reader and writer both call
`teardown(s)` on the same `s`; the second caller sees `socket != s` → `wasLive=false` → only a
no-op `closeQuietly` (no double `markDisconnected`, no double reschedule). At most one writer per
stream; a writer that errored has already exited before its teardown reschedules a replacement.

**(D) Re-queue ordering / duplication during teardown.** On a writer IOError the in-hand frame is
`queue.offer`-ed back at the TAIL (reordered behind newer frames) and may be partially written
before the failure (the peer then desyncs on framing/CRC → drops the connection → reconnect →
redelivery). Raft tolerates reorder + dup (AppendEntries are idempotent by `(term, prevLogIndex)`),
and a desync is bounded by the inbound length/CRC check that drops the connection. Correct; no wedge.
`framesDropped` accounting is monotonic and covers all three drop sites (closed, queue-full-evict,
re-queue-on-overflow) — consistent with RR-054's later metric work.

**Residual liveness note (not a defect):** if connect keeps failing, backoff grows to MAX 30 s, so a
recovered peer is reconnected within ≤30 s of recovery (or sooner on the next heartbeat's
`scheduleConnect`). Slow but bounded; flagged for B6.

**Minor shutdown race (note):** a `connectAndStartWriter` that passes the `closed.get()` gate just
before `close()` runs can publish a fresh socket that `close()` (which already nulled the old
fields) will not close — a transport-shutdown-time socket leak, reclaimed by the daemon executor /
JVM exit. Not a correctness/wedge issue. jcstress-worthy.

### jcstress-must-cover list (B6 — consume verbatim)

1. **enqueue vs teardown (out null-vs-published):** `enqueueOrDrop` reads `out==null` and decides to
   `scheduleConnect` concurrently with `teardown` clearing `out`/`socket` and with
   `connectAndStartWriter` publishing them — assert no frame is left queued with no scheduled
   connect (no permanent wedge) and no double in-flight connect.
2. **scheduleConnect CAS vs connectAndStartWriter finally reset:** the `connectInFlight`
   false→true→false transition vs the finally's `!queue.isEmpty()` reschedule — assert exactly one
   pending connect survives when frames remain, zero when the queue is genuinely empty.
3. **reader-teardown vs writer-teardown on the same socket `s`:** assert the `this.socket==s`
   identity guard makes teardown idempotent (one `markDisconnected`, one reschedule at most) and
   never clobbers a newer published socket.
4. **publish vs writer-start visibility:** `socket`/`out` volatile publication in
   `connectAndStartWriter` happens-before the writer task reads `o` — assert the writer never writes
   to a null/stale stream (no two writers on one stream).
5. **close() vs in-flight connect:** `closed.set(true)` + field-null vs a `connectAndStartWriter`
   that already passed the `closed` gate — assert at most a benign leaked socket (no use of a
   closed stream, no writer left running past close beyond one loop iteration).
6. **drop-oldest eviction vs concurrent writer poll:** `enqueueOrDrop`'s `poll()`+`offer()`
   drop-oldest vs the writer's `poll(1,SECONDS)` draining — assert `framesDropped` accounting is
   exact (no double count, no missed count) and FIFO-modulo-eviction holds.

## 2. R-01 preservation — HELD

`configd-transport` has **zero** `import io.configd.raft` (the only "RaftNode" hits are Javadoc
prose). The tick-thread `send` path — `send` → `outbound.computeIfAbsent` → `enqueueOrDrop`
(non-blocking `offer` + `scheduleConnect`'s non-blocking `connectExecutor.schedule`) — touches only
thread-safe transport-internal state (`ConcurrentHashMap`, `ArrayBlockingQueue`, `AtomicBoolean`,
and `connectionManager` under its monitor). No `Socket`, stream, DNS resolve, or handshake on the
caller; `createClientSocket` runs only on `connectExecutor`. No cross-thread RaftNode access
introduced.

## 3. The tests

**`TcpRaftTransportBlackholeTest` — genuinely discriminating on this host.** Uses
`10.255.255.1:9999`, a non-routable host whose SYNs are dropped → `connect()` parks for the full OS
SYN timeout (the production fault, no sudo). The pre-fix capture proves this host black-holes (the
worker parked 134.1 s at `:343`); post-fix the caller is released in 0.18 s (my re-run). The test
correctly observes a *bounded window* (2 s budget, 10 s observation) rather than waiting out ~127 s.
`repeatedSendsToBlackholedPeerStayBounded` additionally catches a fix that bounds only the FIRST
connect. **Portability note (B6):** if run on a network that REJECTs (RST) 10.255.255.1 instead of
dropping, the test would pass post-fix AND pre-fix (it does not assert the address actually
black-holes), losing discrimination — the host-independent detector is the static guard. Acceptable
for a regression guard given the static guard backstop.

**`NoBlockingConnectOnConsensusPathTest` (static guard) — sound, two narrow evasion gaps.** Scans
transport + consensus-core + server `src/main` for `new Socket(host,…)` /
`factory.createSocket(host,…)` / out-of-connector `startHandshake()`, stripping comments + string
literals so Javadoc prose does not false-positive; `scanRootsResolveToRealDirectories` prevents a
silent scan-nothing. Evasion gaps (note for B6, low severity): (i) a **fully-qualified** `new
java.net.Socket(addr,port)` would not match `new\s+Socket(` (the regex requires the bare token after
`new`); (ii) `startHandshake()` is exempt for the entire `TcpRaftTransport.java` file, not scoped to
`createClientSocket`, so a future `startHandshake` added elsewhere in that file (e.g. on a tick
path) would slip past. Neither is the canonical regression shape; the behavioural drill + blackhole
test cover the runtime property.

**`reconnectionAfterConnectionDrop` rework — NOT actually changed by the RR-002 commits; still proves
self-healing, but carries vestigial dead code (note).** Contrary to the brief, git shows
`TcpRaftTransportTest.java` was untouched by `e91242f` (last changed by `6cbeb21`, RR-094). The test
wraps `send` in a try/catch-retry loop (lines 255-266) whose premise — "the first send after
disconnect may fail" — is now FALSE against the non-throwing `send`, so `sent` is trivially true on
the first iteration and the retry loop is dead. **The load-bearing reconnection proof is intact**:
`secondReceived.await(5 s)` (line 268) still requires the transport to detect the drop, tear down,
and reconnect to the restarted B2 for the second message to arrive end-to-end — that assertion is
not weakened. Recommend a follow-up to delete the now-dead retry loop and refresh the stale comment
so the test reflects the new contract (the reconnection assertion stays). Not a blocker.

## 4. Pre-fix capture internal consistency — CONSISTENT

The unit capture shows the worker parked at `TcpRaftTransport.java:343` (the timeout-less `new
Socket(addr,port)`) via `send → sendFrame → ensureConnected → createClientSocket`, 134.1 s wall
(the SYN timeout unwinding) with the assertion firing at the 2 s budget — internally coherent. The
live-drill pre-fix capture: 28 rounds, 26 with a PUT/HEALTH failure first at t=3 s, leadership shed
to 503 — consistent with the tick thread parking in connect and failing to heartbeat. The
revert→test-fail pairs are coherent: (i) remove connect timeout → static guard fails at `:488`
(note correctly observes the runtime test can't see (i) because the caller stays released — the
static guard is its dedicated detector); (ii) establishment back on caller →
`repeatedSendsToBlackholedPeerStayBounded` fails at 2.1 s (the 1 s bounded connect prevents a 127 s
hang while still blowing the 2 s budget); (iii) guard detection proven live by catching (i). The
SpotBugs MT_CORRECTNESS delta (transport 0→0) is plausible (concurrent collections + atomics +
volatiles).

## 5. Second-agent verification runs

After `install -DskipTests` of transport + deps and `clean` on the module under test:
- `TcpRaftTransportBlackholeTest` + `NoBlockingConnectOnConsensusPathTest`: **4/4, 0.18 s + 0.21 s.**
- Full transport suite: **Tests run: 115, Failures: 0** (matches the claim).
- **THE LIVE DRILL** (`DRILL_MODE=postfix DROP_WINDOW_S=40 OP_DEADLINE_S=2`, after a CLEAN re-shade
  of the server jar per the stale-artifact warning): **31 rounds, 0 PUT/HEALTH failures**, every op
  6–16 ms vs the 2 s deadline, linearizable-ok 12/31 (the rest a transient "Not Leader" from the
  ReadIndex confirmation race — NOT a stall; latency always bounded). DROP rule armed on port 9092
  then removed by the EXIT trap.
  **iptables baseline (precise):** `iptables -L -n | grep -c DROP` reads **2 before AND after** — but
  both are a pre-existing **FORWARD chain** `policy DROP` + one `DROP all` rule (Docker/system),
  unrelated to the drill. The drill-relevant **INPUT-chain** DROP count and the **port-9092** rule
  count are **0 before AND after**. No stray rule left by the drill.

## 6. mTLS unweakened — CONFIRMED

`setNeedClientAuth(true)` (server, gated on `requireClientAuth`, `:512`) and
`setEndpointIdentificationAlgorithm("HTTPS")` (client hostname verification, `:472`) are intact. The
handshake bound `setSoTimeout(2000)` (`:477`) is **cleared back to `setSoTimeout(0)` immediately
after `startHandshake()`** (`:479`), so no lingering read timeout breaks long-lived idle
connections. The writer loop only writes (no read), so soTimeout is irrelevant to it; the inbound
reader uses the restored `soTimeout=0` (infinite) blocking `readInt`, correct for an idle long-lived
connection. F-0050/F-0051 hostname/SNI handling preserved (uses `getHostString`, not `InetAddress`).

## 7. Bounds sanity vs RR-006 — acceptable; A5 note

Connect 1000 ms / handshake 2000 ms. RR-006 (OPEN) means the named 150/300 ms election timeouts are
currently consumed as 10 ms tick counts → **real election timeout 1.5–3 s today**, so connect (1 s)
< election (1.5 s): fine. **After A5 fixes RR-006**, the real election timeout becomes 150–300 ms, so
**connect (1000 ms) and handshake (2000 ms) will exceed a full election cycle** (≈3–13×). This is
**safe** because connects/handshakes are now OFF the tick thread — a slow connect cannot delay
heartbeat or cost leadership; a leader that loses leadership mid-connect is harmless (the connect
runs on the connector). The only consequence is that per-peer *reconnection* latency can exceed an
election cycle, so a transiently-blipped follower may briefly miss heartbeats. **Recommendation for
A5 (not a blocker):** when RR-006 lands, lower `CONNECT_TIMEOUT_MS` toward the new election timeout
(e.g. 250–500 ms) so reconnects are snappy relative to elections; leave `HANDSHAKE_TIMEOUT_MS` as a
generous multiple of connect. State this in the A5 handoff so the two timing changes are considered
together.

## 8. ADR-0035 (HLC reconciliation) — **APPROVED.** Status set to Accepted with sign-off.

The AMEND/descope decision is sound and the evidence is accurate (verified live): `LogEntry` has no
HLC field; `HybridClock` has zero `src/main` consumers; `StalenessTracker.recordUpdate(version,
timestamp)` documents `timestamp` "(informational)" and ignores it, storing `clock.nanoTime()`
(`configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:96,100,154`). The
§2/§4/§5.3/§9/INV-W2 contract anchors match verbatim, and `DEFAULT_RAFT_GROUP = 0` confirms the
ADR-0030 single-group premise that makes cross-group HLC ordering moot (§5.3 already says cross-group
order is "NOT GUARANTEED"). The §4 seq reconciliation is **consistent with ADR-0033** as I
independently verified in the RR-004 review (S is the applied-mutation counter skipping no-op/RCFG;
`StateMachine.apply` returns `long`); the proposed §4/INV-V1/INV-V2 rewording ("gap-free over the
mutation stream") matches the implemented behavior. The §2 single-leader-clock redefinition is more
honest than per-entry HLC (it avoids conflating propagation latency with inter-node skew) and needs
no `LogEntry`/WAL/snapshot/codec format change (no RR-064 reopening). Scope is correct: the ADR
authorizes the DECISION only and defers the `consistency-contract.md` edits to the RR-031/RR-015
consolidated pass and the implementation to S3. One non-substantive nit (recorded in the sign-off):
the ADR cites `configd-observability` for `StalenessTracker`, which actually lives in
`configd-edge-cache`. No required changes to the decision. Status line and review-architect sign-off
updated in the ADR.

---

## Register action

RR-002 → **RESOLVED**, second-agent verification (drill + test re-runs) appended. Note: the lead's
official gate-1 run is still owed for **RR-094** (TLS-timeout flake), not for RR-002.

## Anything blocking A5

No hard block. A5 next touches RaftConfig/RaftNode timing units (RR-006), HttpApiServer strong
reads, and reconfig tests. Two RR-002-adjacent items for the A5 handoff (both non-blocking):
(1) when RR-006 collapses the 10× tick-unit error, revisit `CONNECT_TIMEOUT_MS`/`HANDSHAKE_TIMEOUT_MS`
vs the new 150–300 ms election timeout (Finding 7); (2) the transport is fully decoupled from
RaftNode, so A5's RaftConfig/RaftNode timing edits do not interact with the transport fix.
