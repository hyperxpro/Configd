# hostile-principal-sre — iter-002
**Findings:** 14
**Date:** 2026-04-19
**HEAD:** post-iter-1 (`22d2bf3` + working-tree edits)
**Severity floor:** S3

> Iter-1 closed the catastrophic SLO-pipeline-decoration set (H-001 / F5
> wire-up), the dr-script-not-found set (F4 created scripts), and the §8
> hard-rule violations. The SLO histograms now actually fire.
>
> **Where the system is now genuinely good:** the metric-name → alert →
> code wiring for `write.commit.latency.p99`, `edge.read.latency.p99`,
> `propagation.delay.p99`, `control.plane.availability` and the
> snapshot-install path is real and end-to-end testable
> (`ConfigdMetrics`, `ConfigdServer.smMetrics`, `HttpApiServer.handleGet`).
> The release pipeline genuinely re-pulls and re-verifies its own image
> in `verify-published`.
>
> What this review hunts is the *operator-facing* surface that survives
> iter-1: scripts that pretend to work but won't, runbooks that quote
> metric names that aren't emitted, paging traps in alert math, and the
> readiness-probe / Kubernetes-vs-systemd impedance mismatch.

---

## H-001 — `restore-snapshot.sh` calls `systemctl`; reference deployment is Kubernetes
- **Severity:** S0
- **Location:** `ops/scripts/restore-snapshot.sh:30-34, 68, 196-211, 263-277`; `ops/runbooks/disaster-recovery.md:175-181`
- **Category:** dr-runbook-broken
- **Evidence:** `restore-snapshot.sh` is the only restore entry point; its happy path is `systemctl stop configd.service` → copy snapshot → invoke conformance → `systemctl start configd.service`. The only reference deployment (`deploy/kubernetes/configd-statefulset.yaml`) runs configd as a pod inside a `restricted` PSS namespace with `readOnlyRootFilesystem: true` and no systemd. `disaster-recovery.md` is the *only* runbook that ever invokes the script and it does so *immediately after* `kubectl delete pvc -l app=configd` — i.e. on a bastion that has neither a `configd.service` unit nor write access to the now-deleted PVC. The script will exit 3 ("failed to stop systemd unit configd.service") at the highest-stakes moment of an incident.
- **Impact:** The DR runbook's only mutation primitive cannot run end-to-end against the reference deployment. The "drill" recorded in `runbook-conformance-template.md` will be impossible against the shipped manifests. Operators in the middle of a quorum-loss incident will hit `command not found: systemctl` after they have already deleted the PVCs — destructive operation completed, recovery operation impossible. This is iter-1's H-002 ("scripts don't exist") replaced with iter-2's "scripts exist but assume the wrong substrate".
- **Fix direction:** Either (a) split into two scripts: `restore-snapshot-systemd.sh` (current) and `restore-snapshot-k8s.sh` that uses `kubectl scale`/`kubectl exec`/`kubectl cp` to write the snapshot into the volume of a re-created pod, OR (b) restructure the script as a Kubernetes Job manifest under `deploy/kubernetes/configd-restore-job.yaml` (same pattern used by `restore-from-snapshot.md` Step 3) and have the runbook `kubectl apply` it. Whichever path is taken: the runbook drill MUST execute end-to-end on the reference manifest in CI and write a result file to `ops/dr-drills/results/`. Without a passing drill the GA gate stays YELLOW.

## H-002 — `restore-from-snapshot.md` invokes the conformance script with the wrong flag names
- **Severity:** S0
- **Location:** `ops/runbooks/restore-from-snapshot.md:226-230`; `ops/scripts/restore-conformance-check.sh:69-79, 86-95, 111-113`
- **Category:** runbook-code-drift
- **Evidence:** The runbook's "Resolution" gate (the literal definition of "restore complete") is:
  ```sh
  ./ops/scripts/restore-conformance-check.sh \
    --snapshot=/tmp/restore.snap \
    --cluster-endpoint=https://configd-0.configd.svc:8080
  ```
  The script accepts `--snapshot-path` (not `--snapshot`), and has a *required* `--target-cluster` argument that the runbook never supplies. Both omissions trigger `emit_fail` immediately: the script exits 1 with `unknown argument: --snapshot=/tmp/restore.snap` before any check runs. There is no `set -e` warning to the operator that the gate failed for a *flag* reason, not a *cluster* reason.
