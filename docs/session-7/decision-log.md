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
