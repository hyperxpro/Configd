# Gate 2 · Workstream A — Raft peer-identity binding (WH-08/09)

Operator decision (this session): build BOTH layers now, never defer. Grounded in etcd
(`--peer-cert-allowed-cn` / `--peer-cert-allowed-hostname`), CockroachDB (`CN=node`), ZooKeeper
quorum-cert verification — all verify the peer's cert identity, none trusts a self-declared wire id.

## As-built gap (confirmed by lead read)
- `RaftFrameDecoder.decode` (configd-netty) builds `InboundMessage(NodeId.of(senderId), frame)` from the
  attacker-controlled 4-byte `senderId` prefix — no cross-check to the TLS cert.
- `InboundHandler` (NettyRaftTransport) captures NO peer identity on handshake (only handles
  `IdleStateEvent`); the JDK `TcpRaftTransport` likewise checks only CA-chain (`setNeedClientAuth`).
- In-body `leaderId`/`candidateId` (RaftMessageCodec) trusted with no comparison to the connection.
- Comments call the `senderId` prefix "authenticated" — an overstatement until this lands.

## Layer 1 — connection-level peer-CN/SAN verification (etcd model, table-stakes)
On peer-TLS handshake completion (server side, BOTH transports), resolve the peer cert principal and:
- Extract the identity per a **configurable node-identity marker** (default: CN attribute; the CN/SAN
  value identifies the node — CockroachDB-style `CN=node` for the "is a node cert at all" gate, plus a
  per-node identifier for Layer 2).
- Verify the identity is in the configured **allowed-node set**; reject (close + count metric) otherwise.
  A client cert (not in the allowed-node set / lacking the node marker) CANNOT open a peer connection.
- **Enforce-when-configured, warn-when-not (etcd semantics):** when the allowed-node identity config is
  supplied, enforce; when unset (legacy/existing test fleet using a shared cert), keep current
  CA-chain-only behavior but emit a LOUD one-time warning that peer-identity verification is
  unconfigured. This builds the capability now (not deferred), is fully testable, and does not break the
  existing green cluster tests that use a single shared cert. Document unset = weaker posture.

## Layer 2 — per-frame senderId ↔ connection-cert-identity binding (defense-in-depth beyond etcd)
- On handshake, map the verified cert identity → the peer's `NodeId` and stash it as a channel
  attribute (Netty) / connection-local (JDK).
- Enforce, when Layer-1 config is active: reject any frame whose `senderId` prefix != the connection's
  verified `NodeId`. Do this at the earliest point that has both (InboundHandler.channelRead0, which has
  ctx→channel attribute + the decoded InboundMessage). Keep `RaftFrameDecoder` pure or pass the pinned id in.
- Also bind the in-body ids: `leaderId` (AppendEntries/InstallSnapshot/TimeoutNow), `candidateId`
  (RequestVote/PreVote) must equal the connection identity. The witness/coalesced paths already use the
  authenticated `from` — leave them, they become genuinely authenticated once Layer 2 binds `from`.
- Reject = drop the connection (desync-equivalent) + a counted metric `raft_peer_identity_mismatch`.

## Parity + honesty
- BOTH `NettyRaftTransport` and `TcpRaftTransport` (JDK) must behave identically (tier-parity discipline;
  cf. PR #66). Mirror the edge plane's `resolveCertIdentity` pattern
  (`getSession().getPeerPrincipal()`), extended with CN/SAN parsing + allowed-set check.
- Fix the "authenticated" comment overstatement in RaftMessageCodec / RaftWireProtocol to state the
  real, now-enforced binding (and the enforce-when-configured caveat).

## Config surface (new)
- Extend `TlsConfig` (or a sibling peer-transport policy) with: `nodeIdentityMarker` (default CN),
  `allowedNodes` (identity → NodeId map, or a verifier), and the enforce/warn toggle derived from
  whether it is supplied. Plumb through the transport constructors + ConfigdServer wiring + any
  `-D` overrides consistent with the repo's existing property conventions.

## Tests (redteam-grade)
1. A peer forging another node's `senderId` (valid node cert, wrong id) → rejected + metric.
2. A client cert (no node marker / not in allowed set) → cannot open a peer connection.
3. In-body `leaderId`/`candidateId` != connection identity → rejected.
4. Well-formed same-identity frames → pass, byte-identical (golden `WireCompatGoldenBytesTest` green).
5. Unset config → legacy behavior + the one-time warning (existing cluster tests stay green).
6. Netty↔JDK parity: the same hostile input is rejected identically on both transports.

## Files (expected)
`configd-transport/TlsConfig.java` (+ maybe a new `PeerIdentityPolicy`), `configd-netty/NettyRaftTransport.java`
(+ `RaftFrameDecoder`), `configd-server` JDK `TcpRaftTransport.java`, the `RaftTransportAdapter` (in-body
id check), `RaftMessageCodec` (comment honesty), `ConfigdServer` wiring, ConfigdMetrics catalog (new metric).