- **Impact:** The DR runbook's success criterion ("complete only after the conformance check passes") cannot be satisfied as written. An operator who has just performed a full destructive restore at 3am will face a `FAIL: unknown argument` and have no clear next step — is the restore broken, or is the gate broken? Worse: the script's "PASS" output is the trust anchor for re-opening the API gateway. A confused operator may decide the gate is "obviously a CLI typo" and re-enable writes against an unverified cluster.
- **Fix direction:** Fix the runbook invocation to `--snapshot-path /tmp/restore.snap --target-cluster configd --cluster-endpoint https://configd-0.configd.svc:8080`. Add a CI test (`ops/scripts/test/runbook-flag-drift.sh`) that grep-extracts every `restore-conformance-check.sh ...` line from `ops/runbooks/*.md` and dry-runs it against `--help` to confirm every flag is recognised. Same test for `restore-snapshot.sh`. Without the test the drift will recur on the next script edit.

## H-003 — `release.md` rollback success criteria depend on three nonexistent endpoints / metrics
- **Severity:** S1
- **Location:** `ops/runbooks/release.md:266-288`
- **Category:** runbook-code-drift
- **Evidence:** The "rollback successful" gate requires *all four* of: (a) every pod ready, (b) `kubectl exec ... curl -sf http://localhost:8080/admin/raft/status | jq .commit_index`, (c) `rate(configd_write_failure_total[5m]) == 0` AND `rate(configd_snapshot_install_failed_total[5m]) == 0` AND `configd_slo_burn_rate_1h < 1.0`, (d) image actually rolled. Of those: `/admin/raft/status` does not exist on `HttpApiServer` (verified by grep — every other runbook that mentions it carries a `<!-- TODO PA-XXXX: admin endpoint missing -->` marker, but `release.md` does not). `configd_write_failure_total` is not registered (the actual name is `configd_write_commit_failed_total`). `configd_slo_burn_rate_1h` is not emitted by any production code (no registration in `MetricsRegistry` or `ConfigdMetrics`).
- **Impact:** Rollback can never be "declared successful" by the documented criteria — the operator will be stuck in incident-open state indefinitely after any rollback, even one that actually worked. The S1 severity (not S0) reflects that the cluster *will* be on the previous good digest by step 5, so the customer-impact mitigation is achieved; the operator-process bug is they cannot close the incident. Compounding factor: the runbook lists the criteria immediately under "If any of the four checks fails after 10 minutes, the rollback itself has failed; do not declare success — open an incident." → operator opens *another* incident on top of the one they were rolling back from.
- **Fix direction:** Replace `/admin/raft/status | jq .commit_index` with `curl -sf http://localhost:8080/metrics | grep '^configd_raft_pending_apply_entries '` (this gauge IS emitted, equals `commitIndex - lastApplied` — when zero on every pod, parity holds). Replace `configd_write_failure_total` with `configd_write_commit_failed_total`. Replace `configd_slo_burn_rate_1h` with the actual MWMBR alert evaluation: `count(ALERTS{alertname=~"ConfigdWriteCommitFastBurn|ConfigdControlPlaneAvailability"}) == 0`. Add the same TODO-PA marker as the other runbooks for `/admin/raft/status` until the endpoint ships.

