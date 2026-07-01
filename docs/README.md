# Configd documentation

A map of what lives here and where to go.

## By what you are trying to do

- **Write a client driver** - start with the protocol spec in [`rfc/driver-protocol/`](rfc/driver-protocol/). It is self-contained and implementable end to end, including the wire framing and golden byte vectors.
- **Deploy or operate a cluster** - [`operations/`](operations/):
  - [`operator-runsheet.md`](operations/operator-runsheet.md) - the server-side release gates (auth, mTLS, audit, replay, signing key, strong reads) and the rate limiter.
  - [`deployer-must-know.md`](operations/deployer-must-know.md) - the deployment-boundary requirements the server does not enforce for you (each is a real failure if ignored).
  - [`known-limitations.md`](operations/known-limitations.md) - what v1 does not do, stated plainly.
  - [`consistency-contract.md`](operations/consistency-contract.md) - the read and write guarantees.
  - [`burn-in-contract.md`](operations/burn-in-contract.md) - the first-30-days alerting and burn-in expectations.
  - [`production-deployment.md`](operations/production-deployment.md), [`security-heap-dump-policy.md`](operations/security-heap-dump-policy.md), and [`runbooks/`](operations/runbooks/) for specific incidents.
- **Understand why it is built this way** - the architecture decision records in [`adr/`](adr/), and the system overview in [`architecture/`](architecture/) (including the Raft owner-thread threading contract).
- **See what is coming next** - [`v2-backlog.md`](v2-backlog.md).

## Evidence and history

- [`archive/`](archive/) preserves the proof behind the shipped state, kept out of the way but not deleted:
  - `archive/readiness/` - the v1 go/no-go review and the audited readiness register.
  - `archive/measurement/` - the two paid EC2 runs (single-box durability, DR, and 6-hour soak; and the horizontal-scaling curve).
  - `archive/security/` - the trust and adversary model behind the at-rest and audit-log integrity decisions.
  - `archive/design/` and `archive/research/` - the design and research trail for the v2 items (encryption at rest, KMS and auth SPIs, the namespace and path model).

## What v1 is

A strongly-consistent, sharded, mTLS-securable configuration store. Writes go through Raft for durability; reads are served from a lock-free edge cache in microseconds. v1 runs a single region-local group by default, with sharding wired and horizontal scale proven but operator-managed. At-rest protection is integrity (tamper detection), not encryption. See [`architecture/`](architecture/) for the full picture and [`operations/known-limitations.md`](operations/known-limitations.md) for the honest edges.
