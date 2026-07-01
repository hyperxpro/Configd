# Deployer MUST-KNOW — Configd v1

> Closes go/no-go **condition C2**: publish, as release gates, the requirements a
> deployer must know or they **create a vulnerability or an outage**. These are
> **system-boundary / deployment conditions the server does NOT enforce for you**
> — distinct from the runsheet's server-side gates
> ([`operator-runsheet.md`](operator-runsheet.md), condition C1). Source:
> [`readiness/v1-go-no-go-2026-07-01.md` §4](readiness/v1-go-no-go-2026-07-01.md)
> (the MUST-KNOW list) and §5.1 (operability caveats).

Read all five. Each is: **the requirement · why it matters (the concrete failure
if ignored) · what to do.** Every claim cites a verified `file:line` or a
measurement doc.

---

## 1. Do NOT store secrets — at-rest is integrity-only, NOT confidential

- **Requirement.** Configd's at-rest protection is **tamper-detection
  (integrity), not encryption (confidentiality)**. Config values — **including
  `secure/` keys** — are stored as **plaintext `byte[]`**.
- **Why it matters.** There is **no `javax.crypto.Cipher` / AES anywhere in
  `src/main`** (verified by grep — zero matches). At-rest uses a keyed
  **HMAC-SHA-256** integrity envelope (ADR-0042) that detects tampering but does
  **nothing** to hide the bytes. Anyone with read access to the data dir, a
  snapshot, or a backup reads every value in the clear. The `secure/` prefix buys
  read **freshness** (fail-closed linearizable reads — see
  [runsheet Gate 6](operator-runsheet.md)), **not secrecy** — the name is about
  staleness, not confidentiality (RR-098).
- **What to do.** Keep passwords, tokens, private keys, and PII in a **dedicated
  secret manager**; store only references/handles in Configd. Encryption-at-rest
  is a **decided v2 item** (node-local AES-GCM at the ADR-0042 seam + a KMS-provider
  SPI — designed, not built). **It is a v1 blocker *for your deployment* iff you
  must store sensitive data or meet a compliance bar** — in that case raise RR-098
  before deploying. (See [go/no-go §4](readiness/v1-go-no-go-2026-07-01.md) item 4
  and the "Encryption at rest — ABSENT → v2" row.)

## 2. Segregate legacy-SUBSCRIBE from watch clients — LOUD: this is an authz bypass if missed

- **Requirement.** If you enable `--edge-port`, you MUST **segregate watch clients
  from edge-cache subscribers at the network boundary.** The server does **not**
  enforce this.
