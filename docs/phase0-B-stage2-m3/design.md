# Phase 0 — Workstream B — Stage 2 — M3: Coalesced Heartbeats (design, before code)

> **Status:** DESIGN, written before the code (per the four-way discipline; D-020). Two independent
> fresh-opus reviews (consensus red-team + architecture/scope) ran on this design before implementation
> and converged on it; their findings are folded in (see §7).
> **Resume base:** `b7cea74` (M2b / H-4 CLOSED). `main` pinned at `cedc706` (D-010). Additive, behind the net.

---

## 1. The problem (why M3 exists)

RR-113: Configd's ~800/s write ceiling is **single-thread heartbeat starvation** — above ~1k/s the
heartbeat slips the election timeout → leadership churn → throughput inverts. The multi-Raft re-threading
(M1) distributes consensus across owner threads, but the **naive heartbeat model sends one AppendEntries
per GROUP per peer per tick**, so at N groups heartbeat traffic scales with group count — which would make
N>1 *slower* than N=1 and reproduce-and-amplify the bottleneck. Coalesced heartbeats send **one heartbeat
per peer-node per tick regardless of group count** (the CockroachDB/TiKV technique), making heartbeat cost
flat in the group count. Without this, multi-Raft is a regression.

A Configd "heartbeat" is an **empty `AppendEntriesRequest`** (`entries().isEmpty()` — see its javadoc:
"empty for heartbeat"). There is no separate lightweight heartbeat message; the periodic
`tickHeartbeat()→broadcastAppendEntries()` round sends each peer whatever it needs (empty when caught up,
entries when behind, snapshot when far behind). **Only the genuinely-empty ones are the amplification
source and the coalescing target.**

## 2. The seam — a `CoalescingRaftTransport` decorator on `RaftTransport`

`RaftNode` holds one `RaftTransport` (`send(NodeId, RaftMessage)`) and every heartbeat is
`transport.send(peer, req)` inside `sendAppendEntries`. Both verification surfaces inject this same
consensus-core interface:
- **prod:** `RaftTransportAdapter implements RaftTransport` (frames + `tcpTransport.send`);
- **sim (cross-node):** a raw lambda `(target,message) -> sim.network().send(...)`.

So the **only seam both surfaces share is the `RaftTransport` interface**. We decorate it. **`RaftNode` is
UNTOUCHED** (the unsynchronised consensus core stays out of the danger budget; rejected: pushing into
`MultiRaftDriver` — the sim has no driver; touching `RaftNode` — needless core risk).

```
RaftNode --send--> CoalescingRaftTransport(delegate) --> delegate (adapter | sim lambda)
```

`CoalescingRaftTransport` (consensus-core, `io.configd.raft`), per GROUP (like the adapter — it knows its
groupId), holding a reference to a `HeartbeatCoalescerProvider`:

```
send(peer, msg):
  if msg is AppendEntriesRequest ae && ae.entries().isEmpty()      // a heartbeat
     && provider.coalescerFor(groupId) is inside its tick window:  // scope to the tick (H-1)
       coalescer.record(peer, groupId, ae); return;                // buffer, do not send yet
  delegate.send(peer, msg);                                        // everything else: immediate, unchanged
```

## 3. The coalescer — repurposed `HeartbeatCoalescer`

The dormant `HeartbeatCoalescer` is **relocated** `io.configd.replication`→`io.configd.raft` (so the
sim/testkit — which depends on consensus-core but not replication-engine — can use it) and **repurposed**:

- intent-only `Map<peer, Set<group>>` → payload-carrying `Map<peer, Map<group, AppendEntriesRequest>>`;
- `record(peer, group, ae)` keeps the LATEST ae per (peer,group) (correct `leaderCommit` if recorded twice);
- `drain()` returns `Map<peer, Map<group, ae>>` and clears;
- the `shouldFlush`/`coalescingWindowNanos`/`windowStartNanos` time-window API is **DELETED** — a window
  that holds a heartbeat past a tick slips the election timeout (the RR-113 failure mode). M3 drains
  **unconditionally every tick** (≈ zero added latency: drain is microseconds after the record, same tick).
