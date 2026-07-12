# Configuration reference

Every operator-facing knob of `configd-server` and `configd-edge-node`, with defaults from the code.
The [operator runsheet](../operations/operator-runsheet.md) tells you *which* controls to enable for
production and in what order; this page is the complete reference for *what exists*.

## How configuration is read

There are two kinds of knobs:

1. **Command-line arguments** (`--api-port`, `--tls-cert`, …) — only settable on the command line.
2. **Properties** (`configd.*`) — resolved in order: **JVM system property** (`-Dconfigd.foo=…`) →
   **environment variable** → **YAML config file** (server only, `--config <file>` or
   `-Dconfigd.config.file=…` / env `CONFIGD_CONFIG`). First layer that defines the key wins.

Environment naming: `CONFIGD_FOO_BAR` maps to `configd.foo.bar` (lowercased, `_` → `.`). Because of
that mapping, **keys containing capital letters cannot be set from the environment** (e.g.
`configd.raft.shardCount`) — use a system property or YAML. Four legacy aliases exist:

| Environment variable | Property |
|---|---|
| `CONFIGD_ENCRYPTION_AT_REST` | `configd.raft.encryption.enabled` |
| `CONFIGD_ENCRYPTION_REQUIRE_ENCRYPTED` | `configd.raft.encryption.requireEncrypted` |
| `CONFIGD_ENCRYPTION_KMS_PROVIDER` | `configd.raft.encryption.kms.provider` |
| `CONFIGD_ALLOW_COLOCATED_SIGNING_KEY` | `configd.security.allowColocatedSigningKey` |

A present-but-unparseable value fails the boot loudly rather than falling back to the default.

Markers used below: **†** = read as a raw JVM system property only (not from environment or YAML);
**(S)** = security-relevant — changing it moves your security posture, so treat it as a runsheet
item, not tuning.

## Command-line arguments — `configd-server`

| Argument | Default | Meaning |
|---|---|---|
| `--node-id <int>` | required | This node's cluster id |
| `--data-dir <path>` | required | Durable storage directory (WAL, snapshots, Raft state) |
| `--peers <csv>` | required | Peer node ids |
| `--bind-address <addr>` | `127.0.0.1` | Bind interface for the Raft and edge ports (S) |
| `--bind-port <int>` | `9090` | Raft peer port |
| `--api-port <int>` | `8080` | HTTP API port (`0` = ephemeral) |
| `--peer-addresses <id=host:port,…>` | none | Explicit peer routing |
| `--edge-port <int>` | disabled | Edge fan-out / watch endpoint; the plane is off until set |
| `--config <path>` | none | YAML config file for `configd.*` properties |
| `--auth-token <token>` | none | Legacy static bearer token; also the master switch that turns auth/ACL enforcement on (S) |
| `--tls-cert / --tls-key / --tls-trust-store <path>` | none | PKCS12 TLS triple — all-or-none; enables mTLS on the Raft and edge ports and server-TLS on the API port (S) |
| `--signing-key-file <path>` | `<data-dir>/signing-key.bin` | Ed25519 signing key location; keep it off the data volume (S) |
| `--strong-read-prefixes <csv>` | `secure/` | Key prefixes served linearizable-or-fail-closed (S) |

## Command-line arguments — `configd-edge-node`

| Argument | Default | Meaning |
|---|---|---|
| `--edge-id <string>` | required | Edge identity; must match the mTLS cert DN when TLS is on (S) |
| `--fanout-endpoints <h:p[,h:p]>` | required | Ordered upstream fan-out endpoints |
| `--api-port <int>` | `8081` | Edge read HTTP port (`0` = ephemeral) |
| `--data-dir <path>` | required | Holds the `epoch.lock` sidecar only — edge state is in-memory |
| `--tls-cert / --tls-key / --tls-trust-store <path>` | none | PKCS12 triple for the upstream mTLS connection (S) |
| `--verify-key <path>` | none | Ed25519 public key (SPKI DER); when set, every delta must verify (S) |
| `--subscribe-prefix <prefix>` (repeatable) | full store | Edge-side storage filter |
| `--reconnect-backoff-ms <long>` | `100` | Base reconnect backoff |
| `--heartbeat-silence-factor <int>` | `8` | Missed-heartbeat count that triggers reconnect |
| `--poison-max-retries <int>` | `3` | Apply-failure retries before terminal exit |

## Storage and durability

