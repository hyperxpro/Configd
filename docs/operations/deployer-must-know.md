# Deployer MUST-KNOW -- Configd v1

These are **system-boundary and deployment conditions the server does NOT enforce for
you** -- distinct from the runsheet's server-side gates
([`operator-runsheet.md`](operator-runsheet.md)). Source: the v1 readiness review
([`../archive/readiness/v1-go-no-go-2026-07-01.md`](../archive/readiness/v1-go-no-go-2026-07-01.md),
section 4, the MUST-KNOW list, and section 5.1, operability caveats).

Read all six. Each is: **the requirement - why it matters (the concrete failure
if ignored) - what to do.** Every claim cites a verified `file:line` or a
measurement doc.

---

## 1. Do NOT store secrets -- at-rest is integrity-only, NOT confidential

- **Requirement.** Configd's at-rest protection is **tamper-detection
  (integrity), not encryption (confidentiality)**. Config values -- **including
  `secure/` keys** -- are stored as **plaintext `byte[]`**.
- **Why it matters.** There is **no `javax.crypto.Cipher` / AES anywhere in
  `src/main`** (verified by grep -- zero matches). At-rest uses a keyed
  **HMAC-SHA-256** integrity envelope (ADR-0042) that detects tampering but does
  **nothing** to hide the bytes. Anyone with read access to the data dir, a
  snapshot, or a backup reads every value in the clear. The `secure/` prefix buys
  read **freshness** (fail-closed linearizable reads -- see
  [runsheet Gate 6](operator-runsheet.md)), **not secrecy** -- the name is about
  staleness, not confidentiality.
- **What to do.** Keep passwords, tokens, private keys, and PII in a **dedicated
  secret manager**; store only references/handles in Configd. Encryption-at-rest
  is a **decided v2 item** (node-local AES-GCM at the ADR-0042 seam + a KMS-provider
  SPI -- designed, not built). **It is a v1 blocker *for your deployment* iff you
  must store sensitive data or meet a compliance bar** -- in that case address this
  before deploying. (See the readiness review,
  [section 4](../archive/readiness/v1-go-no-go-2026-07-01.md), item 4,
  and the "Encryption at rest -- ABSENT - v2" row.)

## 2. Grant the edge hydration identity whole-store READ -- SUBSCRIBE is gated on it

