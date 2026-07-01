# Encryption at Rest — Options Ranked + Recommendation

> **Session:** Encryption-at-rest research (RR-098), 2026-06-28. **Status:** research only — no code.
> Reads on from [`prior-art.md`](prior-art.md) and [`configd-analysis.md`](configd-analysis.md).
> This document is **for the operator**, who makes the call. It presents four concrete options ranked, the
> threat each defends, its key-management and performance profile, its build effort/risk, and a clear v1/v2
> recommendation. **Nothing here is built or decided by this session — it is an evidence-based recommendation.**

---

## 0. The frame before the options

Three facts shape every option:

1. **D-2 is already decided:** v1 ships **without** at-rest encryption (operator decision, 2026-06-27;
   register §11-B D-2). `secure/` is documented as freshness-not-confidentiality; "do not store secrets, use
   a secret manager" is in known-limitations / README / Integration-Guide / consistency-contract. **This
   recommendation does not reopen v1; it scopes the v2 work** that D-2 deferred to.
2. **The realistic threat is media theft**, and it is the one Configd's own ADR-0042 already names: adversary
   **A2/T3** with write (and, for confidentiality, read) access to backup/snapshot/WAL storage — the backup
   bucket, shared NFS, the `restore-snapshot.sh` path, a decommissioned disk. ADR-0042 gave that adversary
   *tamper-evidence*; encryption-at-rest gives *confidentiality* against the same adversary. **Full live-host
   compromise (keys in RAM) is out of scope** — every system studied draws this line ([`prior-art.md`](prior-art.md) §6).
3. **Configd already has the seam.** Its at-rest integrity envelope is a per-node, below-replication,
   storage-layer transform; the replication wire carries plaintext over mTLS (verified, [`configd-analysis.md`](configd-analysis.md) §2). So the *data-plane* mechanism for the lightest real encryption is a **new
   `algId` at an existing, reviewed composition point** — not new architecture.

**Key sub-decision that separates the options:** *data-plane placement* (storage-layer/node-local vs
end-to-end/value-level) and *key management* (derive-from-signing-key vs operator-static-key vs KMS
auto-unseal) are **independent axes**. Options B and D share the same data-plane (node-local storage-layer)
and differ only in key-management maturity. Option C is the one that changes the data-plane (value-level),
and it is the only one that needs cluster-wide/edge key distribution.

---

## 1. The four options

### Option A — None (status quo): integrity only, "don't store secrets"

The current v1 posture. Config values (incl. `secure/`) are plaintext on disk, protected by the ADR-0042
integrity envelope (tamper-evidence) and mTLS in transit; the documented contract is "do not store secret
material — use a secret manager."

- **Defends (confidentiality):** nothing. (Integrity: tamper/forgery of WAL/snapshot/raft-state, via HMAC.)
- **Key management:** the existing signing key only (no encryption key).
- **Performance:** baseline (no cipher).
- **Build effort/risk:** zero — already shipped.
- **Honest read:** correct **iff** the deployment genuinely holds no secrets and faces no at-rest compliance
  bar. The risk is **silent misuse** — an operator who stores a token under `secure/` believing the name
  implies secrecy. The loud docs are the only mitigation; they are necessary but not a technical control.

### Option B — Node-local storage-layer encryption (the recommended v2 first step)

Encrypt the WAL, snapshot blob, and durable Raft state at each node's local disk path — at the **same seam
as the ADR-0042 envelope** — with a per-node data key. Replication stays plaintext-over-mTLS; **no key
leaves a node; no cluster-wide key distribution.** This is the CockroachDB model
([`prior-art.md`](prior-art.md) §4), realized at a seam Configd already has.

- **Construction:** new envelope `algId` — **AES-256-GCM** (AEAD, single pass; GCM tag replaces the HMAC,
  CRC32C stays) is the lead choice; AES-256-CTR-then-HMAC (reuses `K_integrity`) is the alternative. Nonce =
  segment-id ‖ offset (WAL) / per-snapshot random; **bind segment-id+offset+term into the AAD** to close the
  tail-truncation residual ADR-0042 left open. Fail-closed posture, per-artifact MAGIC, downgrade refusal,
  and torn-vs-tamper rule all inherited from ADR-0042.