| Property | Default | Meaning |
|---|---|---|
| `configd.groupCommit.enabled` | `true` | Coalesced group fsync (vs per-op fsync) |
| `configd.groupCommit.maxBatch` | `4096` | Max entries per fsync batch |
| `configd.groupCommit.lingerMicros` | `0` | Linger to grow a batch before fsync |
| `configd.nodeAnchor.intervalMs` | `1000` | Durability-anchor refresh cadence (time bound) |
| `configd.nodeAnchor.auditRecords` | `64` | Durability-anchor cadence (record-count bound) |
| `configd.raft.maxReassembledSnapshotBytes` † | `536870912` (512 MiB) | Fail-closed cap on in-heap snapshot reassembly |

## Raft and consensus timing

Defaults are tuned for a same-AZ LAN; widen the election window before deploying across anything
slower.

| Property | Default | Meaning |
|---|---|---|
| `configd.raft.electionTimeoutMinMs` | `150` | Election timeout lower bound |
| `configd.raft.electionTimeoutMaxMs` | `300` | Election timeout upper bound |
| `configd.raft.heartbeatIntervalMs` | `50` | Leader heartbeat interval |
| `configd.raft.maxInflightAppends` | `10` | Max in-flight AppendEntries per follower |
| `configd.raft.ownerPoolSize` | `1` | Raft owner-thread pool; set ≥ shard count at N>1 |
| `configd.raft.witnessStrict` | `false` | Strict-witness voting posture (S) |
| `configd.raft.outboundAckTimeoutMs` † | `10000` | Dead-peer detection on outbound links |
| `configd.raft.inboundReadTimeoutMs` † | `15000` | Inbound idle/slow-read deadline (slowloris guard) (S) |
| `configd.raft.maxInboundConnections` † | `1024` | Max accepted inbound Raft connections (S) |
| `configd.raft.netty.workerThreads` † | `max(2, cpus)` | Raft transport worker threads |
| `configd.netty.transport` † | auto (Epoll → NIO) | Netty transport tier; `io_uring` is opt-in and measured slower at high fan-out (ADR-0043) |
| `configd.admin.transferAwaitMillis` † | `5000` | Server-side await for a leadership-transfer request |

## Sharding and leadership auto-balance

| Property | Default | Meaning |
|---|---|---|
| `configd.raft.shardCount` | `1` (range 1–16) | Static shard count — fixed at deploy time, not resizable live |
| `configd.edge.allowPartialShardView` | `false` | At N>1, admit legacy whole-store SUBSCRIBE clients that would see only the primary shard (S) |

The auto-balance loop (`LeaderBalanceLoop`, dormant at N=1) keeps one leader per box after
failovers:

| Property | Default | Meaning |
|---|---|---|
| `configd.raft.autobalance.enabled` | `true` | Kill switch |
| `configd.raft.autobalance.dryRun` | `false` | Log what it would do, act on nothing |
| `configd.raft.autobalance.intervalMs` | `30000` | Base check cadence |
| `configd.raft.autobalance.jitterPct` | `25` | Cadence jitter (0–100) |
| `configd.raft.autobalance.imbalanceThreshold` | `2` | Min leader-count spread that triggers a shed |
| `configd.raft.autobalance.cooldownMs` | `60000` | Quiet period after a transfer |
| `configd.raft.autobalance.maxInFlightTransfers` | `1` | Transfers per cadence (values >1 reserved, not honored) |
| `configd.raft.autobalance.instabilityWindowMs` | `5000` | Back-off look-back for election storms |

## Authentication

All (S). The provider chain is `configd.auth.providers` (ordered, comma-separated) or the single
`configd.auth.mode`; built-ins are `none`, `bearer`, `basic`, `mtls`, with `oidc` discovered via
`ServiceLoader`. A `bearer` provider must be last in the chain (enforced, fail-closed). The legacy
`--auth-token` flag is a static bearer independent of the SPI chain — and it is what flips
auth/ACL enforcement on.

