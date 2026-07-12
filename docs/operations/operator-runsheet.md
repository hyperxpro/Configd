# Operator Runsheet (secure-by-config release gates)

> DR and soak evidence lives in [`../archive/measurement/`](../archive/measurement/) - the two paid EC2
> runs, [`ec2-2026-06-30/`](../archive/measurement/ec2-2026-06-30/) (single-box durability plus a 6 h
> soak) and [`ec2-horizontal-2026-07-01/`](../archive/measurement/ec2-horizontal-2026-07-01/)
> (multi-machine horizontal scale). The first-30-days operating posture is the
> [burn-in contract](burn-in-contract.md).

**Audience:** the release engineer / SRE who signs off that a cluster is
production-ready.

**What this file is.** A checklist you run **before** declaring a Configd
cluster production-ready. It verifies every server-side security control is on
and observable. Read it together with its companion
[`deployer-must-know.md`](deployer-must-know.md) -- the
"know-this-or-you-create-a-vulnerability" list of deployment conditions the server
does not enforce for you.

## The one thing to internalize: secure-by-config, not by-default

This is a **deliberate posture choice: secure-by-config, not secure-by-default**.
**Except rate limiting, every security control is OFF until you turn it on**, and each off control emits
a loud startup warning or a `disabled` banner line. An out-of-the-box node is
**plaintext, unauthenticated, unaudited, no replay protection**. None of the
gates below are optional for a production node.

Every gate is verified two ways: a **startup log line** to grep, and a
**behavioral probe** with an expected response. Trust the behavioral probe over
the banner.

---

## Release-gate checklist (fill this in per cluster)

| # | Gate | What to set | Verify (startup line . behavioral probe) | Default |
|---|------|-------------|------------------------------------------|---------|
| 1 | **Auth ON** | `--auth-token <token>` | `Auth         : enabled` . unauth `PUT /v1/config/k` -> **401** | **OFF** (loud 5-line WARNING) |
| 2 | **mTLS ON** (peer + edge) | `--tls-cert` `--tls-key` `--tls-trust-store` (all three) | `TLS          : enabled` . plaintext dial to peer/edge port -> **handshake failure** | **OFF** -> plaintext |
| 3 | **Audit ON** | *(none -- auto-ON iff Auth ON)* | `Audit log    : security-audit (KEYED HMAC-SHA256 chain...)` | **OFF** (there is **no `--audit` flag**) |
| 4 | **Replay protection ON** | `-Dconfigd.replay.enabled=true` (sysprop, **not** a CLI flag) | `Replay guard : ON (window 300000ms, nonce cap 1000000)` . replayed nonce -> **409**, stale/missing -> **401** | **OFF** |
| 5 | **Signing key NOT co-located** | `--signing-key-file <path outside --data-dir>` | Node **boots** (no `SecurityException`); a co-located key **refuses to start** by default | Fail-closed **ON** |
| 6 | **Strong reads** | `--strong-read-prefixes secure/,...` (or accept the default) | `Strong reads : [secure/] (fail-closed linearizable, ADR-0030 INV-1)` | **ON** for `secure/` (safe default) |
| 7 | **No silent public bind** | *(default loopback; `-Dconfigd.security.allowInsecurePublicBind=true` to opt out)* | non-loopback bind + auth OFF + no override -> **refuses to start** | **ON** (fail-closed footgun-fix) |
| 8 | **Write admission** | *(tune `-Dconfigd.write.maxInflightProposals=N`)* | sustained flood -> **429 + Retry-After** | **ON** (conservative default) |
| 9 | **Shard-aware readiness + drain** | *(none)* | lost-quorum shard -> `/health/ready` **NOT-ready**; SIGTERM -> draining first | **ON** (correctness) |
| -- | **Rate limiting** | *(none -- unconditionally ON)* | `Write rate   : 10000/s (burst 10000)` . sustained flood -> **429** | **ON** (the one control on-by-default) |
| -- | **Auth modes** (Basic/OIDC/mTLS/node-join) | see Gate 1b | positive+negative probe per configured mode | -- |
| -- | **Key-material custody** + **fsync probe** | deploy-level (`ulimit -c 0`, swap off; run fsync probe) | see sections below | -- (operator) |