## H-004 — `ConfigdControlPlaneAvailability` alert math is a denominator trap
- **Severity:** S1
- **Location:** `ops/alerts/configd-slo-alerts.yaml:99-119`; `configd-server/src/main/java/io/configd/server/ConfigdServer.java:191-203`; `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:299-313`
- **Category:** alert-math
- **Evidence:** The alert expression is `1 - (sum(rate(configd_write_commit_failed_total[30m])) / sum(rate(configd_write_commit_total[30m])))`. The wiring increments `writeCommitTotal` only on `onWriteCommitSuccess` (`ConfigdServer.java:193`) and `writeCommitFailed` only on `onWriteCommitFailure` (`:200`). So `total` counts SUCCESSES, not ATTEMPTS. The Prometheus convention is for `*_total` to be the denominator (attempts) and the failed counter to be a numerator subset. With the current wiring, `failed/total == failed/successes`, which is unbounded as successes → 0. When the cluster is fully broken (zero successes, ten failures/min), the formula returns `1 - (10/0) == NaN` → no alert fires → silent outage. When the cluster is partially broken (one success, ten failures/min), the formula returns `1 - 10 == -9` which evaluates `< 0.99999` → alert fires with a nonsensical "availability = -900%" reading on the dashboard.
- **Impact:** A *complete* control-plane outage produces zero `_total` increments and divides by zero — the very condition the alert is named for cannot trigger it. Edge-case under partial outage produces a paging alert with a confusing message that operators will discount as "instrumentation broken, ignore". Both failure modes erode trust in the SLO surface that iter-1 just finished wiring.
- **Fix direction:** Either (a) increment `writeCommitTotal` on EVERY apply attempt (success path *and* failure path in `ConfigStateMachine.apply` — move the `metrics.onWriteCommitTotal()` call before the `try`), and rename the success counter to `configd_write_commit_succeeded_total`, OR (b) change the alert to use `failed / (failed + total)` so the denominator is actual attempts. Option (a) matches Prometheus convention and is preferred; add an `availability` derived counter for dashboards. Add a property test in `ConfigStateMachineMetricsTest` that asserts `total == succeeded + failed` after any sequence of applies.

## H-005 — `ConfigdEdgeReadFastBurn` is a raw p99-threshold alert, not a burn-rate alert
- **Severity:** S1
- **Location:** `ops/alerts/configd-slo-alerts.yaml:57-68`
- **Category:** alert-math
- **Evidence:** The alert is named "FastBurn" and the file's preamble (lines 8-11) describes itself as MWMBR per Google SRE workbook §5. But the actual expression is `histogram_quantile(0.99, sum by (le) (rate(configd_edge_read_seconds_bucket[5m]))) > 0.001`. That is a literal p99-over-5-min check, not a burn-rate division. Compare to the (correctly-shaped) `ConfigdWriteCommitFastBurn` at lines 20-37 which divides "fraction-under-budget" by the inverse-budget multiplier. The edge-read alert will page on a *single* 5-min window where p99 exceeded 1ms — that is a 1.7%-of-day breach, well under the 14.4× burn rate threshold the comment documents.
- **Impact:** During normal traffic with occasional GC pauses, p99 will momentarily cross 1 ms and the alert will page on-call without the cluster being anywhere near burning the monthly budget. Alert fatigue is the largest single contributor to MTTR regression in any oncall rotation. The matching `ConfigdEdgeReadP999Breach` has the same shape — also a raw threshold misnamed "burn".
- **Fix direction:** Rewrite as a real MWMBR alert against the bucket: `(sum(rate(configd_edge_read_seconds_bucket{le="0.001"}[5m])) / sum(rate(configd_edge_read_seconds_count[5m]))) < (1 - 14.4 * (1 - 0.99))`. Add a slow-burn 1-hr counterpart at 6× rate. Match the existing `ConfigdWriteCommitFastBurn` shape verbatim. The `histogram_quantile`-based threshold is a *symptom dashboard panel*, not a paging alert — keep it for Grafana.