| Property | Default | Meaning |
|---|---|---|
| `configd.auth.mode` | unset (off) | Single provider name |
| `configd.auth.providers` | empty | Ordered provider chain |
| `configd.auth.bearer.token` | required for `bearer` | Shared-secret token |
| `configd.auth.bearer.principal` | `root` | Principal the token authenticates as |
| `configd.auth.bearer.roles` | none | Roles granted to the token |
| `configd.auth.basic.users` | required for `basic` | `user:pbkdf2Hash:role1\|role2,…` user store |
| `configd.auth.mtls.roles` | none | Roles granted to a verified client cert (identity-only without it) |
| `configd.auth.clockSkewLeewayMs` | `60000` | Leeway on credential-expiry deadlines |
| `configd.auth.expiry.tokenWindowFraction` | `0.20` | Token refresh-window fraction of lifetime |
| `configd.auth.expiry.tokenWindowFloorMs` / `.tokenWindowCeilMs` | `30000` / `300000` | Token refresh-window clamp |
| `configd.auth.expiry.certWindowFraction` | `0.10` | Cert refresh-window fraction |
| `configd.auth.expiry.certWindowFloorMs` / `.certWindowCeilMs` | `300000` / `3600000` | Cert refresh-window clamp |
| `configd.auth.expiry.enforceCertNotAfter` | `false` | Enforce cert `notAfter` mid-connection on edge sessions |
| `configd.auth.revocation.mode` | `OFF` | Edge client-cert revocation posture: `OFF` / `LAX` / `STRICT` |
| `configd.auth.revocation.exemptInterNode` | `true` | Keep the Raft interior exempt from revocation checks (deliberate footgun guard) |
| `configd.auth.revocation.responderTimeoutMs` | `3000` | Revocation responder timeout |
| `configd.auth.revocation.crlFile` | none | CRL-file responder |

### OIDC issuers

Per issuer, prefix `configd.auth.oidc.issuer.<name>.` — issuer names are discovered from the
configured keys. All (S) unless noted.

| Suffix | Default | Meaning |
|---|---|---|
| `.uri` | required | Issuer URI (must be `https`) |
| `.audience` | required | Expected `aud` claim |
| `.jwksUri` | discovery | Explicit JWKS endpoint; omitting enables discovery |
| `.discovery` | `true` iff `.jwksUri` absent | OIDC discovery toggle |
| `.algs` | `RS256,ES256` | Allowed signature algorithms; `none`/`HS*` are refused |
| `.clockSkewSeconds` | `60` | Claim-validation skew |
| `.requireTypeAtJwt` | `true` | Require `typ: at+jwt` |
| `.claimsPath` | absent = default-deny | Path to the roles claim |
| `.rolePrefix` | empty | Prefix applied to mapped roles |
| `.roleMap.<external>` | empty = pass-through (warned) | External-role → Configd-role map |
| `.jwks.ttlSeconds` | `600` | JWKS cache TTL |
| `.jwks.refreshTimeoutMs` | `15000` | JWKS refresh timeout |
| `.jwks.refreshAheadSeconds` | `60` | Refresh-ahead margin |
| `.jwks.rateLimitSeconds` | `30` | Min interval between forced refreshes |
| `.jwks.outageToleranceSeconds` | `3600` | How long stale keys stay usable in an outage |
| `.jwks.connectTimeoutMs` / `.jwks.readTimeoutMs` | `2000` / `3000` | JWKS HTTP timeouts |
| `.jwks.sizeLimitBytes` | `65536` | Max JWKS document size |

## TLS, mTLS, and peer identity

All (S). The `--tls-*` triple (above) turns TLS on: mutual TLS on the Raft and edge fan-out ports,
server-side TLS on the API port (the API requests a client certificate only when the auth chain
includes `mtls`).

| Property | Default | Meaning |
|---|---|---|
| `configd.raft.peerIdentity.marker` | `CN` | RDN type carrying the peer node identity |
| `configd.raft.peerIdentity.markerType` | `rdn` | How the marker is carried: `rdn` or `san-uri` |
| `configd.raft.peerIdentity.allowedNodes` | empty | `identity=nodeId` allow-list; required (boot error if empty) once auth+TLS are both on |
| `configd.raft.peerIdentity.trustStore` | shared store | Separate PKCS12 CA for the Raft interior |
| `configd.raft.peerIdentity.trustStorePassword` | Raft store password | Password for the above |

## Audit log

No knob: the tamper-evident audit log (keyed HMAC-SHA256 hash chain) is enabled exactly when auth
is enabled. Its record cap is fixed.

## Replay protection

| Property | Default | Meaning |
|---|---|---|
| `configd.replay.enabled` | `false` | Opt-in timestamp+nonce replay guard on mutating API requests (S). Window (±5 min) and nonce capacity (1M) are fixed. |

## At-rest encryption and KMS

