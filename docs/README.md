# Configd documentation

A map of what lives here and where to go.

## By what you are trying to do

- **Write a client driver** — start with the protocol spec in [`rfc/driver-protocol/`](rfc/driver-protocol/).
  It is self-contained and implementable end to end, including the wire framing and golden byte vectors,
  and it is the normative specification for both planes: the driver-facing edge/fan-out plane and the
  intra-cluster Raft plane. It is validated byte-for-byte against the codecs and golden fixtures. A
  conforming **Java reference client** (`configd-client` plus `-core`/`-http`/`-edge`) and the
  `configd-conformance` suite ship as the worked example. ADR-0028 and ADR-0029 record why the framing is
  shaped this way; the RFC records what the bytes are, and where a diagram and the RFC disagree, the RFC
  and the code win.
- **Deploy or operate a cluster** — [`operations/`](operations/):
  - [`operator-runsheet.md`](operations/operator-runsheet.md) — the server-side release controls (auth,
    mTLS, audit, replay protection, signing key, strong reads) and the rate limiter.
  - [`deployer-must-know.md`](operations/deployer-must-know.md) — the deployment-boundary requirements the
    server does not enforce for you; each is a real failure if ignored.
  - [`known-limitations.md`](operations/known-limitations.md) — what Configd does not do, stated plainly.
  - [`consistency-contract.md`](operations/consistency-contract.md) — the read and write guarantees.
  - [`burn-in-contract.md`](operations/burn-in-contract.md) — the first-30-days alerting and burn-in
    expectations.
  - [`production-deployment.md`](operations/production-deployment.md) and
    [`security-heap-dump-policy.md`](operations/security-heap-dump-policy.md).
  - The incident runbooks live at [`ops/runbooks/`](../ops/runbooks/) (repo root) — the canonical set an
    operator follows during an incident.
- **Get started building and running** — [`wiki/Getting-Started.md`](wiki/Getting-Started.md), with a
  lighter tour in [`wiki/Architecture-Overview.md`](wiki/Architecture-Overview.md) and container notes in
  [`wiki/Docker.md`](wiki/Docker.md).
- **Call the HTTP API** — [`wiki/HTTP-API.md`](wiki/HTTP-API.md): every endpoint, query parameter,
  header, and status code on both services.
- **Look up a configuration knob** — [`wiki/Configuration.md`](wiki/Configuration.md): every CLI
  argument and `configd.*` property with its default; the
  [operator runsheet](operations/operator-runsheet.md) stays the guide to which ones to enable.
- **Decide whether Configd fits** — [`wiki/Comparison.md`](wiki/Comparison.md) (versus etcd, Consul,
  ZooKeeper, Spring Cloud Config) and the [`wiki/FAQ.md`](wiki/FAQ.md).
- **Understand how it works** — the system overview and load-bearing invariants in
  [`architecture/`](architecture/) (including the multi-shard-watch authorization invariant and the Raft
  owner-thread threading contract).
- **Read the format internals** — also under [`architecture/`](architecture/): the at-rest/on-disk
  format spec ([`frozen-format-v1.md`](architecture/frozen-format-v1.md)), the peer-quorum anchor
  witness, and the node-join and upgrade contracts as built.
- **Understand the threat model** — [`architecture/threat-model.md`](architecture/threat-model.md):
  the trust domains and adversary model behind the at-rest and audit-log integrity decisions.
- **Understand a decision** — the architecture decision records in [`adr/`](adr/).

## Evidence and history

Measured claims in these docs (failover, staleness bounds, throughput knees, the faulted
linearizability matrix) state their numbers inline. The raw measurement runs and the
design/research decision trail behind them were removed from the working tree and are pinned in
git history (`docs/measurement/`, `docs/archive/`).

## What Configd is

A strongly-consistent, sharded, mTLS-securable configuration store with a full authentication system
(No-Auth, HTTP Basic, Bearer, mTLS, OIDC/JWT). Writes go through Raft for durability and linearizability;
reads are served from a lock-free edge cache in microseconds. The default is a single region-local Raft
group, with sharding wired, horizontal scale proven, and leadership auto-balanced. At-rest protection is
integrity (tamper detection) by default, with opt-in AES-256-GCM encryption available behind a KMS
provider. See [`architecture/`](architecture/) for the full picture and
[`operations/known-limitations.md`](operations/known-limitations.md) for the honest edges.
