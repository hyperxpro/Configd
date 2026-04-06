# Configd Security Audit -- Production Readiness Review

**Date:** 2026-04-11
**Auditor:** Security Auditor (PRR)
**Scope:** Full codebase audit of `io.configd` -- all 10 Maven modules
**Verdict:** **NOT READY FOR PRODUCTION** -- 5 blockers identified

---

## Executive Summary

Configd is a well-engineered distributed configuration system with strong correctness foundations (TLA+ model-checked consensus, extensive property-based tests, runtime invariant monitoring). However, it is **missing the entire security layer**. There is no transport encryption, no authentication, no authorization, no config signing, and no audit logging. The system is currently a library-level implementation of Raft consensus and HAMT-based storage with zero security controls.

The codebase has zero instances of TLS, mTLS, certificate handling, HMAC, digital signature, encryption, or any authentication/authorization framework. This is not a case of partial implementation -- security has not been started.

| Severity | Count |
|----------|-------|
| Blocker  | 5     |
| Major    | 6     |
| Minor    | 5     |

---

## 1. Transport Security (mTLS)

### BLOCKER-1: No transport encryption whatsoever

**Files examined:**
- `configd-transport/src/main/java/io/configd/transport/RaftTransport.java`
- `configd-transport/src/main/java/io/configd/transport/ConnectionManager.java`
- `configd-transport/src/main/java/io/configd/transport/FrameCodec.java`
- `configd-transport/src/main/java/io/configd/transport/MessageRouter.java`
- `configd-transport/src/main/java/io/configd/transport/BatchEncoder.java`

**Finding:** The transport layer is entirely unencrypted. `RaftTransport` is an interface that accepts `Object message` and sends to a `NodeId` target. `FrameCodec` defines a raw binary wire format with no encryption envelope, no MAC, and no authentication header. The frame layout is: `[Length: 4 bytes][Type: 1 byte][GroupId: 4 bytes][Term: 8 bytes][Payload: variable]` -- all plaintext.

There is no TLS, no SslContext, no SslHandler, no certificate loading code, no cipher suite configuration, and no reference to `javax.net.ssl` or Netty SSL anywhere in the codebase. A `grep -ri` for `tls`, `ssl`, `certificate`, `SslContext`, `SslHandler`, `mTLS`, or `mutual.?tls` across all `.java` files returns zero results.

**Impact:** All Raft consensus traffic (votes, log entries, snapshots), Plumtree gossip, HyParView membership messages, and config data flow in plaintext. An attacker on the network can:
- Read all configuration values in transit
- Inject forged Raft messages to corrupt consensus
- Inject forged AppendEntries to overwrite config data
- Impersonate any node via NodeId spoofing

**Remediation:**
1. Implement mTLS for all inter-node communication (control plane)
2. Implement TLS for edge-to-control-plane communication (data plane)
3. Require cert-based node identity verification (NodeId must be bound to certificate CN/SAN)
4. Disable all plaintext fallback paths
5. Support cert rotation without restart

### BLOCKER-2: No node identity verification

**Files examined:**
- `configd-common/src/main/java/io/configd/common/NodeId.java`
- `configd-transport/src/main/java/io/configd/transport/MessageRouter.java`

**Finding:** `NodeId` is a simple string wrapper used as the sole identity for all nodes in the cluster. There is no cryptographic binding between a NodeId and the network connection presenting it. The `MessageRouter` accepts messages from any `NodeId from` parameter with no verification that the sending connection actually belongs to that node.

**Impact:** Any entity that can reach the network port can claim to be any NodeId and participate in Raft elections, log replication, and config distribution. This enables trivial cluster takeover.

---

## 2. Authorization (AuthZ)

### BLOCKER-3: No authentication or authorization on any API

**Files examined:**
- `configd-control-plane-api/src/main/java/io/configd/api/ConfigWriteService.java`
- `configd-control-plane-api/src/main/java/io/configd/api/ConfigReadService.java`
- `configd-control-plane-api/src/main/java/io/configd/api/AdminService.java`
- `configd-control-plane-api/src/main/java/io/configd/api/RateLimiter.java`

**Finding:** None of the API services have any authentication or authorization checks. The `ConfigWriteService.put()` method accepts key, value, and scope and proposes directly to Raft -- there is no caller identity, no session token, no ACL check. The `AdminService` allows `addNode`, `removeNode`, and `transferLeadership` with no access control beyond checking if the local node is the leader. The comment in `ConfigWriteService` at line 14 mentions "Validate the request (key, value, ACL)" but no ACL validation is implemented.

