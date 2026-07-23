# Configd threat model -- security and supply chain

> Signed off 2026-06-14: the §5 honest fence is correct and complete (neither over-claiming coverage
> nor hiding an in-scope gap). The three gaps this model originally found and surfaced --
> inbound read deadline (was F-S7-FUZZ-1), self-signed-leaf expiry/revocation (was F-S7-TLS-1), and
> the unauthenticated edge `/metrics` scrape (was F-S7-TLS-2) -- are now closed in code; see the §6
> ledger rows for the negative tests that pin each.
> **Prime directive:** every control in this model is verified only by a passing negative test that
> proves the attack fails -- never by reading its configuration.
> This document scopes *what* is verified and draws the honest fence (§2.5) around what is not.

## 0. Purpose and method

This threat model enumerates the assets worth protecting, the adversaries who would attack them, the
trust boundaries where a control must hold, and, explicitly, what is out of scope for a local,
pre-production hardening pass. Each in-scope control maps to a hardening workstream (A-E) whose
deliverable is a negative test. The fence (§5) is the auditable statement of what an adversary could
still do that was not proven safe as of this pass; those items are tracked as follow-up work.

## 1. System under assessment (trust domains)

```
                    ┌─────────────────────── CONTROL PLANE (a Raft cluster) ───────────────────────┐
  operator/client   │  HttpApiServer (:apiPort)      RaftNode × N  ───peer mTLS (9090)───  RaftNode │
  ───TLS/Bearer──▶  │  /v1/config (RW)  /metrics      │  DurableRaftState  RaftLog(WAL)  Snapshot   │
                    │  AclService  RateLimiter        │  on-disk data dir  (raft-log, *.snapshot)   │
                    └───────────────│──── FanOutServer (edge mTLS) ──────────────────│──────────────┘
                                    │ signed CommitNotification / snapshot transfer  │
                    ┌───────────────▼──────────── DATA PLANE (edge fleet) ───────────▼──────────────┐
  read clients ───▶ │  EdgeNode  EdgeHttpServer (:edgePort /v1/config /metrics)  EdgeCache (HAMT)    │
                    └──────────────────────────────────────────────────────────────────────────────┘
            BACKUP / RESTORE STORAGE  ◀── snapshots, WAL copied out; restore-snapshot.sh writes data dir
            BUILD / RELEASE  ── Maven deps → jars → container image → registry (cosign/SLSA/SBOM)
```

Trust domains, from most to least trusted:
- **T0 -- node key material:** mTLS private keys (`/secrets`), the cluster Ed25519 signing key
  (`SigningKeyStore`), and the cluster integrity key. Compromise of T0 defeats every cryptographic
  control; protecting T0 from a network/storage adversary is the whole game.
- **T1 -- a node's live process + its data dir** (`raft-log`, `*.snapshot`, `raft.persistent_state`).
- **T2 -- the wire** between nodes and between control plane and edges.
- **T3 -- backup/snapshot storage and the restore path** -- written by a backup pipeline / operator,
  read back as authoritative on restore. **Wider write access than T1, no T0 key material.**
- **T4 -- the build/release supply chain** -- third-party dependencies, build host, registry.

## 2. Assets

| # | Asset | Property at risk | Primary control | Workstream |
|---|---|---|---|---|
| AS-1 | Committed configuration state (data-plane truth) | Integrity / authenticity | config signing (Ed25519) + consensus | A (compose) |
| AS-2 | Raft durable state: `currentTerm`/`votedFor`, WAL, snapshots | **Integrity** -- a forged value breaks Election Safety / injects committed state | **PA-2021 at-rest integrity** | **A** |
| AS-3 | Control-plane admin API (write/delete/membership/restore) | Authn / authz / anti-replay | Bearer auth + `AclService` + (new) replay guard | D |
| AS-4 | T0 key material at rest | Confidentiality / integrity | file mode 0600, separate mount; key-mgmt review | A, B (review) |
| AS-5 | Operational telemetry (`/metrics`) | Confidentiality (reconnaissance) | scrape auth / network segmentation | B, D |
| AS-6 | Inter-node & edge wire | Confidentiality / integrity / peer identity | mTLS (TLSv1.3, `setNeedClientAuth`) | B |
| AS-7 | Node availability under hostile input | Bounded resource use (no crash/OOM/hang) | framing bounds + 1 MB ceiling | C |
| AS-8 | Build artifacts + dependency graph | Supply-chain integrity, no known-exploitable CVE, no leaked secret | CVE scan, SBOM, repro build, gitleaks | E |
| AS-9 | Audit trail (once it exists) | Completeness + tamper-evidence | append-only audit log | D |

