# Changelog

All notable changes to Configd are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The on-disk wire format and snapshot format follow the
[ADR-0029](docs/adr/adr-0029-wire-format-v1.md) framing
contract. Per §8.10 of the gap-closure rules, any wire-incompatible
change MUST land in two consecutive minor releases (deprecation in
`N`, removal no earlier than `N+2`) before the older format is
removed. The whole-system format-compatibility contract (C0-C9) is
[`docs/design/group-b/07-upgrade-capability-as-built.md`](docs/design/group-b/07-upgrade-capability-as-built.md).

## [Unreleased]

### Added

- **At-rest encryption (opt-in).** Node-local AES-256-GCM at the ADR-0042 envelope seam
  (`algId=2`, `-Dconfigd.raft.encryption.enabled=true`), OFF by default and byte-identical when off.
  Roots are held in a persisted dual-slot keyring (`NodeKeyring`) with **non-destructive** term and
  signing-key rotation. Pluggable KMS SPI (`local` HKDF-from-signing-key) plus an external
  **HashiCorp Vault Transit** provider (`configd-kms-vault`, ServiceLoader-discovered).
- **Full authentication system.** One pluggable authenticator chain shared by both planes
  (`AuthenticatorChain`, ServiceLoader): No-Auth / HTTP Basic / Bearer / mTLS, plus an
  **OIDC/JWT** resource-server authenticator (`configd-authn-oidc`). Edge token/basic `AUTH`/`REFRESH_AUTH`
  frames (`0x13`/`0x14`, wire version `0x04`), credential expiry/revocation (`CREDENTIAL_EXPIRED`), and a
  node-join `PeerIdentityPolicy` (mTLS-only interior, CN or SAN-URI/SPIFFE allow-list).
- **Leadership auto-balance.** Decentralized `LeaderBalanceLoop` (on by default at N>1) that sheds an
  over-owned leader per cycle, plus the ADMIN-gated `POST /v1/admin/groups/{gid}/transfer-leadership` route.
- **Chunked InstallSnapshot.** Large snapshots stream as ordered chunks (per-chunk cap 4 MiB), lifting the
  old 4 MiB total-state ceiling; follower reassembly under a fail-closed `configd.raft.maxReassembledSnapshotBytes`.
- **Multi-shard (N>1) watches.** Server-side aggregating endpoint (one `FanOutSessionCore` per covered shard,
  per-shard `(gid, S)` cursor vector, independent resume).
- **Conforming Java reference client + conformance suite.** `configd-client` (+ `-core`/`-http`/`-edge`) and
  `configd-conformance` (CI-wired, both planes vs golden vectors); the `configd-wire` module extracts the
  frozen wire codec.
- **Frozen-format durability.** Dual-slot monotonic durability anchor (`raft-anchor`) closing truncation /
  rollback fail-closed at recovery, a peer-quorum witness for within-term vote-rollback, and version markers
  on every persistent + wire format (fail-closed on unknown).
- **Observability (Gate 2).** New metrics incl. `configd_snapshot_bytes`,
  `raft_shard_replication_lag_max_<gid>`, `raft_shard_snapshot_reassembly_refused_<gid>`,
  `raft_shard_snapshot_chunk_send_rejected_<gid>`, `raft_shard_append_send_rejected_<gid>`,
  `configd_raft_transport_frames_dropped`, `configd_raft_transport_inbound_connections_refused`,
  `configd_raft_transport_connection_decode_dropped_total`,
  `configd_http_request_rejected_bad_request_total` / `_payload_too_large_total`.
- **Upgrade contract (C0-C9).** The `_acl/format` policy version sentinel + the whole-system
  format-compatibility contract.

### Changed

- **Faulted-linearizability is now a real Jepsen-grade matrix (E1), not a 15-second smoke.** The
  `configd-linz` harness gained `SIGSTOP`/`SIGCONT` pauses, packet loss, multi-node quorum-breaking
  partitions, clock skew, and overlapping combination faults (a new ADVERSARIAL schedule), plus
  at-rest-encryption / auth / clock-skew / multi-shard postures. The matrix **found a real
  linearizability bug** (see Fixed below) on the pre-fix bytes and re-ran every-history-LINEARIZABLE on
  the fixed code; both discrimination seeds were re-authored against the evolved code and turn the checker
  RED. The standing CI faulted-linz job now runs this matrix. Results:
  `docs/measurement/e1-faulted-linz-2026-07-10/`. (Endurance — the ≥72 h soak — remains pending.)
- **Default bind is loopback (`127.0.0.1`).** Binding a non-loopback interface while auth is OFF is refused
  unless `--allow-insecure-public-bind` is set (a footgun-fix, not "auth required by default").
- **Write-admission control ON by default** (`configd.write.maxInflightProposals`, conservative value; 429 +
  Retry-After when exceeded).
- **Readiness is shard-aware** (a node that lost quorum on any hosted shard reports NOT-ready) and flips to
  draining on SIGTERM.
- **Driver-protocol RFC** reconciled to as-built (auth frames, `STALE_TOPOLOGY`/`CREDENTIAL_EXPIRED`,
  multi-shard watches, transfer-leadership route).

### Deprecated

### Removed

- The leader-side ">4 MiB snapshot too large for v1 wire" drop path (replaced by chunked transfer).

### Fixed

- **Linearizable-read safety: ReadIndex now commits a current-term entry before serving reads (Raft
  §6.4).** Found by the E1 faulted-linz matrix: a freshly-elected leader could serve a `?consistency=
  linearizable` GET as 404/absent for a committed-and-acked present key, because `RaftNode.readIndex()`
  captured `readIndex = commitIndex` before its current-term no-op had committed (the local commitIndex
  can lag entries the log already holds). `readIndex()` now returns not-leader (→ 503 + `X-Leader-Hint`,
  client retries) until `noopCommittedInCurrentTerm` — the same gate `proposeConfigChange` already
  required. N=1 unaffected. Regression: `ReadIndexNoOpBeforeServeTest`.
- `SigningKeyStore.writeForTest` now applies the 0600 restriction (previously a dormant test-only helper).

### Security

- No silent unauthenticated public bind (see Changed); key-material custody guidance (core-dump/swap-off,
  deploy-level) documented in the operator runsheet and deployer must-knows.

## [0.1.0] - TBD

GA target. Tracked in `docs/progress.md` and `docs/ga-review.md`. The
release is cut per `ops/runbooks/release.md` once every Phase-11 gate
is GREEN. Until the tag is pushed and the release workflow
(`.github/workflows/release.yml`) emits a verified Cosign-signed
image with SLSA provenance, this entry remains a placeholder.
