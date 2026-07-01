# Encryption at Rest — Configd-Specific Analysis

> **Session:** Encryption-at-rest research (RR-098), 2026-06-28. **Status:** research only — no code.
> Reads on from [`prior-art.md`](prior-art.md); feeds [`recommendation.md`](recommendation.md).
>
> This is the document that matters. Configd is **not** a generic KV store — it is sharded,
> consensus-replicated, with a WAL + snapshots + an edge fan-out plane, and it already ships an at-rest
> **integrity** envelope (ADR-0042). Encryption interacts with all of that. The charter names four cruxes
> and forbids hand-waving them: (a) the WAL/snapshot **storage-layer vs end-to-end** question for a
> *consensus* system; (b) the **edge key-distribution** fork; (c) **key availability vs failover**; (d) the
> **write-path performance** cost. Each is analyzed against verified code below, with `file:line` evidence.

---

## 1. Plaintext-surface inventory — what holds config plaintext today

Every on-disk artifact that contains config values or Raft state, the class/method that writes it, and its
current at-rest protection. (Verified by reading the source on branch `research-encryption-at-rest`, HEAD of
`pre-ec2-cleanup`. The integrity envelope is ADR-0042 / `IntegrityEnvelope.java`.)

| # | Surface | Writer (`file:line`) | On-disk form | Holds config values? | At-rest protection today |
|---|---|---|---|---|---|
| 1 | **WAL** `raft-log.wal` | `RaftLog.serializeEntry` → `Storage.appendToLog` (`RaftLog.java:635,371,781`; `FileStorage.java:115`) | `[len][ IntegrityEnvelope{ [idx][term][command] } ][CRC32]` | **Yes** — `command` is the opaque config PUT/DELETE/BATCH payload | **Integrity only** (HMAC-SHA256, ADR-0042); CRC32 frame; **plaintext** |
| 2 | **Snapshot blob** `raft-log.snapshot` | `RaftLog.serializeSnapshot` → `Storage.put` (`RaftLog.java:681,526`) | `IntegrityEnvelope{ [idx][term][dataLen][data][cfgLen][cfg] }` | **Yes** — `data` = full state-machine snapshot (every key+value) | **Integrity only**; atomic-rename; **plaintext** |
| 3 | **State-machine snapshot bytes** (inside #2) | `ConfigStateMachine.snapshot()` (`ConfigStateMachine.java:350+`) | `[seq][count]( [klen][key][vlen][value] )*` | **Yes** — every key and value, in the clear | inherits #2; **plaintext** |
| 4 | **Durable Raft state** `raft.persistent_state` | `DurableRaftState.persistValues` (`DurableRaftState.java:157`) | `IntegrityEnvelope{ [term:8][votedFor:4] }` | No (consensus metadata) | **Integrity only**; atomic-rename; **plaintext** |
| 5 | **Snapshot meta** `raft-log.snapshot-meta` | `RaftLog.compact` (`RaftLog.java:558`) | `[idx:8][term:8]` | No | none (non-secret indices) |
| 6 | **In-memory store (HAMT)** | `VersionedConfigStore` / `HamtMap` | not persisted — rebuilt from #1+#2 on boot | values in RAM only | n/a (memory; out of at-rest scope) |
| 7 | **Audit log** (when auth on) | `AuditLog` → `Storage.appendToLog` (`ConfigdServer.java:765,847`) | HMAC-chained records | request metadata (keys, principals) | **Integrity** (HMAC chain, `K_audit`); **plaintext** |
| 8 | **Edge store** | `LocalConfigStore` (`configd-edge-cache/.../LocalConfigStore.java:48`) | **in-memory only — no disk write of config values** | values in RAM only | n/a — see §4 |

**The crucial structural fact:** Configd has **no separate on-disk store file**. Config values live on disk
in exactly two places — **the WAL entries (#1) and the snapshot blob (#2/#3)** — and the in-memory HAMT (#6)
is reconstructed purely by replaying them. So *"encrypt the config data at rest"* reduces precisely to
**"encrypt the WAL records and the snapshot blob"** (plus, for completeness, the durable Raft state #4 and
the audit log #7). This is a much smaller and cleaner surface than a database with a separate heap/SSTable
store, and it maps one-to-one onto the existing ADR-0042 envelope points.

**Crypto baseline (greenfield for encryption).** There is **no `javax.crypto.Cipher`, no AES, no
`SealedObject`** anywhere in `*/src/main/java`. What exists and is reusable: `javax.crypto.Mac` (HMAC-SHA256,
in `IntegrityEnvelope`, `Hkdf`, `AuditLog`), Ed25519 signing (`SigningKeyStore`, `ConfigSigner`), a clean
RFC-5869 **HKDF** (`io.configd.common.Hkdf`), `CRC32C`, and `MessageDigest.isEqual` (constant-time compare).
So encryption is **additive** — a new cipher path alongside proven HMAC/HKDF infrastructure, not a rewrite.

---

## 2. Where the integrity envelope sits — the seam, verified

ADR-0042 applies `IntegrityEnvelope.wrap()` as a **pure transform on each artifact's local persistence
path**, and *nowhere else*. This was verified against the replication paths, because the answer determines
the entire design:

- **WAL:** `serializeEntry()` (which calls `integrity.wrap(WALE_MAGIC, …)`) is invoked **only** at
  `RaftLog.java:371` (`appendToLogNoSync`) and `:781` (`appendToLog`) — both **local disk writes**.
- **Replication wire:** `AppendEntriesRequest` carries `List<LogEntry>` where `LogEntry(long index, long
  term, byte[] command)` is the **in-memory record** (`LogEntry.java:13`, `AppendEntriesRequest.java:20-25`).
  The leader ships **plaintext `LogEntry` records**; the envelope is **not** on the wire.
- **InstallSnapshot:** `sendInstallSnapshot()` builds the request from `latestSnapshot.data()` — the
  **in-memory state-machine snapshot bytes** (`RaftNode.java:2057-2066`), **not** the on-disk enveloped blob
  from `readSnapshotBlob()`. Again the wire carries **plaintext**.

```
                    leader                                 follower
  state machine  --plaintext LogEntry / snapshot-->   receives plaintext
        |          (over the transport: mTLS)               |
   serializeEntry()                                    serializeEntry()      <-- envelope (and, in a
   IntegrityEnvelope.wrap()                            IntegrityEnvelope.wrap()    future, encryption)
        |                                                   |                      is applied HERE,
   FileStorage.append (local disk)                     FileStorage.append          PER NODE, locally
```

**Conclusion (the linchpin of this whole analysis):** Configd's at-rest envelope is *already* a **per-node,
below-replication, storage-layer transform**. The transport already carries plaintext (protected by mTLS,
register §8.3 — `setNeedClientAuth(true)` on both the Raft and fan-out transports). Therefore **node-local
storage-layer encryption (the CockroachDB model, [`prior-art.md`](prior-art.md) §4) drops into the existing
composition points with zero wire-format change and zero cluster-wide key distribution.** Configd did not
have to be designed for this; it already has the exact seam.

---

## 3. The consensus crux — storage-layer vs end-to-end encryption of the WAL/snapshot

This is the question the charter flags as the heart of the matter. Raft replicates log entries between nodes
(mTLS in transit) and persists them (plaintext at rest). There are two places to add encryption:

### Option S — storage-layer (each node encrypts its own WAL/snapshot with a local key)

Add the cipher at the **same seam as the integrity envelope** (§2): wrap inside `serializeEntry` /
`serializeSnapshot` / `DurableRaftState.persistValues`, on each node's local disk path.

- **Wire:** unchanged — AppendEntries/InstallSnapshot keep carrying plaintext over mTLS.
- **Keys:** **node-local.** Each node encrypts what *it* persists with *its own* data key; no node needs any
  other node's key. A follower decrypts only its own WAL to replay; a new replica streams a plaintext
  snapshot over mTLS and re-encrypts locally.
- **Key distribution:** **none.** This is the property that makes it tractable.
- **What it defends:** disk theft, backup/snapshot leakage, a decommissioned volume, an attacker who reads a
  replica's data directory — i.e. **exactly the ADR-0042 A2/T3 adversary** (backup bucket, shared NFS,
  `restore-snapshot.sh`), now for confidentiality, not just integrity.
- **What it does not defend:** a live node (the state machine holds the HAMT in plaintext in RAM); the edge
  (§4); the keyspace shape if only values are encrypted at the WAL level (key names are inside the `command`
  payload, so whole-record encryption actually *does* cover key names here — a small win over K8s, where
  paths leak).

### Option E2E — end-to-end (encrypt entries before they are proposed; replicate ciphertext)

Encrypt the `command` (or the whole entry) **above** Raft — in `ConfigWriteService` / before `RaftNode.propose()` —
so the ciphertext *is* the replicated log entry.

- **Wire:** now carries ciphertext (defense-in-depth beyond mTLS — marginal, since mTLS already protects
  transit).
- **Keys:** **every replica that applies the entry needs the same key** to decrypt before applying to its
  state machine; **every node that ever becomes leader, restores a snapshot, or serves a read needs it too.**
- **Key distribution:** **cluster-wide, and it inherits the Kubernetes problem verbatim** — the
  *decryptors ⊇ encryptors* invariant ([`prior-art.md`](prior-art.md) §2.4): a new key must be present as a
  decrypt capability on every voter before any node writes with it, and key rotation becomes a two-phase,
  add-before-use / migrate-then-remove protocol *layered on top of Raft's own membership protocol*. With
  sharding (N groups, rehoming, range splits/merges — Phase 1) the key set must follow each shard as it
  moves between owners. This is a hard distributed-systems problem Configd would be **adding**, not solving.
- **It also fights the system:** encrypted-above-Raft entries are opaque to any future server-side operation
  on content (the etcd #7542 tradeoff — no content-based compare/condition), and identical values stop being
  detectable.
- **The one thing it buys** that S does not: confidentiality of values **in the log itself on a live node's
  RAM is unchanged** (the state machine still decrypts to apply), so E2E's *only* real extra is wire
  ciphertext + the ability to keep data encrypted on a node that **stores but does not apply** a shard
  (a non-voting log host). That is a narrow benefit for a large cost.

### Verdict for Configd's consensus plane: **storage-layer (Option S).**

mTLS already covers the wire, so E2E's headline benefit is redundant; meanwhile E2E imports the full
cluster-wide key-distribution + two-phase-rotation burden and breaks server-side content operations, and
**sharding makes it strictly worse** (keys must chase shards across owners). Option S defends the realistic
at-rest threat (the ADR-0042 adversary), needs **no key distribution**, drops into the **existing envelope
seam with no wire change**, and is exactly how CockroachDB — the closest prior art — does it. The consensus
crux resolves cleanly **in favor of node-local storage-layer encryption.**

### How encryption composes with the existing envelope (construction, not hand-wave)

The ADR-0042 envelope is `[MAGIC][formatVersion][algId][reserved][payload][MAC][CRC32C]` with `algId`
∈ {0=NONE, 1=HMAC_SHA256}. Encryption is a **new `algId`**, added as a transform at the same point. Two
established constructions, both standard (never roll your own — [hard rule §5.5 of the charter]):

- **(a) AES-256-GCM (AEAD), `algId=2`.** The GCM tag *replaces* the HMAC (GCM gives confidentiality **and**
  authenticity in one pass); CRC32C stays for corruption/forward-compat. Envelope becomes
  `[MAGIC][ver][algId=2][reserved][keyId][nonce][ciphertext+tag][CRC32C]`. One pass, simplest, and what
  Vault/K8s/AWS all use. **Nonce uniqueness is the one correctness constraint** — derive the 96-bit nonce
  deterministically from **segment-id ‖ record-offset** (WAL) or a per-snapshot random nonce, never reuse a
  (key, nonce) pair, and rotate the data key before 2³² records per NIST SP 800-38D (exactly Vault's
  auto-rotate trigger).
- **(b) AES-256-CTR-then-HMAC, `algId=3`.** Encrypt the payload with AES-CTR, keep the existing HMAC over the
  header+ciphertext (encrypt-then-MAC). Matches CockroachDB's CTR choice and **reuses the proven `K_integrity`
  HMAC path** unchanged; needs a second derived key for the cipher. Slightly more moving parts than GCM.

**Bind the location into the AAD/MAC input** ([`prior-art.md`](prior-art.md) §5.4): include `MAGIC ‖ formatVersion ‖ keyId ‖ segment-id ‖ record-offset ‖ term` in the GCM AAD (or the MAC input), so an attacker
who controls the data directory cannot relocate, reorder, truncate, or splice records — closing the
whole-log tail-truncation residual that ADR-0042 §"Consequences" item 4 explicitly left open.

**Recommendation leans (a) AES-256-GCM** for simplicity and single-pass AEAD; see [`recommendation.md`](recommendation.md).
Either way, the **fail-closed posture, the per-artifact MAGIC, the `algId` downgrade refusal, and the
torn-vs-tamper rule from ADR-0042 all carry over unchanged** — encryption inherits a reviewed integrity
framework rather than starting from scratch.

---

## 4. The edge plane — the fork that (mostly) dissolves

The charter raises a real design fork: if config is encrypted at rest, do edge replicas **decrypt to serve
reads** (key on every edge — a large key-distribution surface) or **serve ciphertext** (clients decrypt —
changes the read model)? For Configd, the premise is **largely false today**, which is the most important
edge finding:

- **The edge store is in-memory only.** `LocalConfigStore` holds a single volatile `ConfigSnapshot`
  (RCU pattern, `LocalConfigStore.java:48`); deltas are applied by swapping an immutable HAMT
  (`applyDelta`, `:229+`). There is **no `Storage`, no WAL, no `.dat` file** — the edge **never writes config
  values to disk** (register §8.2 "edge store is in-memory → bounded exposure"; known-limitations §1). Even
  `secure/` values at the edge are *"kept in-memory only, never written to disk — the RR-098 mitigation."*
- **Therefore the edge has no at-rest surface to encrypt.** At-rest encryption is a **control-plane concern**
  (surfaces #1–#4, #7 in §1). The "key on every edge" fork **does not arise for v1/v2** as long as the edge
  stays memoryless. Storage-layer encryption on the control plane is invisible to the edge: the edge receives
  plaintext deltas/snapshots over the **already-mTLS fan-out transport** (register §8.3,
  `NettyFanOutServer:215`) and holds them in RAM.

**The residual edge exposure is memory, not disk** — a heap dump / core dump / swap of an edge process
contains `secure/` values in the clear. That is a *runtime* confidentiality problem (the live-process threat
every TDE explicitly excludes, [`prior-art.md`](prior-art.md) §6), not an at-rest one, and it is **out of
RR-098's scope**. It would only be addressed by **value-level / end-to-end** encryption (Option C in
[`recommendation.md`](recommendation.md)) where the edge serves ciphertext and clients decrypt — the genuine
fork, but one that only becomes relevant **if** Configd ever decides to hold secrets (which current posture
says it must not).

**The fork only re-opens if the edge gains disk persistence** (e.g. a future on-disk edge cache for fast
restart). At that point the edge would need either its own node-local storage-layer key (Option S extended to
edges — bounded, since edges hold only their subscribed slice) or to serve ciphertext. **This should be a
documented design constraint on any future persistent-edge work**, recorded now so it is not missed.

---

## 5. Key availability vs failover — the consensus liveness constraint

A consensus store has a non-negotiable rule: **it must not lose write availability because a key is
unavailable.** Encryption interacts with two lifecycle moments:

- **Node restart / crash-replay.** A node that restarts must decrypt its own WAL + snapshot to replay and
  rejoin. If decryption needs a live external call (KMS) and KMS is down, the node **cannot rejoin —
  shrinking the quorum precisely during an incident** ([`prior-art.md`](prior-art.md) §3.3). This is the
  failure a quorum cannot vote its way out of, because the KMS dependency is *correlated* across all nodes.
- **Write path.** If every WAL append needed a key operation against an external service, a KMS blip would
  stall the commit pipeline on every node at once.

Configd's durability contract (`fsync`-before-ack, no early-ack — register §11.5; group-commit amortizes the
fsync, `RaftLog.syncWal`/`FileStorage.syncLog`) means writes are already gated on local `fsync`. The design
rule, applied to Configd:

> **The data key must be available locally, derived once at boot, never fetched per-operation; and key
> acquisition must not introduce a new boot-time dependency that can block a node from rejoining.**

Two ways to satisfy this, in increasing key-management maturity (both keep KMS off the per-write path):

1. **Derive the data key from the cluster signing key via HKDF — the zero-new-dependency option.** The node
   *already must* load the Ed25519 signing key at boot for config signing (ADR-0027) and already derives
   `K_integrity` and `K_audit` from it via `Hkdf.deriveKey(...)` with distinct `info` strings
   (`ConfigdServer.java:1115,1204`). Add a third: `K_enc = HKDF(IKM = signing-key, info =
   "configd/raft-at-rest-encryption/v2", salt = keyId)`. The encryption key is then available **the instant
   the signing key is read — no external call, no new failure mode, no unseal step that can block rejoin.**
   This is the lightest possible lift and is consistent with ADR-0042's own key-derivation precedent. Cost:
   the encryption key **shares fate with the signing key** (if the signing key leaks, data is decryptable —
   but if the signing key leaks the attacker can already forge committed state, so the marginal loss is
   bounded), and there is **no managed key rotation** beyond rotating the signing key.

2. **Auto-unseal a per-node root key via KMS at boot — the managed option.** Wrap a per-node root key under a
   KMS CMK; make **one** KMS `Decrypt` at startup; cache the root key in memory; derive per-segment DEKs
   locally. KMS is on the rare boot/rotation path only. This gives off-host key custody and managed rotation
   (the keyring/term model) at the cost of a **boot-time KMS dependency** — mitigated by multi-Region keys +
   backoff, but it is a real new dependency a node needs to rejoin. **Mitigation:** an already-running node
   must continue on its cached key through a KMS outage and only fail-closed for *new* key acquisition; and
   the unseal must be **non-blocking for replay** wherever possible (decrypt-from-cache).

**The unseal-blocks-boot tension, made concrete.** Vault's seal model blocks *all* operations until
unsealed — acceptable for a secrets manager, **questionable for a config store on the read/availability
path.** Configd should therefore prefer **auto-unseal (never interactive Shamir)** and treat the encryption
key as **required-at-boot but locally-derivable**, so that the strong default (fail-closed if the key is
configured but unavailable, mirroring ADR-0042's sticky fail-closed posture) does not turn a key-management
hiccup into a cluster-wide outage. Option 1 sidesteps this entirely; Option 2 must engineer around it.

---

## 6. Write-path performance — the cost estimate

Encryption lands on the hot write path (per WAL append) and the snapshot path (bulk). Estimate from the
measured baseline and the cipher characteristics:

- **Baseline:** the single-group write knee is **~800 writes/s**, and the binding constraint is **`fsync` +
  consensus/election latency, not CPU** (register §9.1; per-op fsync ceiling was ~380/s pre-group-commit, and
  group commit amortizes fsync across a batch — `RaftLog.syncWal`). The write path is **I/O- and
  coordination-bound, not compute-bound.**
- **What encryption adds:** AES-256-GCM is **CPU only — no extra `fsync`, no extra round-trip.** With AES-NI
  (every modern x86/ARM server core), AES-GCM runs at **multiple GB/s per core**. Config entries are small
  (a key + value, tens to hundreds of bytes), so per-entry encryption is **sub-microsecond** and is *"adds
  CPU work, parallelizable across entries in a group-commit batch"* (consistent with the surface map's hot-path
  note). Against a per-commit budget dominated by an `fsync` (hundreds of µs to single-digit ms) plus a
  replication round-trip, the cipher cost is **in the noise.**
- **Prior-art calibration:** database TDE measures **2–4% overhead typically, <0.5% with AES-NI**
  ([`prior-art.md`](prior-art.md) §4.3), and that is for storage engines whose bottleneck is page I/O. A
  consensus store whose bottleneck is `fsync` + election coordination will see **even less** relative impact.
- **Snapshot path:** a snapshot is a single bulk AES-GCM pass over the serialized state-machine bytes —
  GB/s with AES-NI, one-time per compaction, off the latency-critical write path.
- **Honest caveat:** this is an **estimate**, not a measurement. It should be **confirmed on the EC2 N×knee
  run** that is already the next money-gated step — add an encryption-on vs encryption-off arm to gate-5 /
  the knee ladder. The *prediction*, to be falsified: **no measurable throughput regression at the knee**,
  because the cipher is CPU and the knee is `fsync`/election-bound. Allocation must also be checked against
  the gate-5 `<1 B/op` read-path floor — encryption is write-path, but a careless `Cipher`/buffer allocation
  per entry could regress the gate; the implementation must reuse buffers and `Cipher` instances (thread-local),
  exactly as the GC work did for the read path (register §9.5/§9.12).

**Bottom line: performance is not a blocker for the consensus plane.** The write path is not CPU-bound, AES-NI
makes the cipher nearly free, and the cost lands as a low-single-digit-percent CPU overhead that the
`fsync`/coordination ceiling absorbs. This removes the most common objection to at-rest encryption.

---

## 7. The `secure/` namespace and the per-namespace option

`secure/` is currently a **freshness** class (always-linearizable, fail-closed reads for kill-switches /
ACL revocations / legal gates — ADR-0030 INV-1), **explicitly not confidentiality**
(`StrongReadKeyClass.java:17-23`, known-limitations §1). The charter asks: would at-rest encryption let
`secure/` become genuinely confidential, and is **per-namespace** (only `secure/` encrypted) a lighter
option than whole-store encryption?

**Two different things are being conflated, and separating them is the key insight:**

- **Whole-store storage-layer encryption (Option S / B)** encrypts the WAL + snapshot, so **`secure/` values
  are confidential *at rest* along with everything else** — on a stolen disk/backup, `secure/` is ciphertext.
  But on a **live node** the state machine still holds `secure/` in plaintext in RAM, and at the **edge** it
  is in plaintext RAM. So Option B makes `secure/` *at-rest-confidential* but **not** confidential against
  the live-process threat. This is the honest, achievable win.
- **Per-namespace / `secure/`-only confidentiality against a live node** requires **value-level encryption**:
  encrypt the `secure/` *value* before it enters the Raft log, so it is ciphertext in the log, in the
  snapshot, on every replica's RAM, **and at the edge** (the edge would serve ciphertext; clients decrypt).
  That is the **end-to-end model (Option E2E / C)** scoped to one namespace — and it **inherits the full
  cluster-wide + edge key-distribution problem** (§3, §4) for those keys, **plus** the loss of any
  server-side operation on `secure/` values. It is *not* a lighter option; it is a **narrower-blast-radius
  version of the heaviest option.**

**So "per-namespace" is lighter only in *coverage*, not in *mechanism*.** If the goal is at-rest protection
of `secure/`, whole-store Option B already covers it for free and is strictly simpler — there is no reason to
scope it. If the goal is genuine confidentiality of `secure/` against a live node and the edge, you need
value-level/E2E for those keys, which is the hard option and only justified **if Configd is repositioned to
hold secrets** — which the current, documented posture says it must not (*"do not store secret material …
use a dedicated secret manager"*, known-limitations §1; `StrongReadKeyClass.java:21`).

**Recommendation preview:** keep `secure/` as a freshness class; let Option B give it at-rest confidentiality
as a side effect; do **not** build per-namespace value-level encryption unless and until a product decision
makes Configd a secrets-holder (and even then, "use a secret manager" remains the better answer for true
secrets). Detail and ranking in [`recommendation.md`](recommendation.md).

---

## 8. Summary of Configd-specific findings

1. **The at-rest surface is just the WAL + snapshot** (+ durable Raft state + audit log) — no separate store
   file; config values live only in #1/#2. Small, clean, maps onto existing envelope points.
2. **The existing ADR-0042 envelope already sits at the node-local storage seam** (verified: wire carries
   plaintext over mTLS; envelope is per-node disk-local). Encryption is a **new `algId`** at that seam — **no
   wire change, no cluster-wide key distribution.**
3. **The consensus crux resolves to storage-layer (node-local), not end-to-end** — mTLS already covers
   transit, and E2E would import the Kubernetes *decryptors⊇encryptors* key-distribution + two-phase-rotation
   burden (made worse by sharding) for a redundant benefit.
4. **The edge fork mostly dissolves** — the edge is memoryless, so it has no at-rest surface; the residual is
   a *runtime* heap-dump exposure (out of RR-098 scope). The fork only re-opens if a future edge gains disk
   persistence — record that as a constraint now.
5. **Key availability is satisfiable without a new failure mode** — derive `K_enc` from the
   already-loaded signing key (zero new dependency), or auto-unseal a per-node root key via KMS at boot
   (managed, but a boot-time dependency to engineer around). **Never KMS-per-write; never interactive
   Shamir.**
6. **Performance is not a blocker** — the write path is `fsync`/election-bound, AES-NI makes the cipher
   near-free (<0.5%–single-digit-% CPU); confirm with an encryption arm on the EC2 knee run.
7. **`secure/` per-namespace encryption is not a lighter option** — whole-store B already gives `secure/`
   at-rest confidentiality; genuine live-node/edge confidentiality needs value-level/E2E and only matters if
   Configd becomes a secrets-holder, which current posture rejects.