- **Requirement.** If you enable `--edge-port` with auth ON, the **edge / hydration
  identity** (the edge node's mTLS cert-DN) MUST hold **`READ` over the root prefix
  `""`** -- otherwise its whole-store `SUBSCRIBE` is refused `NOT_AUTHORIZED` at
  admission and edge hydration never starts.
- **Rollout ordering (this is a breaking change with auth ON).** The gate is new:
  existing edge certs that lack root READ are now **refused `NOT_AUTHORIZED` at
  SUBSCRIBE**. Grant the edge / hydration identities root READ **before** upgrading,
  or hydration breaks on restart.
- **Why it matters.** The same `--edge-port` serves the pre-existing **whole-store
  `SUBSCRIBE` fan-out** -- the trusted server-to-edge backbone (ADR-0038). It is now
  gated at admission on a **whole-store READ cover** (a root-prefix `READ` grant with
  no intersecting `READ` deny anywhere; `WATCH` is not required -- a `SUBSCRIBE` is a
  read feed, not a watch), enforced by `AclServiceWatchAuthorizer`
  (`configd-server/.../fanout/AclServiceWatchAuthorizer.java`). A cert that completes
  the mTLS handshake but lacks root READ is refused before any frame flows. This
  **closes the prior watch-gate bypass**: a watch-only principal (subtree
  `READ  and  WATCH`, no root READ) can no longer escalate to the whole store by opening
  a raw `SUBSCRIBE`. See
  [`known-limitations.md` section 2](known-limitations.md) ("Co-resident legacy
  SUBSCRIBE (now authorized)").
- **Authentication is not authorization -- mTLS ON but auth OFF still leaks the whole
  store.** The gate is active **only when ACL/auth is enabled** (an `--auth-token` is
  set), and auth is **decoupled from TLS** (`ServerConfig.authEnabled()` vs
  `tlsEnabled()`). In the **mTLS-REQUIRED-but-no-auth-token** posture the authorizer is
  absent, so **any valid edge cert still pulls the whole store** -- the transport is
  authenticated but nothing is authorized. There, **per-cert trust** (who you issue edge
  certs to) **plus network segregation** of the raw `SUBSCRIBE` listener from untrusted
  clients remains your **only** control -- do not drop it in that posture. Over a
  **plaintext** edge port with auth ON, the identity is the literal `"plaintext"`,
  denied unless `"plaintext"` holds root READ.
- **A whole-store-READ grant IS a whole-store read (including a live change stream).**
  Granting an identity root READ authorizes it to pull the entire store via `SUBSCRIBE`
  **and** to receive a **live `NOTIFY` change stream of the whole store** -- not just a
  point-in-time snapshot (`WATCH` capability is not required for this). That is the
  feed's purpose, not a bypass. Do not grant root READ to a principal you intend to
  restrict to a subtree; use a per-subtree grant, and reserve root READ for the
  edge/hydration backbone (and `root`).
- **Default config.** Out-of-the-box only `root` holds the root grant (and `root`
  already holds the whole store), so nothing is over-exposed by default.

## 3. `scope` is NOT a tenant-isolation boundary at N=1

- **Requirement.** Do **not** rely on the `scope` field (`GLOBAL/REGIONAL/LOCAL`)
  for authorization or tenant isolation.
- **Why it matters.** At N=1 the ACL gate, the store keys, the read path, and the
  watch filter are **uniformly scope-blind** over a single flat keyspace; `scope`
  is **forward-compat metadata** for future sharding/namespacing, not an
  authorization dimension ([`known-limitations.md` section 2](known-limitations.md), lines
  62-63; [readiness review section 4 item 5](../archive/readiness/v1-go-no-go-2026-07-01.md)). A grant or a
  watch is decided on the **key**, independent of scope -- so two "tenants"
  separated only by `scope` share one authorization namespace.
- **What to do.** Achieve tenant isolation with **distinct key prefixes + ACL
  policy** (`_acl/` roles/policies with deny-precedence), not with `scope`. Treat
  `scope` as a routing/label hint only.

## 4. Keep snapshot state < 4 MiB -- a larger InstallSnapshot wedges the follower

- **Requirement.** Keep the state a single Raft snapshot must ship **under 4 MiB**,
  and **alert** if the cap is hit.
- **Why it matters.** The Raft message codec caps a single snapshot blob at
  **`MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024`** (4 MiB) --
  `configd-server/.../RaftMessageCodec.java:76` (enforced at encode `:128`/`:133`
  and decode `:480`/`:495`). A follower needing a **> 4 MiB** `InstallSnapshot`
  never receives it: the over-cap frame is **dropped to stderr** --
  `"Dropping InstallSnapshot ... (codec rejected -- snapshot too large for v1 wire)"`
  (`configd-consensus-core/.../RaftNode.java:2074-2077`) -- and the send is
  abandoned. That follower cannot bootstrap from snapshot; if the leader has
  compacted past the entries it still needs, the follower is **permanently
  behind** ([`known-limitations.md` section "Snapshot size cap"](known-limitations.md),
  lines 186-204). Chunked `InstallSnapshot` is a **v2** item.
  - **Note (do not mis-cite):** the 4 MiB limit is the **app-layer snapshot-blob
    cap in `RaftMessageCodec`**, which is **stricter** than the transport frame cap
    `FrameCodec.MAX_FRAME_SIZE = 16 MiB` (`configd-transport/.../FrameCodec.java:104`).
    The **4 MiB** figure is the operative one; the 16 MiB frame cap is a separate,
    looser bound.
- **What to do.**
  1. Tune snapshot/compaction policy so state stays **< 4 MiB** at snapshot time.
  2. **Alert on the drop.** There is currently **no Prometheus metric** for this --
     the only signal is the **stderr line** above
     ([`known-limitations.md` section "Encoder-drop observability"](known-limitations.md),
     lines 216-224: "no metric counter exports drop frequency -- it's stderr
     only"). Scrape logs for `Dropping InstallSnapshot`.
  3. **Alert on `matchIndex` lag** -- a follower whose `matchIndex` falls and stays
     far behind the leader's `commitIndex` is the proxy for "stuck because
     snapshot install was rejected." Fold this into the first-30-days burn-in
     alerting -- the [burn-in contract](burn-in-contract.md) section 2C snapshot row.
  - This is a **deployment-conditional blocker**: it blocks **iff** expected
    snapshot state exceeds 4 MiB (see the readiness review,
    [section 5.4](../archive/readiness/v1-go-no-go-2026-07-01.md)).

## 5. Horizontal scale is OPERATOR-managed -- leadership is NOT auto-balanced

- **Requirement.** If you deploy multiple machines for horizontal write scale, you
  MUST **place and maintain one group-leader per box (1-1-1)** yourself. v1 has no
  automatic leadership balancer.
- **Why it matters.** The proven **2.45x across 3 machines** (near-linear,
  656->1075->1607 w/s;
  [`../archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`](../archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md))
  **requires** exactly one leader per box. But:
  - **`RaftNode.transferLeadership` exists in core** (`RaftNode.java:625`) **but is
    NOT exposed on any admin HTTP route** -- the only routes are `/health/live`,
    `/health/ready`, `/metrics`, and `/v1/config/` (`AdminApiHandler.java:132-144`)
    -- **and is NOT invoked on shutdown.** (The `AdminService.transferLeadership`
    interface exists but has **no wired implementation / no route** in the server.)
    So there is **no runtime lever** to place a group's leader on a chosen node.
  - **Fresh simultaneous boot rarely lands 1-1-1 (~1 in 20):** whichever node is
    ready a beat sooner "sweeps" and wins **all** its groups, biasing to
    `3-0-0`/`2-1-0`
    ([`../archive/measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md`](../archive/measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md)).
  - After a failover, leaders can **drift/pile onto one node**, collapsing
    aggregate throughput back toward the **single-box plateau (~1100 w/s)** -- or,
    per group, the single-group knee (**~800 w/s**) -- until an operator
    re-balances. (The aggregate is robust to *modest* imbalance -- a 2-1-0
    placement still sustained ~1628 w/s -- but not to a full sweep.)
- **What to do.**
  - Reach 1-1-1 by **fresh-boot-until-balanced**: boot all nodes fresh in parallel,
    check per-node `raft_shard_leader_*` counts, repeat until 1-1-1 (**~4-20 boots**,
    stochastic but reliable; 1-1-1 is a **stable fixed point at rest**).
  - **N>1 thread and shard sizing.** The Raft owner pool must have at least as many
    threads as shards; if it does not, all shards serialize on one owner thread and
    throughput does not increase -- the server logs a startup warning when this
    mismatch is detected. The shard count is capped at 16; in practice, roughly ten
    to eleven busy leaders saturate a 16-vCPU box before CPU becomes the bottleneck
    (see the archived horizontal measurement,
    [`../archive/measurement/ec2-horizontal-2026-07-01/`](../archive/measurement/ec2-horizontal-2026-07-01/)).
  - **Monitor leadership distribution continuously** and re-balance (by controlled
    restart) after failovers.
  - Do **not** rely on **sustained** multi-shard horizontal scale in v1 until the
    leadership-balancing follow-up lands (expose `transferLeadership` on an admin
    route / a balancer / transfer-on-graceful-shutdown -- see the readiness review,
    section 3.2, the one horizontal-scale operability gap). At **N=1 (the v1
    default)** this item does not apply.

## 6. Keep control-plane and edge identity policies consistent -- one person is two principals in v1

- **Requirement.** If you use both the control-plane API and the edge watch surface,
  keep the bearer token (or OIDC) policy and the certificate-DN (mTLS) policy
  consistent for the same human operator.
- **Why it matters.** In v1, the control-plane API authenticates with a **bearer
  token** and the edge authenticates with **mTLS** client certificates -- so one
  person can hold two distinct principals: their bearer/token identity on the API
  and their certificate-DN identity on the edge. Watch authorization is evaluated
  **per-principal**: a person whose bearer-token identity is read-restricted on a
  key can still watch (and therefore read) that key under their certificate-DN
  identity if the cert-DN policy grants it -- or vice versa. The two identity
  policies are **not automatically linked** and the server does not cross-check them.
- **What to do.** Treat a watch grant as equivalent to the corresponding read grant
  across both auth mechanisms. If you restrict a key for a token identity, apply
  the same restriction to the certificate-DN identity for the same person. Audit
  the `_acl/` policy for both identity types whenever you change access to sensitive
  keys.

---

## Quick reference

| # | MUST-KNOW | The failure if ignored | Enforced by server? |
|---|-----------|------------------------|---------------------|
| 1 | Don't store secrets (integrity-only at rest) | Plaintext secrets readable from disk/snapshot/backup | No -- deployment choice |
| 2 | Grant edge hydration identity root READ | Edge SUBSCRIBE refused NOT_AUTHORIZED (auth on); whole-store read requires root READ | Yes -- gated at admission (auth on) |
| 3 | `scope` is not tenant isolation | Cross-tenant read/write via scope-blind gate | No -- scope is metadata |
| 4 | Snapshot < 4 MiB | Follower wedges permanently on >4 MiB InstallSnapshot | Cap enforced; **alerting is on you** |
| 5 | Place one leader per box | Aggregate collapses to single-box plateau | No -- no auto-balancer in v1 |
| 6 | Keep cert-DN and bearer policies consistent | A person read-restricted by token can watch via cert identity | No -- operator must align both policies |

**Companion:** [`operator-runsheet.md`](operator-runsheet.md) -- the six server-side
release gates (Auth - mTLS - Audit - Replay - Signing-key - Strong-reads) plus the
always-on rate limiter.
