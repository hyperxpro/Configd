# Group B §2.7 — Upgrade-Capability: As-Built Fix + Normative Contract

> **Status:** built (the one code fix) + normative (the contract). Companion to the investigation
> `investigation/07-upgrade-capability.md` (the full format inventory at `file:line`, the reference-system
> grounding, and the C0–C9 contract text this doc ratifies as-built). The investigation's **verdict stands:
> the door is open** — every wire and at-rest binary format is version-discriminated and fails closed on an
> unknown version, so a mixed-version cluster cannot silently misparse or corrupt data. This gate closes the
> **one** residual silent-misparse vector it found (the `_acl/` text policy format), resolves the one
> forward-compat open question (OQ-3) against the Gate-7 as-built, and records the normative upgrade contract.
>
> The keywords **MUST**, **MUST NOT**, **SHOULD**, **MAY** are as in RFC 2119 / RFC 8174.

This is a **contract + verification**, not migration machinery: there is no v2 to migrate to. It states the
rules a *future* format change MUST obey so the door stays open, and it fixes the single format that could
have let a future change slip through silently.

---

## 1. What shipped — the `_acl/format` version sentinel

**File:** `configd-control-plane-api/src/main/java/io/configd/api/PolicySerializer.java`.

The `_acl/` policy text format was the only format in the inventory without a version marker. Its
whole-subtree, fail-closed parse already rejects an unknown `_acl/` key shape, an unknown effect, and an
unknown capability — so *most* future extensions already fail closed. The residual hole was **positional**: a
role line's `prefix` is the verbatim remainder after the caps token, and a binding line is a verbatim role
name, so a hypothetical future grammar that **appended a positional field to an existing line** would have
been **silently absorbed** by an old reader rather than rejected. Because `_acl/` is cluster-replicated and
parsed on every node, a mixed-version window on such a change would be an **authorization split-brain** — a
security path. The fix converts the today-implicit interlock into an explicit, tested version knob.

**The change (additive, byte-identical for every existing deployment):**

- A reserved metadata key `_acl/format` (constant `FORMAT_KEY`) and a public `SUPPORTED_ACL_FORMAT = 1`.
- In `parse(...)`, a `key.equals(FORMAT_KEY)` branch runs **first**; it validates the value as the supported
  integer version (via `parseFormatVersion`) and contributes **nothing** to roles/bindings.
- **Absence ⇒ format 1.** Every existing deployment has no `_acl/format` key, so the absent-⇒-v1 path is
  unchanged and existing `_acl/` policies serialize/parse **identically**. (The key is a KEY, not a byte
  prefix, so it never touches the frozen serialized form of an existing `_acl/roles/…`/`_acl/bindings/…`
  value.)
- **A present unsupported/malformed value fails closed:** `_acl/format=2`, `=0`, a blank value, or a
  non-integer throws `PolicyParseException`, which the loader treats as **fail-closed-to-last-good** — it
  rejects the whole load, keeps the last-good policy, and increments `configd.acl.policy.load.failed`
  (`AclConfigPolicyLoader.rebuild`). It never deny-alls and never allow-alls. The value is stripped before
  parse, so a trailing newline / surrounding whitespace is tolerated (consistent with the line-oriented text
  format).

**The fix composes with the write-time gate for free.** A write to `_acl/format` is under the reserved
`_acl/` prefix, so `AdminApiHandler` requires ADMIN and runs it through the SAME
`AclConfigPolicyLoader.validateAclWrite` → `PolicySerializer.parse`. Consequently a `PUT _acl/format=2` on a
v1 node is **refused at the door (400, pre-commit)**, and if an unsupported-format policy nonetheless arrives
via snapshot/replay from a newer node, the reload path **fails closed to last-good**. Write-time and
reload-time reject the identical set — never two validators that could drift.