---

## Gate 1 - Authentication ON

- **What to set:** `--auth-token <token>`. Auth is considered enabled iff the
  token is present and non-blank (`ServerConfig.authEnabled()`,
  `configd-server/.../ServerConfig.java:209-211`).
- **What ON means:** the bearer token is checked with a **constant-time compare**
  (`MessageDigest.isEqual`, `ConfigdServer.java:725`). A bearer match
  authenticates **as `root` with empty roles** (`ROOT_PRINCIPAL`, `Set.of()`,
  `ConfigdServer.java:732`) and `root` is granted **ALL** permissions on every
  key (`ConfigdServer.java:738`). So in the default config, **"authenticated via
  `--auth-token`" means "is root."** Multi-principal separation comes only from
  `_acl/` policy or the mTLS cert-DN path - a shared bearer token is a single
  root identity, not per-user auth.
- **How to VERIFY:**
  - **Startup line:** `Auth         : enabled` (`ConfigdServer.java:2300`).
  - **Behavioral:** an **unauthenticated** mutating request is rejected:
    ```sh
    curl -sk -o /dev/null -w '%{http_code}\n' -X PUT \
      https://<host>:<api-port>/v1/config/probe --data-binary 'x'
    # expect: 401
    ```
    (The `/metrics` endpoint is also bearer-gated when auth is on -
    `AdminApiHandler.java:163-170` returns **401** without a token.)
- **Failure mode if left OFF:** the loud 5-line `WARNING` banner prints
  (`ConfigdServer.java:713-719`): *"Authentication is DISABLED... All
  write/delete/admin endpoints are unauthenticated... DO NOT run in production."*
  **Every** `PUT`/`DELETE`/admin call and `/metrics` scrape is open to anyone who
  can reach the port. Config can be silently rewritten by any client. **Note:** with auth OFF the default
  bind is **loopback** (`127.0.0.1`); binding a non-loopback interface with auth OFF is **refused** unless
  `-Dconfigd.security.allowInsecurePublicBind=true` is set (Gate 7 below).

## Gate 1b - Authentication modes (No-Auth / Basic / OIDC-Bearer / mTLS)

Authentication resolves through **one pluggable authenticator chain shared by both planes**
(`AuthenticatorChain`/`AuthenticatorFactory`, ServiceLoader-discovered). A `--auth-token` bearer (Gate 1) is
the simplest mode (a single `root` identity); the chain also supports:

- **HTTP Basic** (RFC 7617) on the API (`Authorization: Basic ...`) and on the edge via a token/basic `AUTH`
  frame; usernames map to Configd roles via `_acl/` policy.
- **Bearer / OAuth2-OIDC.** Configure the OIDC authenticator (`configd-authn-oidc`, ServiceLoader) with the
  issuer + JWKS; a bearer token is validated as an OIDC/JWT access token and its claims mapped to roles
  (`ClaimsRoleMapper`). The JWKS set is cached for 600 s by default
  (`configd.auth.oidc.issuer.<name>.jwks.ttlSeconds`), refreshed 60 s ahead of expiry, and tolerates up to
  1 h of issuer outage by serving the last-known-good set. **Fail-closed:** if the issuer/JWKS is
  unreachable and no cached set is usable, the request is rejected (`401`/`503`), never downgraded to
  anonymous. A `503`-class outcome is retryable.
- **mTLS.** On the edge and Raft-peer surfaces the verified client-cert DN is the authoritative identity
  (Gate 2). The edge accepts an mTLS cert **and/or** a token/basic `AUTH` frame; credentials carry
  **expiry/revocation** (`CREDENTIAL_EXPIRED` close, proactive `REFRESH_AUTH`). The proactive refresh window
  is a fraction of the credential's remaining lifetime, clamped to a floor and ceiling
  (`configd.auth.expiry.*`): 20% for a bearer token, clamped to 30 s-5 min, and 10% for a certificate,
  clamped to 5 min-1 h. Revocation checking is **off by default**
  (`configd.auth.revocation.mode=off`); turning it on (`lax`/`strict`) checks a CRL file that is
  re-parsed whenever it changes on disk (`configd.auth.revocation.crlFile`).
