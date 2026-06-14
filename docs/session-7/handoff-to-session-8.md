# Session 7 → Session 8 Handoff + Pre-S8 Readiness Summary (Security)

> Session 7 made Configd **adversarially verified**. Every security control the prior sessions had
> *configured* is now proven by a **negative test that performs the attack and asserts it is refused**
> (charter §2.1). `gate-7` is CI-wired (`needs: gate-6`, cumulative) and locks the security bar.
> This handoff is for S8's **go/no-go**: it states the posture, the residual + accepted risks, the
> S7.5 infra-manifest additions, and the **crypto-review flag** S8 must weigh.

## 1. Security posture — what is now PROVEN (each by a named passing negative test)

See `threat-model.md` §6 for the full claim→test ledger. Summary:

| Area | Proven (attack → refused) | Lead item |
|---|---|---|
| **PA-2021 at-rest integrity** (ADR-0042) | tampered snapshot byte / CRC-recomputed (only HMAC catches) / tampered WAL record / forged format-version / algId=NONE downgrade / forged `DurableRaftState` votedFor / **forged install-snapshot** → all refused. Torn WAL tail still tolerated (not mistaken for tamper). S4 durability cells re-green. | **RESOLVED** |
| **mTLS** (both planes) | plaintext / expired-cert / wrong-SAN / TLSv1.2-downgrade → refused on control-plane Raft AND data-plane edge fan-out | verified |
| **Wire protocol** | arbitrary/malformed/oversized/length-lie → bounded reject, no crash/OOM/unbounded-alloc/hang; ceiling enforced reject-before-alloc | verified |
| **API authn/authz** | unauthenticated mutating call → 401; read-scoped → write → 403; cross-prefix → 403 | verified |
| **Replay** | verbatim captured request replayed → 409 (nonce+window) | verified (passive-only) |
| **Audit log** | every mutating op + auth failure recorded; **keyed-HMAC** chain defeats an attacker who edits *and re-chains* the whole log | verified |
| **Supply chain** | build reproducibility (31 byte-identical jars); CycloneDX SBOM committed + gate-diffed; runbooks don't echo creds | verified |

PA-2021 is **RESOLVED** (not handed forward again, per charter §10.2): snapshot + WAL +
`DurableRaftState` + install-snapshot are integrity-protected (HMAC-SHA-256 keyed via HKDF from the
cluster signing key) and versioned; composes with RR-003 durable-prefix and S4 torn-write.

## 2. Crypto-review flag for S8 go/no-go (charter §2.5/§10.4 — NOT self-certified)

