# §2.7 Upgrade-Capability Verification + Contract

Investigation (read-only) for the Group-B arc. Scope: verify that Configd's **frozen**
persistent + wire formats do not foreclose a future version, inventory every format's
version marker at `file:line`, and write the normative upgrade contract. No production
code was changed by this investigation; the two recommended fixes below are specified for
the arc to apply.

Repo state: `main` @ `f971a89` (post wire-hardening arc, PR #67).

---

## 1. Executive summary, verdict, recommendation

**Verdict: THE DOOR IS OPEN. No binary format forecloses a future version.** Every wire
and at-rest binary format carries an explicit version discriminator (a version byte/short,
or a magic + version, or is nested inside a self-versioned carrier) and **fails closed on
an unknown version** — it terminates the connection / rejects the load, and never silently
misinterprets. A mixed-version cluster during a hypothetical rolling upgrade therefore
**cannot corrupt data or silently misparse** on any binary format: the failure mode is
"refuse", not "guess".

**One residual silent-misparse vector, and one forward-compat slot gap, were found:**

1. **`PolicySerializer` (the `_acl/` policy text format) — RESIDUAL RISK, fix recommended.**
   The policy grammar has no version marker. Its whole-subtree-atomic, fail-closed parse
   already rejects unknown key shapes / effects / capabilities (so *most* future
   extensions fail closed), **but** the role-line "verbatim prefix remainder" and the
   verbatim binding line mean a future grammar that **appends a positional field to an
   existing line** would be **silently absorbed** by a current reader rather than rejected.
   Because policy is a security-critical, cluster-replicated value that every node parses,
   a mixed-version window on such an extension is an **authorization split-brain**. Fix in
   §4.1 (a recognized `_acl/format` sentinel + a contract rule).

2. **Keyring cloud-KMS entry — no encryption-context slot (forward-compat note, not a
   foreclosure).** The frozen keyring entry persists `(term, wrapAlgId, nonce, wrappedRoot)`
   only; a future external-KMS provider (Gate 7 / Vault) that needs a non-derivable
   encryption-context at unwrap has no slot for it. This is **not** a foreclosure because
   the keyring body is versioned (`KEYRING_FORMAT_VERSION`) and a future version can add
   the slot — but it should be confirmed against the real Vault provider before that format
   is treated as permanently frozen. Open question OQ-3.

**The two flagged "unversioned serializers" `WrappedKey` and `WatchCursor` are NOT
foreclosures** (the postgres-bar gap flag is stale/imprecise): `WrappedKey` is an
in-memory record whose only on-disk projection is the versioned keyring entry, and
`WatchCursor` is versioned-by-carrier (it only ever appears inside a version-stamped edge
frame) and additionally carries a `topologyEpoch` generation marker. Details in §2 and §4.

**Recommendation:** apply the two fixes in §4 (one code change to `PolicySerializer`, one
one-line reserved-slot check), adopt the normative upgrade contract in §5 into the RFC, and
put OQ-1/OQ-2 (the cluster-version / coordinated-upgrade mechanism for the Raft plane's
**first** frame-format bump) to the operator. The single most important contract statement
is: **Configd has no cluster-version / finalization mechanism today; the first Raft
frame-format change MUST introduce one (or a Hello handshake) so that v-next binaries keep
emitting the old frame until an operator-gated flip — otherwise a naive v-next that emits
new frames immediately breaks the rolling upgrade's availability (not its safety).**

---

## 2. Inventory table

`VM` = has a version marker. `FC` = a reader fails closed on an unknown version (never
silently misparses). All line numbers are on `main` @ `f971a89`.

### Wire — Raft plane (node ↔ node)

| Format | file:line | VM? | FC on unknown? | Verdict |
|---|---|---|---|---|
| **`FrameCodec`** (transport frame) | `configd-transport/.../FrameCodec.java:70` (`WIRE_VERSION=0x02`, u8) | YES | YES — `UnsupportedWireVersionException` @ `:306`; reserved 8B epoch MBZ fail-closed @ `:315` | **DOOR OPEN.** No in-band negotiation (strict tripwire, by design `:57-64`); coordinated upgrade required. Reserved epoch = a live forward-compat slot. |
| **`RaftMessageCodec`** (Raft RPC payloads) | `configd-server/.../RaftMessageCodec.java:45` | inherited | YES — via `FrameCodec` version + `MessageType.fromCode` | OK — carrier-versioned; per-message caps; in-version growth already demonstrated (optional-trailing `nextExpectedOffset` `:609-620`). |
| **`MessageType`** (type registry) | `configd-transport/.../MessageType.java:66` (`fromCode`) | discriminant | YES — throws on unknown code `:67-69` | OK — new RPC types ride a `WIRE_VERSION` bump; old node fails closed. |
| **`CommandCodec`** (config mutation blob) | `configd-config-store/.../CommandCodec.java:182` | carrier-versioned `:26-34` | YES — `MalformedCommandException` on unknown type byte `:182` | OK — never standalone; nested in WAL envelope / edge frame / snapshot. |

### Wire — Edge plane (driver ↔ server)

| Format | file:line | VM? | FC on unknown? | Verdict |
|---|---|---|---|---|
| **`EdgeFrameCodec`** (edge frame) | `configd-distribution-service/.../EdgeFrameCodec.java:72/:85/:99` (`0x01`/`0x02`/`0x03`, u8) | YES | YES — `BAD_WIRE_VERSION` @ `:636`; per-connection pin `:645` | **DOOR OPEN.** First-frame version pin = the real negotiation (`peekVersion` `:1161`); v1→v2→v3 evolution already shipped and is the worked template for a future bump. |
| **`FrameType`** (edge type registry) | `.../wire/FrameType.java:64` (`fromCode`) | discriminant | YES — throws on unknown code `:70` | OK — new frame types ride a version bump (watch `0x0A–0x12` are `0x02`-only). |
| **`EdgeSnapshotCodec`** (snapshot body) | `.../wire/EdgeSnapshotCodec.java:43` | carrier-versioned `:33-42` | YES — via the enclosing `SNAPSHOT_*` frame version | OK — trailer-less body; leading u64 is the DATA seq, not a format version `:91`. |
| **`WatchCursor`** (resume token) | `.../wire/WatchCursor.java:50` (`topologyEpoch`) | by-carrier + generation marker | YES — only decoded inside a version-stamped edge frame; epoch `0` reserved-illegal `:65` | OK — **not** a foreclosure (see §4.3). Not persisted to disk by the edge client (in-memory resume, `EdgeStreamClient.java:63`). |

### At-rest (durability)

| Format | file:line | VM? | FC on unknown? | Verdict |
|---|---|---|---|---|
| **`IntegrityEnvelope`** (durability envelope; encryption-at-rest seam) | `configd-common/.../IntegrityEnvelope.java:75` (`FORMAT_VERSION=3`, u16) + magic + `algId` + reserved | YES | YES — version `:382`, `algId` `:417`, reserved-MBZ `:390` | **DOOR FULLY OPEN.** `algId` NONE/HMAC/GCM=2 is itself a live extension axis; reserved byte MBZ = forward-compat slot. |
| **`KeyringCodec`** (keyring body; **`WrappedKey` persistence seam**) | `configd-consensus-core/.../KeyringCodec.java:53` (`KEYRING_FORMAT_VERSION=1`, u16) + `wrapAlgId` | YES | YES — version `:137`, `wrapAlgId` `:162` | **DOOR OPEN.** `wrapAlgId` LOCAL_GCM=1 / CLOUD_KMS=2 is the external-KMS extension axis. See OQ-3 (context slot). |
| **`WalContainer`** (WAL file header) | `configd-common/.../WalContainer.java:41` (`FILE_VERSION=1`, u8) + magic + flags-MBZ + reserved-MBZ | YES | YES — `:88` | **DOOR OPEN.** flags + reserved = forward-compat slots. |
| **`AnchorFile`** (per-shard anchor container) | `configd-consensus-core/.../AnchorFile.java:48` (`FILE_VERSION=1`, u8) + `ANCHOR_MAGIC` + MBZ | YES | YES — `:289` | **DOOR OPEN.** Inner `AnchorRecord` rides `IntegrityEnvelope`. |
| **`NodeAnchor*`** (node anchor) | `.../NodeAnchorRecord.java:25` (rides `IntegrityEnvelope` under `NODE_ANCHOR_MAGIC`) | inherited | YES — via envelope + container | OK — versioned-by-carrier (`IntegrityEnvelope` + `NodeAnchorFile` container header, mirrors `AnchorFile`). |
| **`TopologyDescriptor`** (topology / deploy-N + A4 epoch) | `.../TopologyDescriptor.java:44` (`FORMAT_VERSION=1`, u16) + `TOPO_MAGIC` + reserved-MBZ | YES | YES — `:132` | **DOOR OPEN.** reserved u32 MBZ = forward-compat slot; `topologyEpoch` is the A4 dynamic-reshard axis. |
| **`RaftArtifactMagic`** (magic registry) | `.../RaftArtifactMagic.java:35` | n/a | n/a (reserved-value discipline) | OK — retired magics never reused `:28-33`; new artifacts get new magics. |
| **Raft durable snapshot** (`SNAP_MAGIC`) | via `IntegrityEnvelope` + a magic-TLV trailer (`signingEpoch`) | inherited | YES — via envelope; TLV trailer is extensible | OK — body carrier-versioned; TLV trailer is itself a forward-compat mechanism. |

### The three flagged "unversioned serializers"

| Serializer | file:line | Truly unversioned? | Foreclosure? | Action |
|---|---|---|---|---|
| **`WrappedKey`** | `configd-common/.../kms/WrappedKey.java:29` | in-memory record; N/A on wire/disk | **NO** | Persisted only as the versioned keyring `wrapAlgId=CLOUD_KMS` entry; ciphertext is provider-opaque by design. See §4.2 + OQ-3. |
| **`WatchCursor`** | `.../wire/WatchCursor.java:41` | no own *format* version | **NO** | Versioned-by-carrier (edge frame `0x02`/`0x03`) + carries a `topologyEpoch` generation marker; never persisted standalone. §4.3. |
| **`PolicySerializer`** | `configd-control-plane-api/.../PolicySerializer.java:60` | **YES — no version marker** | **AJAR (residual risk)** | Whole-subtree fail-closed on unknown key/effect/cap, **but** silent-absorbs a positional append to an existing line → ACL split-brain risk. **FIX §4.1.** |

---

## 3. Reference-system findings (rolling upgrade / cluster version)

Both mature strongly-consistent systems reach upgradeability the same way Configd's binary
formats already permit: **a per-frame/per-record version discriminator that fails closed,
plus a cluster-wide logical version ("cluster version") that gates when new formats turn
on, decoupled from the per-node binary version.** Configd has the first half (the
fail-closed discriminators, verified in §2); it does **not** yet have the second half (a
cluster-version / finalization interlock). That is the gap the contract must name.

### 3.1 etcd — min-common-version negotiation + irreversible finalization

Source: etcd upgrade guide, "Upgrade etcd from 3.4 to 3.5"
(<https://etcd.io/docs/v3.5/upgrades/upgrade_3_5/>), §"Upgrade procedure" / cluster-version
notes.

- **Cluster version = the lowest member version.** "Internally, etcd members negotiate with
  each other to determine the overall cluster version, which controls the reported version
  and the supported features." "While upgrading, an etcd cluster supports mixed versions of
  etcd members, and operates with the protocol of the lowest common version." The cluster is
  "only considered upgraded once all of its members are upgraded."
- **Finalization is one-way.** "If all members have been upgraded to v3.5, the cluster will
  be upgraded to v3.5, and downgrade from this completed state is not possible. If any
  single member is still v3.4 … it is possible from this mixed cluster state to return to a
  v3.4 binary on all members." (This is why a pre-upgrade snapshot is mandated.)
- **Takeaway for Configd:** the "operate at the lowest common version until every node is
  upgraded" rule is exactly what a Configd Raft-plane frame-format bump needs, and it
  requires v-next binaries to be able to *speak the old version* — which Configd's frozen
  version byte + retained old encoder makes possible, but which nothing enforces today.

### 3.2 CockroachDB — binary version vs. cluster (logical) version + the finalization interlock

Sources: "Upgrade a cluster's version"
(<https://www.cockroachlabs.com/docs/stable/upgrade-cockroach-version>) and its
"Decide how the upgrade will be finalized" step.

- **Two decoupled versions** (paraphrase of the finalization model): a per-node **binary
  version** (the `cockroach` executable, upgraded one node at a time — "your cluster remains
  available while you upgrade one node at a time in a rolling fashion") and a cluster-wide
  **logical/cluster version** finalized via `SET CLUSTER SETTING version = '{VERSION}'`. Until
  finalization, "the command `SHOW CLUSTER SETTING version` will return the previous version."
- **The interlock gates new formats** (verbatim): "Certain features and performance
  improvements, such as those requiring changes to system schemas or objects, are not
  available until the upgrade is finalized." Finalization runs "a series of migration jobs …
  to enable certain types of features and changes in the new major version that cannot be
  rolled back," and "once a major-version upgrade is finalized, the cluster cannot be rolled
  back to the prior major version."
- **Preserve rollback across the mixed window.** Auto-finalization "begins as soon as all
  nodes have rejoined the cluster using the new binary. If you need the ability to roll back
  a major-version upgrade, you can disable auto-finalization and finalize the upgrade
  manually." (The legacy knob was `cluster.preserve_downgrade_option`; the current stable
  page frames it as disabling auto-finalization, i.e. `cluster.auto_upgrade.enabled = false`.)
  Finalization cannot complete while any node is still on the old binary or any node is
  `DEAD`/non-decommissioned.
- **Takeaway for Configd:** the CRDB model is the recommended template — introduce a
  Configd "cluster wire version" (a new frozen node-level descriptor, natural sibling of
  `TopologyDescriptor`), default v-next binaries to emit the **old** frame, and flip to the
  new frame only after an operator-gated finalization once every node runs the v-next
  binary. The frozen `FrameCodec` version byte is the safety net that makes an
  accidental-early-flip a *refused connection* (availability blip) rather than corruption.

### 3.3 How Configd's edge plane already matches the reference model

The edge plane's shipped `0x01 → 0x02 → 0x03` evolution is a concrete, in-tree worked
example of a safe format bump on the **client-facing** plane (a bidirectional negotiation is
unnecessary there because the *client* chooses the version): RFC `06-wire-framing.md`
§1.3 / §4 documents "**first-frame version pin** (the real negotiation) … no hello /
capabilities frame … a `0x03` SUBSCRIBE to a server that only speaks `0x01`/`0x02` **fails
closed** with `BAD_WIRE_VERSION` (no silent downgrade)", and `00-overview.md` §04-D1: "**no**
version negotiation, **no** version header, **no** capabilities exchange. A future revision
is a new [version byte value]." The Raft plane needs the *coordinated-upgrade* variant of
this because it is peer-to-peer (both ends must agree), whereas the edge plane's
client-proposes model suffices for a client↔server hop.

---

## 4. Specific fixes for the foreclosure / residual risks

### 4.1 `PolicySerializer` — add a recognized `_acl/format` version sentinel (RECOMMENDED)

**File:** `configd-control-plane-api/src/main/java/io/configd/api/PolicySerializer.java`,
in `parse(Map<String, byte[]> aclSubtree)` (`:77`), the key-shape dispatch (`:91-108`).

**Problem (precise).** A role line is parsed as `<effect> <caps> <prefix>` where `prefix`
is the **verbatim remainder** after the caps token (`:137-147`) — intentionally, so a flat
prefix may contain spaces. A binding line is taken **verbatim** as a role name (`:189`).
Consequently a future v2 grammar that **appends a positional field** to an existing role or
binding line is not rejected by a v1 reader — it is silently folded into `prefix` / the role
name. Unlike an unknown effect/capability (fail-closed `:133`,`:172`) or an unknown `_acl/`
key shape (fail-closed `:105`), this specific extension shape is a **silent semantic
misparse**. Because `_acl/` values are cluster-replicated and every node parses them, a
mixed-version window on such a change yields **divergent effective ACLs per node** — an
authorization split-brain on a security path.

**Fix.** Reserve a recognized, validated `_acl/format` key (absent ⇒ implicit version `1`)
and fail closed on any unsupported value, before the roles/bindings dispatch:

- Add `private static final String FORMAT_KEY = ACL_PREFIX + "format";` and
  `public static final int SUPPORTED_ACL_FORMAT = 1;`.
- In the `parse` loop, branch `key.equals(FORMAT_KEY)` first: parse the value as an integer
  and `throw new PolicyParseException(...)` unless it equals `SUPPORTED_ACL_FORMAT`. (An
  absent key means version 1 — the current, byte-identical behaviour.)
- The key is metadata, not a role/binding, so it contributes nothing to `roles`/`bindings`.

**Why this is safe and opens the door.** It is **byte-identical for every existing
deployment** (none carry an `_acl/format` key, so the absent ⇒ v1 path is unchanged; the
golden/existing policy fixtures do not change). It converts the today-implicit interlock
(v1 already whole-subtree-rejects an unknown `_acl/` key shape `:105`, so a v2 that writes
`_acl/format=2` alongside v2 role lines is *already* rejected by v1) into an **explicit,
tested version knob** with a fail-closed unknown path that mirrors every binary codec.

**Compatibility rule (frozen).** ACL format `1` is the current grammar and is permanent. A
future grammar change **MUST** bump `_acl/format` (v1 nodes then fail closed on the whole
subtree and keep last-good) and **MUST NOT** extend an existing role/binding line's
positional grammar without that bump. New capability may also be introduced via a new
`_acl/<shape>/…` key (v1 already fail-closes on an unrecognized shape) or a new
effect/capability keyword (v1 already fail-closes).

### 4.2 `WrappedKey` — no fix needed (verification result)

`WrappedKey` (`configd-common/.../kms/WrappedKey.java:29`) is an **in-memory carrier**, not
a frozen on-disk format. The default `LocalDerivedKmsProvider` even stores an **empty**
ciphertext (it re-derives from the signing key; `LocalDerivedKmsProvider.java:90-100`). The
only durable projection of a wrapped root is the **versioned** `KeyringCodec` entry
`[term][wrapAlgId][nonce][wrappedRoot]` under `KEYRING_FORMAT_VERSION` (`KeyringCodec.java:35`),
where a cloud-KMS blob rides `wrapAlgId=WRAP_ALG_CLOUD_KMS=2` (`:57`) and is opaque to the
core by design. The postgres-bar "unversioned" flag is therefore **stale** for the
persistence question. (Residual forward-compat concern → OQ-3.)

### 4.3 `WatchCursor` — no fix needed (verification result)

`WatchCursor` has no *format*-version field, but it is **never serialized standalone**: it is
encoded/decoded only inside the version-stamped edge frames (`EdgeFrameCodec.encodeCursorInto`
`:445` / `decodeCursor` `:903`), so `EDGE_WIRE_VERSION` versions it, and it additionally
carries a `topologyEpoch` **generation** marker with epoch `0` reserved-illegal
(`WatchCursor.java:53`, decode reject `EdgeFrameCodec.java:730`,`:909`). The edge client keeps
the resume position **in memory** (`EdgeStreamClient.java:63`) — there is no unversioned
on-disk cursor. Not a foreclosure.

---

## 5. The normative upgrade contract (ready to drop into the RFC)

> ### Upgrade & format-compatibility contract
>
> **C0 — Frozen formats are permanent.** Every wire and at-rest format enumerated in the
> inventory (§2) is frozen. A shipped version's byte layout for a given version value never
> changes; the golden-fixture / `wire-compat` CI gate enforces this. Evolution happens only
> by **introducing a new version value**, never by editing an existing one.
>
> **C1 — Every format is version-discriminated and fails closed.** A reader MUST reject a
> record/frame whose version marker it does not recognize with a distinct, structured error
> and MUST NOT parse it under any other version's grammar. This is verified for all formats
> in §2. Corollary: a mixed-version cluster cannot corrupt data or silently misparse any
> **binary** format — the failure mode is "refuse the connection / reject the load", never
> "guess".
>
> **C2 — No silent downgrade.** A reader configured for a stronger posture MUST NOT accept a
> weaker one: `IntegrityEnvelope` refuses `algId=NONE` under a key (`:406`) and (with
> `requireEncrypted`) refuses a legacy HMAC record (`:427`); the edge codec refuses a frame
> whose stamped version differs from the connection's pinned version (`:645`). A future
> version MUST preserve this "never silently weaken" rule.
>
> **C3 — Reserved slots are the forward-compat doors and MUST stay MBZ.** The `FrameCodec`
> 8-byte reserved epoch (MBZ, fail-closed non-zero, `:315`), the `WalContainer` /
> `AnchorFile` flags+reserved (MBZ), the `IntegrityEnvelope` reserved byte (MBZ, `:390`), and
> the `TopologyDescriptor` reserved u32 (MBZ) are reserved for a future version and MUST be
> written zero and rejected-if-nonzero by the current version. A future version assigns
> meaning to a reserved slot **only together with a version bump**.
>
> **C4 — Magics are never reused.** A retired artifact magic (`RaftArtifactMagic`,
> e.g. `RFST`) is permanently reserved; a future artifact gets a new magic. This prevents a
> resurrected value from being confused with a different artifact across versions.
>
> **C5 — Edge plane (client ↔ server): first-frame version pin.** The driver picks its wire
> version with its first frame and stamps that version on every subsequent frame; the server
> pins the connection to it. There is no hello/capabilities exchange; a version the server
> does not support fails closed with `BAD_WIRE_VERSION`. A future edge revision is a new
> version-byte value under this same mechanism (the shipped `0x01→0x02→0x03` evolution is the
> template). See `06-wire-framing.md` §1.3/§4.
>
> **C6 — Raft plane (node ↔ node): coordinated upgrade, no in-band negotiation (today).** The
> Raft transport has **no** version-negotiation handshake — the frame version byte is a
> strict tripwire and all nodes MUST run the same `WIRE_VERSION`. Because the byte fails
> closed, a mixed-`WIRE_VERSION` cluster is **safe** (connections terminate; no corruption),
> but a frame-format change is **not** a drop-in rolling upgrade. Therefore the **first**
> Raft frame-format change MUST ship with one of:
>   - **(a) a cluster-version / finalization interlock** (the CockroachDB model): v-next
>     binaries default to emitting the **old** frame version; a new frozen node-level "cluster
>     wire version" descriptor (a sibling of `TopologyDescriptor`) gates the flip to the new
>     frame, and an operator finalizes it only after every node runs the v-next binary; or
>   - **(b) an in-band Hello/version handshake** added at connection setup in v-next, letting
>     two adjacent versions agree on the lowest common frame version (the etcd model). The
>     `FrameCodec` doc already anticipates this (`:57-64`).
>
>   Until such a mechanism exists, a Raft `WIRE_VERSION` bump is an **all-at-once** upgrade
>   (stop-the-world or blue/green), not a rolling one. A v-next binary MUST NOT emit a new
>   frame version to peers before the gating mechanism authorizes it, or it will break the
>   rolling window's **availability** (peers fail the connection closed).
>
> **C7 — At-rest is forward-and-backward safe within a keyring.** `IntegrityEnvelope` +
> `KeyringCodec` retain every prior `keyTerm`/root untouched across rotation, so a v-next
> reader still verifies/decrypts old-term records and an enable-encryption migration reads
> legacy HMAC records via the same keyring. A future version MUST preserve non-destructive
> rotation and old-term readability.
>
> **C8 — ACL policy format is version-gated (see §4.1).** ACL format `1` is frozen. A future
> grammar change MUST bump the `_acl/format` sentinel (v1 nodes fail closed on the whole
> subtree and keep last-good) and MUST NOT extend an existing line's positional grammar
> without that bump.
>
> **C9 — Mixed-version safety statement.** Under C1–C8, at any point in a supported upgrade,
> a node running the old binary and a node running the new binary either (i) interoperate
> byte-identically (the new binary still emits the old version), or (ii) fail closed loudly
> (a refused connection / rejected load / rejected policy). Neither node ever silently
> misinterprets the other's bytes or silently weakens a security posture. Data integrity and
> authorization correctness are preserved across the entire window; only **availability** of
> a not-yet-gated new feature is deferred until finalization.

---

## 6. Open questions needing an operator decision

- **OQ-1 (Raft rolling-upgrade mechanism).** For the first Raft frame-format bump, choose the
  CockroachDB-style **cluster-wire-version + finalization interlock** (§5 C6-a, recommended —
  reuses the frozen-descriptor pattern and the fail-closed byte) or the etcd-style **in-band
  Hello handshake** (C6-b). This does not need building now (there is no v2), but the contract
  must commit to one so v-next is designed for it. **Recommendation: C6-a**, as a sibling
  descriptor to `TopologyDescriptor`.
- **OQ-2 (all-at-once acceptance).** Until OQ-1 is built, is an **all-at-once** (blue/green or
  brief stop-the-world) upgrade acceptable for a Raft `WIRE_VERSION` bump? (Edge-plane and
  at-rest changes are already rolling-safe; only a Raft frame-format change is gated.) The
  arc's answer determines whether OQ-1 is a v1.x or a v2 item.
- **OQ-3 (cloud-KMS encryption-context slot).** The frozen keyring `CLOUD_KMS` entry persists
  `(term, wrapAlgId, nonce, wrappedRoot)` only — no encryption-context/AAD slot. Confirm
  against the real Vault provider (Gate 7) that its unwrap context is **derivable** from
  `(nodeKeyId, term)` (as the local-GCM AAD is); if a provider needs a non-derivable context,
  the keyring format version must be bumped to add the slot **before** it is treated as
  permanently frozen. Not a foreclosure (the format is versioned), but a design confirmation
  owed to Gate 7.
- **OQ-4 (apply the `PolicySerializer` fix now?).** §4.1 is a small, byte-identical-for-existing
  -data change on a security path. Recommend applying it in this arc so the ACL version knob is
  explicit and tested rather than relying on the implicit whole-subtree-reject discipline.