- **Key management — two maturity rungs (this is the only thing that varies between B and D):**
  - **B-minimal (HKDF-from-signing-key):** `K_enc = HKDF(IKM=signing-key, info="configd/raft-at-rest-encryption/v2", salt=keyId)` — a third derived key beside `K_integrity`/`K_audit` (`ConfigdServer.java:1115,1204`).
    **Available the instant the signing key is read; no new file, no external call, no unseal step.** The
    lightest possible lift, consistent with ADR-0042's own precedent.
  - **B-operator-key:** an operator-supplied store key (CockroachDB style) wraps an auto-generated per-node
    DEK registry — off-signing-key custody, manual rotation, a key file to manage.
- **Defends:** stolen disk, leaked backup/snapshot, decommissioned volume, a read of any replica's data
  directory — confidentiality for **all** config at rest, including `secure/`. **Does not defend** a live
  node (HAMT in RAM) or the edge (RAM).
- **Performance:** estimated **<0.5%–low-single-digit % CPU**, **no measurable throughput regression** (write
  path is fsync/election-bound; AES-NI cipher is sub-µs per small entry) — confirm on the EC2 knee run
  ([`configd-analysis.md`](configd-analysis.md) §6).
- **Build effort/risk:** **moderate, low-risk.** New cipher path + `K_enc` derivation + per-segment nonce
  discipline + negative tests (mirror ADR-0042's: tampered/forged/downgraded artifacts refused; **add**
  decrypt-failure-fails-closed, nonce-uniqueness, key-mismatch-refused). Reuses HKDF/HMAC/envelope infra and
  the gate-7 negative-test pattern. **No wire change, no key distribution, no new boot dependency (B-minimal).**

### Option C — Per-namespace / `secure/`-only value-level (end-to-end) confidentiality

Encrypt the *value* of `secure/` keys (or a configured set) **before** it enters the Raft log, so it is
ciphertext in the log, snapshot, every replica's RAM, **and at the edge** (edge serves ciphertext; clients
decrypt). Makes `secure/` genuinely confidential **including against a live node and the edge** — the one
thing B cannot do.

- **Key management:** **cluster-wide + edge** — every replica that applies/serves the key, every future
  leader, every restoring node, **and every client/edge that decrypts** needs the key. Inherits the
  Kubernetes *decryptors⊇encryptors* invariant and two-phase rotation ([`prior-art.md`](prior-art.md) §2.4),
  **made worse by sharding** (keys chase shards across owners) and by the edge fan-out surface.
- **Defends:** confidentiality of the chosen values **everywhere they live**, incl. live-node RAM and edge
  heap dumps — the strongest. **Cost:** the engine **cannot operate on encrypted values** (no content-based
  conditions on `secure/`); a real key-distribution + rotation protocol layered on Raft + the edge plane.
- **Performance:** negligible CPU per value, but the operational/availability cost of cluster-wide key
  management is the real price.
- **Build effort/risk:** **high.** It is the heaviest option scoped narrowly — a genuine distributed
  key-management subsystem, edge decryption/serve-ciphertext changes, and a rotation protocol. **Only
  justified if Configd is repositioned to hold secrets** — and even then, *"use a secret manager"* remains
  the better answer for true secret material.

### Option D — Full envelope + KMS auto-unseal + keyring rotation (B done with managed keys)

**Same node-local storage-layer data-plane as B**, but with the mature key-management stack from Vault/KMS
prior art: a per-node **root key wrapped by a KMS/HSM CMK**, **auto-unsealed once at boot**, a **term-versioned
keyring** for online rotation (new term encrypts new writes; old terms decrypt old segments; lazy
re-encryption via compaction), and **managed master-key rotation** (rewrap, no bulk re-encrypt).

