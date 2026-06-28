# Encryption at Rest — Prior Art (mechanism extraction)

> **Session:** Encryption-at-rest research (RR-098), 2026-06-28. **Status:** research only — no code.
> **Scope of this document:** how mature systems actually implement encryption at rest, extracted at
> the *mechanism* level (cipher, key hierarchy, unseal, rotation, threat model), not summarized. The
> Configd-specific mapping is in [`configd-analysis.md`](configd-analysis.md); the ranked options and
> the v1/v2 call are in [`recommendation.md`](recommendation.md).
>
> Every claim here is cited inline to a primary source. The four underlying deep-dives (Vault, etcd/K8s,
> cloud-KMS envelope, database TDE) were each researched against primary docs and the load-bearing quotes
> verified verbatim; this file distills them and keeps the citations.

---

## 0. The two axes every design chooses on

Across all the systems studied, two architectural choices recur. Naming them up front makes the rest of
the survey legible, because **every system is a point on these two axes**:

1. **Where the crypto sits relative to the store.**
   - **Below the store / storage-layer ("TDE-style"):** encrypt whole pages / files / log-records on the
     I/O path, transparently, below the query or consensus engine. The engine sees plaintext; only the
     disk holds ciphertext. (SQL Server, Oracle, MySQL InnoDB, **CockroachDB**, Vault's barrier.)
   - **Above the store / value-level ("application-style"):** the application encrypts specific values
     *before* handing them to the store; the store only ever sees ciphertext and **cannot compute on it**.
     (Kubernetes apiserver→etcd, SQL Server Always Encrypted, pgcrypto.)

2. **Where the master key lives, and the key hierarchy below it.** Universally a **two-tier envelope**: a
   long-lived *master key / KEK* (held outside the data — operator file, keystore, HSM, or cloud KMS)
   *wraps* short-lived *data-encryption keys (DEKs)* that actually encrypt the bytes. This indirection is
   what makes **master-key rotation cheap** (rewrap the small DEKs; never re-encrypt the bulk data) and is
   the single most-copied idea in the field.

The threat model is the third, non-negotiable framing: **at-rest encryption defends the bytes on stolen
media (disk / backup / snapshot / decommissioned hardware). It does not defend a live process that has
already turned those bytes into keys in memory, nor an attacker who also steals the keystore.** Every
vendor states this explicitly; §6 collects the quotes.

---

## 1. HashiCorp Vault — the barrier + envelope + unseal stack

Vault is the operator's named reference and the most complete worked example, so it is studied hardest.
Primary sources: Vault [architecture](https://developer.hashicorp.com/vault/docs/internals/architecture),
[security model](https://developer.hashicorp.com/vault/docs/internals/security),
[seal/unseal](https://developer.hashicorp.com/vault/docs/concepts/seal),
[rotation internals](https://developer.hashicorp.com/vault/docs/internals/rotation),
[`operator rotate`](https://developer.hashicorp.com/vault/docs/commands/operator/rotate),
[`operator rekey`](https://developer.hashicorp.com/vault/docs/commands/operator/rekey), and the barrier
source [`vault/barrier_aes_gcm.go`](https://github.com/hashicorp/vault/blob/main/vault/barrier_aes_gcm.go).

### 1.1 The barrier (storage-layer encryption)

Vault's encryption layer is the **barrier**: a transparent encrypt-on-write / decrypt-on-read proxy that
sits **between Vault Core and the physical storage backend**. *"Since the storage backend resides outside
the barrier, it's considered untrusted so Vault will encrypt the data before it sends them to the storage
backend"* (architecture). The backend (Consul, Raft, S3, …) is by design a **dumb, untrusted blob store
of opaque ciphertext** — it never sees plaintext, structure, or keys.

Cipher: **AES-256-GCM with 96-bit random nonces** (security model) — AEAD, so every object gets
confidentiality *and* an authentication tag verified on read. The on-disk wire format prepends a key
**term** and a **version** byte so each blob is self-describing about which key produced it
([barrier source](https://github.com/hashicorp/vault/blob/main/vault/barrier_aes_gcm.go)):

```
+-----------+----------+-----------+----------------------------+
| Term      | Version  | Nonce     | Ciphertext + GCM auth tag  |
| 4 bytes   | 1 byte   | 12 bytes  | (variable)                 |
+-----------+----------+-----------+----------------------------+
```

Decryption reads the 4-byte term *first* to select the keyring version, then verifies+decrypts. In format
version 2 Vault additionally mixes the **storage path in as AAD**, so an attacker who controls the backend
cannot relocate/swap/splice a valid ciphertext to a different path — GCM authentication fails. **Structure,
not just values, is cryptographically anchored.** (This "bind the location into the AAD" idea is the one
Configd should adapt to *log offset / segment id*; see [`configd-analysis.md`](configd-analysis.md) §3.)

### 1.2 The envelope / key hierarchy

Three tiers (architecture, seal):

| Tier | Key | Encrypts | Stored where |
|---|---|---|---|
| 1 | **Encryption key** (in a multi-version **keyring**) | all Vault data | ciphertext → storage backend |
| 2 | **Root key** (historically "master key") | the keyring | encrypted → storage backend |
| 3 | **Unseal key** (Shamir) **or** external **seal** (KMS/HSM) | the root key | encrypted root key → backend; **unseal key never persisted in plaintext** |

The chain inverts on unseal: obtain the unseal-or-seal key → decrypt root key → decrypt keyring → decrypt
data. **The root key is never written to disk in plaintext** — only ever wrapped. The **keyring** holds
*versioned* encryption keys; the latest term encrypts new writes, older terms are retained so existing
ciphertext (which embeds its term) stays readable. This versioned keyring is what makes rotation O(1).

### 1.3 Unsealing (key availability at boot)

At startup a Vault node can reach storage but holds no plaintext root key — it is **sealed** and *every*
data operation is blocked until unsealed. Two ways to unseal:

- **Shamir secret sharing (default):** the unseal key is split into *n* shares with a *k*-of-*n* threshold;
  operators submit shares one at a time, across different clients, until *k* is reached; Vault reconstructs
  the unseal key **in memory**, decrypts the root key, then discards it. Nothing sensitive is written to
  disk in plaintext.
- **Auto-unseal (cloud KMS / HSM / Transit):** *"At startup, Vault connects to the trusted device or
  service and prompts it to decrypt the root key from storage"* (seal). The wrapping key stays **inside the
  KMS/HSM** and never enters Vault's address space. Shamir unseal keys are replaced by **recovery keys**
  (which cannot decrypt the root key, only authorize privileged ops).

The seal state machine maps directly onto node lifecycle: **sealed** = storage reachable, data
undecryptable, only status/unseal permitted; **unsealed** = keys in RAM, full service; reseal/restart/
storage-failure → back to sealed and the in-memory keys are gone. **Hard dependency to note:** if the seal
mechanism/keys are permanently lost, the cluster is unrecoverable even from backups.

### 1.4 Rotation — two distinct operations

| | `operator rotate` | `operator rekey` |
|---|---|---|
| Tier | encryption (data) key in the keyring | root key + unseal/recovery shares |
| Re-encrypts stored data? | **No** — term-versioned keyring; new key encrypts new writes, old terms decrypt old data | **No** — only re-wraps the keyring under the new root key |
| Operator quorum? | No (key invisible to operators) | Yes — threshold of current unseal keys |
| Automatic? | Yes — auto-rotates before the GCM 2³² op safety limit (NIST SP 800-38D) | No (operator-driven) |

The lesson: **rotation is a metadata operation, not a data-movement operation.** Old key versions must be
retained until a (lazy, optional) re-encryption pass rewrites old ciphertext — you never *have* to.

### 1.5 Threat model

**Defends:** stolen disk / leaked backup / compromised-or-untrusted storage backend (the backend only ever
holds AES-256-GCM blobs); tampering (GCM tag verified every read; v2 path-AAD defeats relocation).
**Does NOT defend:** *"If an attacker is able to inspect the memory state of a running Vault instance, then
the confidentiality of data may be compromised"* (security model) — **unsealed = keys in RAM**; an attacker
holding a threshold of unseal/recovery shares; a malicious operator/plugin/host. One line: **Vault-at-rest
protects the bytes on the disk, not a running process that already turned them into keys.**

### 1.6 Seal wrap (Enterprise)

An optional *outer* layer applied by the seal (HSM/KMS) on top of the barrier for the crown-jewel CSPs
(root key, keyring, recovery key, key shares) — always on for those, opt-in per-entry otherwise. Relevant
because it converts the KMS from a *boot-time* dependency into a *runtime* one: *"Vault Enterprise enables
seal wrapping by default, which means the KMS service must be available at runtime and not just during the
unseal process"* ([AWS KMS seal](https://developer.hashicorp.com/vault/docs/configuration/seal/awskms)).
A cautionary data-point for any KMS-coupled design.

---

## 2. etcd + Kubernetes — encrypt *above* the replicated store

This is the canonical **value-level / above-the-store** design, and — because the Kubernetes control plane
is itself a multi-replica system writing to one replicated backend — it is the richest source of lessons
about **key distribution across replicas**. Primary sources: K8s
[*Encrypting Confidential Data at Rest*](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/),
[*Using a KMS provider*](https://kubernetes.io/docs/tasks/administer-cluster/kms-provider/),
[KMS v2 beta blog](https://kubernetes.io/blog/2023/05/16/kms-v2-moves-to-beta/),
[KEP-3299](https://github.com/kubernetes/enhancements/tree/master/keps/sig-auth/3299-kms-v2-improvements),
[etcd security guide](https://etcd.io/docs/v3.6/op-guide/security/).

### 2.1 etcd stores ciphertext; the apiserver encrypts

etcd has **no native at-rest value encryption** — *"No. etcd doesn't encrypt key/value data stored on disk
drives"* (etcd security guide); its crypto is transport-only (TLS). Its own recommendations are *"let
client applications encrypt and decrypt the data"* or *"use a feature of underlying storage systems … like
dm-crypt."* So Kubernetes encrypts **one layer up, in the kube-apiserver, before bytes reach etcd**. With
encryption on, a Secret read straight from etcd shows a **plaintext key path** and an **opaque, prefixed
ciphertext value**:

```
/registry/secrets/default/secret1   k8s:enc:aescbc:v1:key1:<binary ciphertext...>
```

The path (`/registry/secrets/default/secret1`) stays plaintext; only the value is encrypted. **Encryption
is value-only and selective: you choose which resource types to encrypt** (typically `secrets`).

### 2.2 The ordered provider list — "first encrypts, all decrypt"

`EncryptionConfiguration` is an **ordered list of providers** per resource. Providers: `identity` (none),
`aescbc` (weak — CBC padding-oracle), `aesgcm` (must rotate every 200k writes), `secretbox`
(XSalsa20-Poly1305), and `kms` (envelope). The load-bearing semantics:

> "The first provider in the list is used to encrypt resources written into the storage. When reading
> resources from storage, each provider that matches the stored data attempts in order to decrypt the data."

**One writer, many readers.** This asymmetry is *exactly* what lets you stage a key as decrypt-only first,
then promote it to writer. The three local providers keep *"Key material accessible from control plane
host"* — the key bytes live in a file on the same disk as etcd; only `kms` moves the root key off-host.

### 2.3 KMS v2 — envelope encryption with a cached seed (the modern model)

The `kms` provider does **envelope encryption**: a local **DEK** AES-GCM-encrypts the value; a remote
**KEK** (in cloud KMS/HSM/Vault) wraps the DEK; the **wrapped DEK is stored alongside the ciphertext in
etcd**. *"an attacker who intends to get unauthorised access to the plaintext values would need to
compromise etcd **and** the third-party KMS provider."*

KMS **v2** (stable in 1.29) fixed v1's "one KMS call per write": a single 32-byte **seed** is wrapped by
the KMS **once per apiserver / per KEK-rotation**, then **reused** — each write derives a single-use DEK via
**HKDF-SHA256** from the seed + 32 bytes of random `info`, and AES-GCM-encrypts with a 12-byte nonce. Cold
apiservers make ~**one `Decrypt` per distinct seed**, not one per object. A pollable `Status` RPC returns
an authoritative `key_id`; when it changes, the apiserver mints a new seed under the new KEK — **KEK
rotation needs no apiserver restart**. (The gRPC contract is `Encrypt`/`Decrypt`/`Status`, operating on the
*DEK/seed*, never the Secret value.)

### 2.4 The rotation protocol — and the rule that matters for consensus

Local-key rotation is a strict **two-phase, add-before-use / migrate-then-remove** sequence
([rotating a decryption key](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/#rotating-a-decryption-key)):
(1) add the new key as a **decrypt-only** entry on **all** apiservers; (2) **restart all** so every server
can decrypt it; (3) only then make it the **write** key; (4) restart all; (5) run the storage-rewrite
`kubectl get secrets -A -o json | kubectl replace -f -` to re-encrypt existing rows; (6) only *after* that,
drop the old key.

The invariant the sequence enforces — and the single most important lesson for a replicated store:

> **decryptors ⊇ encryptors, always.** A key must be a *decrypt* capability on **every** replica before it
> becomes a *write* capability on **any** replica; an old key may be retired only **after** every record
> (and backup) encrypted under it has been rewritten.

This is structurally **the same shape as Raft joint-consensus / two-phase membership change**: propagate the
new capability into the cluster's common knowledge and confirm convergence *before* anyone depends on it;
remove the old one only *after* nothing references it. A naïve "swap the key and restart" is the encryption
analogue of a single-phase membership change — a window where the cluster **cannot decrypt its own data**.

### 2.5 Threat model

**Defends:** raw etcd access / etcd backup / stolen etcd disk (covers peer replication and the WAL, since
those carry already-encrypted bytes); with KMS, requires *two* independent compromises. **Does NOT defend:**
a compromised apiserver (holds DEKs, calls KMS); **host compromise with local providers** (key file
co-located with etcd on the same disk — *"fails to protect against a host compromise … a skilled attacker
can … extract the encryption keys"*); and **metadata/keyspace shape** (object names, namespaces, sizes leak
via plaintext key paths). Value-only encryption hides values, not structure.

---

## 3. Cloud-KMS envelope encryption — the "GenerateDataKey" pattern + the availability tension

This is the key-management substrate the others plug into. Primary sources: AWS KMS
[GenerateDataKey](https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html),
[data keys / envelope](https://docs.aws.amazon.com/kms/latest/developerguide/data-keys.html),
[encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html),
[rotation](https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html),
[quotas](https://docs.aws.amazon.com/kms/latest/developerguide/requests-per-second.html),
[multi-Region keys](https://docs.aws.amazon.com/kms/latest/developerguide/multi-region-keys-overview.html),
the [AWS Encryption SDK caching docs](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/data-key-caching.html);
GCP [envelope encryption](https://docs.cloud.google.com/kms/docs/envelope-encryption); Vault
[Transit](https://developer.hashicorp.com/vault/api-docs/secret/transit).

### 3.1 The core pattern

**The master key (CMK/KEK) never leaves the KMS/HSM boundary in plaintext.** The encrypt flow (AWS
canonical):

1. Call **`GenerateDataKey`** → KMS returns **both** a plaintext DEK *and* that DEK encrypted under the CMK
   (`Plaintext` + `CiphertextBlob`).
2. Encrypt your data locally with the plaintext DEK (AES-256-GCM), then **erase the plaintext DEK from
   memory**.
3. **Store the wrapped DEK next to the ciphertext.** To read: send the wrapped DEK to **`Decrypt`** → get
   the plaintext DEK → decrypt locally.

KMS does no bulk crypto and stores nothing. GCP has **no `GenerateDataKey`** — you generate the DEK locally
and call KMS only to *wrap* it (Google recommends the Tink library). Vault Transit defaults to the inverse
— **encrypt-for-you**, key never leaves Vault — but `transit/datakey` replicates the GenerateDataKey flow.
**Encryption context / AAD** binds ciphertext to a logical context (non-secret, exact-match-to-decrypt; a
rename/move changes it and decryption fails).

### 3.2 Rotation is transparent; old backing keys are retained

CMK rotation *"changes only the current key material … the KMS key is the same logical resource"*; old
backing keys are retained so old ciphertext decrypts with **no re-encryption and no code change** (AWS:
default 365 days, 90–2560 configurable; GCP: new primary version, old versions still decrypt; Vault:
`/rotate` + `min_decryption_version` + `/rewrap`). Same pattern as Vault's keyring (§1.4) and every TDE
master key (§4).

### 3.3 The availability dependency — the central tension for a consensus store

Envelope encryption makes **KMS a synchronous dependency of the decrypt path**: at boot and on every cache
miss you must call KMS to unwrap the DEK. If KMS is unreachable (or the CMK is disabled/deleted/denied) the
data is inaccessible — AWS's own EBS example: *"the attachment fails, because Amazon EBS cannot use the KMS
key to decrypt the volume's encrypted data key."* KMS is **regional** and its per-account RPS quota is a
**global ceiling shared with every other workload** — a noisy neighbour can throttle you.

For a consensus store this is acute: **if every WAL append calls KMS, a KMS blip stalls the commit pipeline
on every node at once (a correlated dependency a quorum cannot vote its way out of); and a node restarting
after a crash cannot replay its encrypted log to rejoin, shrinking the quorum precisely during an
incident.** The documented mitigations:

1. **DEK caching with a bounded TTL** (the primary lever). Acquire a DEK once per *segment/epoch*, cache the
   plaintext DEK in memory, encrypt many records locally; KMS is touched *"only to create the initial data
   key and when the cache misses."* AWS frames caching as a deliberate **security-vs-availability dial**: a
   longer TTL = more outage tolerance but more DEK reuse (larger blast radius). Bound reuse with max-age /
   max-messages / max-bytes thresholds.
2. **Auto-unseal at boot, not KMS-per-op** (Vault's model, §1.3). Protect one long-lived root key with the
   KMS CMK; make **one** KMS `Decrypt` at startup, then derive per-segment DEKs locally. KMS is on the rare
   boot/rotation path, never the per-write path; an already-up node survives a KMS outage entirely.
3. **Multi-Region keys + exponential backoff** to remove the single-region KMS endpoint as a correlated SPOF
   for boot/recovery.

**Net rule:** envelope encryption is compatible with a write-available consensus store **iff the KMS
interaction is amortized off the per-write path** — one wrapped root key per node, one boot-time unwrap,
cached per-segment DEKs.

---

## 4. Database TDE — node-local storage-layer encryption, and the CockroachDB model

The directly-applicable prior art, because **CockroachDB is a distributed, Raft-replicated, range-sharded
store with a WAL and snapshots** — structurally the same shape as Configd. Primary sources: CockroachDB
[encryption reference](https://www.cockroachlabs.com/docs/stable/security-reference/encryption),
[operational guide](https://www.cockroachlabs.com/docs/stable/encryption),
[design RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md);
MySQL [InnoDB data-at-rest](https://dev.mysql.com/doc/refman/8.0/en/innodb-data-encryption.html);
SQL Server [TDE](https://learn.microsoft.com/en-us/sql/relational-databases/security/encryption/transparent-data-encryption);
Oracle [TDE](https://docs.oracle.com/en/database/oracle/oracle-database/19/asoag/introduction-to-transparent-data-encryption.html).

### 4.1 CockroachDB Encryption At Rest (study closest)

- **Cipher:** **AES in CTR mode**, 128/192/256-bit; a stream cipher that fits append-only WAL segments and
  immutable SSTables cleanly (encrypt on write, decrypt on read, no size change). IV = 96-bit per-file random
  nonce + 32-bit block counter.
- **Two-tier envelope:** an operator-supplied **store key (KEK)** wraps **data keys (DEK)** that CockroachDB
  auto-generates; the store key encrypts the *data-keys registry*, the data keys encrypt the on-disk files.

  | Tier | Key | Provided by | Encrypts | Stored where |
  |---|---|---|---|---|
  | KEK | Store key (128/192/256-bit) | **operator** (key file) | the data-keys registry | operator file, **off the data partition** |
  | DEK | Data keys (auto-generated) | CockroachDB | the actual files (SSTables, **WAL**) | `COCKROACHDB_DATA_KEYS` registry, encrypted by the store key |

- **Two registries:** an *encrypted* data-keys registry (the secret keys) **plus** a *plaintext* file
  registry (`COCKROACHDB_REGISTRY`) recording, **per file**, which `key_id` + nonce + counter encrypted it.
  Nonces/counters aren't secret in CTR mode, so the registry can be plaintext. **Every file is
  self-describing about its key** — old segments stay readable under old keys while new segments adopt the
  new key.
- **Rotation:** the store key rotates by operator (supply old + new; data keys decrypted under old,
  re-encrypted under new, a fresh data key generated). **Data keys auto-rotate, default one week**, old keys
  retained. Re-encryption is **lazy** — *"relies on normal storage engine churn"* / *"storage layer
  compactions … may take several days"*, and dormant data may never be re-encrypted without an explicit pass.
- **Scope:** covers SSTables **and the WAL**; explicitly leaves **logs, the file-registry metadata, and
  `BACKUP` output in plaintext** (a snapshot/backup path is *not* automatically covered by store-level EAR).

**The decisive insight — node-local, storage-layer, decoupled from replication.** Encryption is
*"performed in the storage layer and configured per store"* and *"only applies to the … data on the local
disk."* Keys never leave the node; **there is no cluster-wide key coordination** — each node generates and
rotates its own data keys. Inter-node traffic rides **TLS**, not the EAR keys. The correct reading (an
inference the docs support but don't state in one line): **replication is logical/plaintext-over-TLS, and
each replica re-encrypts what it persists with its own data keys.** Rebalancing, range splits, leadership
change, and node addition therefore require **no key movement** — a new replica streams the snapshot over
TLS and encrypts it locally. This is the pattern that **sidesteps the key-distribution problem** that the
Kubernetes/end-to-end model (§2.4) walks into.

### 4.2 The universal two-tier pattern (MySQL / SQL Server / Oracle)

Every other mature TDE confirms the envelope hierarchy and the cheap-rotation consequence:

| System | Master key (KEK) — held where | DEK(s) | Data cipher | Master-key rotation cost |
|---|---|---|---|---|
| **CockroachDB** | store key — operator file | per-store data keys | AES-CTR 128/192/256 | rewrap data-keys registry; lazy data re-encrypt via compaction |
| **MySQL InnoDB** | master key — **keyring** (file / KMS / HSM / Vault) | per-tablespace key (in `.ibd` page 0) | AES-CBC (data) | rewrap tablespace keys only — atomic, instance-wide |
| **SQL Server** | cert/asym key → DMK → SMK → DPAPI, or **EKM/HSM/Key Vault** | Database Encryption Key | AES, page-level | rotate the DEK protector; no data rewrite |
| **Oracle** | TDE master key (AES-256) — **wallet / HSM / OKV / OCI KMS** | tablespace/table key | AES, block-level | rewrap tablespace keys; data untouched |

Common consequences: **master-key rotation = rewrap the small DEKs** (fast, no bulk movement); **data
re-encryption is rare and usually lazy**; **old keys must be retained** to read old files/backups; **lose
the master key → lose the data**. The repeated security caveat: file-based keyrings (key on the same host as
the data) are *"not … a regulatory compliance solution"* — use a KMS/HSM/Vault keyring for production, and
**never co-locate the key with the data** (the same mistake Configd's D-1 signing-key check already guards
against).

### 4.3 Page/storage-level vs value-level — the functionality tradeoff

TDE encrypts whole pages/files **below the query engine**; the buffer pool holds decrypted pages, so
indexes, sorts, range scans, and joins all work unchanged — *"no application changes."* Crypto cost is paid
**only on physical I/O** (read = decrypt, write = encrypt); a page already cached is free. Measured overhead
is **2–4% typically, <0.5% with AES-NI**. Value-level encryption (SQL Server Always Encrypted, pgcrypto) is
the opposite: ciphertext at rest defends even a privileged live DB, but the engine **cannot sort, range, or
compute** on encrypted columns (only equality, and only with deterministic mode). This is the same fork as
§0 axis 1, with a concrete cost attached.

---

## 5. Synthesis — what the field teaches a consensus + WAL + snapshot store

Distilling all four studies into the lessons that drive the Configd design:

1. **Encrypt node-locally, below the consensus log — not end-to-end above it.** CockroachDB's model
   (per-store storage-layer encryption + plaintext-over-TLS replication + per-node keys) is the
   directly-applicable pattern. It delivers the realistic at-rest protection (media theft) **without a
   cluster-wide key-distribution protocol**. The Kubernetes/end-to-end model demonstrates exactly how painful
   the alternative is: the *decryptors ⊇ encryptors* invariant and a two-phase, add-before-use / migrate-then-
   remove rotation dance across every replica.

2. **Reuse the two-tier envelope.** A long-lived KEK (operator file → graduating to KMS/HSM) wraps
   short-lived per-node DEKs. This makes master-key rotation a cheap rewrap and keeps the KEK off the data
   partition. Never invent a new construction — AES-256-GCM (AEAD) or AES-CTR-then-HMAC are the established
   choices; every system above uses one of them.

3. **Make each on-disk unit self-describing about its key** (Vault's term prefix; Cockroach's per-file
   `key_id`; K8s' `k8s:enc:…:` prefix). Tag each WAL record / snapshot / state file with a `keyId` so any
   node, new leader, or restoring node selects the right key with zero coordination, and so rotation can run
   forward while old units stay readable.

4. **Bind the storage location into the AAD.** Vault binds the storage *path*; a WAL is ordered and
   append-only, so bind **segment id + record offset (+ term/epoch)** — cryptographically detecting
   truncation, reorder, replay, and splicing, the failure modes a consensus log must resist (and which
   ADR-0042's per-record HMAC explicitly does *not* cover for the whole-log set).

5. **Keep KMS off the per-write path** (§3.3). One wrapped root key per node, one boot-time auto-unseal,
   cached per-segment DEKs with a TTL chosen to exceed any KMS outage you must ride through. A consensus
   store must never couple quorum write-availability to an external regional service.

6. **Rotation is metadata, not data movement; old keys outlive their last record.** Advance the write key;
   let compaction/snapshotting lazily re-encrypt; retain every historical key until a sweep has rewritten
   every log entry, **snapshot, and backup**. Snapshots and backups are long-lived ciphertext and part of
   the key-retention calculus.

7. **State the threat boundary honestly.** At-rest encryption defends stolen disks / snapshots / backups /
   decommissioned hardware. It does **not** defend a live node serving decrypted data to authorized clients,
   a process whose keys are in RAM, or an attacker who also steals the keystore. For per-value confidentiality
   against a live node you need value-level encryption above the log — accepting the loss of server-side
   operations and a real key-distribution problem. Every vendor draws this exact line; Configd must too.

These seven points are applied to Configd's actual architecture — including the verification that its
existing ADR-0042 envelope already sits at the node-local seam — in [`configd-analysis.md`](configd-analysis.md),
and turned into ranked options with a v1/v2 call in [`recommendation.md`](recommendation.md).

---

## Source index

**Vault:** architecture · security model · seal/unseal · rotation internals · `operator rotate` ·
`operator rekey` · seal wrap · `barrier_aes_gcm.go` (all under `developer.hashicorp.com/vault` and
`github.com/hashicorp/vault`).
**etcd / Kubernetes:** kubernetes.io *Encrypting Confidential Data at Rest* · *Using a KMS provider* ·
*Decrypt … at Rest* · KMS v2 blogs (2022-09, 2023-05) · KEP-3299 · `kubernetes/kms` v2 & v1beta1 protos ·
etcd.io v3.6 security guide · etcd-io/etcd#7542.
**Cloud KMS:** AWS KMS GenerateDataKey / Decrypt / data-keys / encryption-context / rotation / quotas /
throttling / multi-Region keys / Encryption SDK caching+thresholds · GCP KMS resource-hierarchy /
key-states / rotation / encrypt-decrypt / envelope-encryption / Tink · Vault Transit docs+API / seal config.
**Database TDE:** CockroachDB encryption reference + operational guide + 2017 design RFC · MySQL InnoDB
data-at-rest + keyring + binlog-encryption · SQL Server TDE + Always Encrypted · Oracle 19c/26ai Advanced
Security TDE · PostgreSQL encryption-options + TDE wiki/proposal · Cybertec/EDB/Percona pg_tde.

*Full per-source URL lists with verbatim quotes are retained in the session research notes. Load-bearing
quotes (Vault barrier format, K8s "first encrypts/all decrypt" + rotation ordering, AWS Encryption-SDK
caching tradeoff and multi-Region "no cross-Region call" caveat, CockroachDB per-store/local-disk-only)
were each independently re-fetched and confirmed verbatim during research.*
