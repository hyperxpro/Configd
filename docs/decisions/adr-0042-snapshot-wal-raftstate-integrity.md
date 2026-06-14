# ADR-0042 — At-rest integrity for snapshot, WAL, and Raft durable state (PA-2021)

- **Status:** Accepted (S7); **crypto construction flagged for specialist review** (charter §2.5/§10.4)
- **Date:** 2026-06-14
- **Context finding:** PA-2021 (S2, `docs/prod-audit-cluster-B.md:549`) — lead P1 of Session 7
- **Supersedes/extends:** the unversioned formats in `DurableRaftState`, `RaftLog.serializeSnapshot`,
  and the WAL entry payload. Composes with RR-003 (durable-prefix) and S4 (torn-write).
- **Decision review:** `docs/session-7/decision-log.md` D-1 (fresh `opus` sub-agent verdict, verbatim).

## Problem

The on-disk Raft durability artifacts have **no integrity protection**:
- `DurableRaftState` (`raft.persistent_state`): 12 bytes `[term:8][votedFor:4]` — no magic/version/CRC.
  A flipped `votedFor` to a valid node id is undetectable and violates Election Safety.
- The snapshot blob (`raft-log.snapshot`): `[index][term][dataLen][data][cfgLen][cfg]` — **no
  CRC, no version, no authentication**. A tampered byte is silently restored as *authoritative
  committed state* — the highest-value injection in the system (bypasses transport + consensus).
- The WAL records (`raft-log`) already carry a per-record CRC32 via `FileStorage` with torn-tail
  tolerance — so *corruption* (PA-2022) is handled — but CRC32 is **not tamper-resistant** (an
  attacker who can write the file recomputes it), and the entry payload has no magic/version.

**Threat (charter):** adversary **A2** with write access to snapshot/WAL/backup storage (T3 —
backup bucket, shared NFS, the `restore-snapshot.sh` operator path) but **without** the node's
protected key material (T0). Transport mTLS does not protect data at rest (defense in depth).
Full host compromise (A holds T0) is explicitly out of scope (threat-model §5.1).

## Decision

Two layers, applied as a pure encode/decode transform over each artifact's existing payload.

### Layer A — versioned format + CRC32C (keyless: corruption / downgrade / format-evolution)

A self-describing envelope, mirroring the `FrameCodec` (ADR-0029) and `SigningKeyStore` precedents:

```
[ MAGIC: 4 ][ formatVersion: 2 ][ algId: 1 ][ reserved: 1 ][ payload ][ MAC: 0|32 ][ CRC32C: 4 ]
  └─ artifact-specific magic        └─ 0=NONE, 1=HMAC_SHA256          └─ present iff algId!=NONE
```
- `MAGIC` distinct per artifact (`RAFT_STATE`, `RAFT_SNAP`, `RAFT_WALE`) to defeat cross-artifact
  confusion. CRC32C (not CRC32) matches the codebase convention (`java.util.zip.CRC32C`, `FrameCodec`).
- CRC32C covers `MAGIC..payload..MAC` — detects corruption, truncation, format-confusion. **Keyless,
  so it is NOT the security control** — it is corruption/forward-compat hardening. CRC-only ≠ PA-2021
  closed (D-1 condition 2).

### Layer B — HMAC-SHA-256 (keyed: tamper / forgery — the security control)

- `MAC = HMAC-SHA-256(K_integrity, MAGIC || formatVersion || algId || payload)`. `algId` and
  `formatVersion` are **inside** the MAC input → an attacker cannot downgrade `algId` to NONE or
  roll `formatVersion` back without invalidating the MAC.
- **`K_integrity = HKDF-SHA256(IKM = cluster Ed25519 private-key encoding, info = "configd/raft-at-rest-integrity/v2", salt = keyId)`** — derived from the **existing cluster-shared signing key**
  (`SigningKeyStore`), so **no new key file and no new key-distribution channel** is introduced
  (charter §10.3 "no new attack surface"). The verify side uses the same derivation.