**Compatibility rule (frozen — encoded in the code comment and normative below as C8).** ACL format `1` is
the current grammar and is permanent. A future grammar change **MUST** bump `_acl/format` (v1 nodes then fail
closed on the whole subtree and keep last-good) and **MUST NOT** extend an existing role/binding line's
positional grammar without that bump. New capability MAY instead ride a new `_acl/<shape>/…` key or a new
effect/capability keyword — an old reader already fail-closes on both.

**Tests (all green offline).**
- `PolicySerializerTest` (25 tests, +7): `_acl/format=1` accepted and contributes no role/binding; the key
  alone is an empty policy; whitespace-tolerant; absent-key byte-identical to the historical path; `=2`/`=0`
  fail closed; blank/non-integer/`1.0` fail closed; an unsupported version rejects the WHOLE subtree even
  when a valid role is also present.
- `AclConfigPolicyLoaderTest` (11 tests, +2): a supported-format key loads normally; an `_acl/format=2`
  reload keeps last-good, bumps `…load.failed`, and does not allow-all.
- The whole `configd-control-plane-api` suite (296 tests) and `ReservedPrefixAdminGateTest` (10) stay green,
  including the ACL byte-identity differential tests — the change is additive on a previously-rejected key.

---

## 2. OQ-3 resolved — the keyring needs **no** encryption-context slot

