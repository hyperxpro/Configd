# Session 7 Threat Model — Configd Security & Supply Chain

> **Status:** DRAFT pending `review-architect` scope-fence sign-off (charter §12.1).
> **Prime directive (charter §2):** every control in this model is VERIFIED only by a
> passing negative test that proves the attack fails — never by reading its configuration.
> This document scopes *what* we verify and draws the honest fence (§2.5) around what we do not.

## 0. Purpose & method

This threat model exists to scope Session 7 and prevent both over- and under-reach. It enumerates
the **assets** worth protecting, the **adversaries** who would attack them, the **trust boundaries**
where a control must hold, and — explicitly — **what is out of scope** for a local, pre-production
hardening pass. Each in-scope control maps to a Session-7 workstream (A–E) whose deliverable is a
negative test. The fence (§5) is the auditable statement of what an adversary could still do that we
did **not** prove safe this session; those items go to the S7.5 infra manifest or the S8 go/no-go.

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
- **T0 — node key material:** mTLS private keys (`/secrets`), the cluster Ed25519 signing key
  (`SigningKeyStore`), and (new this session) the cluster integrity key. Compromise of T0 defeats
  every cryptographic control; protecting T0 from a network/storage adversary is the whole game.
- **T1 — a node's live process + its data dir** (`raft-log`, `*.snapshot`, `raft.persistent_state`).
- **T2 — the wire** between nodes and between control plane and edges.
- **T3 — backup/snapshot storage and the restore path** — written by a backup pipeline / operator,
  read back as authoritative on restore. **Wider write access than T1, no T0 key material.**
- **T4 — the build/release supply chain** — third-party dependencies, build host, registry.

## 2. Assets

| # | Asset | Property at risk | Primary control | Workstream |
|---|---|---|---|---|
| AS-1 | Committed configuration state (data-plane truth) | Integrity / authenticity | config signing (Ed25519) + consensus | A (compose) |
| AS-2 | Raft durable state: `currentTerm`/`votedFor`, WAL, snapshots | **Integrity** — a forged value breaks Election Safety / injects committed state | **PA-2021 at-rest integrity** | **A** |
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
| **A2** | **Storage/backup writer** | **Write** to T3 (snapshot/WAL/backup); **no T0 key material** | **AS-2** | **A** (PA-2021) — *lead* |
| **A3** | Malicious / compromised peer | Speaks the wire with (or without) a valid cert; tries to install forged snapshot | AS-2 | A (install-snapshot) + B (cert identity) |
| **A4** | Unauthorized API client | No creds / read-scoped creds / captured-and-replayed request | AS-3, AS-5 | **D** (authn/authz/replay) |
| **A5** | Hostile wire input | Malformed/oversized/truncated frames, slowloris, connection floods | AS-7 | **C** (fuzzing) |
| **A6** | Supply-chain adversary | Compromised dependency, tampered build, committed secret | AS-8 | **E** |

## 4. Trust boundaries & the controls that must hold at each

- **B-WIRE (T2):** mTLS must reject plaintext, no-cert, wrong-CA, expired, and wrong-identity
  connections, and must not downgrade below TLSv1.2/weak ciphers. *Verified by B's negative tests.*
- **B-DISK (T1↔T3):** AS-2 at rest must be integrity-protected so that a T3 writer (A2) who lacks
  T0 keys cannot inject state that loads as authoritative. **Defense in depth:** this boundary must
  hold *even if B-WIRE is assumed bypassed* — transport security does not protect data at rest
  (charter §2.2). *Verified by A's tamper/forge/downgrade negative tests.*
- **B-INSTALL (T1↔peer):** a snapshot installed from a peer must pass the same AS-2 integrity check
  as a local restore — a compromised peer (A3) must not install forged state even over a valid mTLS
  channel. *Verified by A wiring the check to `handleInstallSnapshot`, the same `RaftLog` path the S4
  reconfig/durability cells exercise.*
- **B-API (T-external↔T1):** every mutating endpoint requires authn; authz denies privilege
  escalation; a replayed authenticated request is rejected; every mutation is audited. *Verified by
  D's 401/403/replay/audit negative tests.*
- **B-RESOURCE (T2 ingress):** hostile input is rejected with bounded resource use; the 1 MB ceiling
  is enforced, not merely documented. *Verified by C's fuzz oracle tied to S5 baselines.*
- **B-BUILD (T4):** no unresolved exploitable high/critical CVE; SBOM present; build reproducible;
  no secret in repo/history. *Verified by E's scans in CI.*

## 5. The fence — explicitly OUT of scope this session (charter §2.5, §10.8)

These are stated honestly so the S8 go/no-go and the S7.5 infra manifest can weigh them. None is
claimed safe; each is either a deployment assumption, a specialist task, or an infra-campaign item.