- **Node-join (Raft interior) is mTLS-only.** A node's membership identity is a certificate marker (default
  Subject-DN CN; optionally SAN-URI/SPIFFE via `configd.raft.peerIdentity.markerType`), matched against a
  per-node allow-list `configd.raft.peerIdentity.allowedNodes`. There is **no** token path to consensus.
- **KMS unseal (when encryption ON).** With `-Dconfigd.raft.encryption.kms.provider=vault-transit`, the
  per-node keyring-custody secret is unsealed from Vault Transit at boot (fail-closed: a KMS outage refuses
  to start rather than downgrade). Selecting an unavailable provider is a fail-loud startup refusal.

**Verify:** exercise the mode(s) your deployment configures with a positive and a negative probe (a valid
credential -> `200`; a missing/invalid one -> `401`; an unauthorized-but-authenticated principal -> `403`). For
OIDC, additionally confirm a token from the **wrong issuer/audience** is rejected. See
[`../design/group-b/`](../design/group-b/) for the full auth surface design.

## Gate 2 - mTLS ON (Raft peer + edge fan-out)

- **What to set:** **all three** of `--tls-cert <pkcs12>` `--tls-key <pkcs12>`
  `--tls-trust-store <pkcs12>`. TLS is enabled iff all three are present
  (`ServerConfig.tlsEnabled()`, `ServerConfig.java:202-204`). Missing any one ->
  **plaintext**.
- **What ON means - read this carefully (the surfaces differ):**
  | Surface | With TLS configured | Client-cert (mTLS)? | Enforced at |
  |---------|--------------------|--------------------|-------------|
  | **Raft peer channel** (`--bind-port`) | TLS | **REQUIRED** (`setNeedClientAuth(true)`) | `TcpRaftTransport.java:572-573` (Netty: `ConfigdServer.java:412`) |
  | **Edge fan-out / watch** (`--edge-port`) | TLS | **REQUIRED** (edge always demands a client cert) | `FanOutServer.java:339`, `NettyFanOutServer.java:249` |
  | **HTTP admin/API** (`--api-port`) | **HTTPS, server-side TLS ONLY** | **NOT required** | `NettyHttpApiServer.java:168-170` |
  The REST admin API is **HTTPS + bearer token**, *not* client-cert mTLS -
  "Client identity is the Bearer token; mTLS is the fan-out/consensus surface"
  (`NettyHttpApiServer.java:170`). Do **not** assume "TLS on" means mutual auth on
  the API port; that port's authentication is **Gate 1 (the bearer token)**.
- **How to VERIFY:**
  - **Startup line:** `TLS          : enabled` (`ConfigdServer.java:2299`); and if
    `--edge-port` is set, `Edge port    : <port> (mTLS)` (`ConfigdServer.java:1017-1018`).
  - **Behavioral (peer/edge require a client cert):** a dial **without** a trusted
    client cert fails the TLS handshake; a dial **with** the P12 client cert
    succeeds. See the worked cross-box example in
    [`../archive/measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md`](../archive/measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md)
    (`curl` over mTLS with a P12 client cert -> `200`; startup shows `TLS : enabled`).
  - **Behavioral (API port is HTTPS):** `https://` works, plaintext `http://` to
    the API port fails.
