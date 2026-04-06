# confused-new-engineer — iter-001
**Findings:** 22

It is 03:02. PagerDuty fired `ConfigdControlPlaneAvailability`. I have
never seen this codebase. Below is every place I would freeze, page my
manager, or do something destructive because the runbook left me without
a verifiable next step.

## 001 — `raftctl` CLI is referenced everywhere but does not exist on disk
- **Severity:** S1
- **Location:** `ops/runbooks/README.md:30`, `ops/runbooks/control-plane-down.md:31,45,48`, `ops/runbooks/write-commit-latency.md:43`, `ops/runbooks/raft-saturation.md:27`, `ops/runbooks/restore-from-snapshot.md:144`, `ops/runbooks/disaster-recovery.md:100,102`
- **Category:** missing-command
- **Evidence:** README says "These runbooks assume the responder ... has shell access to the cluster, the Grafana dashboard ..., and the `raftctl` CLI." Then control-plane-down.md says `Run \`raftctl status\` — if no node reports \`leader\`, the cluster has no quorum.`
- **What I'd do at 3am:** `which raftctl` — nothing. `find ~/Programming/Configd -name 'raftctl*'` — nothing. Page next-tier on-call and ask "where do I get raftctl?"
- **Fix direction:** either ship the binary (or document `kubectl exec configd-0 -- /usr/local/bin/raftctl` if it's bundled in the image), or replace every `raftctl` invocation with the `curl http://.../raft/status` HTTP equivalent (which also does not exist — see finding 002).
- **Proposed owner:** ops/runbooks

## 002 — `/raft/status` HTTP endpoint is invoked by restore runbook but is not implemented
- **Severity:** S1
- **Location:** `ops/runbooks/restore-from-snapshot.md:122,133`
- **Category:** missing-command
- **Evidence:** `kubectl -n configd exec configd-0 -- curl -sf http://localhost:8080/raft/status | jq '.last_applied_index'`
- **What I'd do at 3am:** Run it, see HTTP 404 (only `/health/live`, `/health/ready`, `/metrics` are registered in `configd-server/src/main/java/io/configd/server/HttpApiServer.java:74-81`), conclude restore is broken, abort restore, escalate. The very check that proves the snapshot loaded does not exist.
- **Fix direction:** implement `/raft/status` returning JSON with `last_applied_index`, or rewrite the verification to query `configd_raft_*` metrics from `/metrics`.
- **Proposed owner:** ops/runbooks

## 003 — None of the Prometheus metrics referenced in the runbooks are registered in code
- **Severity:** S1
- **Location:** `ops/runbooks/edge-read-latency.md:25,33`, `ops/runbooks/propagation-delay.md:22,38`, `ops/runbooks/disaster-recovery.md:36`, `ops/runbooks/raft-saturation.md:4,17`, `ops/runbooks/write-commit-latency.md:30,40`
- **Category:** undefined-metric
- **Evidence:** "Inspect the corresponding `_seconds_bucket` series for each", "The metric `configd_snapshot_rebuild_total` should increment only on leader-change", "Confirm via `configd_edge_read_total` rate", "Query `configd_edge_apply_lag_seconds` per edge", "Check `configd_changefeed_backlog_bytes`", "Confirm via `configd_write_commit_total` rate going to 0", "`configd_raft_pending_apply_entries > 5000`".
- **What I'd do at 3am:** Open Grafana / Prometheus, type `configd_write_commit_total` — no series. Type each name from the runbooks — no series. The only registered metric in the codebase is `configd_raft_commit_count_total` (`configd-observability/src/test/java/io/configd/observability/PrometheusExporterTest.java:93`); `MetricsRegistry.counter(...)` calls in the live tree only register `propagation.lag.violation`. There is nothing to triage from. Page back upstream.
- **Fix direction:** either wire the metrics in code (instrument `ConfigStateMachine.signCommand`, the apply path, the changefeed path, etc.) before GA, or rewrite the runbooks to use the metric names that actually exist. The drift here is identical in shape to the `docs/runbooks/` purge that ga-review.md called out — but the canonical `ops/runbooks/` set has the exact same problem.
- **Proposed owner:** ops/runbooks

## 004 — `Configd Overview` Grafana dashboard has no install / URL pointer
- **Severity:** S2
- **Location:** `ops/runbooks/control-plane-down.md:16`, `ops/runbooks/write-commit-latency.md:18`, `ops/runbooks/README.md:29`
- **Category:** broken-link
- **Evidence:** "Look at `Configd Overview` dashboard — `Write commit p99` panel and `Raft apply queue depth`."
- **What I'd do at 3am:** Open Grafana — there is no dashboard called `Configd Overview` because nobody told me to import the JSON at `ops/dashboards/configd-overview.json`. Spend 20 min trying to import the JSON; discover it requires a `cluster` template variable that the JSON declares but does not document. Type `histogram_quantile(...)` queries by hand from `ops/alerts/configd-slo-alerts.yaml` instead.
- **Fix direction:** add a "Dashboard URL" line to each runbook header (e.g. `Dashboard: https://grafana.<env>/d/configd-overview`), or at minimum link `ops/dashboards/configd-overview.json` and document the import procedure in `ops/runbooks/README.md`.
- **Proposed owner:** ops/runbooks

## 005 — Bucket-unit mismatch: every histogram alert in the file may silently never fire
- **Severity:** S1
- **Location:** `ops/alerts/configd-slo-alerts.yaml:23,42`, `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:42-47`
- **Category:** undefined-metric
- **Evidence:** Alert queries `configd_write_commit_seconds_bucket{le="0.150"}`. Exporter ladder is `{1, 5, 10, 50, 100, 500, 1000, ...}` (integer/long, unit-agnostic). Operator must align units, but no operator config exists; the runbooks themselves do not say "convert le to seconds."
- **What I'd do at 3am:** assume the alert is real because it paged me, follow `write-commit-latency.md`, see no histogram series matching the threshold, conclude the system is healthy and the alert is buggy, silence the alert. Now write commit latency genuinely degrades and there is no alert. Disaster.
- **Fix direction:** either ship a default `bucketBounds` whose values match the alert thresholds (seconds: `0.001, 0.005, ..., 0.150, 0.5, 1.0, 5.0`), or set `le` thresholds in alerts to match nanoseconds. handoff.md §5.3 acknowledges this but ga-review.md still marks it YELLOW; for a 3am responder, this is a silent-no-fire S1.
- **Proposed owner:** ops/runbooks

## 006 — `disaster-recovery.md` references `./ops/scripts/restore-snapshot.sh` which does not exist
- **Severity:** S1
- **Location:** `ops/runbooks/disaster-recovery.md:95`
- **Category:** missing-command
- **Evidence:** `./ops/scripts/restore-snapshot.sh --snapshot=<URI> --target=data-configd-0`
- **What I'd do at 3am:** `ls ops/scripts/` — directory does not exist. Search the repo — no file named `restore-snapshot.sh`. Stop. Page incident commander. The runbook's "last resort" path requires a tool I cannot find or write at 3am.
- **Fix direction:** create `ops/scripts/restore-snapshot.sh` with the operator-glue contract documented inline, or replace this step with an explicit `kubectl apply -f -` Job manifest (the way restore-from-snapshot.md §5 already does it).
- **Proposed owner:** ops/runbooks

## 007 — `disaster-recovery.md` applies `deploy/kubernetes/configd-bootstrap.yaml` which does not exist
- **Severity:** S1
- **Location:** `ops/runbooks/disaster-recovery.md:98`
- **Category:** broken-link
- **Evidence:** `kubectl apply -f deploy/kubernetes/configd-bootstrap.yaml`
- **What I'd do at 3am:** `ls deploy/kubernetes/` shows only `configd-statefulset.yaml`. Apply the statefulset instead? It will come up as a 3-replica cluster, not a single-voter bootstrap, and likely re-partition. Stop and escalate.
- **Fix direction:** ship a `configd-bootstrap.yaml` (single-voter, `INITIAL_CLUSTER=configd-0`), or rewrite step to scale the existing statefulset to `replicas=1` first (mirroring restore-from-snapshot.md §6).
- **Proposed owner:** ops/runbooks

## 008 — Restore conformance check script does not exist
- **Severity:** S1
- **Location:** `ops/runbooks/restore-from-snapshot.md:165`
- **Category:** missing-command
- **Evidence:** `./ops/scripts/restore-conformance-check.sh --snapshot=/tmp/restore.snap --cluster-endpoint=https://configd-0.configd.svc:8080`
- **What I'd do at 3am:** Same as 006 — script does not exist. Cannot mark restore "complete" by the runbook's own definition. The restore-from-snapshot.md drill (handoff Pre-GA Step 3) is therefore unrunnable.
- **Fix direction:** ship the script or remove the step (and explicitly document an alternative pass criterion such as the InvariantMonitor.assertAll() the handoff alludes to).
- **Proposed owner:** ops/runbooks

## 009 — "Page the gateway team" / "page platform team" — no rotation defined
- **Severity:** S2
- **Location:** `ops/runbooks/control-plane-down.md:28`, `ops/runbooks/write-commit-latency.md:35`, `ops/runbooks/propagation-delay.md:33`
- **Category:** missing-owner
- **Evidence:** "Writes succeeding on some routes, failing on others → API gateway issue, not Configd. Page gateway team." / "fsync queue depth growing → disk saturation, page out the platform team."
- **What I'd do at 3am:** I have no idea who the gateway team or platform team are at this org. ADR-0025 says rotation is operator-procured and Configd will not ship a roster. Nothing in the runbook tells me how to look it up. I open PagerDuty, search "gateway", get five teams, page one at random.
- **Fix direction:** add an "Operator setup required" section to every runbook (ADR-0025 §Consequences promised this; none of the 5 runbooks I read have it) with placeholders the operator must fill in: `<paging-service: ___>`, `<gateway-team-rotation: ___>`, `<platform-team-rotation: ___>`.
- **Proposed owner:** ops/runbooks

## 010 — Wrong ADR cross-reference in `control-plane-down.md`
- **Severity:** S3
- **Location:** `ops/runbooks/control-plane-down.md:55-56`
- **Category:** broken-link
- **Evidence:** "Do not bypass the signing chain to recover writes — the verify-only fail-close was deliberate (see ADR-0014 / S3 fix in `ConfigStateMachine.signCommand`)."
- **What I'd do at 3am:** Open `docs/decisions/adr-0014-zgc-shenandoah-gc-strategy.md` — it's the GC strategy ADR, not signing. Re-grep for the actual fail-close ADR — none of the 26 ADRs documents it. Five wasted minutes.
- **Fix direction:** point to the right ADR (likely needs creating: `adr-XXX-sign-or-fail-close.md`) or remove the citation and link `ConfigStateMachine.signCommand` directly.
- **Proposed owner:** ops/runbooks

## 011 — `write-commit-latency.md` cites a nonexistent ADR with a TODO
- **Severity:** S3
- **Location:** `ops/runbooks/write-commit-latency.md:55`
- **Category:** broken-link
- **Evidence:** `docs/decisions/adr-0007-raft-commit-pipeline.md (if it exists — TODO)`
- **What I'd do at 3am:** notice the `(if it exists — TODO)`, ignore. But this is the canonical runbook for a paging alert; "TODO" in a runbook at 3am is morale-killing and signals other steps may be similarly half-done.
- **Fix direction:** delete the line or write the ADR. `adr-0007` is currently `deterministic-simulation-testing.md` — adopt a free number.
- **Proposed owner:** ops/runbooks

## 012 — `disaster-recovery.md` Step 3 forensic backup loops over hardcoded `configd-0/1/2` only
- **Severity:** S2
- **Location:** `ops/runbooks/disaster-recovery.md:35-37`
- **Category:** missing-prereq
- **Evidence:** `for pod in configd-0 configd-1 configd-2; do kubectl exec $pod -- tar czf - /data > /backup/${pod}-$(date -Is).tgz; done`
- **What I'd do at 3am:** the soak/Pre-GA targets a 5-node cluster (handoff.md §Step 1 "5-node cluster per `deploy/kubernetes/`"), and the statefulset can be scaled. I run the loop and miss `configd-3` and `configd-4`'s data dir — losing forensic evidence I need 6 hours later.
- **Fix direction:** derive the pod list dynamically: `kubectl get pods -l app=configd -o name`. Also verify `/backup/` exists and is sized to hold N×data.
- **Proposed owner:** ops/runbooks

## 013 — `kubectl delete -n configd statefulset/configd` followed by `pvc delete` with no `--dry-run` gate
- **Severity:** S0
- **Location:** `ops/runbooks/disaster-recovery.md:88-92`
- **Category:** missing-rollback
- **Evidence:** `kubectl delete -n configd statefulset/configd` then `kubectl delete -n configd pvc -l app=configd`
- **What I'd do at 3am:** the runbook says "DESTRUCTIVE — only do this with incident commander sign-off" — but I'm groggy, I miss the prose, I copy-paste the block (the way every on-call has done at least once). I just deleted production state with zero recovery.
- **Fix direction:** prepend a `read -p "Type DELETE to confirm: "` shell guard, or fence the destructive commands behind `--dry-run=client` first with an explicit "now remove `--dry-run` and re-run" note. Match the extra friction restore-from-snapshot.md §4 already added on PVC delete (which still lacks a confirmation prompt — same fix).
- **Proposed owner:** ops/runbooks

## 014 — "Rotate the Ed25519 keypair immediately" with no command spelled out
- **Severity:** S1
- **Location:** `ops/runbooks/disaster-recovery.md:74-76`
- **Category:** missing-command
- **Evidence:** "**Rotate the Ed25519 keypair immediately.** The new key starts signing all subsequent commits."
- **What I'd do at 3am:** Search code for a key-rotation API. `Grep "rotateKey\|RotateKey"` returns nothing in the production tree. I don't know whether this is `kubectl create secret`, an admin RPC, a config flag, or a bespoke CLI. Page the security lead, who probably hasn't documented the procedure either.
- **Fix direction:** add the command (`kubectl -n configd create secret generic configd-signing --from-file=key=newkey.pem --dry-run=client -o yaml | kubectl apply -f -` plus the rolling-restart sequence), and link the public-key republish step.
- **Proposed owner:** ops/runbooks

## 015 — "Force-step-down the minority leader" — no command spelled out
- **Severity:** S2
- **Location:** `ops/runbooks/control-plane-down.md:48`
- **Category:** missing-command
- **Evidence:** "Force-step-down the minority leader."
- **What I'd do at 3am:** I assume the syntax is `raftctl step-down` and try it; raftctl doesn't exist (finding 001). I `kubectl delete pod` the offending node — Kubernetes restarts it and possibly re-elects it as leader again. Loop.
- **Fix direction:** spell out the command, e.g. `raftctl transfer-leadership --from <peer-id>` (the same one write-commit-latency.md:42 uses). Then fix finding 001 so this command actually works.
- **Proposed owner:** ops/runbooks

## 016 — "Look at the dashboard panel `Leader churn`" — panel exists, but unit and threshold for "elections > 1/min" needs translation
- **Severity:** S3
- **Location:** `ops/runbooks/control-plane-down.md:33-34`, `ops/runbooks/propagation-delay.md:40-42`
- **Category:** undefined-jargon
- **Evidence:** "look at `Leader churn` — repeated elections indicate one voter is rejecting `RequestVote`"; "Check `Leader churn` panel; if elections > 1/min, jump to `control-plane-down.md`."
- **What I'd do at 3am:** Find the panel — `ops/dashboards/configd-overview.json:99` queries `configd_raft_elections_total` × 60. Metric does not exist in code (finding 003). Panel will be empty regardless.
- **Fix direction:** depends on finding 003 fix. After registering `configd_raft_elections_total`, document the panel's unit (elections/minute) and the trigger threshold inline.
- **Proposed owner:** ops/runbooks

## 017 — `disaster-recovery.md` Step 2 "set the read-only flag" at the API gateway — no command
- **Severity:** S2
- **Location:** `ops/runbooks/disaster-recovery.md:30`
- **Category:** missing-command
- **Evidence:** "Freeze writes. At the API gateway, set the read-only flag. Confirm via `configd_write_commit_total` rate going to 0."
- **What I'd do at 3am:** Configd does not ship an API gateway. I don't know whether "read-only flag" is a kubectl annotation, a NetworkPolicy change, an Envoy filter, a Configd config key. The metric I'd use to confirm doesn't exist (finding 003).
- **Fix direction:** push this into the "Operator setup required" section (finding 009): operator must document their gateway's read-only toggle. Or, if a Configd-side admin RPC exists, document it.
- **Proposed owner:** ops/runbooks

## 018 — `handoff.md` Pre-GA Step 1 says "wire the write loop / read loop / RSS sampler" but the script doesn't expose hooks
- **Severity:** S2
- **Location:** `docs/handoff.md:175-178`, `perf/soak-72h.sh:1-40`
- **Category:** missing-prereq
- **Evidence:** "The harness today only honours the duration contract; you must wire the write loop / read loop / RSS sampler described in the script's header comment block before running."
- **What I'd do at 3am (or, more likely, week 1 of GA prep): I open the script. The header lists the criteria but the script body (lines 39-end) only `mkdir`s the out dir and writes a result file. No write loop. No read loop. No RSS sampler. The handoff says "wire" them — this is a from-scratch implementation, not configuration, but the handoff implies a half-day's work.
- **Fix direction:** either implement the loops in the script (bash + a JMH-driven client), or be explicit in handoff.md that Step 1 is a multi-day implementation task before it can run.
- **Proposed owner:** ops/runbooks

## 019 — `ops/dashboards/configd-overview.json` queries require a `$cluster` template variable that nothing documents how to set
- **Severity:** S3
- **Location:** `ops/dashboards/configd-overview.json:30,58,77,90,102,114`
- **Category:** missing-prereq
- **Evidence:** All panels query `{cluster="$cluster"}` but nothing in `ops/runbooks/` or `ops/dashboards/` says how the operator labels metrics with `cluster`.
- **What I'd do at 3am:** Import the dashboard, see no data in any panel, assume Prometheus is broken, escalate. The label is a Prometheus relabel-config concern, not a Configd one — but the operator needs to know.
- **Fix direction:** add a one-paragraph "Operator setup required: scrape config must apply `cluster=<name>` external label" note to `ops/dashboards/`-adjacent README, or remove the `cluster` filter from the queries and rely on Prometheus federation.
- **Proposed owner:** ops/runbooks

## 020 — `restore-from-snapshot.md` Step 5 references `<operator-restore-image>` with no example
- **Severity:** S2
- **Location:** `ops/runbooks/restore-from-snapshot.md:88`
- **Category:** missing-prereq
- **Evidence:** Job manifest with `image: <operator-restore-image>` and env var `SNAPSHOT_URI` — the runbook gives no example image, no contract on what the entrypoint must do, no fallback if the operator hasn't built one.
- **What I'd do at 3am:** I'm in the middle of a restore and I don't have an image. I cannot apply the Job. Either I write a Dockerfile in 30 minutes (and risk getting the snapshot format wrong), or I `kubectl exec` and untar by hand (which the runbook doesn't authorise). Page release engineering.
- **Fix direction:** ship a reference restore image in the same registry as the runtime, document the exact contract (input env, decompression, fsync, exit code semantics). Or replace with `kubectl debug node/<n> --image=alpine` + explicit untar steps.
- **Proposed owner:** ops/runbooks