- **Key management:** off-host KEK in KMS/HSM (true custody separation); one KMS `Decrypt` at boot
  (auto-unseal); cached DEKs keep KMS off the per-write path; multi-Region CMK + backoff for availability.
  **New dependency:** boot-time KMS reachability — a node needs it to rejoin (engineer around with cache +
  non-blocking replay; never interactive Shamir on a config store) ([`configd-analysis.md`](configd-analysis.md) §5).
- **Defends:** same at-rest threat as B, **plus** off-host key custody (a stolen disk *and* the host's local
  key file is not enough — the KEK is in the HSM) and **compliance-grade managed rotation** (PCI/FIPS-style
  key lifecycle).
- **Performance:** same as B on the write path (KMS is off it); adds a boot-time KMS round-trip.
- **Build effort/risk:** **high.** B's data-plane **plus** a KMS integration, an unseal state machine, a
  keyring/rotation subsystem, and the availability engineering to keep boot-KMS from threatening quorum.
  Best built **on top of** a shipped B, not instead of it.

---

## 2. Comparison at a glance

### Threat-defense matrix (✅ defends · ⚠️ partial · ❌ no)

| Threat | A (none) | B (node-local) | C (value-level `secure/`) | D (B + KMS/keyring) |
|---|---|---|---|---|
| Stolen disk / data directory | ❌ | ✅ | ✅ (those keys) / ⚠️ (rest) | ✅ |
| Leaked backup / snapshot | ❌ | ✅ | ✅ (those keys) / ⚠️ (rest) | ✅ |
| Decommissioned volume | ❌ | ✅ | ⚠️ | ✅ |
| Disk theft **+ local key file** | ❌ | ❌ (B-minimal: key derives from co-located signing key) | ❌ | ✅ (KEK off-host in HSM) |
| Live node (keys/HAMT in RAM) | ❌ | ❌ | ⚠️ (those values stay encrypted until used) | ❌ |
| Edge heap dump of `secure/` | ❌ | ❌ | ✅ | ❌ |
| Tamper / forgery (already shipped) | ✅ (HMAC) | ✅ | ✅ | ✅ |

### Cost / fit

| Dimension | A | B | C | D |
|---|---|---|---|---|
| Data-plane | none | node-local storage-layer | value-level / end-to-end | node-local storage-layer |
| Key distribution | n/a | **none** | **cluster-wide + edge** | none (per-node root key) |
| New boot dependency | none | **none** (B-minimal) / key file (B-operator) | key distribution | **KMS reachability** |
| Wire-format change | none | **none** | entries become ciphertext | none |
| Server-side ops on values | full | full | **lost** for encrypted ns | full |
| Write-path perf cost | 0 | ~0 (fsync-bound) | ~0 + key-mgmt ops cost | ~0 + boot KMS call |
| Managed key rotation | n/a | manual / signing-key | manual + 2-phase | **yes (keyring)** |
| Build effort | shipped | **moderate, low-risk** | high | high |
| Reuses ADR-0042 seam | — | **yes** | partly | yes |

---

## 3. Recommendation

**Ranked for Configd's actual position (a config store told not to hold secrets, facing media-theft and
compliance as the realistic at-rest concerns):**

> **1st — B (node-local storage-layer), as the v2 first step, starting at B-minimal key management.**
> **2nd — D, as the maturity target for B's key management when off-host custody or compliance-grade rotation
> is required.**
> **3rd — A, which correctly remains the v1 posture (already decided).**
> **4th — C, deferred indefinitely unless a product decision makes Configd a secrets-holder.**

### The v1/v2 call

- **v1 stays A** (confirmed — D-2 already decided this). Keep the loud "do not store secrets" docs; they are
  the only control and must stay prominent. **No change requested.**