- **Cross-box requirement (the `EdgeTransportSanMismatch` risk class):** the Raft
  peer client enforces HTTPS endpoint identification - it verifies the peer's
  cert **SAN** against the **name it dialed**. The shipped
  `deploy/compose/secrets/server.pem` carries a shared identity
  `CN=configd-cp` with `SAN = dns:cp1,cp2,cp3,localhost, ip:127.0.0.1`. Cross-box
  therefore needs, on **every** node: (1) `/etc/hosts` mapping `cp1/cp2/cp3` -> the
  private IPs; (2) `--peer-addresses 1=cp1:9291,2=cp2:9291,3=cp3:9291` (dial **by
  the SAN name**, never by raw IP); (3) the TLS triple. Full worked example:
  [`03-mtls-bringup.md`](../archive/measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md).
- **Failure mode if left OFF:** plaintext Raft consensus and plaintext edge
  fan-out on the wire - any on-path attacker reads/forges config traffic and edge
  deltas. With TLS off, the edge port serves **plaintext** (`Edge port : <port>
  (PLAINTEXT)`, `ConfigdServer.java:1018`).
- **Edge hydration grant (auth ON):** with auth on, the whole-store `SUBSCRIBE`
  fan-out is gated at admission on a **whole-store READ cover**, so the edge /
  hydration identity (the edge node's mTLS cert-DN) MUST hold **`READ` over the
  root prefix `""`** or its `SUBSCRIBE` is refused `NOT_AUTHORIZED` and edge
  hydration never starts. Out-of-the-box only `root` holds that grant. See
  [`deployer-must-know.md` section 2](deployer-must-know.md).

## Gate 3 - Audit log ON (auto-tied to Auth)

- **What to set:** **nothing directly - there is NO `--audit` flag.** The audit
  log is enabled **iff authentication is enabled**:
  `AuditLog auditLog = (authInterceptor != null) ? new AuditLog(...) : null`
  (`ConfigdServer.java:923`). Turning on **Gate 1** turns on audit. (An audit
  trail only has subjects to record once there are authenticated principals.)
  Do not hunt for a separate toggle - it does not exist.
- **What ON means:** a **keyed HMAC-SHA256 chain** under `K_audit` (HKDF-derived,
  domain-separated, from the cluster signing key - `ConfigdServer.java:292`),
  written to append-only durable storage and bounded to
  `AuditLog.DEFAULT_MAX_RECORDS`. The keyed chain means a file-rewriting attacker
  cannot forge a consistent history.
- **How to VERIFY:**
  - **Startup line:** `Audit log    : security-audit (KEYED HMAC-SHA256 chain,
    append-only, cap <N>)` (`ConfigdServer.java:925-926`). Its **presence**
    confirms both auth and audit are on; its **absence** means auth is off.
- **Failure mode if left OFF (i.e. auth off):** no tamper-evident record of
  who changed what - post-incident forensics are impossible, and there is no
  detection of after-the-fact log tampering.

## Gate 4 - Replay protection ON

- **What to set:** the **system property** `-Dconfigd.replay.enabled=true`
  (`ConfigdServer.java:934`). This is a **JVM sysprop, not a CLI flag** - passing
  `--replay...` will fail arg parsing.
- **What ON means:** clients stamp each mutating request with
  `X-Configd-Timestamp` (epoch ms) and a unique `X-Configd-Nonce`
  (`ReplayGuard.java:55,58`). Requests outside a **+/-5-minute** window
  (`DEFAULT_WINDOW_MS = 300_000`, `ReplayGuard.java:49`) or reusing a seen nonce
  are rejected; the nonce store is bounded to **1,000,000** entries
  (`DEFAULT_MAX_NONCES`, `ReplayGuard.java:52`) with TTL + LRU eviction.
  **Scope of protection (be honest with yourself):** this defends **only against
  passive capture-and-replay**. A holder of the bearer token can still mint a
  **fresh** request (new nonce + current timestamp) - per-request content signing
  is a follow-up, not built yet (`ReplayGuard.java:17-24`). It is not a substitute for
  Gate 1.
- **How to VERIFY:**
  - **Startup line:** `Replay guard : ON (window 300000ms, nonce cap 1000000)`
    (`ConfigdServer.java:936-937`).
  - **Behavioral:** a **replayed** nonce returns **409**; a **stale/future or
    missing** replay header returns **401** (`AdminApiHandler.java:581-601`:
    `STALE`/`MALFORMED` -> 401 at :590-594, `REPLAY` -> 409 at :596-598; hooked on
    `PUT` at :314-316 and `DELETE` at :368-370).
- **Failure mode if left OFF:** a captured mutating request (e.g. a `DELETE`) can
  be replayed verbatim by an on-path attacker with no time bound.

## Gate 5 - Signing key NOT co-located with the data dir

- **What to set:** `--signing-key-file <path>` pointing **outside** `--data-dir`
  (mount it on separate storage - KMS/HSM/mounted secret). This is **fail-closed
  by default**: `enforceSigningKeyNotColocated` throws a `SecurityException` and
  **refuses to boot** if the signing key lives inside the data dir it protects
  (`ConfigdServer.java:1251-1266`, invoked from `deriveRaftIntegrityEnvelope`
  `:1218`). The at-rest **integrity** key is HKDF-derived from this signing key,
  so a storage-tampering adversary who could both read the co-located key and
  rewrite artifacts could forge a valid MAC - defeating the integrity layer.
- **Opt-out (dev/test/single-node ONLY - do not use in prod):** **two** forms
  downgrade the refusal to a loud warning - the sysprop
  `-Dconfigd.security.allowColocatedSigningKey=true` **or** the env var
  `CONFIGD_ALLOW_COLOCATED_SIGNING_KEY=true` (`ConfigdServer.java:1216-1217`;
  warning banner `:1267-1277`). Leave **both** unset in production.
- **How to VERIFY:**
  - **Behavioral:** the node **boots without** a `SecurityException`, and startup
    shows **no** "signing key is CO-LOCATED" warning banner. Confirm the key path
    is genuinely outside the data dir.
  - A deliberate negative check (staging): put the key inside `--data-dir` with no
    opt-out set -> the node must **refuse to start** with the co-location check message.
- **Failure mode if left mis-configured:** with the key co-located **and** the
  opt-out set, a host/storage-tampering adversary (threat A2/T3) can read the key
  and forge a valid at-rest MAC - the snapshot/WAL/state integrity guarantee
  becomes worthless.

## Gate 6 - Strong reads (freshness, NOT confidentiality)

- **What to set:** `--strong-read-prefixes secure/,<more>`. **Omitting the flag
  keeps the safe default `secure/`** (`ServerConfig.java:263-266` ->
  `StrongReadPolicy.DEFAULT_PREFIX`); passing an **explicit blank** value is a
  deliberate opt-out that disables enforcement. GETs under a strong-read prefix
  are served **fail-closed linearizable** (`ConfigdServer.java:914`).
- **Important:** strong-read is a **freshness** class (no stale reads for those
  keys), **not** a confidentiality control. It does **not** encrypt anything - see
  [`deployer-must-know.md` item 1](deployer-must-know.md) (do NOT store secrets).
- **How to VERIFY:**
  - **Startup line:** `Strong reads : [secure/] (fail-closed linearizable,
    ADR-0030 INV-1)` (`ConfigdServer.java:915-916`).
- **Failure mode if disabled (explicit blank):** reads of security-relevant keys
  may be served **stale** from a non-leader, so a client can act on a
  rolled-back/old value after a failover.

## Always-on - Rate limiting (verify, don't configure)

- **What:** a global **10,000/s** token bucket (burst 10,000) **plus** a
  per-principal bucket, gated **before** the Raft propose so a hostile tenant
  cannot spend the whole write budget or reach consensus with a flood
  (`ConfigdServer.java:782-798`). This is the **one control that is
  unconditionally on**.
- **How to VERIFY:**
  - **Startup line:** `Write rate   : 10000/s (burst 10000)` (`ConfigdServer.java:785`).
  - **Behavioral:** a sustained flood past the cap returns HTTP **429
    "Overloaded"** (`AdminApiHandler.java:405-411`).
- **Note:** the reserved namespaces `_acl/` and `_system/` require **ADMIN** for
  **every** method (mutation and disclosure), fail-closed, with write-time `_acl/`
  validation returning **400** pre-commit (`AdminApiHandler.java:477-525`
  reserved-prefix gate, `:332-338` `validateAclWrite`, `isReserved` `:537-538`).
  This is active whenever auth is on and needs no configuration.

## Gate 7 - No silent unauthenticated public bind (default loopback)

- **What to set:** nothing, if you run with auth ON (Gate 1) - bind whatever interface you need. The default
  bind is **loopback (`127.0.0.1`)**. To bind a **non-loopback** interface with **auth OFF**, you must set
  the system property `-Dconfigd.security.allowInsecurePublicBind=true` (there is no CLI flag for this),
  which logs a loud warning and continues. This is a **footgun-fix, not "auth required by default"** - a
  deliberate no-auth public deployment stays possible via the override.
- **How to VERIFY:** a non-loopback bind with auth OFF and no override **refuses to start**; with the override
  it starts and prints the insecure-public-bind warning. An auth-ON public bind needs no override.
- **Failure mode if mis-set:** setting the override in production with auth OFF exposes an unauthenticated
  store publicly - segregate the port at the network boundary and only use the override knowingly.

## Gate 8 - Write-admission control (ON by default)

- **What to set:** nothing - `configd.write.maxInflightProposals` is **ON by default** with a conservative
  tuned value; tune with `-Dconfigd.write.maxInflightProposals=N` (`0` disables).
- **How to VERIFY:** a sustained write flood is shed with **HTTP 429 + `Retry-After`** (a pre-commit reject),
  bounding leader memory; watch `configd_write_rejected_overloaded_total`.
- **Failure mode if disabled:** an unbounded write burst can grow leader in-flight memory before the always-on
  rate limiter alone catches it.

## Gate 9 - Readiness is shard-aware and drains on SIGTERM

- **What to set:** nothing - `/health/ready` reflects **every** shard this node should serve (a node that lost
  quorum on any hosted shard reports NOT-ready), and SIGTERM flips readiness to draining **before** closing.
- **How to VERIFY:** point the orchestrator readiness probe at `/health/ready`; on SIGTERM the node reports
  NOT-ready first so the LB stops routing and in-flight work drains. At N>1, kill a shard's quorum and confirm
  the node reports NOT-ready (it no longer lies via a group-0-only check).

## Deploy-level key-material custody - core dumps and swap

- **What to set (the server cannot enforce this):** JVM zeroization of key material is platform-bounded, so
  belt-and-braces custody is deploy-level. Set **`ulimit -c 0`** (or systemd `LimitCORE=0`) for the Configd
  unit; keep **`-XX:-HeapDumpOnOutOfMemoryError`** (heap dumps expose config values,
  see [`security-heap-dump-policy.md`](security-heap-dump-policy.md)); run with **swap off** (`swapoff -a`) or
  encrypted swap on nodes that hold the signing key or an encrypted data dir.
- **Why:** a core dump or a swapped-out page can persist raw key/root bytes to disk, defeating the at-rest and
  audit integrity guarantees (threat A2/T3). Treat these as part of the signing-key custody procedure alongside
  the Gate 5 co-location guard. This is a **tested boundary**, not an in-JVM guarantee.

## Verify fsync on the target hardware

- **What to run (on the target storage, before production):** a `pg_test_fsync`-equivalent probe to confirm the
  WAL device's fsync method and latency (`fdatasync`/`O_DSYNC`), and that fsync actually reaches durable media
  (no lying write cache). A reference wrapper is [`../../ops/scripts/fsync-probe.sh`](../../ops/scripts/fsync-probe.sh);
  the durability runbook is [`../../ops/runbooks/disk-full-fsync.md`](../../ops/runbooks/disk-full-fsync.md).
- **Why:** Configd acknowledges a write only after a durable majority fsync; a device that buffers or reorders
  fsync silently weakens the 0-loss durability contract. **Run this on the actual production storage** - do not
  assume the CI/dev environment's characteristics.

---

## Edge fan-out prefix filtering (ADR-0045) - a posture, not a gate

- **What:** on a co-located trusted deployment the fan-out drain filters whole signed deltas to a
  prefix-scoped edge's subscription server-side, cutting per-edge egress by ~1/f (an edge wanting 1% of the
  keyspace moves ~100x less). It preserves per-delta Ed25519 authenticity (whole deltas dropped, never
  rewritten) and always ships strong-read (`secure/`) keys. The trust spent is the edge trusting the server's
  covered-through assertion on the HEARTBEAT - sound **only** within the operator's mTLS domain.