- single-threaded, no synchronization (unchanged contract): **one coalescer per OWNER thread**, never
  shared across owners (preserves threading-contract §2 isolation; touched only by its owner's thread).

A `tick window` flag (`beginTick()` / `endTickAndDrain(sender)`) scopes coalescing to the heartbeat-tick
path: empties produced *inside* `tickOwner` coalesce; empties from inbound/propose/transfer tasks (outside
the window) pass through immediately (H-1 — they must not be delayed to the next tick).

## 4. The drain — at `tickOwner` end, try/finally

`MultiRaftDriver.tickOwner(i)` wraps its per-group `node.tick()` loop:

```
coalescer(i).beginTick();
try   { for each owned, non-migrating group g: node(g).tick(); }   // records heartbeats into coalescer(i)
finally { coalescer(i).endTickAndDrain(sender(i)); }               // H-2: a mid-tick throw still flushes
```

`endTickAndDrain`, per peer with pending heartbeats:
- **exactly 1 group** → `delegate.send(peer, theAe)` — a NORMAL `AppendEntriesRequest`, pass-through;
- **>1 group** → `delegate.send(peer, new CoalescedHeartbeat(localNode, [(g,ae)...]))`.

Migrating / non-owned groups never `tick()` ⇒ never record ⇒ correctly not heartbeated this tick. The
decorator resolves the CURRENT owner's coalescer on each record (a `Supplier` bound at wiring, not a
fixed reference — D-020 review A2), so after a group rehomes its heartbeats land in the NEW owner's
coalescer (the one that owner drains), never the old owner's; record and drain stay on one owner thread
(no cross-thread write on the non-synchronised buffer). The drain is pure I/O to the delegate and never
re-enters a `RaftNode`. Each peer-send is exception-isolated (one peer failing must not starve the others
— mirrors `sendAppendEntries`'s existing `try/catch`). NB the combined coalescing×ACTIVE-rehoming surface
is still a Phase-1 re-verification item per D-016 (production is N=1 / no rehome); the dynamic resolver
makes the mechanism rehoming-correct, but a live placement policy re-runs the proofs.

## 5. The wire — UNCHANGED at N=1 (production)

**Production is N=1 (single group).** Every prod drain has exactly one group ⇒ pass-through normal
`AppendEntriesRequest` ⇒ the production network wire, `FrameCodec`, `MessageType`, and the sealed
`RaftMessage` set are **ALL UNCHANGED**. No new wire type, no codec change, no security-path edit.

`CoalescedHeartbeat` is a plain VALUE (record: `from` + `List<GroupHeartbeat(int groupId, AppendEntriesRequest ae)>`),
**not** a `RaftMessage` and **not** a wire frame. It arises only at **N>1 (the test-only multi-group
surface)** and is demuxed by a real method:

```
MultiRaftDriver.routeCoalescedHeartbeat(NodeId from, CoalescedHeartbeat ch):
   for each (groupId, ae) in ch: routeMessage(groupId, ae);   // marshals each onto its owner; N=1 → the one group
```

This is production-ready code, exercised by the N>1 test surfaces, **inert in N=1 prod** (which never
receives a `CoalescedHeartbeat`). Mirrors the workstream's posture exactly: build + prove the mechanism,
ship it inert at N=1, **Phase-1 activates N>1-over-TCP** (adds the wire frame + demux wiring) and
re-verifies — like M2 rehoming (D-016).

## 6. The three proofs → surfaces (each non-vacuous)

| Proof | Surface | Assertion | Non-vacuity (test-the-tester) |
|---|---|---|---|
| **(1) Flat-in-N** | counting `RaftTransport`, multi-group single-node, poolSize=1 | coalesced frames **== 1** per peer per tick, independent of G∈{1,several,many} | the un-coalesced baseline emits **G** frames/peer/tick (asserted to scale with G) |
| **(2) No spurious election** | cross-node deterministic sim (`SeedSweepTest`) WITH the decorator wired + draining every tick, incl. a sustained-load variant | leader stable; no spurious term/leadership change; commit + failover survive across the seed sweep | a deliberately-broken coalescer (drop/delay a peer's heartbeat) → followers time out → election → sweep **RED** |
| **(3) Correctness preserved** | multi-group demux round-trip + the existing S2–S4 surface re-run with the decorator present | every group receives correct timely liveness; commits flow across all groups; full invariant surface green | neuter the demux / drop a group → that group stalls / invariant fires |

`flat in N` = **flat in GROUP count at fixed pool size** (≤poolSize/peer/tick; ==1 at poolSize=1). poolSize
is a deploy constant; groups grow with workload. This is the CockroachDB per-sender technique mapped onto
the owner-thread model. Strict global one-per-peer would require a **shared cross-thread coalescer** = a
lock on the hot heartbeat path = a threading-model regression — **rejected**.

## 7. Folded-in review findings (the two pre-code reviews)

- **Vacuous-green trap (both reviews, the key finding):** the cross-node sim is single-group-per-node ⇒
  coalescing of 1 group is a no-op; the real-executor harness is single-node groups ⇒ no elections. Neither
  alone is non-vacuous for proof (2). Resolution: proof (2) is the *pipeline* (record→drain→deliver) on the
  cross-node sim (genuinely exercised every tick; broken drain → RED); the *multi-group merging* is proven
  by (1) flat-in-N + (3) demux. Stated plainly so we never claim the sim proves cross-group merging.
- **H-1 (red-team):** a blind decorator would coalesce empties from transfer/NACK tasks too, delaying them
  ≤1 tick. Fixed by the **tick-window scope** (§3) — only in-tick empties coalesce.
- **H-2 (red-team):** a mid-`tickOwner` throw must still flush → **try/finally drain** (§4); per-peer send
  isolation.
- **Window deletion (both):** the `shouldFlush` time-window is the RR-113 failure mode; deleted (§3).
- **Wire minimalism (architecture):** no `MessageType`/`FrameCodec`/sealed-`RaftMessage` change — achieved
  by N=1-passthrough / N>1-value (§5).
- **M-1 (red-team, deferred):** an oversized `CoalescedHeartbeat` could leak inflightCount past the RR-103
  guard. Inapplicable at M3 (N=1 prod sends only empties, which never encode-reject; test transports don't
  reject). **Phase-1** must bound/split the `CoalescedHeartbeat` when it ships on the wire.
- **Inbound groupId-hardcode (architecture, deferred):** `RaftTransportAdapter` ignores `frame.groupId()`
  on inbound (latent — prod is single-group). Orthogonal to M3 (leader-outbound). **Phase-1** fixes it
  before N>1-over-TCP.
- **Response coalescing (architecture, deferred):** out of scope; RR-113's bottleneck is the leader's
  outbound. Its own milestone with its own liveness proof.

## 8. What stays green / invariant

- Net non-vacuous across all four classes (off-owner / cross-group / rehoming-race / double-ownership):
  the coalescer is per-owner, touched only on the owner thread during/after its own tick — no new
  cross-thread reach. Re-confirmed RED on a deliberate violation after the change.
- gates 1–7 + the rehoming surface + the deterministic sim: green. **NB (D-020 review, finding 1):** the
  sim is NOT byte-identical to the un-coalesced trajectory — on a tick that mixes a buffered heartbeat with
  an immediately-sent entry-carrying AppendEntries, the heartbeat drains *after*, shifting the
  `SimulatedNetwork` PRNG draw order; payloads and per-node send counts are identical, but the per-seed
  schedule differs, so the M3 seed-sweep is a **re-established baseline** (green on the new trajectory), not
  the identical prior one. D-016 dormant-rehoming posture untouched (coalescing adds no rehoming activation).
- No throughput number claimed — that is Workstream C on hardware. M3 proves the heartbeat PROPERTY.

## 9. Smallest-first build order (S1)

1. Repurpose+relocate `HeartbeatCoalescer` (payload-carrying, drop window) + rewrite its unit test.
2. `CoalescingRaftTransport` decorator + `CoalescedHeartbeat`/`GroupHeartbeat` value + `HeartbeatCoalescerProvider`.
3. `MultiRaftDriver`: per-owner coalescers, `tickOwner` begin/drain (try/finally), `routeCoalescedHeartbeat` demux.
4. Wire prod (`ConfigdServer`) + sim (`ClusterHarness`) to the decorator + drain. Net non-vacuous re-confirmed. Checkpoint.