## 021 — `handoff.md` says "Run `./mvnw -T 1C verify`" — no working-dir, and CI runs without privileged build secrets, but local maven cache may need warming
- **Severity:** S3
- **Location:** `docs/handoff.md:24`
- **Category:** missing-prereq
- **Evidence:** "Run in order; each commit should pass `./mvnw -T 1C verify`"
- **What I'd do at 3am:** Run from `~/Programming/Configd` — works, but the first run pulls Maven Central + jqwik + cosign-related plugins; if I'm on a constrained network this takes 20+ minutes. handoff.md doesn't warn about cold-cache time. Not a blocker, but contributes to "I expected this to be fast" surprise.
- **Fix direction:** add a "first run takes ~20 min for dependency resolution; subsequent runs ~3 min" note. Optionally a `--offline`-ready prerequisite step.
- **Proposed owner:** ops/runbooks (or repo CONTRIBUTING)

## 022 — None of the 5 canonical runbooks include the "Operator setup required" section ADR-0025 mandates
- **Severity:** S2
- **Location:** all of `ops/runbooks/{control-plane-down,write-commit-latency,edge-read-latency,propagation-delay,disaster-recovery}.md`
- **Category:** missing-owner
- **Evidence:** ADR-0025 §Consequences (`docs/decisions/adr-0025-on-call-rotation-required.md:47-49`): "Each runbook ends with an 'Operator setup required' section enumerating the paging integration the runbook assumes."
- **What I'd do at 3am:** This is a meta-finding I'd notice in the morning post-mortem: the ADR promises a per-runbook integration manifest that doesn't exist. The runbook references "page X" steps (finding 009), uses metrics that aren't registered (finding 003), and uses tools that don't exist (findings 001, 006, 007, 008) precisely because the operator-setup contract was never authored.
- **Fix direction:** add the section to every runbook. Template: `## Operator setup required` → paging service, page targets per severity, dashboard URL, metric registration prereqs, CLI/binary install location.
- **Proposed owner:** ops/runbooks
