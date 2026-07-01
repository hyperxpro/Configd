# Encryption at Rest — Decision Log

> **Session:** Encryption-at-rest research (RR-098), 2026-06-28. Branch `research-encryption-at-rest`.
> **Posture:** research only — no production code, no crypto implementation, no money spent. Deliverables
> are the three docs in this directory + this log. Stops at a docs-only PR.

## Methodology

- **Prior art before opinion.** Four mature systems were studied at the mechanism level (cipher, key
  hierarchy, unseal, rotation, threat model) from primary sources, with load-bearing quotes verified
  verbatim: HashiCorp Vault (the operator's named reference, studied hardest), etcd + Kubernetes KMS, the
  cloud-KMS envelope pattern (AWS/GCP/Vault Transit), and database TDE (CockroachDB closest, + MySQL/SQL
  Server/Oracle/Postgres). Captured in [`prior-art.md`](prior-art.md).
- **Mapping verified against code, not asserted.** The crux claims were checked in the source rather than
  assumed — in particular that Configd's replication wire carries plaintext (so node-local encryption needs
  no key distribution). Captured in [`configd-analysis.md`](configd-analysis.md) with `file:line` evidence.
- **Options ranked with honest cost.** Four options (A none → D Vault-style) with a threat-defense matrix and
  a cost/fit table, then a single clear v1/v2 recommendation **for the operator to decide**. In
  [`recommendation.md`](recommendation.md).
- **Hard rules honored:** no implementation; mechanisms (not summaries) extracted; the consensus +
  edge key-distribution + key-availability questions analyzed, not hand-waved; only established constructions
  recommended (AES-GCM, AES-CTR-then-HMAC, HKDF, KMS envelope — never custom crypto); docs-only under
  `docs/research/encryption-at-rest/`.

## Findings / decisions recorded this session (evidence, not directives)

- **D-ENC-1 — Verified: Configd's ADR-0042 integrity envelope is a per-node, below-replication, storage-layer
  transform.** `serializeEntry()` (which applies the envelope) is called only on local WAL writes
  (`RaftLog.java:371,781`); `AppendEntriesRequest` carries in-memory `LogEntry(index,term,command)` plaintext
  (`AppendEntriesRequest.java:20-25`, `LogEntry.java:13`); `sendInstallSnapshot` ships
  `latestSnapshot.data()` plaintext (`RaftNode.java:2057-2066`). **Consequence:** node-local storage-layer
  encryption drops into the existing seam with **no wire change and no cluster-wide key distribution.**
- **D-ENC-2 — The consensus crux resolves to storage-layer (node-local), not end-to-end.** mTLS already
  covers transit (register §8.3), so end-to-end's only extra benefit (wire ciphertext) is redundant, while it
  would import the Kubernetes *decryptors⊇encryptors* cluster-wide key-distribution + two-phase-rotation
  burden (worsened by sharding) and break server-side content operations.
- **D-ENC-3 — The edge key-distribution fork largely dissolves.** The edge store is in-memory only
  (`LocalConfigStore.java:48`, no disk write of values), so the control-plane has the only at-rest surface.
  Residual is a *runtime* heap-dump exposure (out of RR-098 scope), addressable only by value-level/E2E
  (Option C). **Constraint recorded:** the fork re-opens if a future edge gains disk persistence.
- **D-ENC-4 — Key availability is satisfiable with no new failure mode.** Derive `K_enc` from the
  already-loaded signing key via HKDF (a third key beside `K_integrity`/`K_audit`,
  `ConfigdServer.java:1115,1204`) — available at boot, no external call, no unseal that can block rejoin. The
  KMS path (Option D) adds a boot-time dependency to engineer around (cache + non-blocking replay; never
  interactive Shamir on a config store).
- **D-ENC-5 — Performance is not a blocker.** Write path is fsync/election-bound (register §9.1); AES-256-GCM
  with AES-NI is sub-µs per small entry (CPU only, no extra fsync). Estimate <0.5%–low-single-digit % CPU,
  **no measurable throughput regression** — to be confirmed with an encryption arm on the EC2 knee run.
- **D-ENC-6 — `secure/`-only per-namespace encryption is not a lighter option.** Whole-store Option B already
  gives `secure/` at-rest confidentiality for free; genuine live-node/edge confidentiality needs
  value-level/E2E (Option C), which is the heaviest option scoped narrowly and only justified if Configd
  becomes a secrets-holder — which current posture rejects.
- **D-ENC-7 — Recommendation (for the operator to decide):** v1 stays A (already decided, D-2); **v2 first
  step = B-minimal** (node-local AES-256-GCM at the ADR-0042 seam, `K_enc` HKDF-from-signing-key); **graduate
  to D** (KMS auto-unseal + keyring) when off-host custody/compliance/managed-rotation is required; **defer C**
  indefinitely.

## What was NOT done (honest scope)

- **No code, no crypto.** No `Cipher` path, no key derivation, no ADR. The construction choices (GCM vs
  CTR-then-HMAC; B-minimal vs KMS) are **flagged for the specialist crypto review** ADR-0042 already
  established, not decided here.
- **No measurement.** The perf numbers are estimates from the measured baseline + cipher characteristics;
  the EC2 confirmation arm is recommended, not run (no money spent this session).
- **No change to v1 posture or to any shipped doc** beyond adding this research directory.

## Handoff — the encryption decision is now evidence-based

What the operator must decide (when RR-098 is scheduled for a release):

1. **Whether/when to pull B into a v2 release** — the trigger is "a deployment must store sensitive data or
   meets an at-rest compliance bar" (known-limitations §1). Until then, A + loud docs remains correct.
2. **Cipher construction** — AES-256-GCM (recommended) vs AES-256-CTR-then-HMAC — for crypto review.
3. **Key-management rung at first ship** — B-minimal (HKDF-from-signing-key, recommended) vs B-operator-key
   vs jump to D (KMS auto-unseal, only if off-host custody is a launch requirement).
4. **Whether C is ever in scope** — i.e. whether Configd is repositioned to hold secrets against a live-node/
   edge threat (current posture: no — use a secret manager).

When B is built, it should: reuse the ADR-0042 envelope/`algId`/HKDF/fail-closed/gate-7-negative-test
machinery; tag each unit with a `keyId`; bind segment-id‖offset‖term into the AAD (closing ADR-0042's
tail-truncation residual); keep KMS off the write path; never co-locate the key with the data; treat
snapshots/backups as in-scope long-lived ciphertext; and add an encryption-on/off arm to the EC2 knee +
gate-5 allocation check. A new ADR (sibling to ADR-0042) should record the decision.

## Pointers

- [`prior-art.md`](prior-art.md) — Vault / etcd+K8s / cloud-KMS / TDE, mechanisms + citations.
- [`configd-analysis.md`](configd-analysis.md) — plaintext-surface inventory; storage-vs-E2E; edge fork;
  key-availability; perf estimate; the `secure/` option.
- [`recommendation.md`](recommendation.md) — four ranked options + the v1/v2 call.
- Precedent: [`../../decisions/adr-0042-snapshot-wal-raftstate-integrity.md`](../../decisions/adr-0042-snapshot-wal-raftstate-integrity.md)
  (at-rest **integrity**; this research is its **confidentiality** sibling).
- Register: `docs/readiness/production-readiness-register.md` §8.2 (RR-098), §11-B D-2.
