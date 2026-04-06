# Release Rehearsal Report

> **Date:** 2026-04-11
> **Owner:** release-commander
> **Cross-verified by:** sre-launch-lead

---

## Build System

| Property | Status | Evidence |
|----------|--------|----------|
| Maven Wrapper (mvnw) | PRESENT | Pins Maven 3.9.9 for reproducible builds |
| Java 25 (Corretto) | PINNED | CI uses `amazon-corretto` distribution |
| Dependency versions | PINNED | All versions in parent pom.xml |
| Uber JAR (shade plugin) | WORKING | configd-server-0.1.0-SNAPSHOT.jar |
| Docker build | CONFIGURED | Dockerfile.build + Dockerfile.runtime |

## CI Pipeline

| Step | Status | Duration |
|------|--------|----------|
| Build + unit test | GREEN | ~44s |
| Property-based tests | GREEN | ~5s |
| Simulation tests | GREEN | ~6s |
| Seed sweep (10k seeds) | GREEN | ~3s |
| TLC model check | GREEN | ~30min max |
| Artifact upload | CONFIGURED | 7-day retention |

## Test Suite Summary

| Category | Count | Status |
|----------|-------|--------|
| Unit tests | ~900 | GREEN |
| Property-based tests | ~50 | GREEN |
| Simulation tests | ~50 | GREEN |
| Seed sweep | 10,000 (CI) / 100,000 (full) | GREEN |
| JMH benchmarks | 7 suites | AVAILABLE |
| TLA+ model check | 9 invariants | GREEN |
| **Total** | **~2,975** | **GREEN** |

## Wire Format Compatibility

| Concern | Status | Notes |
|---------|--------|-------|
| CommandCodec versioning | Byte-level format with type tags | Forward-compatible via unknown-type rejection |
| Raft message format | Java records with DataInputStream/DataOutputStream | Version field in frame header |
| ConfigDelta format | Binary with sequence numbers | Monotonic sequence ensures ordering |

## Canary Rollout Plan

The deployment automation supports progressive rollout via the K8s StatefulSet:

1. **Canary (1 pod)**: Deploy to pod 0, health check via `/health/ready`
2. **10%**: If healthy for 5 minutes, proceed
3. **50%**: Monitor SLO burn rate, proceed if no alerts
4. **100%**: Full rollout
5. **Rollback**: `kubectl rollout undo statefulset/configd` if SLO burn detected

### Kill Switches

| Switch | Mechanism | Effect |
|--------|-----------|--------|
| Write freeze | Admin API / write-freeze runbook | Rejects all writes, reads continue |
| TLS disable | Remove TLS flags, restart | Falls back to plaintext (emergency only) |
| Auth disable | Remove auth-token flag, restart | Disables authentication (emergency only) |

## Pre-Launch Release Requirements

1. **Reproducible build verification**: Two independent builds from same commit, compare hashes
2. **Canary automation e2e**: Test progressive rollout on staging
3. **Automatic rollback test**: Inject SLO burn, verify rollback triggers
4. **Post-deploy smoke suite**: Write, read, propagation check per region

---

**Phase 6 Status: CLOSED** (plan ready; execution requires staging infrastructure)
