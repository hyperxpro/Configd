# Session 7 Closeout — Security & Supply Chain

**Branch:** `session-7-security`. **Gate:** `gate-7` CI-wired (`needs: gate-6`, cumulative) — GREEN
locally (`docs/session-7/captures/gate-7-run.txt`). **Prime directive honored:** every security
control is proven by a passing negative test that performs the attack and asserts refusal — none by
configuration (charter §2.1). **PA-2021 is RESOLVED** (the lead P1, not handed forward again).

## 1. Claim → evidence (runnable; negative-test names)

Each row's command runs the attack and asserts it is refused. Build deps first:
`./mvnw -q -o -pl <module> -am install -DskipTests`.

| Control (attack → expected) | Command | Result |
|---|---|---|
| Tampered snapshot / CRC-recomputed / forged-version / downgrade / **forged install-snapshot** → refused | `./mvnw -o -pl configd-consensus-core test -Dtest=SnapshotIntegrityTest` | 6/6 |
| Tampered WAL record → refused; torn tail → tolerated | `./mvnw -o -pl configd-consensus-core test -Dtest=WalRecordIntegrityTest` | 2/2 |
| Forged `DurableRaftState` votedFor/term → refused | `./mvnw -o -pl configd-consensus-core test -Dtest=DurableRaftStateIntegrityTest` | 5/5 |
| Integrity codec: tamper/downgrade/version/truncation + HKDF RFC-5869 | `./mvnw -o -pl configd-common test -Dtest=IntegrityEnvelopeTest,HkdfTest` | 17/17 + 4/4 |
| PA-2021 composes — S4 durability cells still green | `./mvnw -o -pl configd-consensus-core test -Dtest=SnapshotCrashRecoveryTest,WalSyncCrashTest,VotePersistenceCrashTest,RaftLogUnitTest` | green |
| mTLS plaintext/expired/downgrade → refused (control plane) | `./mvnw -o -pl configd-transport test -Dtest=RaftTransportMtlsAttackTest` | 3/3 |
| mTLS plaintext/expired/downgrade → refused (data plane) | `./mvnw -o -pl configd-server test -Dtest=FanOutServerMtlsAttackTest` | 3/3 |
| mTLS wrong-SAN → refused by client | `./mvnw -o -pl configd-edge-node test -Dtest=EdgeTransportSanMismatchTest` | 1/1 |
| Wire fuzz: malformed/oversized/length-lie → bounded reject | `./mvnw -o -pl configd-transport test -Dtest=FrameCodecFuzzTest,InboundReadDeadlineFuzzTest` | 15/15 |
| Edge wire fuzz | `./mvnw -o -pl configd-distribution-service test -Dtest=EdgeFrameCodecFuzzTest` | 8/8 |
| API 401 (authn) vs 403 (authz); privilege escalation refused | `./mvnw -o -pl configd-server test -Dtest=ConfigHandlerAuthTest` | 15/15 |
| Verbatim replay → 409 | `./mvnw -o -pl configd-server test -Dtest=ConfigHandlerReplayTest` ; `… -pl configd-control-plane-api -Dtest=ReplayGuardTest` | 4/4 ; 9/9 |
| Audit: keyed-HMAC chain defeats a log editor; every mutating op recorded | `./mvnw -o -pl configd-control-plane-api test -Dtest=AuditLogTest` ; `… -pl configd-server -Dtest=ConfigHandlerAuditTest` | 11/11 ; 2/2 |
| Build reproducibility (config + byte-identical proof) | `grep project.build.outputTimestamp pom.xml` ; nightly `GATE7_FULL=1 bash gates/gate-7.sh` | set; 31 jars identical |
| SBOM present + no drift | `bash gates/gate-7.sh` SBOM step (regen + normalized-diff) | no drift |
| CVE scan (CVSS≥7 fails) | `NVD_API_KEY=… ./mvnw -Pcve-scan org.owasp:dependency-check-maven:aggregate -DfailBuildOnCVSS=7` | ENV-BLOCKED → CI nightly |
| Secret scan | `gitleaks detect --config .gitleaks.toml --redact` | ENV-BLOCKED → CI nightly |
| The whole bar, one command | `GATE7_SKIP_GATE6=1 bash gates/gate-7.sh` | **GREEN** |