The investigation left OQ-3 open: the frozen keyring's cloud-KMS entry persists `(term, wrapAlgId, nonce,
wrappedRoot)` with no encryption-context/AAD slot; confirm against the real Vault provider (Gate 7) that its
unwrap context is derivable, or the `KEYRING_FORMAT_VERSION` would need a bump **before** the format is
treated as permanently frozen.

**Conclusion: no keyring slot and no `KEYRING_FORMAT_VERSION` bump are needed.** Verified against the Gate-7
as-built:

- Gate 7 places the external-KMS seam at the **keyring-custody secret** (the IKM the two keyring-wrapping keys
  `K_keyringMac` / `KEK_wrap` are HKDF-derived from), **one level above** the keyring — not inside a keyring
  entry (`ConfigdServer.deriveRaftIntegrityEnvelope` :1620-1647, `unsealKeyringCustodySecret` :1702-1747).
- The external KMS seals that per-node custody secret and hands back a `WrappedKey`, which is persisted in its
  **own versioned carrier** `raft-kms-root` (`KmsSealedRootStore`, magic `RKMS` + `FORMAT_VERSION`). The
  `WrappedKey.context()` map (persisted in that carrier as `contextCount * (key,value)`) carries the
  provider's **self-describing** metadata; the AAD *actually enforced* at unseal is the **configured**
  `aadContext`, defaulting to `KmsBootContext.nodeId` — re-derived from config at every boot
  (`VaultTransitKmsProvider.unwrap` :105-119, `sealContext` :139-142). It is **never** read from the keyring.
- The keyring entries themselves therefore stay **local** (`WRAP_ALG_LOCAL_GCM`), AES-256-GCM-wrapped under
  the `KEK_wrap` derived from the recovered custody secret — byte-identical in structure to the `local`
  posture. The `WRAP_ALG_CLOUD_KMS` discriminant in `KeyringCodec` remains a reserved, fail-closed code path;
  the Gate-7 custody model never writes it (the cloud custody lives in `raft-kms-root`, not a keyring entry).

So the non-derivable-context case OQ-3 worried about does not arise: the only external context is `nodeId`,
which is derivable from node identity and lives in the versioned sealed-root carrier. The keyring format is
safe to treat as frozen. **If a future provider ever needed a non-derivable per-entry context, the keyring
body is versioned (`KEYRING_FORMAT_VERSION`, fail-closed on unknown at `KeyringCodec.decodeBody` :137) and a
new version can add the slot — still not a foreclosure.**

**Inventory delta since the investigation (`main` @ `f971a89`, pre-Gate-7).** Gate 7 added one new at-rest
format, which satisfies the contract (door open):

| Format | file:line | VM? | FC on unknown? | Verdict |
|---|---|---|---|---|
| **`KmsSealedRootStore`** (`raft-kms-root`; the external-KMS custody carrier) | `configd-server/.../KmsSealedRootStore.java:54` (`MAGIC=RKMS` + `FORMAT_VERSION=1`) | YES | YES — bad magic / unsupported version / truncation / trailing bytes all "refuse to start" (:101,:106,:127,:132) | **DOOR OPEN.** The `context` map is itself a forward-compat slot (self-describing provider metadata); integrity rides the provider's own AEAD (a tampered/relocated blob fails the authenticated decrypt → fail-closed boot). Present only on external-posture nodes; the `local` posture writes no such file (byte-identical). |

---

## 3. The normative upgrade & format-compatibility contract (as-built)

> ### C0 — Frozen formats are permanent.
> Every wire and at-rest format enumerated in the investigation §2 inventory (plus `raft-kms-root`, §2 above)
> is frozen. A shipped version's byte layout for a given version value never changes; the golden-fixture /
> `wire-compat` CI gate enforces this. Evolution happens only by **introducing a new version value**, never by
> editing an existing one.
>
> ### C1 — Every format is version-discriminated and fails closed.
> A reader MUST reject a record/frame whose version marker it does not recognize with a distinct, structured
> error and MUST NOT parse it under any other version's grammar. **Corollary:** a mixed-version cluster cannot
> corrupt data or silently misparse any **binary** format — the failure mode is "refuse the connection /
> reject the load", never "guess". (Verified for every format in the investigation §2; and for `raft-kms-root`
> in §2 above.)
>
> ### C2 — No silent downgrade.
> A reader configured for a stronger posture MUST NOT accept a weaker one: `IntegrityEnvelope` refuses
> `algId=NONE` under a key and (with `requireEncrypted`) refuses a legacy HMAC record; the edge codec refuses a
> frame whose stamped version differs from the connection's pinned version; the KMS boot path refuses to
> **downgrade** to no-encryption or a different provider when the selected provider is unavailable
> (`unsealKeyringCustodySecret` fail-closed). A future version MUST preserve this "never silently weaken" rule.
>
> ### C3 — Reserved slots are the forward-compat doors and MUST stay MBZ.
> The `FrameCodec` 8-byte reserved epoch, the `WalContainer` / `AnchorFile` flags+reserved, the
> `IntegrityEnvelope` reserved byte, and the `TopologyDescriptor` reserved u32 are reserved for a future
> version: written zero, rejected-if-nonzero by the current version. A future version assigns meaning to a
> reserved slot **only together with a version bump**.
>
> ### C4 — Magics are never reused.
> A retired artifact magic (`RaftArtifactMagic`) is permanently reserved; a future artifact gets a new magic
> (e.g. Gate 7's `raft-kms-root` took the new `RKMS`, sibling to the family). This prevents a resurrected value
> from being confused with a different artifact across versions.
>
> ### C5 — Edge plane (client ↔ server): first-frame version pin.
> The driver picks its wire version with its first frame and stamps that version on every subsequent frame;
> the server pins the connection to it. There is **no** hello/capabilities exchange; a version the server does
> not support fails closed with `BAD_WIRE_VERSION`. A future edge revision is a new version-byte value under
> this same mechanism (the shipped `0x01→0x02→0x03` evolution is the worked template). This is the plane's
> **real negotiation** because the *client* chooses the version — a bidirectional handshake is unnecessary for
> a client↔server hop. Normative detail: RFC `docs/rfc/driver-protocol/06-wire-framing.md` §1.3 / §4; overview
> fail-closed clause OV7 in `00-overview.md`.
>
> ### C6 — Raft plane (node ↔ node): coordinated upgrade, no in-band negotiation (today).
> The Raft transport has **no** version-negotiation handshake — the frame version byte is a strict per-frame
> tripwire and all nodes MUST run the same `WIRE_VERSION`. Because the byte fails closed, a mixed-`WIRE_VERSION`
> cluster is **safe** (connections terminate; no corruption), but a frame-format change is **not** a drop-in
> rolling upgrade. Therefore the **first** Raft frame-format change MUST ship with a coordination mechanism —
> the **recommended** design (not built now; there is no v2) is the CockroachDB-style **cluster-wire-version +
> finalization interlock**: a new frozen node-level "cluster wire version" descriptor (a natural sibling of
> `TopologyDescriptor`), with v-next binaries defaulting to emit the **old** frame version and an operator
> finalizing the flip to the new frame only after every node runs the v-next binary (etcd's in-band
> Hello/lowest-common-version handshake is the alternative). Until such a mechanism exists, a Raft
> `WIRE_VERSION` bump is an **all-at-once** upgrade (blue/green or brief stop-the-world), not a rolling one. A
> v-next binary MUST NOT emit a new frame version to peers before the gating mechanism authorizes it, or it
> breaks the rolling window's **availability** (peers fail the connection closed) — never its safety.
>
> ### C7 — At-rest is forward-and-backward safe within a keyring.
> `IntegrityEnvelope` + `KeyringCodec` retain every prior `keyTerm`/root untouched across rotation
> (non-destructive `appendTerm` / `rewrapUnderNewKek`), so a v-next reader still verifies/decrypts old-term
> records and an enable-encryption migration reads legacy HMAC records via the same keyring. The external-KMS
> custody secret (`raft-kms-root`) is sealed once and re-unsealed each boot with a config-derived AAD, adding
> no per-term coupling. A future version MUST preserve non-destructive rotation and old-term readability.
>
> ### C8 — ACL policy format is version-gated (BUILT — §1).
> ACL format `1` is frozen. A future grammar change MUST bump the `_acl/format` sentinel (v1 nodes fail closed
> on the whole subtree and keep last-good) and MUST NOT extend an existing role/binding line's positional
> grammar without that bump. New capability MAY ride a new `_acl/<shape>/…` key or a new effect/capability
> keyword (both already fail-closed on an old reader).
>
> ### C9 — Mixed-version safety statement.
> Under C0–C8, at any point in a supported upgrade a node running the old binary and a node running the new
> binary either (i) **interoperate byte-identically** (the new binary still emits the old version), or (ii)
> **fail closed loudly** (a refused connection / rejected load / rejected policy). Neither node ever silently
> misinterprets the other's bytes or silently weakens a security posture. **Data integrity and authorization
> correctness are preserved across the entire window;** only **availability** of a not-yet-gated new feature is
> deferred until an operator-gated finalization (C6).

---

## 4. Open-question status

- **OQ-1 (Raft rolling-upgrade mechanism).** Still an **operator decision**, not built (no v2 exists). The
  contract commits v-next to be *designed for* it; **recommendation: the CockroachDB-style cluster-wire-version
  + finalization interlock** (C6), a sibling descriptor to `TopologyDescriptor`, because it reuses the
  frozen-descriptor pattern and the already-frozen fail-closed frame byte.
- **OQ-2 (all-at-once acceptance).** Still an operator decision: until OQ-1 is built, is an all-at-once
  (blue/green or brief stop-the-world) upgrade acceptable for a Raft `WIRE_VERSION` bump? (Edge-plane and
  at-rest changes are already rolling-safe; only a Raft frame-format change is gated.)
- **OQ-3 (cloud-KMS encryption-context slot).** **RESOLVED — §2.** The keyring needs no context slot and no
  `KEYRING_FORMAT_VERSION` bump: the Vault unwrap context is the config-derived `nodeId`, carried in the
  versioned `raft-kms-root` carrier, never in the keyring.
- **OQ-4 (apply the `PolicySerializer` fix now?).** **DONE — §1.** Applied in this gate; the ACL version knob
  is now explicit and tested rather than relying on the implicit whole-subtree-reject discipline.