- **v2 first step = B-minimal.** When RR-098 is pulled into a release, build **node-local storage-layer
  encryption** at the existing ADR-0042 seam, with **AES-256-GCM** and **`K_enc` derived from the signing key
  via HKDF**. Rationale: it defends the **realistic at-rest threat** (the same A2/T3 adversary ADR-0042 names),
  **composes with reviewed infrastructure** (envelope, HKDF, fail-closed posture, gate-7 negative tests),
  introduces **no key distribution, no wire change, and no new boot dependency**, and costs **~nothing on the
  write path**. It is the lightest real encryption and the cleanest fit to Configd's architecture.
- **Graduate B's key management to D** (KMS auto-unseal + term-versioned keyring + managed rotation) **when a
  named requirement appears** — off-host key custody (so a stolen disk + local key file is insufficient), a
  compliance mandate (PCI/FIPS-style key lifecycle), or an operator demand for online key rotation without
  re-deriving the signing key. D is **B's data-plane with a mature key stack**, so it is purely additive on
  top of a shipped B — build B first.
- **Do not build C** unless Configd is explicitly repositioned to hold secret material against a live-node /
  edge threat. If that day comes, prefer integrating a real secret manager over building a bespoke
  cluster-wide + edge value-encryption + rotation subsystem; C is the heaviest option and only its narrow
  blast-radius distinguishes it from a general E2E build.

### The two design decisions the operator/owner must make when B is scheduled

1. **Cipher construction:** AES-256-GCM (AEAD, single pass — recommended) vs AES-256-CTR-then-HMAC (reuses
   `K_integrity`). Flag both for the **specialist crypto review** that ADR-0042 already established as the
   gate for at-rest crypto.
2. **Key-management rung at first ship:** B-minimal (HKDF-from-signing-key — recommended start; key shares
   fate with the signing key, no managed rotation) vs B-operator-key vs jump straight to D (KMS auto-unseal —
   only if off-host custody is a launch requirement, accepting the boot-time KMS dependency).

### Non-negotiables for whichever option is built (from the prior art)

- **Never roll custom crypto** — AES-GCM / AES-CTR-then-HMAC / HKDF / KMS envelope only (charter §5.5).
- **Self-describing ciphertext** — tag each unit with a `keyId` so any node/leader/restoring-node selects the
  right key with zero coordination, and rotation runs forward while old units stay readable.
- **Bind the location into the AAD** (segment-id ‖ offset ‖ term) — close the whole-log tail-truncation
  residual ADR-0042 left open.
- **KMS off the per-write path; auto-unseal, never interactive Shamir** — a config store must not couple
  quorum write-availability (or a node's ability to rejoin) to an external service.
- **Don't co-locate the key with the data** — the same rule ADR-0042's D-1 signing-key check already enforces
  (`enforceSigningKeyNotColocated`, `ConfigdServer.java:1156`). B-minimal inherits this via the signing key;
  D satisfies it by construction (KEK in HSM).
- **Snapshots and backups are long-lived ciphertext** — they are in scope, and old keys must be retained
  until a re-encryption sweep has rewritten every WAL entry, snapshot, **and backup**.
- **Confirm the perf estimate** with an encryption-on/off arm on the EC2 knee + gate-5 allocation check.

---

## 4. One-paragraph answer to the charter's question

The right at-rest encryption design for Configd is **node-local, storage-layer encryption of the WAL +
snapshot + durable Raft state**, added as a new AES-256-GCM `algId` at the existing ADR-0042 envelope seam,
keyed by an HKDF derivation from the cluster signing key — graduating to a KMS-auto-unsealed, term-versioned
keyring when off-host custody or managed rotation is required. It costs essentially nothing on the
fsync/election-bound write path, needs no wire change and no cluster-wide key distribution (because Configd's
replication already ships plaintext over mTLS and the envelope is already per-node), and defends the
realistic media-theft threat — the same adversary ADR-0042 already addresses for integrity. **v1 correctly
stays as-is (A, already decided); B is the recommended v2 first step; D is its maturity target; C
(value-level `secure/` confidentiality) is unnecessary unless Configd becomes a secrets-holder — which its
documented posture rejects, in favor of a dedicated secret manager.**
