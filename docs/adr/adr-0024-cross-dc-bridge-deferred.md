# ADR-0024: Cross-DC Bridge Is Out of Scope

## Status
Accepted (2026-04-17)

## Context

A Configd cluster runs in a single datacenter. Production deployments
that span DCs need either (a) a single Raft group with WAN replicas, or
(b) per-DC Raft groups bridged by an async replication tier.

Option (a) is rejected: WAN p99 latency dominates the commit budget and
any partition between DCs forces a leader election that disenfranchises
half the cluster.

## Decision

Configd does not build a per-DC Raft + async bridge architecture. A
cluster supports exactly one DC; multi-DC deployments run N independent
clusters.

## Rationale

1. **WAN-stretched Raft violates the write SLO.** A 70 ms cross-DC RTT
   immediately blows the write commit budget once the leader needs an
   out-of-DC follower for quorum.
2. **An async bridge needs its own consistency model.** Cross-DC writes
   under async bridging would need last-writer-wins or CRDT semantics
   that diverge from the linearizable model Configd ships. That is a
   different consistency model, not an extension of this one.
3. **DR is solved differently.** A 14-day shadow-traffic harness and the
   disaster-recovery runbook cover the single-DC failure mode by
   directing traffic to a separately provisioned standby cluster.

## Consequences

- Clusters are single-DC. Multi-DC deployments require N independent
  clusters and application-layer routing.
- The capacity table notes "1 cluster per DC" as a hard rule.
- A cross-DC bridge would need its own consistency contract and its own
  ADR; this ADR fixes the surface as single-DC by design.

## Related

- ADR-0015 (multi-region topology)
- `ops/runbooks/disaster-recovery.md` (single-DC failure mode)

## Verification

There is no `CrossDcBridgeTest` because there is no bridge to test. The single-DC restriction is enforced operationally by the deployment manifest (`deploy/kubernetes/configd-statefulset.yaml`), which assumes a single Kubernetes namespace per cluster.

- **Invalidated by:** any deployment that places a Raft voter in a different datacenter from the leader (this would WAN-stretch the consensus group).
- **Operator check:** `kubectl get nodes -o jsonpath='{.items[*].metadata.labels.topology\.kubernetes\.io/zone}'` for the configd StatefulSet reports voters from a single failure domain; cross-DC traffic is achieved only via separate clusters with application-layer routing.