## H-006 — `ConfigdSnapshotInstallStalled` pages on a single retry, no debounce
- **Severity:** S1
- **Location:** `ops/alerts/configd-slo-alerts.yaml:133-140`
- **Category:** alert-noise
- **Evidence:** `expr: increase(configd_snapshot_install_failed_total[15m]) > 0` for `1m`. A single transient network blip during InstallSnapshot increments the counter; the alert pages within one minute. The runbook (`snapshot-install.md` §Mitigation) literally says "wait one retry window. The leader retries InstallSnapshot automatically and most transients self-clear" — the *runbook's own advice* is to ignore the alert at first, which means the alert paged on something that wasn't actionable.
- **Impact:** Every transient network event between the leader and any one follower will page the warn channel. After three such pages an oncall engineer learns "ignore this alert", which is the same as not having the alert. When a *real* snapshot-install regression happens (snapshot format change, signing key fail-close), the alert will fire and be ignored.
- **Fix direction:** Threshold on a sustained failure rate, not "ever". Use `rate(configd_snapshot_install_failed_total[15m]) * 900 >= 3` (≥ 3 failures in 15 min) for `5m`, OR pair the failure counter with a success counter and alert on `failed_total / install_total > 0.5` over 30 min. Update `snapshot-install.md` Symptoms section to match the new threshold ("≥ 3 failures over 15 minutes" instead of "any failure in last 15 min"). Cross-reference §Mitigation's "wait one retry window" so the alert only fires after the wait would have failed.