There is no:
- Authentication middleware or interceptor
- Principal/identity extraction
- Role-based access control (RBAC)
- Key-level or prefix-level ACLs
- Audit log of who changed what

The `RateLimiter` is a single global token bucket -- not per-tenant. There is no tenant identity concept.

**Impact:** Any entity that can reach the API can write, delete, or read any configuration value. Admin operations (membership changes, leadership transfer) are unprotected. A rogue edge node or compromised client can overwrite critical production config.

**Remediation:**
1. Implement authentication (mTLS client certs or signed tokens)
2. Implement RBAC with least-privilege enforcement: separate read, write, and admin roles
3. Implement per-key-prefix ACLs
4. Add mandatory audit logging for all write and admin operations
5. Make the `RateLimiter` per-tenant, not global

---

## 3. Config Signing

### BLOCKER-4: No config signing or integrity verification

**Files examined:**
- `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`
- `configd-config-store/src/main/java/io/configd/store/CommandCodec.java`
- `configd-config-store/src/main/java/io/configd/store/ConfigDelta.java`
- `configd-config-store/src/main/java/io/configd/store/ConfigSnapshot.java`
- `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java`
- `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java`

**Finding:** No config values are signed at any point in the pipeline. The `CommandCodec` encodes commands as raw `[type][key][value]` with no HMAC, no digital signature, and no integrity hash. `ConfigDelta` carries `fromVersion`, `toVersion`, and `mutations` -- no signature field. `ConfigSnapshot` carries `data`, `version`, and `timestamp` -- no signature field.

Edges apply deltas blindly via `DeltaApplier.offer()` and `LocalConfigStore.applyDelta()`. There is no signature verification before applying a delta to the edge store. A `grep` for `sign`, `signature`, `verify`, `hmac`, `digest`, `SHA`, `encrypt`, `decrypt`, `cipher` across all `.java` files returns zero security-related results (only hash-code methods and comment-level matches).

**Impact:** A man-in-the-middle or compromised distribution node can inject arbitrary config values to edge nodes. There is no way for an edge to verify that a config delta actually originated from a committed Raft entry.

**Remediation:**
1. Sign every committed config entry at the Raft leader using an asymmetric key (Ed25519 or ECDSA)
2. Include the signature in `ConfigDelta` and `ConfigSnapshot`
3. Verify signatures at every edge node before applying deltas
4. Implement key rotation for signing keys
5. Expose the current signing key fingerprint as a health check

---

## 4. Replay Protection

### MAJOR-1: No cryptographic replay protection on fan-out channel

**Files examined:**
- `configd-distribution-service/src/main/java/io/configd/distribution/FanOutBuffer.java`
- `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java`
- `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java`
- `configd-edge-cache/src/main/java/io/configd/edge/VersionCursor.java`

**Finding:** The system uses monotonic version numbers for ordering (`ConfigDelta.fromVersion`/`toVersion`, `VersionCursor`) but has no cryptographic nonce or signed sequence counter on the fan-out channel. The `DeltaApplier` rejects stale deltas (where `toVersion <= currentVersion`) and detects gaps (where `fromVersion != currentVersion`), which provides some replay protection at the application level.

However, since deltas are unsigned (see BLOCKER-4), an attacker can:
1. Capture a valid delta sequence
2. Wait for the edge to restart (version resets)
3. Replay old deltas to the edge, effectively reverting config to an older state

The `PlumtreeNode` uses `MessageId(version, timestamp)` for deduplication, which prevents duplicate delivery within a session, but not across edge restarts.

**Impact:** Old config versions can be replayed to edges after restart. Combined with the lack of signing, this is a practical attack vector for reverting security-critical config changes.

**Remediation:**
1. Sign deltas (covered by BLOCKER-4)
2. Include a monotonic nonce bound to the sender's epoch in each delta
3. Persist the edge's last-applied version + epoch to stable storage so it survives restarts

### MAJOR-2: Plumtree gossip has no sender authentication

**Files examined:**
- `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java`
- `configd-distribution-service/src/main/java/io/configd/distribution/HyParViewOverlay.java`

