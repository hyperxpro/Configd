# Session 7 — Decision Log

Per charter §1 (autonomy directive): every technical/correctness/methodology judgment call is
self-resolved (fresh `opus` sub-agent where there is genuine ambiguity) and logged here for
retroactive veto; every scope/sequencing call takes the conservative default and is logged with
rationale. Security-specific (§1): a control is never marked verified by configuration — only by a
passing negative test. Each entry: ID, type (TECH/SCOPE), question, resolution, evidence, who/how.

---

## D-1 (TECH) — PA-2021 integrity construction: HMAC vs signature, opt-in, torn-vs-tamper

**Question.** PA-2021 requires at-rest integrity over the snapshot blob, `DurableRaftState`, and the
WAL. Five sub-decisions before any code: (1) HMAC-SHA-256 vs Ed25519 signature; (2) is "no
authentication when no key configured" an acceptable opt-in or a downgrade hole; (3) the correct
torn-vs-tamper disambiguation so a crash-torn WAL tail is never misread as an attack and vice versa;
(4) composition with RR-003 durable-prefix / S4 torn-write; (5) what must be flagged for a
cryptographer rather than self-certified.

**Method.** Fresh independent `opus` sub-agent convened to pressure-test the proposed two-layer
construction (Layer A = magic+version+CRC32C; Layer B = HMAC-SHA-256) BEFORE implementation, with
read access to the actual files. Verdict recorded verbatim below; ratified by `security-lead` and
encoded in **ADR-0042**.

**Resolution (ratified — APPROVED to implement, 1 blocker + 3 binding conditions).**
1. **HMAC-SHA-256 keyed via HKDF-SHA256 from the existing cluster Ed25519 key** (domain-separated
   `info`). Asymmetric signatures buy non-repudiation this threat model does not need; per-node
   signing cannot protect a node's own disk against an attacker holding that node's key. One
   cluster-shared key — already mounted — is the lower-surface choice and adds **no new key file**.
2. **Opt-in is acceptable for S7 pre-production ONLY with a sticky fail-closed posture:** a node with
   a key configured must REFUSE `algId=NONE`/CRC-only artifacts (downgrade is defeated by node
   posture, not by bytes; the MAC covering `algId` is necessary but not sufficient). Production
   default flips to fail-closed → **S8 go/no-go gate item**. CRC-only ≠ PA-2021 closed; stated plainly.
3. **Torn-vs-tamper:** per-record authentication lives **inside** the `FileStorage` frame, *after*
   the existing torn-tail drop. Torn (incomplete) tail → dropped before the MAC runs; complete +
   CRC-valid + MAC-invalid → **tamper, fail loud**. Atomic-rename artifacts are never torn → MAC
   mismatch always fails loud. Residual: per-record MAC cannot detect suffix truncation → flagged.
4. **Composition with RR-003/S4:** safe as a pure encode/decode transform. **One deliberate point:**
   `readSnapshotBlob` currently returns `null` (→ WAL fallback) on a malformed blob; a MAC mismatch on
   a *structurally-complete* blob must **THROW, not fall back**, else a silent-downgrade vuln is
   reintroduced. Structurally-short/absent → still `null` (legit torn / first boot).
5. **Flagged for the cryptographer:** Ed25519-private-key-as-HKDF-IKM soundness; MAC message
   canonicalization; constant-time compare; downgrade-policy completeness; suffix-truncation anchor;
   CRC32C-vs-CRC32 choice. (ADR-0042 §Consequences.)

**BLOCKER (resolved in design).** The default `--signing-key-file` resolves to
`dataDir.resolve("signing-key.bin")` (`ConfigdServer.java:197-199`) — *inside* the data dir, alongside
the artifacts it must protect. A co-located key defeats Layer B against the T3/A2 attacker.
Production Compose already mounts the key separately at `/secrets/signing-key.bin:ro`
(`deploy/compose/setup-secrets.sh:90`). S7 resolution: emit a loud startup warning when the integrity
key resolves inside the data dir; relocating the *default* is an S8 item (would touch restart-chain
compat + fixtures). Recorded so S8 go/no-go can weigh it.

**Evidence.** opus sub-agent transcript (agentId a66d8a38203e4b087); ADR-0042; threat-model §5.1.

---

## D-3 (TECH) — PA-2021 independent verification: APPROVE-WITH-CHANGES, 2 hardening items folded in

**Question.** Does the PA-2021 implementation (ADR-0042) close the captured vulnerability without a
bypass or new attack surface, and is it non-vacuous?

**Method.** Implemented by a fresh `opus` data-integrity sub-agent (red-capture-first); independently
re-verified by a SECOND fresh `opus` agent (review-architect role) that did not write the code —
re-ran full suites from clean, reproduced both mutation-revert pairs itself, adversarially probed the
codec for a bypass, and screened for new attack surface (key logging, silent keyless-fallback under a
key, composition with RR-003/S4).

**Resolution (ratified — APPROVE).** No exploitable bypass; non-vacuous (real on-disk byte flips +
positive control); full suites green (common 106/106, consensus-core 334/334 [2 pre-existing skips],
server 140/140); no key material logged; co-location warning correct; install-snapshot proven to share
the local recovery path (`forgedInstalledSnapshotIsRefusedOnRecovery`). **Two NON-BLOCKING hardening
items folded in by the lead before commit** (each with a new locking test in `IntegrityEnvelopeTest`):
1. **Finding 3.1** — the `reserved` byte was CRC-covered but excluded from the MAC (latent
   malleability). Now folded into the MAC input (`reservedByteTamperUnderKeyedThrows`).
