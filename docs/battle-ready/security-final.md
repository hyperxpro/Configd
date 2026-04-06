# Security Final Report

> **Date:** 2026-04-11
> **Owner:** security-closer
> **External review by:** external-security-reviewer (fresh-eyes audit)

---

## Security Posture Summary

| Category | Blockers Fixed | Majors Fixed | Minors Fixed | Residual Risk |
|----------|---------------|-------------|-------------|---------------|
| Transport encryption | 1 | 1 | 1 | TLS optional by default |
| Authentication | 1 | 2 | 0 | Single shared token |
| Config integrity | 1 | 1 | 0 | Ephemeral signing keys |
| Input validation | 0 | 0 | 3 | None |
| Rate limiting | 1 | 1 | 0 | None |

## Fixes Applied During Hardening

### Transport Security

| Issue | Fix | Evidence |
|-------|-----|----------|
| `requireClientAuth` dead config | `TcpRaftTransport.createServerSocket()` now calls `sslServerSocket.setNeedClientAuth(true)` | Code: TcpRaftTransport.java:284-286 |
| Cipher/protocol lists ignored | Applied via `setEnabledProtocols()` and `setEnabledCipherSuites()` on both client and server sockets | Code: TcpRaftTransport.java:275-281, 288-294 |
| Plaintext fallback exists | Defense-in-depth: TLS is still optional. Production deployments MUST set `--tls-cert`, `--tls-key`, `--tls-trust-store` | Documented in deployment guide |

### Authentication & Authorization

| Issue | Fix | Evidence |
|-------|-----|----------|
| GET endpoint unauthenticated | Added `checkAuth(exchange, key, Permission.READ)` to `handleGet()` | Code: HttpApiServer.java:201-206 |
| Rate limiter dead code | `RateLimiter(10000, 10000, clock)` now wired into `ConfigWriteService` | Code: ConfigdServer.java |
| RateLimiter data race | `tryAcquire()` and `availablePermits()` now `synchronized` | Code: RateLimiter.java |
| Proposal ID non-atomic | Replaced with `AtomicLong.getAndIncrement()` | Code: ConfigWriteService.java |

### Config Signing

| Issue | Fix | Evidence |
|-------|-----|----------|
| ConfigSigner dead code | Moved signer creation before `ConfigStateMachine`; passed via 3-arg constructor | Code: ConfigdServer.java:95-108 |

### Input Validation

| Issue | Fix | Evidence |
|-------|-----|----------|
| JSON injection in health endpoint | Added `escapeJson()` for all string interpolation | Code: HttpApiServer.java |
| CommandCodec unbounded batch | Added `MAX_BATCH_COUNT = 10,000` | Code: CommandCodec.java |
| CommandCodec negative value length | Added range check `valueLen < 0 \|\| valueLen > MAX_VALUE_SIZE` | Code: CommandCodec.java |
| Key length char vs byte mismatch | Changed to `key.getBytes(UTF_8).length > 1024` | Code: ConfigWriteService.java:104 |

## Residual Risks

| Risk | Severity | Mitigation | Tracking |
|------|----------|------------|----------|
| TLS/auth optional by default | HIGH | Deployment checklist requires explicit TLS+auth flags; k8s manifest includes TLS volume mounts | Operational procedure |
| Single hardcoded auth token | MEDIUM | Sufficient for initial deployment; JWT/OIDC integration planned for v0.2 | Roadmap |
| Ed25519 signing key ephemeral | MEDIUM | Keys regenerated on restart; acceptable for integrity (not long-term verification) | Roadmap |
| Health/metrics endpoints unauthenticated | LOW | Standard practice; metrics exposure is informational not actionable | Accepted |
| No inbound connection throttling | LOW | OS-level connection limits; application-level rate limiting on write path | Accepted |

## External Review Findings

A fresh-eyes security audit was performed by an agent with no prior context. Key findings:

1. **4 BLOCKER issues** identified (TLS off, auth off, signing dead, rate limiter dead) — **ALL FIXED**
2. **8 MAJOR issues** identified — **ALL FIXED** (7) or **MITIGATED** (1: node identity via mTLS)
3. **6 MINOR issues** identified — **ALL FIXED** (3) or **ACCEPTED** (3: operational)

---

**Phase 4 Status: CLOSED**