**Finding:** Plumtree accepts `EagerPush`, `IHave`, `Prune`, and `Graft` messages from any `NodeId from` with no verification. An attacker can send a `Prune` message to degrade the spanning tree, send forged `EagerPush` to inject data, or send `Graft` to force themselves into the eager peer set. HyParView similarly accepts `Join`, `ForwardJoin`, `Shuffle`, `Disconnect`, and `Neighbor` messages without authentication.

**Impact:** An unauthenticated entity can join the overlay network, inject gossip messages, and disrupt config distribution.

---

## 5. Supply Chain

### MAJOR-3: No SBOM generation

**Files examined:**
- `pom.xml` (root and all module POMs)

**Finding:** There is no CycloneDX, SPDX, or OWASP dependency-check plugin configured. A `grep` for `sbom`, `cyclonedx`, `spdx`, and `owasp` across the entire repository returns zero results. No Software Bill of Materials is generated as part of the build.

**Impact:** Cannot systematically track or audit transitive dependencies for vulnerabilities.

**Remediation:** Add `cyclonedx-maven-plugin` to generate SBOM on every build.

### MINOR-1: Dependency versions are pinned (good) but not verified

**Finding:** All dependency versions are pinned in the root POM's `<dependencyManagement>` section (no version ranges):
- `org.agrona:agrona:1.23.1`
- `org.jctools:jctools-core:4.0.5`
- `org.junit.jupiter:junit-jupiter:5.11.4`
- `org.openjdk.jmh:jmh-core:1.37`
- `org.hdrhistogram:HdrHistogram:2.2.2`
- `io.micrometer:micrometer-core:1.14.4`
- `net.jqwik:jqwik:1.9.2`

All are test or observability dependencies -- no known critical CVEs in these specific versions as of the audit date. However, there is no hash verification (`<checksum>` enforcement), no Maven Wrapper lock file, and no reproducible build verification.

### MINOR-2: Docker images use `apt-get install maven` without pinning

**Files examined:**
- `docker/Dockerfile.build`
- `docker/Dockerfile.runtime`

**Finding:** Both Dockerfiles install Maven via `apt-get install -y maven` without pinning the Maven version. The base image `eclipse-temurin:25-jdk-noble` is tagged but not pinned to a specific digest. This creates a non-reproducible build that could pull different Maven versions on different build runs.

**Remediation:** Pin Maven version and base image digests. Use `maven:3.x.y-eclipse-temurin-25` as a more controlled alternative.

---

## 6. Secret Handling

### MAJOR-4: Config values stored as plaintext bytes with no encryption at rest

**Files examined:**
- `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java`
- `configd-config-store/src/main/java/io/configd/store/VersionedValue.java`
- `configd-config-store/src/main/java/io/configd/store/HamtMap.java`
- `configd-config-store/src/main/java/io/configd/store/ConfigSnapshot.java`
- `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java`

**Finding:** All config values are stored as raw `byte[]` in `VersionedValue` records within the HAMT data structure. There is no encryption-at-rest layer. Since this is a configuration management system, it will inevitably store sensitive values (database credentials, API keys, encryption keys, service tokens). These values exist in plaintext in:
1. The Raft log (in-memory and any future WAL)
2. The HAMT store on every replica
3. Every edge node's local store
4. Snapshots transferred over the network

**Impact:** Any memory dump, heap dump, or snapshot capture exposes all configuration secrets.

**Remediation:**
1. Implement envelope encryption for values marked as sensitive
2. Support a `SecretValue` type that stores encrypted bytes and decrypts only at the edge on read
3. Redact secret values from debug/diagnostic outputs

### MINOR-3: No secrets found in source code or test fixtures

**Finding:** A grep for `password`, `secret`, `token`, `apikey`, `api_key`, `credential`, and `private.?key` across all `.java` files found no hardcoded secrets. The only matches are:
- JMH Blackhole constructor strings (auto-generated, harmless: "Today's password is swordfish" -- this is a standard JMH placeholder)
- A test that uses `"session.token"` as a key name, with values `"abc123"` and `"def456"` (test data, not real credentials)

No `.env` files, no `.yaml`/`.yml` configuration files, and no `.properties` files with secrets were found.

### MINOR-4: No logging framework in production code

**Finding:** The production source code contains zero references to any logging framework (`java.util.logging`, SLF4J, Log4j, etc.). There are no `System.out.println` or `System.err.println` calls in production code. This means there is currently zero risk of secrets being leaked to logs, but it also means there is no operational logging at all, which is a separate operational concern.