## 3. Adversaries

| ID | Adversary | Capability | Targets | Boundary tested by |
|---|---|---|---|---|
| **A1** | Network MITM | Read/inject/replay/downgrade on T2; no T0 keys | AS-1, AS-6 | **B** (mTLS), D (replay) |
| **A2** | **Storage/backup writer** | **Write** to T3 (snapshot/WAL/backup); **no T0 key material** | **AS-2** | **A** (PA-2021) -- *lead* |
| **A3** | Malicious / compromised peer | Speaks the wire with (or without) a valid cert; tries to install forged snapshot | AS-2 | A (install-snapshot) + B (cert identity) |
| **A4** | Unauthorized API client | No creds / read-scoped creds / captured-and-replayed request | AS-3, AS-5 | **D** (authn/authz/replay) |
| **A5** | Hostile wire input | Malformed/oversized/truncated frames, slowloris, connection floods | AS-7 | **C** (fuzzing) |
| **A6** | Supply-chain adversary | Compromised dependency, tampered build, committed secret | AS-8 | **E** |

## 4. Trust boundaries & the controls that must hold at each

- **B-WIRE (T2):** mTLS must reject plaintext, no-cert, wrong-CA, expired, and wrong-identity
  connections, and must not downgrade below TLSv1.2/weak ciphers. *Verified by B's negative tests.*
- **B-DISK (T1↔T3):** AS-2 at rest must be integrity-protected so that a T3 writer (A2) who lacks
  T0 keys cannot inject state that loads as authoritative. **Defense in depth:** this boundary must
  hold *even if B-WIRE is assumed bypassed* -- transport security does not protect data at rest.
  *Verified by A's tamper/forge/downgrade negative tests.*
- **B-INSTALL (T1↔peer):** a snapshot installed from a peer must pass the same AS-2 integrity check
  as a local restore -- a compromised peer (A3) must not install forged state even over a valid mTLS
  channel. *Verified by A wiring the check to `handleInstallSnapshot`, the same `RaftLog` path the
  reconfig/durability tests exercise.*
- **B-API (T-external↔T1):** every mutating endpoint requires authn; authz denies privilege
  escalation; a replayed authenticated request is rejected; every mutation is audited. *Verified by
  D's 401/403/replay/audit negative tests.*
- **B-RESOURCE (T2 ingress):** hostile input is rejected with bounded resource use; the 1 MB ceiling
  is enforced, not merely documented. *Verified by C's fuzz oracle tied to the measured baselines.*
- **B-BUILD (T4):** no unresolved exploitable high/critical CVE; SBOM present; build reproducible;
  no secret in repo/history. *Verified by E's scans in CI.*

## 5. The fence -- explicitly out of scope

These are stated honestly so a future review can weigh them. None is
claimed safe; each is either a deployment assumption, a specialist task, or an infra-campaign item.

1. **Full host / root compromise of a live node (T0+T1 together).** An adversary who holds the node's
   key material *and* its disk defeats every at-rest integrity scheme (they re-sign). PA-2021 protects
   against the **T3 storage/backup writer (A2) without T0 keys** -- the realistic, high-value case -- not against a fully-compromised host. **Deployment assumption made explicit:** the integrity key
   lives outside attacker-writable snapshot/WAL/backup storage (separate mount / KMS / `/secrets`),
   exactly as the mTLS keys do.
2. **Cryptographic construction soundness.** The PA-2021 integrity construction (MAC/signature choice,
   key derivation, canonicalization, the torn-vs-tamper disambiguation) is flagged for specialist
   cryptographic review. This model verifies it *behaves* correctly under tamper via negative tests;
   it does not self-certify the primitive as cryptographically sound.
