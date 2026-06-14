# Runbook: Control-Plane Availability / Leader Loss / Partition

**Alert:** `ConfigdControlPlaneAvailability` (page, 10m)
**SLO:** control-plane write availability ≥ 99.999% over rolling 30 min
(`1 − failed/(failed+total)`)
**Severity:** page

Covers the whole partition family: no leader, network partition / region
loss, and failover. Write availability has dropped below five-nines — this
is active-outage territory. Edge reads continue (bounded-stale from local
HAMT) but config writes are failing.

> Reconciled for S6: the only operator-visible surfaces on `HttpApiServer`
> are `/health/live`, `/health/ready`, `/metrics`, and `/v1/config/<key>`.
> There is **no** `/admin/*` or `/raft/status` endpoint. Leader identity is
> read from the `X-Leader-Hint` response header (a non-leader write returns
> `503` + that header). There is **no** operator-triggerable add/remove-server
> RPC (`proposeMembershipChange` is unwired): a node reset keeps its StatefulSet
> ordinal (= node-id) so membership is unchanged; a permanent topology change
> rebuilds via [restore-from-snapshot.md](restore-from-snapshot.md) /
> [disaster-recovery.md](disaster-recovery.md).

## Symptom

- `ConfigdControlPlaneAvailability` pages after 10 min of sustained breach.
- `Configd Control Plane` dashboard **"Write throughput + outcomes"** shows
  `failed` (`configd_write_commit_failed_total`) rising and `committed`
  (`configd_write_commit_total`) flat/zero.
- Writes return `503` (NotLeader / lost leadership) — clients see no
  `X-Leader-Hint`, or every voter points at itself.

## Diagnosis

1. **Confirm it is real, not a lagging transient.** `Configd Control Plane`
   **"Write throughput + outcomes"**: if `committed` is flowing right now,
   the alert is trailing a brief blip — wait one scrape before deeper triage.
2. **Find the leader (or prove there is none).** A write to a non-leader
   returns `503` with `X-Leader-Hint: <id>`. Probe each voter:
   ```sh
   for pod in configd-0 configd-1 configd-2; do
     echo "== $pod =="
     kubectl -n configd exec "$pod" -- \
       curl -sS -o /dev/null -D - -X PUT \
         -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
         --data-binary 'probe' \
         "http://localhost:8080/v1/config/__probe__/leader-check" \
       | grep -iE 'X-Leader-Hint|HTTP/' || true
   done
   ```
   - All point at the same `X-Leader-Hint` id → a leader exists; the outage
     is elsewhere (gateway, or that leader is wedged → step 4).
   - None carry the header (or each names itself) → no quorum / no leader.
3. **Check voter health and quorum.**
   ```sh
   kubectl -n configd get pods -l app=configd
   ```
   (The StatefulSet labels pods `app=configd`, per
   `deploy/kubernetes/configd-statefulset.yaml`.) Need ≥ ⌊N/2⌋+1 healthy.
4. **Leader churn vs. wedged leader.** `Configd Control Plane` **"Term
   changes / min"** (`rate(configd_raft_elections_total[5m]) * 60`):
   - Climbing → election storm (a voter with a stale log keeps forcing
     elections). Identify it from per-pod logs (leader-id / term transitions).
   - Flat with a leader present but no commits → the leader is wedged (apply
     stalled or RR-103-family inflight starvation) → cross to
     [raft-saturation.md](raft-saturation.md).

## Resolution steps

1. **No leader, ≥ quorum voters healthy:** wait one election timeout
   (~1 s default) for a new leader. If elections keep storming (term
   changes > 1/min), recycle the churn-source voter so it restarts with a
   fresh tick clock:
   ```sh
   kubectl -n configd delete pod <churn-source-pod>
   ```
2. **No leader, < quorum healthy (region/majority loss):** quorum is lost —
   this is a disaster. Do **not** force-reconfigure (split-brain risk).
   Restore connectivity / failed voters first; partitioned voters rejoin
   automatically via AppendEntries / InstallSnapshot once reachable. If the
   region is permanently gone, escalate to
   [disaster-recovery.md](disaster-recovery.md) to rebuild capacity — there is
   no operator-triggerable membership-change RPC; redeploy a node into the
   surviving region (it rejoins at its existing node-id) or restore a fresh
   cluster from a snapshot.
3. **Minority "leader" after a partition:** Raft quorum prevents true
   split brain — a minority leader cannot commit, so its writes already fail
   quorum. Force it to restart into the majority partition:
   ```sh
   kubectl -n configd delete pod <minority-pod>
   ```
4. **Leader present but wedged (no commits, term flat):** step it down by
   deleting only the named leader pod so re-election picks a healthy voter (a
   direct `delete pod` removes exactly that pod; the PodDisruptionBudget
   `maxUnavailable: 1` bounds concurrent disruptions so a second voter is not
   taken down with it):
   ```sh
   kubectl -n configd delete pod <leader>
   ```
   If apply backlog is the cause, follow [raft-saturation.md](raft-saturation.md).
5. **Do not** bypass the signing chain to "recover" writes — the verify-only
   fail-close (ADR-0027, `ConfigStateMachine.signCommand`) is deliberate. Do
   not raise the SLO.

## Verification

- A consistent `X-Leader-Hint` across all voters (one leader).
- `Configd Control Plane` **"Write throughput + outcomes"**: `committed` rate
  back at baseline, `failed` returns to ~0.
- `ConfigdControlPlaneAvailability` clears after one full 30-min rolling
  window with no breach.
- A test write commits and reads back.

## Escalation

- < quorum healthy and connectivity not restorable → page the IC and go to
  [disaster-recovery.md](disaster-recovery.md) ("quorum lost beyond automatic
  recovery").
- Writes failing on some gateway routes but the cluster is healthy → page the
  API-gateway team (not Configd).
- Suspected signing-chain fail-close as root cause → follow the "Signing key
  compromise" branch of [disaster-recovery.md](disaster-recovery.md) before
  resuming writes.

## Validation (fault injection)

`gates/rr-002-blackhole-drill.sh` `iptables -j DROP`s one follower's raft
port (SYN black-hole — the real production fault; REJECT would not reproduce
it) and proves the leader keeps committing on the surviving quorum.
`gates/e2e-compose-scenario.sh` phase 2 SIGKILLs the leader mid-stream and
asserts a new leader is elected, writes resume, and no edge cursor ever goes
backwards across failover. The availability metric
(`configd_write_commit_failed_total`) emission is proven by
`MetricsWiringContractTest.uncommittedWriteRecordsFailureCounter` (forced
uncommittable leader). Recovery-verified = a new `X-Leader-Hint` is stable
and `configd_write_commit_total` resumes.

## Related

- `docs/decisions/adr-0025-on-call-rotation-required.md` — escalation contract.
- `docs/decisions/adr-0027-sign-or-fail-close.md` — the fail-close path.
- [raft-saturation.md](raft-saturation.md), [disaster-recovery.md](disaster-recovery.md),
  [disk-full-fsync.md](disk-full-fsync.md)