---

## 7. DoS / Abuse Protection

### MAJOR-5: No per-tenant rate limiting

**Files examined:**
- `configd-control-plane-api/src/main/java/io/configd/api/RateLimiter.java`
- `configd-control-plane-api/src/main/java/io/configd/api/ConfigWriteService.java`

**Finding:** The `RateLimiter` is a single global token bucket with `permitsPerSecond` and `burstPermits`. It is not tenant-aware -- there is no concept of tenant identity, and all writes share a single rate limit. The `ConfigWriteService` checks `rateLimiter.tryAcquire()` without any caller identity. A single aggressive client can consume the entire write budget.

Additionally, the `RateLimiter` may be null: `ConfigWriteService` accepts `null` for the rate limiter parameter, in which case rate limiting is completely disabled.

**Remediation:**
1. Make rate limiting mandatory (not nullable)
2. Implement per-tenant or per-client rate limiting
3. Add per-tenant quota enforcement

### MAJOR-6: No value size limit enforcement

**Files examined:**
- `configd-config-store/src/main/java/io/configd/store/CommandCodec.java`
- `configd-control-plane-api/src/main/java/io/configd/api/ConfigWriteService.java`
- `configd-consensus-core/src/main/java/io/configd/raft/RaftConfig.java`

**Finding:** The `CommandCodec.encodePut()` method uses a 4-byte integer for value length, allowing values up to 2 GB. There is no maximum value size enforcement in `ConfigWriteService.put()`, `CommandCodec`, or `ConfigValidator`. Key length uses a 2-byte unsigned short (max 65535 bytes), also without enforcement at the API layer.

The Raft layer has `maxBatchBytes` (256 KB default) which limits the total payload per AppendEntries batch, and `maxPendingProposals` (1024) which provides backpressure. However, a single proposal with a 2 GB value would pass write validation and be proposed to Raft.

The `ConfigWriteService` only validates that the key is non-blank -- there is no size check on key or value.

**Impact:** A malicious or buggy client can:
1. Propose an arbitrarily large value, causing OOM on all replicas during replication
2. Fill the Raft log with large entries, exhausting disk and memory
3. Cause OOM during snapshot serialization (all values are serialized into a single `byte[]`)

**Remediation:**
1. Enforce maximum key length (e.g., 1024 bytes) and maximum value size (e.g., 1 MB) at the API layer
2. Reject oversized proposals in `ConfigWriteService` before they reach Raft
3. Add a total store size limit with appropriate error responses

### MINOR-5: Fan-out amplification is partially bounded

**Files examined:**
- `configd-distribution-service/src/main/java/io/configd/distribution/SlowConsumerPolicy.java`
- `configd-distribution-service/src/main/java/io/configd/distribution/FanOutBuffer.java`
- `configd-distribution-service/src/main/java/io/configd/distribution/WatchCoalescer.java`

**Finding:** The system has several good defenses against fan-out amplification:
- `SlowConsumerPolicy` disconnects and quarantines slow consumers (HEALTHY -> SLOW -> DISCONNECTED -> QUARANTINED)
- `FanOutBuffer` has a bounded `maxEntries` capacity
- `WatchCoalescer` batches rapid mutations (10ms window, 64 max batch) before dispatching
- Raft `maxPendingProposals` (1024) provides backpressure at the consensus layer

However, `SubscriptionManager` has no limit on the number of subscriptions per node or the total number of subscribers. A malicious actor could register an unbounded number of watch subscriptions, consuming memory and amplifying dispatch cost.

---

## 8. Crash-dump Hygiene

### BLOCKER-5: Heap dumps expose all config values in plaintext

**Files examined:**
- `configd-config-store/src/main/java/io/configd/store/HamtMap.java`
- `configd-config-store/src/main/java/io/configd/store/VersionedValue.java`
- `configd-config-store/src/main/java/io/configd/store/ConfigSnapshot.java`
- `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java`
- `configd-consensus-core/src/main/java/io/configd/raft/LogEntry.java`

**Finding:** All configuration values are held in-memory as raw `byte[]` arrays:
- In `VersionedValue.value` (the HAMT leaf nodes)
- In `LogEntry.command` (the Raft log entries, which contain serialized PUT commands with key+value)
- In `ConfigSnapshot.data` (the full HAMT tree)
- In snapshot transfer buffers (`SnapshotTransfer.SnapshotSendState.snapshotData`)

