# Runbook: Control Plane Availability

**Alert:** `ConfigdControlPlaneAvailability`
**SLO:** `control.plane.availability >= 99.999%` (5×9s) over rolling 30 min
**Severity:** page (after 10 min)

## Symptoms

- Page from `ConfigdControlPlaneAvailability` after 10 minutes of
  sustained breach.
- `Configd Overview` dashboard shows `Write commit p99` panel empty or
  pegged at the timeout, and/or `Raft apply queue depth` flat at zero
  while clients see write errors.
- Per-voter probes against the HTTP API return a non-2xx status with
  no `X-Leader-Hint` header, or with each voter pointing at itself.

## Impact

Write availability has dropped below five-nines averaged over the last
30 minutes. At our scale a single bad minute consumes nearly 20% of the
monthly error budget — this alert means we are in active outage
territory. Edge reads continue from local HAMT (still bounded-stale)
but every config change submitted by an operator or system is failing.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdControlPlaneAvailability` from
   `ops/alerts/configd-slo-alerts.yaml` into the operator's paging
   service (PagerDuty / OpsGenie / equivalent) and confirm the page
   actually reaches the on-call rotation.
2. Distribute `${CONFIGD_AUTH_TOKEN}` to the on-call workstation /
   bastion so the per-voter HTTP probes below succeed.
3. Make sure `kubectl` access to the `configd` namespace is granted to
   the on-call principal — the per-voter probes shell into pods.

## Diagnosis

1. **Confirm.** Look at `Configd Overview` dashboard — `Write commit p99`
   panel and `Raft apply queue depth`. If both are sane and writes are
   succeeding right now, the alert may be lagging on a transient — wait
   one more scrape interval before deeper triage.

2. **Identify failure mode.**
   - All writes timing out → likely no Raft leader. See **No leader**
     below.
   - Writes failing with explicit Raft errors → check Raft log for
     `LeadershipLost`, `NotLeaderError`. See **Split brain** below.
   - Writes succeeding on some routes, failing on others → API gateway
     issue, not Configd. Page gateway team.

### No leader

1. Probe each voter for leader-hint. The HTTP API returns
   `X-Leader-Hint` on a write that hits a non-leader. If every voter
   responds without the header (or with itself), there is no quorum.
   <!-- TODO PA-XXXX: admin endpoint missing — there is no
   `/raft/status` or `/admin/raft/status` on HttpApiServer today.
   The leader-hint header is the only operator-visible signal.
   -->
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
2. Check voter health: `kubectl -n configd get pods -l app=configd`.
   If ≥ ⌊N/2⌋ + 1 are healthy but no leader emerges, look at the
   per-pod log leader-id transition rate — repeated elections indicate
   one voter is rejecting `RequestVote` responses.
   <!-- TODO PA-XXXX: dedicated `Leader churn` panel/metric not yet
   emitted; the per-pod log leader-id transitions are the current
   operator-visible signal. N-109 closure (iter-2): label selector
   was `app=configd-server` (wrong); StatefulSet labels pods
   `app=configd` per deploy/kubernetes/configd-statefulset.yaml. -->


### Split brain

The Raft implementation cannot have a true split brain (joint consensus
prevents it), but a network partition can leave a minority that thinks
it's leader. Symptom: two voters both omit the `X-Leader-Hint` header
from the probe above (each thinks it is leader and answers writes
locally).
<!-- TODO PA-XXXX: admin endpoint missing — without `/raft/status`
the only signal is the divergent leader-hint result of the per-voter
probe in step 1. -->

1. Identify which side has quorum (≥ ⌊N/2⌋ + 1 voters).
2. The minority leader is the side without quorum — it cannot commit,
   so its writes are failing anyway. Move to mitigation.

## Mitigation

- **No leader, voters healthy:** wait one election timeout (default
  ~1 s) for the followers to elect a new leader. If election storms
  continue (per-pod log leader-id transitions > 1/min), kill the
  most-churn-source voter pod (`kubectl -n configd delete pod <pod>`);
  the StatefulSet controller will respawn it with a fresh tick clock.
  <!-- TODO PA-XXXX: dedicated Leader-churn panel/metric not yet
  emitted; log scraping is the current signal. -->

- **No leader, < ⌊N/2⌋ + 1 voters healthy:** quorum is lost. This is a
  disaster — escalate to `disaster-recovery.md`.
- **Split brain (minority leader):** force-step-down the minority by
  deleting its pod (so a fresh start joins the majority partition).
  This is destructive of the in-flight writes the minority "leader"
  was trying to commit, all of which would have failed quorum anyway.

## Resolution

A leader exists, `X-Leader-Hint` is consistent across voters, and
`configd_write_commit_total` rate has resumed at the baseline rate
documented in `docs/perf-baseline.md`. The
`ConfigdControlPlaneAvailability` alert clears after one full
30-minute rolling window with no breaches. Mark the incident resolved
in the operator's incident-tracking system.

## Rollback

If a mitigation made the situation worse (e.g. deleting a pod broke
quorum further, or transfer-leadership pinned the cluster on an
unhealthy voter):

- Restore the deleted pod by re-applying the StatefulSet manifest:
  `kubectl -n configd rollout restart statefulset/configd` and wait
  for the deleted ordinal to re-join.
- If quorum cannot be regained, escalate to `disaster-recovery.md` and
  follow the "Quorum lost beyond automatic recovery" decision branch.

## Postmortem

Open a post-incident review within one business day. Required fields:

- Timeline (alert fired, page acknowledged, leader restored,
  alert cleared).
- Root cause (network partition? disk stall on a voter? signing-chain
  fail-close per ADR-0027?).
- Error budget consumed for the rolling 30-day window.
- Action items: what dashboard / alert / code change would have caught
  this earlier? File issues against the relevant component.

If the underlying cause was the sign-or-fail-close path, follow the
"Signing key compromise" branch of `disaster-recovery.md` for the
forensic checklist before resuming writes.

## Related

- `ProductionSloDefinitions.CONTROL_PLANE_AVAILABILITY`
- `docs/decisions/adr-0025-on-call-rotation-required.md` — escalation
  hand-off contract
- `docs/decisions/adr-0027-sign-or-fail-close.md` — the sign-or-fail-
  close decision referenced in "Do not bypass the signing chain to
  recover writes." (S3 fix in `ConfigStateMachine.signCommand`).
- `runbooks/disaster-recovery.md` — escalation target when quorum is
  unrecoverable.

## Do not

- Do not raise the SLO. Five-nines is the customer contract.
- Do not bypass the signing chain to recover writes — the verify-only
  fail-close was deliberate (see ADR-0027 and the S3 row in
  `docs/ga-review.md`; the implementation is
  `ConfigStateMachine.signCommand`).