- Verify on every load / restore / install-snapshot. Compare in **constant time**
  (`MessageDigest.isEqual`).

### Downgrade posture (D-1 condition 2 — sticky fail-closed)

- A node with an integrity key configured runs **fail-closed**: it REFUSES an artifact whose
  `algId=NONE` (or whose MAC is absent) — the MAC covering `algId` is necessary but not sufficient;
  posture, not bytes, defeats strip-the-MAC. A node with **no** integrity key configured still gets
  Layer A (version+CRC32C) and reads either form (opt-in authentication, pre-production only).
- **Production default must flip to fail-closed** — tracked as an **S8 go/no-go gate item**, not
  closed here (D-1 condition 2).

### Torn-vs-tamper rule (D-1 condition 3 — the critical disambiguation)

- **WAL:** authentication lives **inside** the `FileStorage` per-record frame, *after* the existing
  torn-tail/length/CRC32 checks. A partial trailing record from a mid-append crash is dropped as
  **torn** by the existing logic *before* the MAC is ever checked. A record that is
  complete + CRC32-valid + **MAC-invalid** is **tamper → fail loud** (throw), never dropped.
- **Atomic-rename artifacts** (snapshot blob, `raft.persistent_state`): never torn (temp+fsync+
  atomic-rename), so a MAC mismatch on a structurally-complete artifact **always fails loud**.
- **`readSnapshotBlob` MUST throw on a MAC mismatch of a structurally-complete blob — it must NOT
  return `null` (the current torn/short → WAL-fallback behavior)**, or a silent-downgrade
  vulnerability is reintroduced (D-1 condition 4, BLOCKING composition point). Structurally-short/
  absent stays `null` (legit torn / first boot).

### Composition with RR-003 / S4 (D-1 condition 4)

- Applied as a pure transform inside `serialize*`/`read*` — the persist-before-truncate ordering
  in `compact()` and the "ahead-of-WAL blob is ignored" recovery rule are unchanged. The S4
  durability cells (`SnapshotCrashRecoveryTest`, `WalSyncCrashTest`, `VotePersistenceCrashTest`,
  `AdversarialCrashRecoveryTest`, `StorageEnospcConsensusReactionTest`, `RaftLogUnitTest`) must
  re-run **green** after the change (gate-7 requirement §9).

## Negative tests (the deliverable — charter §2.1)

Each attack succeeds pre-fix (captured), fails post-fix, second-agent reproduces:
1. Tampered snapshot byte → `restore/recovery refused` (throws, does not load).
2. Tampered WAL record (complete) → recovery refused; **torn tail still tolerated** (separate cell).
3. Forged/rolled-back `formatVersion` or `algId=NONE` under a configured key → refused.
4. Forged `DurableRaftState` (valid-looking `votedFor`, recomputed CRC, **no valid MAC**) → refused.
5. Forged install-snapshot from a peer → refused on the same path as local restore.

## Consequences / residual risk (→ crypto review + manifest)

Flagged for **specialist cryptographic review** (we verify behavior, do not self-certify primitive):
1. Using the Ed25519 private-key encoding as HKDF IKM — soundness of that IKM choice.
2. MAC message canonicalization (fixed-width prefix; no length-extension concern with HMAC).
3. Downgrade-policy completeness; constant-time comparison.
4. **Suffix truncation:** a per-record WAL MAC authenticates each record but not the *set* — an
   attacker who truncates committed records from the tail produces a shorter-but-valid log. Mitigated
   operationally by the snapshot boundary + commit index; a whole-log anchor is **flagged** (manifest).
5. CRC32C vs CRC32 rationale.

**Key-location requirement (D-1 BLOCKER):** `K_integrity`'s value depends on the cluster signing key
living **outside** attacker-writable storage. Production Compose already mounts it at
`/secrets/signing-key.bin:ro` (`deploy/compose/setup-secrets.sh`). The **insecure default**
(`dataDir.resolve("signing-key.bin")`, `ConfigdServer.java:197-199`) co-locates it — S7 emits a loud
startup warning when the key resolves inside the data dir; relocating the default is an S8 item.
