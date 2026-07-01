# v2 backlog

The honest list of what v1 does not do, and the work that would follow. v1 is deliberately scoped: a single-region, sharded-Raft config store with an mTLS edge, shipped with horizontal scale proven but operator-managed. Everything here is designed-or-understood but not built for v1.

## Security and encryption

- **Encryption at rest.** v1 protects the at-rest artifacts for integrity (tamper detection) only; values are stored in plaintext. The build is node-local AES-GCM at the same envelope seam as the integrity layer (a new algorithm id, no wire change, no cluster-wide key distribution, because the envelope is per-node and below replication). Binding `segment-id || offset || term` into the GCM additional-authenticated-data also closes the whole-log truncation gap the integrity layer leaves open. See `docs/archive/research/encryption-at-rest/`.
- **KMS provider SPI.** Pluggable key custody for the encryption work: typed key material (`RootKey`, `WrappedKey`, `KeyId`, not bare `byte[]`), fail-closed and boot-unseal-only (a KMS round-trip must never sit on the write or replay path, or a KMS outage shrinks quorum mid-incident). A custom `Destroyable` root key is required because `SecretKeySpec.destroy()` is a broken no-op on JDK 25. See `docs/archive/research/kms-spi/`.
- **Authentication SPI.** Make authentication pluggable behind a `Principal` seam; authorization stays in-core. Authenticator resolution must be specific-before-catch-all (a bearer catch-all placed first silently disables OIDC) and must fail closed on any foreign credential. See `docs/archive/design/auth-spi/`.
- **Latent defect (dormant).** `SigningKeyStore.writeForTest` builds a `PosixFilePermissions.fromString("rw-------")` but never applies it, so the file is not chmod 0600. It has zero callers (the production `generateAndWrite` path is correct), so it is harmless today; fix or delete the test-only helper.

## Scale and operations

- **Leadership balancing.** Multi-machine horizontal scale (the proven 2.45x across three machines) needs one group-leader per box, but v1 has no balancer. `RaftNode.transferLeadership` exists in core but is not exposed on an admin route and is not invoked on shutdown. The work is to expose it (an admin route, transfer-on-graceful-shutdown, or an automatic balancer). Until then, N=1 is the default and N>1 leadership is operator-managed.
- **N>1 tuning (when multi-shard is enabled).** The owner pool must have at least as many threads as shards, or all shards serialize on one owner thread for no gain; the shard count is capped at 16 (roughly ten or eleven busy leaders saturate a 16-vCPU box). See `docs/archive/measurement/ec2-horizontal-2026-07-01/`.
- **Chunked InstallSnapshot.** A single snapshot blob is capped at 4 MiB on the v1 wire; larger state cannot be shipped to a lagging follower. Chunked snapshot transfer removes the cap.
- **Cross-shard watches.** v1 watches are per-key and per-shard ordered. A watch spanning shards (a global-order or cross-shard subscription) is a later increment.

## Ecosystem

- **Client drivers.** The driver-protocol RFC (`docs/rfc/driver-protocol/`) is stand-alone implementable end to end, including golden wire vectors. Drivers can be built from it on demand.
- **Allocation win.** A measured server-sink allocation reduction (about 176 bytes per operation) is understood and ready to fold in.