- **Why it matters (concrete bypass).** The same `--edge-port` also serves the
  pre-existing **whole-store `SUBSCRIBE` fan-out** — the trusted server↔edge
  backbone (ADR-0038) — which has **NO per-key ACL**. **Any cert that completes
  the mTLS handshake can pull the ENTIRE store via `SUBSCRIBE`, bypassing the
  watch-authorization gate.** The per-key watch gate (`AclServiceWatchAuthorizer`,
  `configd-server/.../fanout/AclServiceWatchAuthorizer.java`) only constrains
  clients that **cannot reach** the legacy `SUBSCRIBE` path; a legacy
  `SUBSCRIBE`-first connection never enters the authorizing veneer at all
  (`WatchMultiplexSink.java:25-26`). This is documented as the deployment security
  model in
  [`known-limitations.md` §2](known-limitations.md) (lines 49-58: "A cert that
  completes the mTLS handshake can obtain the whole store via SUBSCRIBE, bypassing
  the watch gate").
- **Not reachable in the default config — but load-bearing the moment you grant a
  non-root watch.** Out-of-the-box only `root` can watch, and `root` already holds
  the whole store, so nothing is leaked *by default*. The bypass becomes real the
  instant you grant a **non-root** watch on a **shared** edge port: that principal
  can sidestep its per-key grant by opening a raw `SUBSCRIBE`.
- **What to do.** Deploy watch clients **segregated** from edge-cache subscribers —
  a **separate trust anchor** for edge-cache subscribers, or the intended
  server↔edge↔client topology where clients cannot reach the raw `SUBSCRIBE`
  listener. Gating/segregating the legacy `SUBSCRIBE` path is a **tracked v1/v2
  hardening follow-up**, **not a server-enforced control in v1**. Until then,
  network segregation is mandatory before any non-root watch grant.

## 3. `scope` is NOT a tenant-isolation boundary at N=1

- **Requirement.** Do **not** rely on the `scope` field (`GLOBAL/REGIONAL/LOCAL`)
  for authorization or tenant isolation.
- **Why it matters.** At N=1 the ACL gate, the store keys, the read path, and the
  watch filter are **uniformly scope-blind** over a single flat keyspace; `scope`
  is **forward-compat metadata** for future sharding/namespacing, not an
  authorization dimension ([`known-limitations.md` §2](known-limitations.md), lines
  62-63; [go/no-go §4 item 5](readiness/v1-go-no-go-2026-07-01.md)). A grant or a
  watch is decided on the **key**, independent of scope — so two "tenants"
  separated only by `scope` share one authorization namespace.
- **What to do.** Achieve tenant isolation with **distinct key prefixes + ACL
  policy** (`_acl/` roles/policies with deny-precedence), not with `scope`. Treat
  `scope` as a routing/label hint only.

## 4. Keep snapshot state < 4 MiB — a larger InstallSnapshot wedges the follower

- **Requirement.** Keep the state a single Raft snapshot must ship **under 4 MiB**,
  and **alert** if the cap is hit.
- **Why it matters.** The Raft message codec caps a single snapshot blob at
  **`MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024`** (4 MiB) —
  `configd-server/.../RaftMessageCodec.java:76` (enforced at encode `:128`/`:133`
  and decode `:480`/`:495`). A follower needing a **> 4 MiB** `InstallSnapshot`
  never receives it: the over-cap frame is **dropped to stderr** —
  `"Dropping InstallSnapshot ... (codec rejected — snapshot too large for v1 wire)"`
  (`configd-consensus-core/.../RaftNode.java:2074-2077`) — and the send is
  abandoned. That follower cannot bootstrap from snapshot; if the leader has
  compacted past the entries it still needs, the follower is **permanently
  behind** ([`known-limitations.md` §"Snapshot size cap"](known-limitations.md),
  lines 186-204). Chunked `InstallSnapshot` is a **v2** item.
  - **Note (do not mis-cite):** the 4 MiB limit is the **app-layer snapshot-blob
    cap in `RaftMessageCodec`**, which is **stricter** than the transport frame cap
    `FrameCodec.MAX_FRAME_SIZE = 16 MiB` (`configd-transport/.../FrameCodec.java:104`).
    The **4 MiB** figure is the operative one; the 16 MiB frame cap is a separate,
    looser bound.
- **What to do.**
  1. Tune snapshot/compaction policy so state stays **< 4 MiB** at snapshot time.
  2. **Alert on the drop.** There is currently **no Prometheus metric** for this —
     the only signal is the **stderr line** above
     ([`known-limitations.md` §"Encoder-drop observability"](known-limitations.md),
     lines 216-224: "no metric counter exports drop frequency — it's stderr
     only"). Scrape logs for `Dropping InstallSnapshot`.
  3. **Alert on `matchIndex` lag** — a follower whose `matchIndex` falls and stays
     far behind the leader's `commitIndex` is the proxy for "stuck because
     snapshot install was rejected." Fold this into the first-30-days burn-in
     alerting — the [burn-in contract](burn-in-contract.md) §2C snapshot row
     (go/no-go **C4**).
  - This is a **deployment-conditional blocker**: it blocks **iff** expected
    snapshot state exceeds 4 MiB ([go/no-go §5.4](readiness/v1-go-no-go-2026-07-01.md)).

## 5. Horizontal scale is OPERATOR-managed — leadership is NOT auto-balanced

- **Requirement.** If you deploy multiple machines for horizontal write scale, you
  MUST **place and maintain one group-leader per box (1-1-1)** yourself. v1 has no
  automatic leadership balancer.
- **Why it matters.** The proven **2.45× across 3 machines** (near-linear,
  656→1075→1607 w/s;
  [`measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`](measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md))
  **requires** exactly one leader per box. But:
  - **`RaftNode.transferLeadership` exists in core** (`RaftNode.java:625`) **but is
    NOT exposed on any admin HTTP route** — the only routes are `/health/live`,
    `/health/ready`, `/metrics`, and `/v1/config/` (`AdminApiHandler.java:132-144`)
    — **and is NOT invoked on shutdown.** (The `AdminService.transferLeadership`
    interface exists but has **no wired implementation / no route** in the server.)
    So there is **no runtime lever** to place a group's leader on a chosen node.
  - **Fresh simultaneous boot rarely lands 1-1-1 (~1 in 20):** whichever node is
    ready a beat sooner "sweeps" and wins **all** its groups, biasing to
    `3-0-0`/`2-1-0`
    ([`measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md`](measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md)).
  - After a failover, leaders can **drift/pile onto one node**, collapsing
    aggregate throughput back toward the **single-box plateau (~1100 w/s)** — or,
    per group, the single-group knee (**~800 w/s**) — until an operator
    re-balances. (The aggregate is robust to *modest* imbalance — a 2-1-0
    placement still sustained ~1628 w/s — but not to a full sweep.)
- **What to do.**
  - Reach 1-1-1 by **fresh-boot-until-balanced**: boot all nodes fresh in parallel,
    check per-node `raft_shard_leader_*` counts, repeat until 1-1-1 (**~4-20 boots**,
    stochastic but reliable; 1-1-1 is a **stable fixed point at rest**).
  - **Monitor leadership distribution continuously** and re-balance (by controlled
    restart) after failovers.
  - Do **not** rely on **sustained** multi-shard horizontal scale in v1 until the
    leadership-balancing follow-up lands (expose `transferLeadership` on an admin
    route / a balancer / transfer-on-graceful-shutdown — go/no-go §3.2, the one
    horizontal-scale operability gap). At **N=1 (the v1 default)** this item does
    not apply.

---

## Quick reference

| # | MUST-KNOW | The failure if ignored | Enforced by server? |
|---|-----------|------------------------|---------------------|
| 1 | Don't store secrets (integrity-only at rest) | Plaintext secrets readable from disk/snapshot/backup | No — deployment choice |
| 2 | Segregate legacy-SUBSCRIBE | Whole-store read bypasses per-key watch ACL | No — network boundary |
| 3 | `scope` ≠ tenant isolation | Cross-tenant read/write via scope-blind gate | No — scope is metadata |
| 4 | Snapshot < 4 MiB | Follower wedges permanently on >4 MiB InstallSnapshot | Cap enforced; **alerting is on you** |
| 5 | Place one leader per box | Aggregate collapses to single-box plateau | No — no auto-balancer in v1 |

**Companion:** [`operator-runsheet.md`](operator-runsheet.md) — the six server-side
release gates (Auth · mTLS · Audit · Replay · Signing-key · Strong-reads) plus the
always-on rate limiter.