1. **Full host / root compromise of a live node (T0+T1 together).** An adversary who holds the node's
   key material *and* its disk defeats every at-rest integrity scheme (they re-sign). PA-2021 protects
   against the **T3 storage/backup writer (A2) without T0 keys** — the realistic, high-value case —
   not against a fully-compromised host. **Deployment assumption made explicit:** the integrity key
   lives outside attacker-writable snapshot/WAL/backup storage (separate mount / KMS / `/secrets`),
   exactly as the mTLS keys do.
2. **Cryptographic construction soundness.** The PA-2021 integrity construction (MAC/signature choice,
   key derivation, canonicalization, the torn-vs-tamper disambiguation) is **flagged for specialist
   cryptographic review** (charter §2.5/§10.4). We verify it *behaves* correctly under tamper via
   negative tests; we do **not** self-certify the primitive as cryptographically sound.
3. **Real-network adversary simulation at scale** — sustained DoS, distributed connection floods,
   cross-region MITM, NUMA/10⁹-key resource exhaustion. → S7.5 infra manifest (M-9/M-10 lane).
4. **Certificate revocation (CRL/OCSP)** — if not already built, flagged, not assumed.
5. **KMS/HSM integration & key rotation automation** — design noted, implementation → manifest.
6. **Side-channel / timing / fault-injection attacks on the primitives.** Out of scope.
7. **Physical access, supply-chain of the toolchain itself (compiler trust), insider with T0.**

## 6. Verification ledger (filled in as workstreams land)

Each row flips to VERIFIED only with a named passing negative test (charter §2.1). PROPOSED ⇒ the
attack test is written and currently RED (pre-fix) or not yet written.

| Boundary | Control | Negative test(s) | Status |
|---|---|---|---|
| B-DISK | Tampered snapshot byte → restore refused | `SnapshotIntegrityTest#tamperedSnapshotPayloadByteIsRefused`, `#tamperedSnapshotWithRecomputedCrcIsRefusedByMac` | **VERIFIED** |
| B-DISK | Tampered WAL record (complete) → recovery refused; torn tail still tolerated | `WalRecordIntegrityTest#tamperedCompleteWalRecordIsRefused`, `#tornTrailingWalRecordIsToleratedAndPriorEntriesRecover` | **VERIFIED** |
| B-DISK | Downgraded/forged format version → refused | `SnapshotIntegrityTest#forgedFormatVersionIsRefused`, `#downgradeToAlgNoneIsRefused` | **VERIFIED** |
| B-DISK | Forged `DurableRaftState` (valid-looking `votedFor`) → refused | `DurableRaftStateIntegrityTest#forgedVotedForIsRefused`, `#forgedTermIsRefused` | **VERIFIED** |
| B-INSTALL | Forged install-snapshot from peer → refused | `SnapshotIntegrityTest#forgedInstalledSnapshotIsRefusedOnRecovery` | **VERIFIED** |
| B-WIRE | plaintext / no-cert / wrong-CA / wrong-SAN → refused (both planes) | `RaftTransportMtlsAttackTest`, `FanOutServerMtlsAttackTest`, `EdgeTransportSanMismatchTest`, existing `FanOutServerMtlsTest`/`EdgeTransportMtlsTest`/`find0051` | **VERIFIED** |
| B-WIRE | expired client cert → refused | `RaftTransportMtlsAttackTest#expiredClientCertificateIsRejected`, `FanOutServerMtlsAttackTest#expiredClientCertificateIsRejected` | **VERIFIED (CA-signed model)** — ⚠ F-S7-TLS-1: the production *self-signed-leaf-as-anchor* model does NOT enforce expiry (RFC 5280 §6.1); → S7.5 manifest |
| B-WIRE | TLS<1.3 / weak cipher downgrade → refused | `RaftTransportMtlsAttackTest#tlsV12OnlyClientIsRejected...`, `FanOutServerMtlsAttackTest#tlsV12OnlyClientIsRejected...` | **VERIFIED** |
| B-WIRE | edge `/metrics` plaintext/no-auth exposure | _(finding)_ | ⚠ F-S7-TLS-2 — documented finding + segmentation recommendation; → S7.5 manifest |
| B-RESOURCE | malformed / oversized(>1 MB) / truncated / slowloris → bounded reject, no crash/OOM/hang | _(C)_ | PENDING |
| B-API | unauthenticated mutating call → 401 | _(D)_ | PENDING |
| B-API | read-scoped credential attempts write/membership → 403 | _(D)_ | PENDING |
| B-API | replayed authenticated mutating request → rejected | _(D)_ | PENDING |
| B-API | every mutating op produces a tamper-evident audit record | _(D)_ | PENDING |
| B-BUILD | no unresolved exploitable high/critical CVE; SBOM; repro; no secret | _(E)_ | PENDING |
```