3. **Real-network adversary simulation at scale** -- sustained DoS, distributed connection floods,
   cross-region MITM, NUMA/10⁹-key resource exhaustion. Tracked as follow-up infrastructure work.
4. **Certificate revocation (CRL/OCSP)** -- if not already built, flagged, not assumed. (Built since;
   see §7.)
5. **KMS/HSM integration and key rotation automation** -- design noted, implementation tracked
   separately. (Built since; see §7.)
6. **Side-channel / timing / fault-injection attacks on the primitives.** Out of scope.
7. **Physical access, supply-chain of the toolchain itself (compiler trust), insider with T0.**

## 6. Verification ledger

Each row flips to verified only with a named passing negative test. Proposed means the attack test is
written and currently red (pre-fix) or not yet written.

| Boundary | Control | Negative test(s) | Status |
|---|---|---|---|
| B-DISK | Tampered snapshot byte → restore refused | `SnapshotIntegrityTest#tamperedSnapshotPayloadByteIsRefused`, `#tamperedSnapshotWithRecomputedCrcIsRefusedByMac` | **VERIFIED** |
| B-DISK | Tampered WAL record (complete) → recovery refused; torn tail still tolerated | `WalRecordIntegrityTest#tamperedCompleteWalRecordIsRefused`, `#tornTrailingWalRecordIsToleratedAndPriorEntriesRecover` | **VERIFIED** |
| B-DISK | Downgraded/forged format version → refused | `SnapshotIntegrityTest#forgedFormatVersionIsRefused`, `#downgradeToAlgNoneIsRefused` | **VERIFIED** |
| B-DISK | Forged `DurableRaftState` (valid-looking `votedFor`) → refused | `DurableRaftStateIntegrityTest#forgedVotedForIsRefused`, `#forgedTermIsRefused` | **VERIFIED** |
| B-INSTALL | Forged install-snapshot from peer → refused | `SnapshotIntegrityTest#forgedInstalledSnapshotIsRefusedOnRecovery` | **VERIFIED** |
| B-WIRE | plaintext / no-cert / wrong-CA / wrong-SAN → refused (both planes) | `RaftTransportMtlsAttackTest`, `FanOutServerMtlsAttackTest`, `EdgeTransportSanMismatchTest`, existing `FanOutServerMtlsTest`/`EdgeTransportMtlsTest`/`find0051` | **VERIFIED** |
| B-WIRE | expired / revoked client cert → refused | `RaftTransportMtlsAttackTest#expiredClientCertificateIsRejected`, `FanOutServerMtlsAttackTest#expiredClientCertificateIsRejected`, `EdgeCertExpiryTest#{jdk,netty}EnforcedCertNotAfterClosesCredentialExpired`, `EdgeCrlRevocationTest#{jdk,netty}StrictRevokedCertRejected` | **VERIFIED** -- the self-signed-leaf-as-anchor gap is closed: admission arms a session-level `notAfter` deadline (`NettyFanOutServer.armCertExpiry`) that closes the connection when the leaf expires mid-session, and a CRL revocation posture (`RevocationChecker`/`CrlFileRevocationChecker`) refuses a revoked leaf at admission |
| B-WIRE | TLS<1.3 / weak cipher downgrade → refused | `RaftTransportMtlsAttackTest#tlsV12OnlyClientIsRejected...`, `FanOutServerMtlsAttackTest#tlsV12OnlyClientIsRejected...` | **VERIFIED** |
| B-WIRE | edge `/metrics` unauthenticated scrape → refused | `AbstractEdgeReadServerContract#metricsScrapeTokenGatesUnauthenticatedScrape` (both transports via `EdgeHttpServerTest`/`NettyEdgeHttpServerTest`), `#metricsOpenWhenNoScrapeTokenConfigured` | **VERIFIED** -- `/metrics` is Bearer-gated on a configured scrape token (`configd.edge.metricsScrapeToken`); an unauthenticated scrape is refused |
| B-RESOURCE | malformed / oversized / truncated / length-lie → bounded reject, no crash/OOM/hang/unbounded-alloc | `FrameCodecFuzzTest`, `EdgeFrameCodecFuzzTest` (23 props, resource oracle); read-loop bounded-alloc proven | **VERIFIED** |
| B-RESOURCE | ceiling enforced (not just documented) | fuzz tests assert layered caps (config-value 1 MiB / Raft frame 16 MiB) reject-before-alloc | **VERIFIED** -- ⚠ F-S7-FUZZ-2: the "1 MB" framing is actually layered caps; tracked as a follow-up decision |
| B-RESOURCE | slowloris / connection-flood → bounded resources | `InboundReadDeadlineFuzzTest` (mechanism pinned), `EdgeFirstFrameDeadlineTest#{jdk,netty}StalledPeerIsReaped` | **VERIFIED** -- every inbound plane bounds an idle connection: the fan-out plane arms a first-frame deadline (`NettyFanOutServer`, `FanOutServer.firstFrameDeadlineMs`) and the HTTP API / edge planes reap idle connections via `IdleStateHandler` (`NettyHttpApiServer`, `NettyEdgeHttpServer`), so a stalled peer cannot hold a file descriptor open indefinitely |
| B-API | unauthenticated mutating call → 401 | `ConfigHandlerAuthTest` (PUT/DELETE no-token/bad-token/malformed → 401) | **VERIFIED** |
| B-API | read-scoped credential attempts write → 403 (no HTTP membership endpoint -- Raft/CLI layer) | `ConfigHandlerAuthTest` (reader→PUT/DELETE→403, cross-prefix→403) | **VERIFIED** |
| B-API | replayed authenticated mutating request → rejected | `ConfigHandlerReplayTest` (verbatim replay→409), `ReplayGuardTest` | **VERIFIED** -- passive-replay only; content-signing → S8 |
| B-API | every mutating op produces a tamper-evident audit record | `AuditLogTest` (keyed-HMAC; `keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey`), `ConfigHandlerAuditTest` | **VERIFIED** -- residual: T0 key-holder (same fence as B-DISK) |
| B-BUILD | build reproducibility | 31 jars byte-identical across two clean builds after `project.build.outputTimestamp` | **VERIFIED** |
| B-BUILD | SBOM present | CycloneDX `gates/sbom/bom.json`; regen+diff lane in `gates/gate-7.sh` | **VERIFIED** |
| B-BUILD | runbook/script commands don't echo credentials | `ops/runbooks/*` + `ops/scripts/*` audited -- clean (no `set -x`/echo of secrets) | **VERIFIED** |
| B-BUILD | no unresolved exploitable high/critical CVE | `-Pcve-scan` profile (dependency-check, fail@CVSS>=7) wired | (env-blocked) NVD needs `NVD_API_KEY` + cache; runs in the gate-7 nightly job (network) |
| B-BUILD | no secret in repo/history | `.gitleaks.toml` present; local sanity-grep clean | (env-blocked) gitleaks not installed locally; wired into CI |
```

---

## 7. Addendum (2026-07-09) -- STRIDE rows for the planes added since the original model

The model above predates the at-rest encryption / KMS plane, the config-policy (`_acl/`/`_system/`)
plane, and the edge trusted-cache tier reaching their built shape. This addendum records STRIDE-shaped
threats and the controls that must hold for each. Two items fenced out of scope above are now **built**
(no longer fenced): **certificate revocation** (an off/lax/strict online-revocation posture; a revoked
edge cert is refused `AUTH_FAIL` at admission) and **KMS/HSM integration + non-destructive key rotation**
(Vault Transit provider + `NodeKeyring`).

### 7.1 KMS / encryption-at-rest plane (`NodeKeyring`, `KmsSealedRootStore`, Vault Transit)

| STRIDE | Threat | Control that must hold |
|---|---|---|
| **S**poofing | A rogue "KMS" answers unseal and hands back a bogus root | The Vault provider authenticates the server↔Vault channel (token/mTLS) and the sealed root is bound to a config-derived AAD (`nodeId`); an unexpected context fails the authenticated decrypt (fail-closed boot). |
| **T**ampering | A T3 disk writer edits the sealed-root carrier or a keyring slot | `raft-kms-root` (`RKMS` magic + version) and the dual-slot keyring are integrity-bound (AEAD / keyring MAC); a tampered slot fails to verify and boot **refuses** rather than re-mint (never silently orphans data). |
| **R**epudiation | -- | (Key operations are not the audit surface; the audit-log chain covers config mutations.) |
| **I**nformation disclosure | Encryption root leaks via a co-located signing key, a core dump, or swap | `local` root fate-shares with the signing key (D-1 co-location guard); Vault custody moves the root off-host; deploy-level `ulimit -c 0` + swap-off (F3) close the core-dump/swap paths. |
| **D**enial of service | A KMS outage stalls writes/replay | KMS is **boot-unseal-only** -- never on the write/replay path -- so an outage cannot shrink quorum mid-incident; selecting an unavailable provider is a fail-loud startup refusal, not a silent downgrade. |
| **E**levation | A downgrade to `algId=NONE`/no-encryption under a key | `IntegrityEnvelope` refuses `algId=NONE` under a key and (with `requireEncrypted`) refuses legacy HMAC records (C2 no-silent-downgrade). |

### 7.2 Config-policy plane (`_acl/`, `_system/` reserved prefixes; `_acl/format`)

| STRIDE | Threat | Control that must hold |
|---|---|---|
| **S**poofing | A non-admin principal writes/reads policy | Reserved prefixes require **ADMIN** for **every** method (mutation *and* disclosure), fail-closed, at the HTTP boundary. |
| **T**ampering | A malformed/poison `_acl/` policy loads and skews authz | Write-time validation (`validateAclWrite`) and the reload path run the **identical** parser; a bad policy is `400` pre-commit or fails closed to **last-good** on reload (never deny-all/allow-all). |
| **R**epudiation | A policy change leaves no trail | Policy writes are ADMIN-gated mutations captured by the keyed-HMAC audit chain. |
| **I**nformation disclosure | A non-ADMIN reads policy to map the authz surface | Reserved-prefix reads are ADMIN-gated (closes policy disclosure). |
| **D**enial of service | A poison key delivered via snapshot/replay freezes all subsequent policy updates | Fails closed to last-good and increments `configd.acl.policy.load.failed`; a stuck-load + non-advancing reload counter is a burn-in rollback trigger (R5). |
| **E**levation | A future grammar change silently absorbed by an old reader (authz split-brain) | The `_acl/format` version sentinel (C8) -- an old node fails closed on the whole subtree on an unknown version; a positional extension without a bump is forbidden. |

### 7.3 Edge trusted-cache tier (fan-out, filtering, hydration)

| STRIDE | Threat | Control that must hold |
|---|---|---|
| **S**poofing | An unauthorized cert pulls the whole store via legacy `SUBSCRIBE` | With auth ON, `SUBSCRIBE` is gated at admission on a **whole-store READ cover** (root-prefix READ, no intersecting deny); watch is per-key authorized. With auth OFF but mTLS ON, per-cert trust + network segregation is the only control (documented). |
| **T**ampering | A relay rewrites a delta or a hydration snapshot | Per-delta **Ed25519** signatures cover the version position (a relay cannot splice); the hydration snapshot is transport-authenticated (mTLS), not signed -- a driver MUST NOT accept it over an unauthenticated transport. |
| **R**epudiation | -- | (Edge reads are not per-event audited -- a deliberate DoS-avoidance choice.) |
| **I**nformation disclosure | `secure/` values persist at the edge | Edge keeps `secure/` values **in-memory only**, never on disk. |
| **D**enial of service | A slow/hostile consumer exhausts fan-out buffers | Bounded per-session queues + the `SlowConsumerGovernor` ladder (SLOW→CATCHUP→QUARANTINED→UNHEALTHY), keyed to the mTLS principal so reconnect storms cannot dodge it. |
| **E**levation | Server-side prefix filtering (ADR-0045) suppresses a matching delta undetected | A **trusted-domain-only** posture -- a well-formed suppression behind a correct covered-S is not edge-detectable; set `configd.edge.fanout.filter=off` when an untrusted relay terminates the fan-out (documented two-way door). |