- **Server posture:** `-Dconfigd.edge.fanout.filter=on|off`, **default on**, fails loud on any other value.
  Set **off** (full-chain feed) the moment a **separate or untrusted relay tier** terminates the fan-out - the
  no-suppression guarantee then matters again. This is a **two-way door**.
- **Edge opt-in:** `-Dconfigd.edge.accept_filtered=on|off`, **default off**, fails loud on any other value. A
  prefix-scoped edge with this on negotiates the `0x03` wire; an unconfigured or full-store edge stays on the
  byte-identical `0x01` wire. Server-and-edge is a **lockstep upgrade** (a `0x03` edge to an old server fails
  loud with `BAD_WIRE_VERSION` and reconnects; keep them in step).
- **What to WATCH:** `edge_fanout_filtered_deltas_total` vs `edge_fanout_delivered_deltas_total`
  (delivered / (delivered + filtered) is the measured keyspace fraction), `edge_fanout_cursor_advances_total`
  (the covered-S heartbeats), `edge_fanout_filtered_sessions_total`.
- **Do NOT watch seq-lag for a filtered edge:** a filtered edge shows **~0 seq-lag by design** - its
  `HEARTBEAT.latestSeq` is the covered-S cursor, not the buffer tip, so cursor-lag is trivially ~0. Watch the
  **commit-timestamp staleness gauge** (`edge_staleness_ms` / `edge_staleness_state`) for filtered edges, not
  seq-lag.