The PA-2021 + audit-log constructions are **flagged for specialist cryptographic review** before
production. We verified *behavior* (tamper → refused) by negative tests; we did **not** certify the
primitives. The specialist must review (ADR-0042 §Consequences, decision-log D-1 #5):
1. Using the Ed25519 private-key encoding as HKDF IKM (soundness of that IKM choice).
2. MAC message canonicalization (fixed-width header; HMAC has no length-extension exposure).
3. Downgrade-policy completeness; constant-time comparison.
4. **Per-record WAL MAC authenticates each record but not the SET** — a tail-truncation residual
   (mitigated by snapshot boundary + commit index; a whole-log anchor is flagged).
5. CRC32C-vs-CRC32 choice.
**S8 must weigh this flag in the production go/no-go.**

## 3. The D-1 BLOCKER — signing-key co-location (S8 must close)

`K_integrity` and `K_audit` are HKDF-derived from the **cluster signing key**. Their secrecy depends
on that key living **outside** attacker-writable snapshot/WAL/backup storage. Production Compose already
mounts it separately (`/secrets/signing-key.bin:ro`), **but** the insecure *default* co-locates it
(`dataDir.resolve("signing-key.bin")`, `ConfigdServer.java`). S7 emits a **loud startup warning** when
co-located; **relocating the default + flipping the at-rest posture to fail-closed is an S8 go/no-go
item** (decision-log D-1, D-6). Until then, the at-rest controls hold against the **T3 storage/backup
writer (A2)** but NOT a full-host compromise (the honest fence, threat-model §5.1).

## 4. Residual + accepted risks (with disposition)

| ID | Risk | Severity | Disposition |
|---|---|---|---|
| **F-S7-FUZZ-1** | No inbound read deadline + unbounded connections → slowloris/FD exhaustion DoS | **HIGH (avail)** | **highest-priority residual.** Mechanism pinned (`InboundReadDeadlineFuzzTest`); fix (inbound idle deadline + connection cap) touches RR-002 transport and needs an integration-scale red/green → **S7.5** (D-5) |
| F-S7-TLS-1 | Self-signed-leaf-as-trust-anchor does not enforce cert expiry (RFC 5280 §6.1) | Med | S7.5 — real CA or custom TrustManager expiry check; compensating control = `-validity 30` short-lived certs |
| F-S7-TLS-2 | Edge `/metrics` plaintext/no-auth/wildcard bind (recon) | Low | S7.5 — network segmentation (preferred, code-free) or F-0055-style scrape auth |
| F-S7-FUZZ-2 | "1 MB ceiling" is layered (config-value 1 MiB / Raft frame 16 MiB) — charter wording | Doc | S8 wording/constant decision |
| — | Replay guard is **passive-only** (a token-holder can mint fresh requests) | Med | S8 — recommend per-request content signing (SigV4-style HMAC) |
| — | Audit log cross-segment hash-chain continuity across rotation | Low | S7.5 (within-segment fully verifiable today) |
| — | Rate limiter is **global**, not per-principal (handoff S6 §1.2) | Med | S7.5/S8 |
| — | Cert revocation (CRL/OCSP) — not in the self-signed model | Med | S7.5 |
| — | Docker base images use floating `eclipse-temurin:25-*-noble` tags (not digest-pinned) | Med | S8 (known gap) |

## 5. Items added to the S7.5 infra manifest (real-network / specialist tooling)

1. **CVE scan** — needs `NVD_API_KEY` CI secret + a persistent dependency-check data-dir cache +
   outbound HTTPS to NVD. `-Pcve-scan` profile is wired (fails on CVSS≥7); gate-7 nightly runs it.
2. **Secret scan** — needs the `gitleaks` binary / `gitleaks-action` (gate-7 nightly installs it).
3. **Slowloris/FD-exhaustion** — end-to-end mTLS slow-drip repro + the inbound-read-deadline fix with
   red/green (F-S7-FUZZ-1); connection-flood scale test.
4. **mTLS** — expired-cert under the production leaf-as-anchor model (F-S7-TLS-1); revocation testing.
5. **Real-network adversary** — distributed connection floods, cross-region MITM (charter §5 fence).

## 6. What S8 inherits green

- `gate-7` CI-wired (`needs: gate-6`, cumulative); green locally (capture:
  `docs/session-7/captures/gate-7-run.txt`) with the ENV-BLOCKED steps (CVE NVD, gitleaks, full
  byte-repro) running on the nightly path. It locks the security bar so S8 cannot silently erode it.
- New session docs under `docs/session-7/` (immutable per charter §10.7): `threat-model.md`,
  `decision-log.md` (D-1…D-6), `transport-security.md`, `wire-fuzzing.md`, `api-security.md`,
  `supply-chain.md`, this handoff. ADR-0042. SBOM at `docs/session-7/sbom/bom.json`.
- Carry-forwards from S6 still open (RR-108 contract-anchor refresh, RR-105 stale-owner re-triage,
  RR-112 box-local 24 h soak, the M-1…M-10 campaign) — unchanged by S7.

## 7. S8 (final session) go/no-go checklist seeded by S7
- [ ] Close the D-1 BLOCKER: relocate the signing-key default outside the data dir; flip the at-rest
      posture to fail-closed by default.
- [ ] Weigh the **crypto-review flag** (§2) — obtain specialist sign-off on the PA-2021/audit constructions.
- [ ] Fix F-S7-FUZZ-1 (slowloris) with the S7.5 e2e red/green — the highest-priority availability residual.
- [ ] Decide replay content-signing (§4) and the 1 MiB/16 MiB ceiling wording (F-S7-FUZZ-2).
- [ ] Confirm the gate-7 nightly CVE + gitleaks lanes ran clean once `NVD_API_KEY` is provisioned.
- [ ] Digest-pin the container base images.
