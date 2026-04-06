# Runbook: Write Commit Latency

**Alert:** `ConfigdWriteCommitFastBurn`, `ConfigdWriteCommitSlowBurn`
**SLO:** `write.commit.latency.p99 < 150 ms`
**Severity:** page (fast-burn after 2 min, slow-burn after 15 min)

## Symptoms

- `ConfigdWriteCommitFastBurn` paged within 2 minutes of breach, or
  `ConfigdWriteCommitSlowBurn` paged at the 15-minute mark.
- `Configd Overview` dashboard shows `configd_write_commit_seconds` p99
  sitting above 150 ms across multiple scrape intervals.
- Per-stage `_seconds_bucket` panels show one stage (sign / append /
  replicate / apply) dominating the budget.

## Impact

A control-plane write (PutConfig / DeleteConfig) is the round-trip from
client RPC to Raft log commit on a quorum of voters. Crossing 150 ms p99
indicates one of the leader's commit pipeline stages is degrading. The
fast-burn variant is paging because we are exhausting the monthly error
budget at 14.4× the steady-state rate — left unchecked the budget is
gone in under one hour.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdWriteCommitFastBurn` / `SlowBurn` from
   `ops/alerts/configd-slo-alerts.yaml` into the on-call paging
   service.
2. Provision the Grafana dashboard `ops/dashboards/configd-overview.json`
   into the operator's Grafana instance.
3. Distribute `${CONFIGD_AUTH_TOKEN}` (with admin scope) so the
   transfer-leadership command below can run from the on-call
   workstation.

## Diagnosis

1. **Confirm scope.** Open the `Configd Overview` Grafana dashboard
   (`ops/dashboards/configd-overview.json`) and check whether the breach is
   isolated to one cluster or global. Cross-check the **Raft apply queue
   depth** panel (`configd_raft_pending_apply_entries`) and per-pod
   leader-id transitions in the logs (no dedicated leader-churn metric
   yet).
   <!-- TODO PA-XXXX: dedicated `Leader churn` panel/metric not yet
   emitted; per-pod log scraping for leader-id transitions is the
   current operator-visible signal. -->


2. **Identify the bottleneck stage.** The commit pipeline has four
   measurable stages:
   - serialize + sign (client thread)
   - leader Raft append (disk fsync)
   - replicate to quorum (network)
   - apply on followers (state machine)
   Inspect the corresponding `_seconds_bucket` series for each — the one
   with the worst p99 is the one to investigate.

3. **Eliminate likely root causes.**
   - Disk: `iostat -x 1` on the leader. fsync queue depth growing → disk
     saturation, page out the platform team.
   - Network: check `node_network_transmit_drop_total` and link MTU
     between voters.
   - State machine: a regression in HAMT mutation cost can cascade. Check
     `configd_apply_seconds` p99 and `git log -p` on `configd-config-store`
     for recent changes.

## Mitigation

Acceptable tactical mitigations until the root cause is fixed:

- Step down the current leader to pick a healthier voter.
  <!-- TODO PA-XXXX: admin endpoint missing — HttpApiServer does
  not yet expose `POST /admin/raft/transfer-leadership`. Until it
  ships, the only operator-visible "step down" is to delete the
  current leader pod (`kubectl -n configd delete pod <leader>`) so
  a re-election picks a different voter; the PDB ensures only the
  leader is evicted. -->
  ```sh
  curl -fsS -X POST \
    -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
    "http://configd-0.configd.svc:8080/admin/raft/transfer-leadership?to=<voter-id>"
  ```
- If apply-queue saturation: throttle ingress at the API gateway. Do
  **not** disable the SLO check.

## Resolution

`configd_write_commit_seconds` p99 returns below 150 ms across the next
two scrape intervals; both fast-burn and slow-burn alerts clear after
their respective windows. The root cause is identified (disk, network,
or state-machine regression) and a follow-up ticket is filed.

If the regression is a state-machine cost change, `git revert` the
offending commit to `configd-config-store` and re-deploy; the apply-
queue should drain within one minute.

## Rollback

If transfer-leadership picked a worse voter (visible in per-pod log
leader-id transitions), transfer back to a healthier voter or delete
the new leader's pod so re-election picks again. If a recent code
deploy correlates with the latency rise, follow the standard rollback
in `release.md` (`kubectl rollout undo statefulset/configd -n configd`).
<!-- TODO PA-XXXX: dedicated Leader-churn panel/metric not yet emitted;
log-scraping is the current signal. -->


## Postmortem

Open a post-incident review within one business day. Required fields:

- Which pipeline stage was the bottleneck and why.
- Whether disk / network / code change was the root cause.
- Was the SLO budget exhausted? Cross-check the in-process `SloTracker`
  exports.
  <!-- TODO PA-XXXX: metric configd_slo_budget_seconds_remaining not
  yet emitted by ConfigdMetrics; the in-process SloTracker is
  authoritative until a derived gauge ships. -->

- Action items: instrumentation gap (which `_seconds_bucket` was missing
  and would have caught this earlier), capacity gap (do we need a
  bigger fsync-bandwidth pool?), or a regression-test gap.

## Related

- `docs/decisions/adr-0007-deterministic-simulation-testing.md` — the
  simulation-testing ADR that exercises the commit pipeline under
  injected disk/network faults; the regression test for any commit-
  pipeline change MUST include a sim-test case (per §8.1 / §8.7).
- `docs/decisions/adr-0001-embedded-raft-consensus.md` — the embedded-
  Raft pipeline whose stages are listed in Diagnosis step 2.
- `ProductionSloDefinitions.WRITE_COMMIT_LATENCY_P99`

## Do not

- Do not raise the SLO threshold. The 150 ms budget is the contract; the
  fix is in the system, not the alert.
- Do not silence the alert without opening an incident ticket.