- **Trust note:** filtering trusts the serving node's assertion "(A,B] had nothing under your prefixes." Do
  **not** enable it across an untrusted relay. A genuine data-loss gap (ring eviction) is still caught
  server-side and re-snapshotted; a malformed covered-S is caught edge-side; a well-formed suppression of a
  matching delta is **not** detectable under this posture (see `known-limitations.md`).

---

## Sign-off

A cluster is **not** production-ready until Gates 1-9 are each **verified by their
behavioral probe** (not merely by a config flag being present), the always-on
controls show their startup lines, the configured **auth modes** (Gate 1b) pass a positive+negative probe,
and the deploy-level **key-material custody** and **fsync probe** steps have been run on the target
hardware. Record the verifying command output per gate.

Then read [`deployer-must-know.md`](deployer-must-know.md) -- ten system-boundary
requirements (secrets, edge-hydration root-READ grant, single-scope, upgrade-ordering,
monitor-leadership-distribution, cross-identity policy alignment, no-silent-public-bind, write-admission
default, shard-aware readiness, key-material core-dump/swap); most are conditions the
server does **not** enforce for you (the edge-hydration grant it now does gate at
admission).

## Cross-references

- [`deployer-must-know.md`](deployer-must-know.md) -- companion list of deployment conditions.
- [`../archive/measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md`](../archive/measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md)
  -- the worked cross-box mTLS bring-up.
- [`burn-in-contract.md`](burn-in-contract.md) -- the first-30-days burn-in
  posture (heightened alerting thresholds, rollback triggers, on-call, exit criteria).
- [`known-limitations.md`](known-limitations.md) -- the snapshot-cap / encoder-drop
  operator signals and the deployment security model.
- [`../archive/measurement/ec2-2026-06-30/02-dr-drills.md`](../archive/measurement/ec2-2026-06-30/02-dr-drills.md)
  and [`04-soak.md`](../archive/measurement/ec2-2026-06-30/04-soak.md) -- DR and 6 h soak evidence.
