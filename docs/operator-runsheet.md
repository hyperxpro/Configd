# Operator Runsheet — Configd v1 (secure-by-CONFIG release gates)

> **Supersedes** the prior v0.1-GA hardening runsheet (the April-2026 "40-day
> harness" artifact from the self-healing-loop era). That document's
> calendar-bounded soak/burn/shadow/longevity harnesses are retired: the DR and
> soak **evidence now lives in [`measurement/`](measurement/)** (the two paid EC2
> runs — [`ec2-2026-06-30/`](measurement/ec2-2026-06-30/) single-box durability +
> 6 h soak, and [`ec2-horizontal-2026-07-01/`](measurement/ec2-horizontal-2026-07-01/)
> multi-machine horizontal scale), and the first-30-days operating posture is the
> **[burn-in contract](burn-in-contract.md)** (go/no-go condition **C4** —
> [`readiness/v1-go-no-go-2026-07-01.md`](readiness/v1-go-no-go-2026-07-01.md) §0/§5.1).

**Audience:** the release engineer / SRE who signs off that a cluster is
production-ready.

**What this file is.** A checklist you run **before** declaring a Configd v1
cluster production-ready. It closes go/no-go **condition C1** ("ship
secure-by-config; put the §4 MUST-KNOW list into the runsheet as release gates,
not footnotes"). Read it together with its companion
[`deployer-must-know.md`](deployer-must-know.md) (condition C2 — the
"know-this-or-you-create-a-vulnerability" list).

## The one thing to internalize: secure-by-CONFIG, not by-default

v1 is a **deliberate posture choice: secure-by-config, not secure-by-default**
([go/no-go §4](readiness/v1-go-no-go-2026-07-01.md)). **Except rate limiting,
every security control is OFF until you turn it on**, and each off control emits
a loud startup warning or a `disabled` banner line. An out-of-the-box node is
**plaintext, unauthenticated, unaudited, no replay protection**. None of the
gates below are optional for a production node.

Every gate is verified two ways: a **startup log line** to grep, and a
**behavioral probe** with an expected response. Trust the behavioral probe over
the banner.

---

## Release-gate checklist (fill this in per cluster)

| # | Gate | What to set | Verify (startup line · behavioral probe) | Default |
|---|------|-------------|------------------------------------------|---------|
| 1 | **Auth ON** | `--auth-token <token>` | `Auth         : enabled` · unauth `PUT /v1/config/k` → **401** | **OFF** (loud 5-line WARNING) |
| 2 | **mTLS ON** (peer + edge) | `--tls-cert` `--tls-key` `--tls-trust-store` (all three) | `TLS          : enabled` · plaintext dial to peer/edge port → **handshake failure** | **OFF** → plaintext |
| 3 | **Audit ON** | *(none — auto-ON iff Auth ON)* | `Audit log    : security-audit (KEYED HMAC-SHA256 chain...)` | **OFF** (there is **no `--audit` flag**) |
| 4 | **Replay protection ON** | `-Dconfigd.replay.enabled=true` (sysprop, **not** a CLI flag) | `Replay guard : ON (window 300000ms, nonce cap 1000000)` · replayed nonce → **409**, stale/missing → **401** | **OFF** |
| 5 | **Signing key NOT co-located** | `--signing-key-file <path outside --data-dir>` | Node **boots** (no `SecurityException`); a co-located key **refuses to start** by default | Fail-closed **ON** |
| 6 | **Strong reads** | `--strong-read-prefixes secure/,...` (or accept the default) | `Strong reads : [secure/] (fail-closed linearizable, ADR-0030 INV-1)` | **ON** for `secure/` (safe default) |
| — | **Rate limiting** | *(none — unconditionally ON)* | `Write rate   : 10000/s (burst 10000)` · sustained flood → **429** | **ON** (the one control on-by-default) |

Source of truth for the gate set: [`readiness/v1-go-no-go-2026-07-01.md` §4](readiness/v1-go-no-go-2026-07-01.md).

---

## Gate 1 — Authentication ON

- **What to set:** `--auth-token <token>`. Auth is considered enabled iff the
  token is present and non-blank (`ServerConfig.authEnabled()`,
  `configd-server/.../ServerConfig.java:209-211`).
- **What ON means:** the bearer token is checked with a **constant-time compare**
  (`MessageDigest.isEqual`, `ConfigdServer.java:725`). A bearer match
  authenticates **as `root` with empty roles** (`ROOT_PRINCIPAL`, `Set.of()`,
  `ConfigdServer.java:732`) and `root` is granted **ALL** permissions on every
  key (`ConfigdServer.java:738`). So in the default config, **"authenticated via
  `--auth-token`" ≡ "is root."** Multi-principal separation comes only from
  `_acl/` policy or the mTLS cert-DN path — a shared bearer token is a single
  root identity, not per-user auth.
- **How to VERIFY:**
  - **Startup line:** `Auth         : enabled` (`ConfigdServer.java:2300`).
  - **Behavioral:** an **unauthenticated** mutating request is rejected:
    ```sh
    curl -sk -o /dev/null -w '%{http_code}\n' -X PUT \
      https://<host>:<api-port>/v1/config/probe --data-binary 'x'
    # expect: 401
    ```
    (The `/metrics` endpoint is also bearer-gated when auth is on —
    `AdminApiHandler.java:163-170` returns **401** without a token.)
- **Failure mode if left OFF:** the loud 5-line `WARNING` banner prints
  (`ConfigdServer.java:713-719`): *"Authentication is DISABLED... All
  write/delete/admin endpoints are unauthenticated... DO NOT run in production."*
  **Every** `PUT`/`DELETE`/admin call and `/metrics` scrape is open to anyone who
  can reach the port. Config can be silently rewritten by any client.

## Gate 2 — mTLS ON (Raft peer + edge fan-out)

- **What to set:** **all three** of `--tls-cert <pkcs12>` `--tls-key <pkcs12>`
  `--tls-trust-store <pkcs12>`. TLS is enabled iff all three are present
  (`ServerConfig.tlsEnabled()`, `ServerConfig.java:202-204`). Missing any one ⇒
  **plaintext**.
- **What ON means — read this carefully (the surfaces differ):**
  | Surface | With TLS configured | Client-cert (mTLS)? | Enforced at |
  |---------|--------------------|--------------------|-------------|
  | **Raft peer channel** (`--bind-port`) | TLS | **REQUIRED** (`setNeedClientAuth(true)`) | `TcpRaftTransport.java:572-573` (Netty: `ConfigdServer.java:412`) |
  | **Edge fan-out / watch** (`--edge-port`) | TLS | **REQUIRED** (edge always demands a client cert) | `FanOutServer.java:339`, `NettyFanOutServer.java:249` |
  | **HTTP admin/API** (`--api-port`) | **HTTPS, server-side TLS ONLY** | **NOT required** | `NettyHttpApiServer.java:168-170` |
  The REST admin API is **HTTPS + bearer token**, *not* client-cert mTLS —
  "Client identity is the Bearer token; mTLS is the fan-out/consensus surface"
  (`NettyHttpApiServer.java:170`). Do **not** assume "TLS on" means mutual auth on
  the API port; that port's authentication is **Gate 1 (the bearer token)**.
- **How to VERIFY:**
  - **Startup line:** `TLS          : enabled` (`ConfigdServer.java:2299`); and if
    `--edge-port` is set, `Edge port    : <port> (mTLS)` (`ConfigdServer.java:1017-1018`).
  - **Behavioral (peer/edge require a client cert):** a dial **without** a trusted
    client cert fails the TLS handshake; a dial **with** the P12 client cert
    succeeds. See the worked cross-box example in
    [`measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md`](measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md)
    (`curl` over mTLS with a P12 client cert → `200`; startup shows `TLS : enabled`).
  - **Behavioral (API port is HTTPS):** `https://` works, plaintext `http://` to
    the API port fails.
- **Cross-box requirement (the `EdgeTransportSanMismatch` risk class):** the Raft
  peer client enforces HTTPS endpoint identification — it verifies the peer's
  cert **SAN** against the **name it dialed**. The shipped
  `deploy/compose/secrets/server.pem` carries a shared identity
  `CN=configd-cp` with `SAN = dns:cp1,cp2,cp3,localhost, ip:127.0.0.1`. Cross-box
  therefore needs, on **every** node: (1) `/etc/hosts` mapping `cp1/cp2/cp3` → the
  private IPs; (2) `--peer-addresses 1=cp1:9291,2=cp2:9291,3=cp3:9291` (dial **by
  the SAN name**, never by raw IP); (3) the TLS triple. Full worked example:
  [`03-mtls-bringup.md`](measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md).
- **Failure mode if left OFF:** plaintext Raft consensus and plaintext edge
  fan-out on the wire — any on-path attacker reads/forges config traffic and edge
  deltas. With TLS off, the edge port serves **plaintext** (`Edge port : <port>
  (PLAINTEXT)`, `ConfigdServer.java:1018`).

## Gate 3 — Audit log ON (auto-tied to Auth)

- **What to set:** **nothing directly — there is NO `--audit` flag.** The audit
  log is enabled **iff authentication is enabled**:
  `AuditLog auditLog = (authInterceptor != null) ? new AuditLog(...) : null`
  (`ConfigdServer.java:923`). Turning on **Gate 1** turns on audit. (An audit
  trail only has subjects to record once there are authenticated principals.)
  Do not hunt for a separate toggle — it does not exist.
- **What ON means:** a **keyed HMAC-SHA256 chain** under `K_audit` (HKDF-derived,
  domain-separated, from the cluster signing key — `ConfigdServer.java:292`),
  written to append-only durable storage and bounded to
  `AuditLog.DEFAULT_MAX_RECORDS`. The keyed chain means a file-rewriting attacker
  cannot forge a consistent history.
- **How to VERIFY:**
  - **Startup line:** `Audit log    : security-audit (KEYED HMAC-SHA256 chain,
    append-only, cap <N>)` (`ConfigdServer.java:925-926`). Its **presence**
    confirms both auth and audit are on; its **absence** means auth is off.
- **Failure mode if left OFF (i.e. auth off):** no tamper-evident record of
  who changed what — post-incident forensics are impossible, and there is no
  detection of after-the-fact log tampering.

## Gate 4 — Replay protection ON

- **What to set:** the **system property** `-Dconfigd.replay.enabled=true`
  (`ConfigdServer.java:934`). This is a **JVM sysprop, not a CLI flag** — passing
  `--replay...` will fail arg parsing.
- **What ON means:** clients stamp each mutating request with
  `X-Configd-Timestamp` (epoch ms) and a unique `X-Configd-Nonce`
  (`ReplayGuard.java:55,58`). Requests outside a **±5-minute** window
  (`DEFAULT_WINDOW_MS = 300_000`, `ReplayGuard.java:49`) or reusing a seen nonce
  are rejected; the nonce store is bounded to **1,000,000** entries
  (`DEFAULT_MAX_NONCES`, `ReplayGuard.java:52`) with TTL + LRU eviction.
  **Scope of protection (be honest with yourself):** this defends **only against
  passive capture-and-replay**. A holder of the bearer token can still mint a
  **fresh** request (new nonce + current timestamp) — per-request content signing
  is an S8/v2 follow-up (`ReplayGuard.java:17-24`). It is not a substitute for
  Gate 1.
- **How to VERIFY:**
  - **Startup line:** `Replay guard : ON (window 300000ms, nonce cap 1000000)`
    (`ConfigdServer.java:936-937`).
  - **Behavioral:** a **replayed** nonce returns **409**; a **stale/future or
    missing** replay header returns **401** (`AdminApiHandler.java:581-601`:
    `STALE`/`MALFORMED` → 401 at :590-594, `REPLAY` → 409 at :596-598; hooked on
    `PUT` at :314-316 and `DELETE` at :368-370).
- **Failure mode if left OFF:** a captured mutating request (e.g. a `DELETE`) can
  be replayed verbatim by an on-path attacker with no time bound.

## Gate 5 — Signing key NOT co-located with the data dir

- **What to set:** `--signing-key-file <path>` pointing **outside** `--data-dir`
  (mount it on separate storage — KMS/HSM/mounted secret). This is **fail-closed
  by default**: `enforceSigningKeyNotColocated` throws a `SecurityException` and
  **refuses to boot** if the signing key lives inside the data dir it protects
  (`ConfigdServer.java:1251-1266`, invoked from `deriveRaftIntegrityEnvelope`
  `:1218`). The at-rest **integrity** key is HKDF-derived from this signing key,
  so a storage-tampering adversary who could both read the co-located key and
  rewrite artifacts could forge a valid MAC — defeating the integrity layer.
- **Opt-out (dev/test/single-node ONLY — do not use in prod):** **two** forms
  downgrade the refusal to a loud warning — the sysprop
  `-Dconfigd.security.allowColocatedSigningKey=true` **or** the env var
  `CONFIGD_ALLOW_COLOCATED_SIGNING_KEY=true` (`ConfigdServer.java:1216-1217`;
  warning banner `:1267-1277`). Leave **both** unset in production.
- **How to VERIFY:**
  - **Behavioral:** the node **boots without** a `SecurityException`, and startup
    shows **no** "signing key is CO-LOCATED" warning banner. Confirm the key path
    is genuinely outside the data dir.
  - A deliberate negative check (staging): put the key inside `--data-dir` with no
    opt-out set → the node must **refuse to start** with the D-1 message.
- **Failure mode if left mis-configured:** with the key co-located **and** the
  opt-out set, a host/storage-tampering adversary (threat A2/T3) can read the key
  and forge a valid at-rest MAC — the snapshot/WAL/state integrity guarantee
  becomes worthless.

## Gate 6 — Strong reads (freshness, NOT confidentiality)

- **What to set:** `--strong-read-prefixes secure/,<more>`. **Omitting the flag
  keeps the safe default `secure/`** (`ServerConfig.java:263-266` →
  `StrongReadPolicy.DEFAULT_PREFIX`); passing an **explicit blank** value is a
  deliberate opt-out that disables enforcement. GETs under a strong-read prefix
  are served **fail-closed linearizable** (`ConfigdServer.java:914`).
- **Important:** strong-read is a **freshness** class (no stale reads for those
  keys), **not** a confidentiality control. It does **not** encrypt anything — see
  [`deployer-must-know.md` item 1](deployer-must-know.md) (do NOT store secrets).
- **How to VERIFY:**
  - **Startup line:** `Strong reads : [secure/] (fail-closed linearizable,
    ADR-0030 INV-1)` (`ConfigdServer.java:915-916`).
- **Failure mode if disabled (explicit blank):** reads of security-relevant keys
  may be served **stale** from a non-leader, so a client can act on a
  rolled-back/old value after a failover.

## Always-on — Rate limiting (verify, don't configure)

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

---

## Sign-off

A cluster is **not** production-ready until Gates 1-6 are each **verified by their
behavioral probe** (not merely by a config flag being present) and the always-on
controls show their startup lines. Record the verifying command output per gate.

Then read [`deployer-must-know.md`](deployer-must-know.md) — five system-boundary
requirements (secrets, legacy-SUBSCRIBE segregation, single-scope, snapshot cap,
leadership placement) that the server does **not** enforce for you.

## Cross-references

- [`deployer-must-know.md`](deployer-must-know.md) — companion C2 list.
- [`readiness/v1-go-no-go-2026-07-01.md`](readiness/v1-go-no-go-2026-07-01.md) —
  §4 is the source for this gate set; §0 lists conditions C1-C4.
- [`measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md`](measurement/ec2-horizontal-2026-07-01/03-mtls-bringup.md)
  — the worked cross-box mTLS bring-up.
- [`burn-in-contract.md`](burn-in-contract.md) — the C4 first-30-days burn-in
  posture (heightened alerting thresholds, rollback triggers, on-call, exit criteria).
- [`known-limitations.md`](known-limitations.md) — the snapshot-cap / encoder-drop
  operator signals and the deployment security model.
- [`measurement/ec2-2026-06-30/02-dr-drills.md`](measurement/ec2-2026-06-30/02-dr-drills.md)
  · [`04-soak.md`](measurement/ec2-2026-06-30/04-soak.md) — DR and 6 h soak evidence.