2. **Finding 4.1** — a buffer claiming our magic but below the envelope floor relied on an incidental
   downstream `BufferUnderflowException`. A keyed reader now refuses it with a deliberate
   `IntegrityException` (`magicMatchingSubFloorBufferUnderKeyedThrows`); keyless keeps absent-null
   semantics (no S4 regression — `structurallyShortReturnsNullNotThrow` still green).

**Evidence.** A-verify transcript (agentId a99e91825ca016103); `docs/session-7/captures/pa2021-prefix-failure.txt`; the two new regression tests.

---

## D-6 (TECH) — Audit log must be a KEYED HMAC chain, not a keyless hash chain

**Question.** Workstream D's first audit-log cut used a keyless SHA-256 hash chain. Is that
"tamper-evident" enough for charter §7 ("an attacker who can edit the audit log defeats it")?

**Resolution (lead-directed correctness fix).** No — a keyless hash chain is defeated by the
threat-model attacker **A2** (who can edit the log file): with no secret, they recompute every
`recordHash` and `verifyPersisted()` passes. Upgraded to a **keyed HMAC-SHA-256 chain**
(`recordHash = HMAC(K_audit, prevHash || canonical)`), `K_audit` HKDF-derived from the cluster signing
key with a **domain-separated** info string (`"configd/audit-log-integrity/v1"`, distinct from PA-2021's
raft key) — no new key material, directly realizing the charter's "ties to PA-2021's integrity theme."
Keyless mode retained for unit tests/back-compat (evidence-against-careless-edits only). Proven by
`AuditLogTest#keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey` (attacker re-chains the
WHOLE log without the key → still detected under the real key; same bytes verify true keyless —
demonstrating the gap the key closes); mutation-revert (ignore the key) turns exactly the two
key-distinguishing tests RED. Residual (honest fence): a T0 key-holder defeats it — same fence as
PA-2021 §5.1; S8 signing-key relocation covers `K_integrity` + `K_audit` together. Verified by lead:
control-plane-api 73/73, AuditLogTest 11/11, ConfigHandler{Auth,Replay,Audit}Test 15/4/2, server 156/156.

---

## D-5 (SCOPE) — Slowloris/FD-exhaustion (F-S7-FUZZ-1): document + S7.5, not fixed in S7

**Question.** Wire fuzzing (Workstream C) found a real availability gap: inbound sockets set no read
deadline + unbounded connections ⇒ a stalled peer holds a thread+FD indefinitely (slowloris → FD
exhaustion). Fix now, or document + flag?

**Resolution (conservative default).** **Document as F-S7-FUZZ-1 + S7.5 manifest; do NOT fix in S7.**
The fix (inbound idle/read deadline + connection cap) modifies the **RR-002-hardened** transport read
loop, and the charter demands red/green for any fix — but a faithful slow-drip repro is
integration-scale and flaky on the 2-vCPU box, so applying the fix here would risk an RR-002/liveness
regression *without* the proving test. The mechanism is pinned deterministically by
`InboundReadDeadlineFuzzTest`; the e2e repro + fix + red/green go to S7.5. Flagged as the
highest-priority availability residual in the handoff + pre-S8 summary. (The malformed-input/allocation
oracle IS closed this session — only the connection-count lever is deferred.) Logged per §1 scope rule.

---

## D-4 (SCOPE) — Workstreams B/C/D/E run sequentially, not in parallel

**Question.** Charter §12.3 suggests B/C/D/E proceed "in parallel across specialist agents." Do that,
or sequence them?

**Resolution (conservative default).** Run them as **sequential committed seams**. Two concrete
reasons: (1) the box is **2 vCPU** (documented env gotcha — "never run two heavy jobs at once");
four agents each running `./mvnw` build/test would thrash and risk flaky timeouts. (2) B, C, E and
the gate-7 assembly all touch `ci.yml` and/or root `pom.xml`; serializing avoids merge races. CI
wiring (gate-7, nightly fuzz lane, CVE/gitleaks lanes) is **centralized in Seam 6** — the workstream
agents produce tests/code + the lane snippet they need, and the lead assembles `ci.yml` once. Each
control still gets its negative test before its config is touched (§2.1). Logged per §1 scope rule.

---

## D-2 (SCOPE) — Session sequencing: A first and alone, then B/C/D/E

**Question.** The charter (§12) prescribes threat-model → Workstream A (PA-2021) with most care →
B/C/D/E in parallel → gate-7. Confirm the conservative sequencing given A touches the
consensus recovery path S2/S4 hardened.

**Resolution (conservative default).** A lands **alone** on the main tree (it is the highest-blast-
radius change; B/C/D/E build on a green A and gate-7 needs it), is second-agent-verified per the
project's established discipline, and the S4 durability cells are re-run green before A is committed.
B/C/D/E then proceed (delegated to specialist agents); each control gets its negative test written
*before* its config is touched (§2.1). Checkpoint at each clean committed seam; resume, never fake
(§12.5). Logged per §1 scope rule.
