# ADR-0013: Lightweight Session Management (Non-Consensus Heartbeats)

## Status
Accepted

## Context
The system must support 10K-1M connected edge nodes with session semantics (ephemeral keys, connection-aware liveness). ZooKeeper's session management creates session objects, ephemeral nodes, and watches on the leader — at 10K+ clients this consumes several GB of leader memory. Critically, ZooKeeper session creation and expiration are consensus operations that saturate the write pipeline during reconnection storms (gap-analysis.md section 2.4). Twitter's 2018 incident demonstrated that hundreds of thousands of clients reconnecting after a partition caused session storms that prevented normal request serving for extended periods. The system targets 10K/s sustained write throughput — session management overhead must not compete with config writes for consensus bandwidth.

## Decision
We adopt the **Chubby KeepAlive piggybacking pattern**: session heartbeats do NOT go through Raft consensus. Session lifecycle is split into local and consensus-required operations:

### Session Creation (Local Only)
1. Client connects to any server (typically the nearest distribution node).
2. Server generates a session token: `{node_id, session_id, creation_timestamp, ttl}` signed with the node's private key.
3. Session is registered in the server's local session table (not in Raft log). Session metadata: token, client address, subscriptions, last heartbeat time.
4. No consensus required. Session creation latency: < 1ms.

### Session Heartbeat (Local Only)
1. Client sends periodic heartbeats (default: every 5 seconds).
2. Server updates the session's last heartbeat timestamp in the local session table.
3. No consensus required. Heartbeat cost: single hash map update (~50ns).
4. Heartbeats piggyback on existing gRPC stream keepalives — no additional connections.

### Ephemeral Key Creation (Consensus Required)
1. When a client creates an ephemeral key (a key that disappears when the session expires), the creation goes through Raft consensus.
2. The Raft log entry includes the session ID. The state machine tracks which session owns which ephemeral keys.
3. This is the only session operation that requires consensus.

### Session Expiration
1. If no heartbeat received within `session_timeout` (default: 30 seconds), the server marks the session as expired locally.
2. Server submits an `ExpireSession(session_id)` entry to Raft — this is a single write, not per-ephemeral-key.
3. The state machine processes the expiration: deletes all ephemeral keys owned by that session in a single atomic batch.
4. If the server crashes before submitting the expiration, any server can detect the orphaned session via the session token's TTL and submit the expiration.

### Session Transfer (Reconnection to Different Server)
1. Client reconnects to a different server and presents its session token.
2. New server verifies the token signature and checks that TTL has not expired.
3. New server registers the session locally and resumes heartbeat tracking.
4. No consensus required for transfer. The session's ephemeral keys remain valid because they are tracked in the Raft state machine by session ID, not by server identity.
5. Transfer latency: < 1ms (token verification + local registration).

## Influenced by
- **Chubby KeepAlive (Burrows, OSDI 2006):** 93% of Chubby RPCs are KeepAlives. Piggybacking session liveness on existing RPCs avoids dedicated session management traffic. Session state maintained on the server side with client-side token for reconnection.
- **ZooKeeper Session Model (anti-pattern):** Session creation, heartbeat, and expiration all go through consensus. At 10K clients with 5-second heartbeat interval: 2K heartbeats/s consuming consensus bandwidth. During reconnection storms: O(N) session creations saturate write pipeline.
- **Consul Session Model:** Sessions use gossip-based TTL with health check integration. Session invalidation is eventually consistent — can lag behind actual client failure. Too loose for ephemeral key semantics.

## Reasoning

### Why not consensus-based heartbeats (ZooKeeper model)?
At 10K connected clients with 5-second heartbeat interval: 2,000 heartbeat writes/s through consensus. This consumes 20% of the 10K/s write throughput target for session housekeeping alone. At 100K clients: 20,000 heartbeats/s — double the total write budget. During reconnection storms (partition heals, distribution node restarts), the burst can be 10-100x normal rate, completely saturating the consensus pipeline and blocking config writes.

### Why not purely local sessions (no consensus at all)?
Ephemeral keys require consensus for correctness: if a session expires, its ephemeral keys must be consistently deleted across all replicas. Without consensus, different replicas could disagree on whether an ephemeral key exists, violating linearizability (INV-L1 in consistency-contract.md). The hybrid approach — local heartbeats, consensus only for ephemeral key creation and session expiration — minimizes consensus load while preserving correctness.

### Why session tokens instead of server-side session lookup?
After a distribution node failure, clients reconnect to a different node. If session state is only on the failed node's memory, sessions appear expired and all ephemeral keys are deleted — a false positive. Session tokens are self-describing: the new server can validate the token without contacting the old server or consensus. The Raft state machine tracks ephemeral key ownership by session ID, which is stable across reconnections.

### Consensus write budget analysis
- Config writes (target workload): 10K/s — full budget.
- Session heartbeats (this design): 0/s through consensus.
- Ephemeral key creates: estimated < 100/s (ephemeral keys are rare — most config is persistent).
- Session expirations: estimated < 10/s (one entry per expired session, not per ephemeral key).
- Total session overhead: < 110/s through consensus = ~1% of write budget. vs ZooKeeper model: 2,000-20,000/s = 20-200% of budget.

## Rejected Alternatives
- **ZooKeeper-style consensus heartbeats:** 2,000-20,000 heartbeats/s through consensus at 10K-100K clients. Consumes 20-200% of the 10K/s write budget. Reconnection storms saturate consensus pipeline, blocking config writes. Twitter 2018 session storm incident demonstrates this failure mode at scale.
- **Gossip-based session TTL (Consul model):** Session expiration is eventually consistent — gossip convergence at 5,000 nodes takes 10-30 seconds (gap-analysis.md section 4.2). Ephemeral key deletion can lag by 10-30 seconds after actual client failure, violating the bounded staleness contract (< 500ms p99). Too loose for ephemeral key semantics.
- **Lease-based sessions (etcd model):** Leases are consensus objects with periodic renewal through Raft. etcd issue #9360 documents mass lease expiration bugs with keys persisting with negative TTLs. etcd issue #15247 shows stuck fsync causing old leader to continue revoking leases after demotion. Shared leases (multiple keys per lease) create the multi-object-lease bug class where one lease expiration cascades across unrelated keys (Kubernetes issue #110210).
- **Stateless sessions (no server-side state):** Cannot support ephemeral keys or session-scoped watches. Clients would need to re-register all subscriptions on every reconnection, creating thundering herd effects.

## Consequences
- **Positive:** Session creation and heartbeat cost is O(1) local operations, not consensus writes. 99% reduction in consensus bandwidth for session management vs ZooKeeper. Session transfer after node failure is sub-millisecond. Reconnection storms do not affect config write throughput.
- **Negative:** Session expiration detection depends on the server the client is connected to. If that server crashes, orphaned session detection relies on TTL timeout + any server submitting the expiration — worst case adds `session_timeout` delay to ephemeral key cleanup.
- **Risks and mitigations:** Orphaned sessions after server crash mitigated by a background scanner on all servers that checks for sessions whose tokens have expired TTL and submits expiration entries. Clock skew in session token TTL mitigated by HLC-based timestamps (max tolerated skew: 500ms, architecture.md section 6). Session token forgery mitigated by HMAC-SHA256 signature with per-node rotating keys.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- security-engineer: ✅