A heap dump (triggered by OOM, `-XX:+HeapDumpOnOutOfMemoryError`, or `jcmd`) will contain all config values in plaintext. Since this system is designed to store sensitive configuration (credentials, tokens, keys), a heap dump is equivalent to a full secret extraction.

The `VersionedValue` record exposes `valueUnsafe()` which returns the raw byte array directly (no copy), meaning the backing array is reachable from multiple references and will appear in heap dumps.

**Impact:** Any heap dump capture (via OOM, diagnostic tool, or container escape) exposes every configuration secret stored in the system.

**Remediation:**
1. Implement envelope encryption for sensitive values (values tagged as secrets are encrypted in the HAMT and Raft log, decrypted only at read time with a key held in a hardware security module or sealed memory)
2. Use `char[]` or `byte[]` with explicit zeroing for sensitive values when they are no longer needed
3. Implement a "secret" value type that is redacted in `toString()` and diagnostic outputs
4. Document heap dump handling procedures and restrict access to heap dump files
5. Consider JEP 493 (JDK 25 preview) for sensitive data handling if available

---

## Summary of Findings

### Blockers (launch blockers, full stop)

| ID | Area | Finding |
|----|------|---------|
| BLOCKER-1 | Transport | No transport encryption -- all traffic is plaintext |
| BLOCKER-2 | Transport | No node identity verification -- NodeId spoofing is trivial |
| BLOCKER-3 | AuthZ | No authentication or authorization on any API surface |
| BLOCKER-4 | Signing | No config signing -- edges cannot verify delta authenticity |
| BLOCKER-5 | Crash-dump | Heap dumps expose all config values in plaintext |

### Major

| ID | Area | Finding |
|----|------|---------|
| MAJOR-1 | Replay | No cryptographic replay protection on fan-out channel |
| MAJOR-2 | Replay | Plumtree/HyParView gossip has no sender authentication |
| MAJOR-3 | Supply Chain | No SBOM generation |
| MAJOR-4 | Secrets | Config values stored as plaintext with no encryption at rest |
| MAJOR-5 | DoS | Rate limiting is global, not per-tenant, and is nullable |
| MAJOR-6 | DoS | No value size limit -- arbitrary-size values can cause OOM |

### Minor

| ID | Area | Finding |
|----|------|---------|
| MINOR-1 | Supply Chain | Dependencies pinned but no hash verification |
| MINOR-2 | Supply Chain | Docker images have unpinned apt packages and image digests |
| MINOR-3 | Secrets | No hardcoded secrets found in source (positive finding) |
| MINOR-4 | Secrets | No logging framework means no log leakage risk (but no ops logging either) |
| MINOR-5 | DoS | Subscription count per node is unbounded |

---

## Positive Findings

While the security posture is not production-ready, the codebase demonstrates strong engineering practices that will make security remediation tractable:

1. **TLA+ model-checked consensus:** The Raft implementation has a formal TLA+ spec (`spec/ConsensusSpec.tla`) with 9 safety invariants verified via TLC model checking. Joint consensus reconfiguration is formally verified.

2. **Runtime invariant monitoring:** The `InvariantMonitor` bridges TLA+ invariants to runtime assertions. Violations are detected and metriced in production mode, fail-fast in test mode.

3. **Monotonic version enforcement:** Strong version monotonicity guarantees throughout the pipeline (`VersionedConfigStore`, `DeltaApplier`, `WatchCoalescer`, `VersionCursor`) prevent accidental state regression.

4. **Backpressure mechanisms:** `maxPendingProposals`, credit-based `FlowController`, and `SlowConsumerPolicy` provide multiple layers of overload protection.

5. **Immutable data structures:** The HAMT-based `ConfigSnapshot` is fully immutable, eliminating a class of concurrent-mutation bugs.

6. **Clean separation of concerns:** The transport interface abstraction (`RaftTransport`) means TLS can be added as an implementation detail without modifying consensus or storage code.

7. **No secrets in source:** Clean codebase with no hardcoded credentials, no `.env` files, and no secrets in test fixtures.

8. **Progressive rollout:** The `RolloutController` with canary/staged deployment reduces blast radius of bad config changes.

9. **Poison pill detection:** The `PoisonPillDetector` quarantines config entries that cause repeated application failures, preventing cascading edge failures.