## 2. Definition of Done (charter §11)

- [x] PA-2021 RESOLVED: snapshot+WAL+install-snapshot integrity-protected & versioned; tamper/forge/
      downgrade negatives green; composes with RR-003/torn-write (S4 cells re-green); crypto flagged;
      install-path-vs-S4-path question answered (same `RaftLog` code — `forgedInstalledSnapshotIsRefusedOnRecovery`).
- [x] mTLS adversarially verified on both planes; negatives green; cipher/version policy asserted.
- [x] Wire protocol fuzzed; ceilings enforced (tested); no crash/OOM/hang; corpus committed; nightly lane defined.
- [x] API authn/authz negatives green; privilege escalation refused; replay protection tested.
- [x] Audit log complete for all mutating ops; **keyed-HMAC** tamper-evidence confirmed.
- [x] SBOM committed; build reproducibility confirmed; runbooks don't echo creds; CVE+secret scans wired (ENV-BLOCKED locally → CI nightly, honestly).
- [x] gate-7 green in CI-wired form; nightly CVE+fuzz+secret lane defined.
- [x] Claim–evidence rows with commands (§1 above).
- [x] Decision Log (D-1…D-7).
- [x] Threat model (`threat-model.md`): assets, adversaries, trust boundaries, honest fence.
- [x] `handoff-to-session-8.md` + pre-S8 readiness summary (posture, residuals, S7.5 manifest, crypto-review flag).

## 3. Honest fence (what S7 did NOT prove — for S8 go/no-go)
- Crypto constructions **flagged for specialist review** (not self-certified).
- D-1 BLOCKER: signing-key default co-located — at-rest controls hold vs the T3/A2 storage writer, not
  full-host compromise; relocation + fail-closed default → S8.
- F-S7-FUZZ-1 slowloris (HIGH avail), F-S7-TLS-1 leaf-as-anchor expiry, F-S7-TLS-2 edge `/metrics`,
  replay-passive-only, global rate limiter, revocation, digest-pinned base images → S7.5/S8 (handoff §4–5).

## 5. Review-architect sign-off (independent, charter §3/§12.1)

A fresh `review-architect` (did not implement any S7 work) gave the final sign-off — **APPROVE, no
blocking findings** — after reviewing the committed session, running gate-7, and empirically testing
its non-vacuity. Confirmed: (1) **scope fence SIGNED** — honest and complete, the three found-but-
deferred gaps (F-S7-FUZZ-1/TLS-1/TLS-2) are surfaced not buried; (2) **no new attack surface** — the
new `IntegrityEnvelope`/`Hkdf`/`AuditLog`/`ReplayGuard` are bounded (anti-DoS), constant-time, and log
no key/credential (grepped); the 401-vs-403 split adds no bypass; (3) **crypto flag complete** —
nothing primitive self-certified; (4) **claim-evidence non-vacuous** — spot-checked
`forgedInstalledSnapshotIsRefusedOnRecovery`, `keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey`,
and the WAL torn-vs-tamper cells as real attacks; ENV-BLOCKED items honestly marked; (5) **gate-7 real**
— ran it (GREEN banner), and verified `assert_class_green` fails on rename/substring-collision/0-test/
real-failure; (6) **immutability holds** — scoped to the S7 commit range (`8b457fe..HEAD`), zero
`docs/session-1..6/`, `gates/gate-[1-6].sh`, or ADR-0001..0041 files modified; only the shared CI files
(ci.yml gate-7 job, release.yml SBOM fix) and S7 code, with the `ConfigClient` 401 change confirmed as
necessary, correct collateral (a 401'd write must be a definite rejection, not INDETERMINATE, for the
linz oracle). Two non-blocking housekeeping items (threat-model header flip — done; immutability-vs-
session-range note — added to the handoff) applied. Full verdict: agent `RA-signoff`.

## 4. Pointers
Threat model + ledger: `threat-model.md`. Decisions: `decision-log.md`. Per-workstream:
`transport-security.md`, `wire-fuzzing.md`, `api-security.md`, `supply-chain.md`. Construction:
`docs/decisions/adr-0042-*`. SBOM: `sbom/bom.json`. Gate capture: `captures/gate-7-run.txt`.
Handoff + pre-S8: `handoff-to-session-8.md`.
