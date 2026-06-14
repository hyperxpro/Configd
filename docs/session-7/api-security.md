# Session 7 — Control-Plane API Security: authn/authz, audit, replay (Workstream D)

> **Prime directive (charter §2.1):** every control below is backed by a passing negative test that
> performs the attack and asserts rejection. The mutating HTTP surface is **PUT/DELETE on
> `/v1/config/{key}`** (there is no HTTP membership/restore endpoint — membership = Raft
> `proposeConfigChange`, restore = `ops/scripts/restore-snapshot.sh`; their authz lives at the
> Raft/CLI layer, not invented here).

## 1. Authentication: 401 vs 403 (corrected semantics)

Previously an unauthenticated mutating call returned **403**. Per RFC 7235 + charter §7 this is now
**401** (with `WWW-Authenticate: Bearer`) for missing/invalid credentials, and **403** only for an
authenticated-but-unauthorized caller. `checkAuth` returns a typed `AuthCheck` (OK / UNAUTHENTICATED /
FORBIDDEN); the handler maps each correctly. Red/green: the old `403` assertion was re-instated against
the new code and observed RED (`expected:<403> but was:<401>`), then corrected.

Negative tests (`ConfigHandlerAuthTest`, 15): PUT/DELETE no-token → 401; malformed `Authorization` →
401; bad token → 401; reader-scoped PUT/DELETE → 403; out-of-prefix valid token → 403; writer
PUT/DELETE → 2xx (positive control). `ConfigClient` updated to map 401 → definite-non-commit FAIL.

## 2. Authorization (privilege escalation)

`AclService` (READ/WRITE/ADMIN, per-key-prefix, longest-match). Negative tests cover read-scoped →
write → 403, read-scoped → delete → 403, and cross-prefix (writer → ungranted prefix) → 403.

## 3. Audit log — tamper-evident via a **keyed HMAC chain** (`AuditLog`)

Every security-relevant control-plane event (each PUT/DELETE attempt and each 401/403 failure) is
recorded with {timestampMs, actor principal (or `-`), action, target key, outcome}, appended durably
(`Storage.appendToLog` = append + CRC32) and **never** logging the credential (principal only —
asserted by test).

**Tamper-evidence is a keyed HMAC-SHA-256 chain** (not a keyless hash):
`recordHash = HMAC-SHA256(K_audit, prevHash || canonicalBytes)`, canonical form length-prefixed
per-field (no field-boundary forgery), constant-time compare on verify. `K_audit` is HKDF-derived from
the cluster signing key with a **domain-separated** info string (`"configd/audit-log-integrity/v1"`,
distinct from PA-2021's raft key) — so no new key material, ties directly to PA-2021's integrity theme.

**Why keyed, not a plain hash chain (the charter §7 bar):** a *keyless* SHA-256 chain is defeated by
the threat-model attacker **A2** who can edit the log file — they simply recompute the whole chain
(SHA needs no secret). The keyed HMAC means an editor who lacks `K_audit` cannot produce a valid
chain. Proven by `AuditLogTest#keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey`: an
attacker edits a record and re-chains the **entire** persisted log without the key →
`verifyPersisted()` under the real key still reports broken; the **same** attacker bytes verify *true*
under a keyless function — explicitly demonstrating the gap the key closes. Mutation-revert (make
`chainHash` ignore the key) turns exactly the two key-distinguishing tests RED.

**Bounded (anti-DoS):** in-memory cap `DEFAULT_MAX_RECORDS=100_000` with on-disk rotation anchored at
the retained head's `prevHash` (within-segment chain fully verifiable; cross-segment continuity → S7.5).

## 4. Replay protection (`ReplayGuard`)

Opt-in (default off, pre-production). Client sends `X-Configd-Timestamp` + `X-Configd-Nonce`; the
server rejects: timestamp outside ±300 s → 401; a nonce seen within the window → 409; missing/malformed
header → 401. Bounded seen-nonce store (TTL eviction + LRU cap `1_000_000`) — not itself a memory-DoS
lever. Negative test (`ConfigHandlerReplayTest`): a valid PUT captured and replayed **verbatim** → 409;
a fresh-nonce PUT → 200; stale-timestamp → 401. Mutation-revert (disable the seen-nonce check) makes
the verbatim replay return 200 (RED).

**Honest trust-model limit:** this defends against **passive capture-and-replay**. It does NOT stop a
holder of the bearer token from minting fresh requests (that is the token's trust model). Stronger
per-request content signing (SigV4-style HMAC over method+path+body+ts+nonce) is **recommended for
S8**, not built here.

## 5. Residual / honest fence
- Audit + replay keys are HKDF-derived from the **co-located** signing key → an attacker with the
  cluster key (full-host / T0 compromise) defeats both — the same fence as PA-2021 §5.1; the S8
  signing-key-relocation closes `K_integrity` and `K_audit` together.
- Replay is passive-only (above) → S8 content-signing recommendation.
- Audit cross-segment hash-chain continuity across rotation → S7.5.
- Per-principal rate limiting (handoff §1.2: the limiter is currently global) → S7.5/S8.

## 6. Gate-7 API-security step (Seam 6)
`configd-control-plane-api`: `AuditLogTest`, `ReplayGuardTest`.
`configd-server`: `ConfigHandlerAuthTest`, `ConfigHandlerReplayTest`, `ConfigHandlerAuditTest`.
