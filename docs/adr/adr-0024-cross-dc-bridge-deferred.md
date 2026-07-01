# ADR-0024: Cross-DC Bridge Deferred to v0.2

## Status
Accepted (2026-04-17). Defers R-07.

## Context

The v0.1 cluster runs in a single datacenter. Production deployments
that span DCs need either (a) a single Raft group with WAN replicas, or
(b) per-DC Raft groups bridged by an async replication tier.

Option (a) is rejected: WAN p99 latency dominates the commit budget and
any partition between DCs forces a leader election that disenfranchises
half the cluster.

## Decision

Defer the per-DC Raft + async bridge architecture to v0.2. v0.1
supports exactly one DC per cluster.

## Rationale

1. **WAN-stretched Raft violates the SLO.** A 70 ms cross-DC RTT
   immediately blows the `< 150 ms p99` write commit budget once the
   leader needs an out-of-DC follower for quorum.
2. **Async bridge needs its own consistency model.** Cross-DC writes
   under async bridging have last-writer-wins or CRDT semantics that
   diverge from the linearizable model v0.1 ships. The ADR for that
   model is separate work.
3. **DR is solved differently in v0.1.** The 14-d shadow-traffic
   harness (C4) and disaster-recovery runbook in Phase 10 cover the
   single-DC failure mode by directing traffic to a separately
   provisioned standby cluster.

## Consequences

- Clusters are single-DC. Multi-DC deployments require N independent
  clusters and application-layer routing.
- The capacity table notes "1 cluster per DC" as a hard rule.
- v0.2 cross-DC ADR will revisit the consistency model — this ADR
  fixes the v0.1 surface as single-DC.

## Migration Plan

- v0.2 (target Q4 2026): per-DC Raft + async bridge, with a
  separately-published consistency contract.

## Related

- R-07 (cross-DC bridge — gap-closure §5)
- ADR-0015 (multi-region topology)
- `runbooks/disaster-recovery.md` (single-DC failure mode)

## Verification

Verification: NOT YET WIRED — would require a `CrossDcBridgeTest` exercising async replication between independent clusters. The single-DC restriction is enforced operationally by the deployment manifest (`deploy/kubernetes/configd-statefulset.yaml`) which assumes a single Kubernetes namespace per cluster; tracked as iter-2 follow-up if v0.2 work begins.

- **Invalidated by:** any v0.1 deployment that places a Raft voter in a different datacenter from the leader (would WAN-stretch the consensus group).
- **Operator check:** `kubectl get nodes -o jsonpath='{.items[*].metadata.labels.topology\.kubernetes\.io/zone}'` for the configd StatefulSet reports voters from a single failure domain; cross-DC traffic is achieved only via separate clusters with application-layer routing.