## H-007 — Kubernetes liveness/readiness probes hard-coded to HTTP; production runs HTTPS
- **Severity:** S1
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:117-132`; `configd-server/src/main/java/io/configd/server/ConfigdServer.java:230-239`; `configd-server/src/main/java/io/configd/server/HttpApiServer.java:92-98`
- **Category:** k8s-misconfig
- **Evidence:** The probes specify `httpGet: { path: /health/ready, port: 8080 }` with no `scheme:` field — kubelet defaults to `HTTP`. But `ConfigdServer.start()` builds an `HttpsServer` on `apiPort` whenever `--tls-cert/--tls-key` are supplied (which is the production path per `release.md`'s deploy section and the manifest comment "DO NOT run in production without TLS"). When TLS is enabled, the probes will fail TLS handshake (kubelet sends plaintext to a TLS socket → connection reset) → `failureThreshold: 3` → pod marked NotReady → cluster-wide cascade as healthy pods are removed from the Service endpoints.
- **Impact:** The first production deploy with TLS will roll out one pod at a time, each failing readiness, each evicted by the StatefulSet controller. The cluster ends up with zero ready voters before the operator notices. The PDB (`maxUnavailable: 1`) does not save you — ready→NotReady transitions don't count against the PDB. The previous-iteration F4 work (H-005 from iter-1, "readiness probe checks only one signal") becomes moot when *no* probe can succeed.
- **Fix direction:** Add `scheme: HTTPS` to both `livenessProbe.httpGet` and `readinessProbe.httpGet` if TLS is enabled, OR (preferred) bind a separate plaintext probe-only listener on a localhost-only port (`/health/*` only, no /metrics, no /v1/config) for kubelet to scrape, while keeping the public API on HTTPS. The latter mirrors how every other StatefulSet workload solves this. Also add a deploy-time pre-flight in `release.md` that asserts `kubectl get pod -l app=configd -o jsonpath='{.items[*].spec.containers[*].livenessProbe.httpGet.scheme}'` matches the TLS posture documented in `--tls-cert`.

## H-008 — `bind-port` defaults to 9090 (Prometheus' default), Service exposes it; metrics-vs-Raft port collision waiting to happen
- **Severity:** S2
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:99-116, 173-180`
- **Category:** ops-footgun
- **Evidence:** The StatefulSet binds Raft on port 9090 (`--bind-port 9090`). 9090 is the canonical Prometheus port number. The Service exposes both 8080 (api) and 9090 (raft) on the headless service. Operators wiring Prometheus into the cluster commonly do `kubectl port-forward svc/configd 9090:9090` expecting to scrape metrics; instead they get the Raft mTLS handshake (or plaintext binary protocol) and either a connection reset (TLS) or garbage Prometheus-format-parse errors. Worse: a NetworkPolicy operator who copies an "allow Prometheus" template (port 9090) accidentally opens the Raft port to the cluster.
- **Impact:** Every operator who has ever run Prometheus will mis-target this port. The blast radius is not catastrophic (Raft has CRC + auth at the transport layer post-iter-1), but it's a chronic ops-ergonomics tax. The first time someone forwards 9090 from the bastion to their laptop and gets binary data, they'll waste an hour debugging "why isn't /metrics responding".
- **Fix direction:** Move Raft to 7000 or any non-Prometheus-canonical port. Update the manifest, the `--peers` flag examples in `disaster-recovery.md`, and the README. Alternatively, expose `/metrics` on the same 8080 (already done) and document that 9090 in this codebase is Raft, not Prometheus, with a comment in the manifest. The lowest-friction fix is the rename.

## H-009 — Tick loop still swallows `Throwable` to stderr with no metric (iter-1 H-003 deferred)
- **Severity:** S1
- **Location:** `configd-server/src/main/java/io/configd/server/ConfigdServer.java:576-585`
- **Category:** silent-failure
- **Evidence:** The catch block is unchanged from iter-1: `System.err.println("CRITICAL: Exception in tick loop (continuing): " + t); t.printStackTrace(System.err);`. No counter, no `_last_seen_timestamp` gauge, no readiness-check downgrade. The class comment continues to acknowledge that a dying tick loop produces a "zombie node". F5 wired the SLO metrics, but the *failure mode that hides the SLO breach* is still uninstrumented.
- **Impact:** A repeating NPE from `propagationMonitor.checkAll`, `watchService.tick`, `plumtreeNode.tick`, or `compactor.compact` silently degrades the node: heartbeats stall, leadership flaps, propagation lags. Operators see the *symptom* (ConfigdPropagationFastBurn) but the runbook's diagnosis steps don't include "check stderr for tick exceptions" — there is no metric to base a runbook on. Iter-1 deferred this to S2; the post-iter-1 reality is that the SLO surface paging on the symptom does not give operators the leading indicator.
- **Fix direction:** As iter-1: `configd_tick_exceptions_total{component="driver|propagation|watch|plumtree|compactor",class="<simple-name>"}`. Increment in the catch block (label-cardinality bounded — 5 components × small enumerated exception set). Add `ConfigdTickLoopUnstable` alert at `rate(configd_tick_exceptions_total[5m]) > 0.1` for `2m`. Page on it. Wire the same gauge into a readiness check (`now - last_successful_tick > 1s` → unhealthy).

## H-010 — `runbook-conformance-template.md` requires `ops/dr-drills/results/` artefact; directory does not exist
- **Severity:** S2
- **Location:** `ops/runbooks/runbook-conformance-template.md:42-58`
- **Category:** drill-process-broken
- **Evidence:** The template specifies that drill results are written to `ops/dr-drills/results/<drill>-<timestamp>/result.txt` and that GA can only mark a runbook GREEN when such a file exists with `within_sla=true, invariant_check=pass`. Verified via `ls /home/ubuntu/Programming/Configd/ops/dr-drills/` → directory does not exist. There is no script that *creates* this artefact from a drill execution. There is no CI job that asserts drills happened. The "expected cadence" table (line 12) lists quarterly / monthly cadences with no automation enforcing them.
- **Impact:** The runbook conformance regime is on paper only. iter-1 demoted Phase 10 to YELLOW partly on this basis; iter-2 confirms there is no path from YELLOW to GREEN because the artefact-emitting machinery doesn't exist. Every runbook stays effectively untested. When an oncall actually executes one of these runbooks during an incident, the inevitable surprises (see H-001/H-002/H-003) will be the first time anyone discovered them.
- **Fix direction:** Create `ops/dr-drills/results/.gitkeep` so the directory exists. Add `ops/scripts/run-drill.sh <runbook-stem>` that wraps a drill execution, captures start/end timestamps, runs `InvariantMonitor.assertAll()` (or the equivalent shell check), and writes the `result.txt` with the template's required fields. Add a GitHub Actions workflow `drill.yml` that the operator triggers manually for each drill type and stores the result as an artefact. Until at least one real drill result is committed, no runbook can move to GREEN.

## H-011 — Runbooks reference 11+ metrics and 1 endpoint that do not exist in production code
- **Severity:** S2
- **Location:** Aggregate — runbook quotes vs. `ConfigdMetrics`/`MetricsRegistry`/`HttpApiServer`. Specific drift sites:
  - `configd_edge_apply_lag_seconds` — `propagation-delay.md:13-14, 45, 80-83`
  - `configd_edge_staleness_seconds` — `disaster-recovery.md:135`, `restore-from-snapshot.md:223`
  - `configd_changefeed_backlog_bytes` — `propagation-delay.md:60`
  - `configd_raft_follower_lag` — `snapshot-install.md:14`
  - `configd_edge_read_latency_seconds` — `edge-read-latency.md:11-13, 71`
  - `configd_slo_budget_seconds_remaining` — `write-commit-latency.md:107`, `edge-read-latency.md:96`
  - `configd_apply_total` — `raft-saturation.md:35`
  - `configd_snapshot_install_total` — `snapshot-install.md:13, 95`
  - `configd_raft_last_applied_index` — `restore-from-snapshot.md:170-179, 188-194`, `snapshot-install.md:97`, `restore-conformance-check.sh:191`
  - `configd_state_machine_hash` — `restore-conformance-check.sh:222`
  - `configd_write_failure_total` / `configd_slo_burn_rate_1h` — `release.md:280-282`
  - `Leader churn` panel — `control-plane-down.md:74-76`, `write-commit-latency.md:43`, `propagation-delay.md:64`
  - `/admin/raft/status` endpoint — `release.md:272`, plus marked TODOs elsewhere
- **Category:** runbook-code-drift
- **Evidence:** Cross-grep across `**/src/main/**/*.java` for each metric name returns zero matches. iter-1's F5 wired the *alert-rule* metrics; but the *runbook diagnosis instructions* still quote a much larger set the F5 work didn't cover. Some sites carry `<!-- TODO PA-XXXX -->` markers (good); others (release.md:280-282; the ones in the script) do not.
- **Impact:** Every diagnosis step in every runbook tells the operator "look at metric X" — when X doesn't exist, the operator has nothing to triage from. The runbook becomes "best guess from log scraping" mid-incident. iter-1 closed the alert-firing gap; iter-2 surfaces that the *post-page diagnosis* is still based on phantom metrics. The conformance script will hard-fail today on any cluster (H-002 is one symptom; the metric drift here is the underlying cause for the script paths).
- **Fix direction:** Fileowner `configd-observability` opens an issue for each missing metric. Either implement the metric (preferred for the lag/staleness/backlog ones — they are real signals) or excise the runbook reference and replace with the closest existing metric + a TODO marker. Add a docs-lint CI job that grep-extracts every `configd_*` token from `ops/runbooks/*.md` + `ops/scripts/*.sh` and asserts each is registered in `MetricsRegistry` (via `ConfigdMetrics` constants or an explicit allow-list). Without the CI gate the drift will recur on the next runbook edit.

## H-012 — `restore-conformance-check.sh` requires `python3` on the bastion; not declared in operator setup
- **Severity:** S2
- **Location:** `ops/scripts/restore-conformance-check.sh:259-300`; `ops/runbooks/restore-from-snapshot.md:42-51` (Operator-Setup section)
- **Category:** dr-prereq-undocumented
- **Evidence:** The script's "Check 4" parses snapshot keys via `python3 - "$SNAPSHOT_PATH" "$PROBE_KEYS_LIMIT" <<'PY' import struct, sys ...`. Line 284 confirms: `if ! command -v python3 >/dev/null 2>&1; then emit_fail "python3 required to parse snapshot keys for read probe"`. The `restore-from-snapshot.md` Operator-Setup block lists `aws cli`, `openssl`, `kubectl`, the public signing key — but never python3. The minimal alpine bastion images that operators commonly use do not ship python3.
- **Impact:** The conformance check fails on Check 4 with "python3 required" *after* steps 1-3 have already run (cluster is up, snapshot loaded, applied index advanced). The operator's only signal that the gate failed was missing tooling, not a real conformance issue — but the runbook says "do not lift the write freeze until conformance passes". Operator either (a) installs python3 mid-incident on the bastion, (b) edits the script to skip Check 4, or (c) lifts the freeze anyway. Each path either delays recovery or silently weakens the gate.
- **Fix direction:** Add `python3 (>= 3.10)` to the Operator-Setup block of `restore-from-snapshot.md` and `disaster-recovery.md`. Pin in `ops/scripts/README.md` (file does not exist; create it). Better fix: rewrite the snapshot-key extraction in pure POSIX shell + `od` so the script has zero non-coreutils prereqs (the snapshot format is a fixed-width binary scan; the python is convenience, not necessity).

## H-013 — `--target-cluster` is described as "audit only" — script silently accepts the wrong cluster
- **Severity:** S2
- **Location:** `ops/scripts/restore-snapshot.sh:108-125, 135`; `ops/scripts/restore-conformance-check.sh:91-95, 115`
- **Category:** dr-safety-gap
- **Evidence:** Both scripts require `--target-cluster <name>` but use it only as a label in the audit log line (`log "snapshot-path=$X target-cluster=$Y dry-run=$Z"`). They never assert the bastion's `kubectl` context, the cluster name in the snapshot manifest (there isn't one — see `MAX_SNAPSHOT_ENTRIES` comments), or the `${EXPECTED_CLUSTER}` env var that `disaster-recovery.md` sets earlier in the runbook. The "type the cluster name to confirm" guard in `disaster-recovery.md:166-170` exists for `kubectl delete pvc` only — not for the restore script that runs *afterwards*.
- **Impact:** An operator running the runbook against the wrong cluster (multi-cluster operators commonly have several `kubectl` contexts) will pass the confirmation guard for the right cluster name, then `restore-snapshot.sh` happily restores against whatever `kubectl` context is *actually* current. The "audit only" flag provides no protection. Compounded with H-001 (script assumes systemd) the failure mode shifts but the safety gap remains.
- **Fix direction:** Have `restore-snapshot.sh` cross-check `--target-cluster` against `$(kubectl config current-context)` and refuse to proceed if they don't match (with a `--force-context` override that prints a 5-second countdown). For the systemd path, cross-check against `/etc/configd/cluster-name` (operator-provisioned, single source of truth). Make the failure mode "refuse to run" not "log mismatch".

## H-014 — Alert `runbook_url` annotations use repo-relative paths; pager systems expect absolute URLs
- **Severity:** S3
- **Location:** `ops/alerts/configd-slo-alerts.yaml:37, 52, 68, 81, 97, 119, 131, 140`
- **Category:** alert-routing
- **Evidence:** Every alert sets `runbook_url: ops/runbooks/<file>.md` (relative path, unanchored). Alertmanager / PagerDuty / OpsGenie integrations consume this annotation as a clickable URL in the page body — they expect `https://github.com/<org>/<repo>/blob/main/ops/runbooks/<file>.md` (or an internal docs-portal URL). A relative path renders as either "ops/runbooks/write-commit-latency.md" plain text, or worse, gets prefixed with the pager's own base URL and 404s.
- **Impact:** At 3am the on-call clicks the runbook link from the page. They get a 404. They have to guess the GitHub org/repo, navigate to the ops/runbooks/ tree, pick the file. Every page costs 30-60 seconds of MTTR overhead and erodes oncall trust in alert quality. Cosmetic-but-real (S3) because it never causes an outage but always degrades incident response.
- **Fix direction:** Either templatize the URL with an operator-provided base (`runbook_url: "{{ .ExternalURL }}/ops/runbooks/<file>.md"`) using Alertmanager's templating, or commit to a canonical full URL `https://github.com/<owner>/<repo>/blob/main/ops/runbooks/...` and document the substitution in `ops/runbooks/README.md`. Add a CI job that lints every `runbook_url:` annotation against this contract.