All (S). Enabling encryption is a **one-way door** — read
[known limitations §1](../operations/known-limitations.md#encryption-at-rest-is-off-by-default)
before touching these.

| Property | Default | Meaning |
|---|---|---|
| `configd.raft.encryption.enabled` | `false` | AES-256-GCM at rest (default is HMAC integrity only, plaintext values) |
| `configd.raft.encryption.requireEncrypted` | `false` | Refuse legacy unencrypted records once the plaintext prefix is gone |
| `configd.raft.encryption.kms.provider` | `local` | Key custody: `local` (HKDF from the signing key) or `vault-transit`; unknown values fail loudly |
| `configd.security.allowColocatedSigningKey` | `false` | Dev-only opt-out of the "signing key must live off the data volume" boot refusal |

### Vault Transit provider

Prefix `configd.kms.vault.` (module `configd-kms-vault`), used when the provider is
`vault-transit`. All (S).

| Suffix | Default | Meaning |
|---|---|---|
| `.address` | required | Vault address |
| `.transitMount` | `transit` | Transit secrets-engine mount |
| `.transitKeyName` | required | Transit key name (note: `transitKeyName`, not `keyName`) |
| `.namespace` | none | Vault namespace |
| `.tls.caFile` | JDK trust | PEM CA for the Vault connection |
| `.aadContext` | node id | Base64 AAD context bound into wrap/unwrap |
| `.bits` | `256` | Derived-key bits (128/256/512) |
| `.timeoutMs` | `5000` | Vault call timeout |
| `.auth.method` | `approle` | `approle` or `token` |
| `.auth.approle.roleId` | required for approle | AppRole role id |
| `.auth.approle.secretId` / `.secretIdFile` | one required | AppRole secret (inline or file) |
| `.auth.token` / `.tokenFile` | one required for token | Vault token (inline or file) |

## Write admission and public-bind guard

| Property | Default | Meaning |
|---|---|---|
| `configd.write.maxInflightProposals` † | `1024` | Write-admission cap; excess is shed as `429`. `0` disables. Effectively system-property-only — a YAML entry is silently ignored (S) |
| `configd.security.allowInsecurePublicBind` | `false` | The server refuses to boot bound to a non-loopback address with auth off; this acknowledges that posture deliberately (S) |

The global write rate limiter (10 000/s, burst 10 000) is fixed, not tunable.

## Edge fan-out (server side)

| Property | Default | Meaning |
|---|---|---|
| `configd.edge.fanout.filter` | `on` | Server-side prefix filtering for opted-in edges; set `off` when an untrusted relay tier terminates fan-out (S) |
| `configd.edge.preAuthMaxFrameBytes` | `16384` | Pre-auth frame size cap (DoS bound) (S) |
| `configd.edge.maxAuthTokenBytes` | `8192` | Max AUTH-frame token size (S) |
| `configd.edge.authTtlMs` | `3600000` | Static-token edge session lifetime (S) |
| `configd.edge.firstFrameDeadlineMs` † | `10000` | Pre-SUBSCRIBE first-frame deadline (slowloris guard) (S) |
| `configd.edge.authWorkerThreads` † | cpus | Edge-auth (PBKDF2) worker pool |
| `configd.edge.authWorkerQueueDepth` † | threads×8 | Edge-auth queue depth |
| `configd.edge.netty.workerThreads` † | `max(2, cpus)` | Fan-out worker threads |

## Edge node process

| Property | Default | Meaning |
|---|---|---|
| `configd.edge.accept_filtered` † | `off` | Opt this edge into server-side prefix filtering |
| `configd.edge.metricsScrapeToken` † | none = open | Bearer token protecting the edge `/metrics` endpoint (S) |

## HTTP transport tuning

Same knob set on both HTTP servers — replace `<svc>` with `server` (control-plane API) or `edge`
(edge read API). All †.

| Property | Default | Meaning |
|---|---|---|
| `configd.<svc>.netty.workerThreads` | `max(2, cpus)` | HTTP worker threads |
| `configd.<svc>.netty.requestTimeoutMillis` | `30000` | Whole-request arrival deadline |
| `configd.<svc>.netty.idleTimeoutMillis` | `60000` | Idle-connection reaping |
| `configd.<svc>.netty.maxRequestBytes` | `1048576` | Max request size (`413` beyond) |

## Shutdown

| Property | Default | Meaning |
|---|---|---|
| `configd.shutdown.drainQuietMs` | `2000` at N>1, `0` at N=1 | Readiness-drain quiet period before exit on `SIGTERM` |
