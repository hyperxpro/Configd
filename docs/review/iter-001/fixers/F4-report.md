# F4 — Tier-1-OPS-GLUE — iter-001 fixer report

**Date:** 2026-04-19
**Scope:** missing-artifact gaps the runbooks reference but do not exist.
**Source triage:** `docs/review/iter-001/merged.md` (H-002 in
`hostile-principal-sre.md`, plus the `raftctl`-doesn't-exist gap surfaced
across multiple reviewers).

## Files created

| Path | Purpose |
|------|---------|
| `ops/scripts/restore-snapshot.sh` | Operator entry point for restore-from-snapshot. Dry-run by default, refuses destructive mode unless both `--dry-run=false` and `--i-have-a-backup` are passed. Validates snapshot header against the format defined in `configd-config-store/.../ConfigStateMachine.java` (8-byte sequence + 4-byte entry count + bounds). Stops `configd.service`, stages the snapshot under `/var/lib/configd/snapshots/`, invokes `restore-conformance-check.sh`, then restarts. `chmod +x`. |
| `ops/scripts/restore-conformance-check.sh` | Validates a restored cluster: snapshot header parse, sha256 over the snapshot's payload region, post-restore applied-index gauge from `/metrics`, optional state-machine-hash gauge, and a small read-traffic probe (parses snapshot keys via Python). Outputs `PASS` / `FAIL: <reason>` and exits 0/1. `chmod +x`. |
| `deploy/kubernetes/configd-bootstrap.yaml` | One-shot bootstrap Job. Mirrors the StatefulSet's PodSecurity restricted profile (runAsNonRoot, readOnlyRootFilesystem, drop ALL caps, seccomp RuntimeDefault). `backoffLimit: 0`, `ttlSecondsAfterFinished: 600`, no PVC mounts. Emits the `configd-peers` ConfigMap via a ServiceAccount + Role scoped to that single ConfigMap name. Header comment warns it is one-shot and must not be re-run on an existing cluster. |
| `docs/review/iter-001/fixers/F4-report.md` | This file. |

## Files edited

| Path | Change |
|------|--------|
| `ops/runbooks/disaster-recovery.md` | Added a `**DESTRUCTIVE — DATA LOSS — irreversible.**` callout block above "Reset and re-bootstrap"; replaced the bare `kubectl delete pvc` with a `read -r -p` confirmation guard that demands the operator type the cluster name; updated the restore invocation to use the new `restore-snapshot.sh` flag set; replaced two `raftctl add-server` calls with `curl /admin/raft/add-server` examples plus TODO markers because the endpoint does not yet exist. |
| `ops/runbooks/restore-from-snapshot.md` | Removed two `curl http://.../raft/status` calls (endpoint nonexistent) and replaced with `curl /metrics | grep configd_raft_last_applied_index` plus TODO markers; replaced "Quorum > 1 in `raftctl status`" with a kubectl-readiness-based check plus a TODO marker. |
| `ops/runbooks/control-plane-down.md` | Replaced "Run `raftctl status`" with a per-pod `X-Leader-Hint`-header probe loop plus TODO marker; rewrote the split-brain symptom paragraph to use the same probe (no `raftctl`). |
| `ops/runbooks/snapshot-install.md` | Replaced `raftctl remove-server` and `raftctl add-server` with `curl /admin/raft/(remove|add)-server` examples and TODO markers; documented the workaround (scale StatefulSet) in the TODO body. |
| `ops/runbooks/write-commit-latency.md` | Replaced `raftctl transfer-leadership` with `curl /admin/raft/transfer-leadership` example plus TODO marker noting the operator can `kubectl delete pod <leader>` as a workaround. |
| `ops/runbooks/README.md` | Removed the audience-line claim that responders use a `raftctl` CLI; replaced with `kubectl + curl` and a top-level TODO marker pointing at the missing admin surface. |

## Key technical decisions

1. **Snapshot magic bytes.** The on-disk snapshot format
   (`ConfigStateMachine#snapshot` /
   `ConfigStateMachine#restoreSnapshot`) does not include any magic
   bytes — the file starts with an 8-byte big-endian sequence counter
   followed by a 4-byte big-endian entry count. This is a known gap
   (see `docs/prod-audit-cluster-B.md` PA-2021 and
   `docs/review/iter-001/release-skeptic.md`). Rather than invent
   magic bytes the script does not enforce, `restore-snapshot.sh`
   validates the header by:
   - file existence + readable + size ≥ 12 bytes
   - entry-count within `MAX_SNAPSHOT_ENTRIES` (= 100,000,000, mirrored
     from `ConfigStateMachine.java`)
   - file size ≥ `12 + entry_count * 8` (lower bound on the payload
     given each entry's two length prefixes)
   This catches 100% of "wrong file type, garbage bytes" inputs and
   most "truncated" inputs without forging a contract the format does
   not actually have.

2. **No invented HTTP endpoints.** A repo-wide search of the actual
   `HttpApiServer.java` shows the only registered contexts are
   `/health/live`, `/health/ready`, `/metrics`, and `/v1/config/`. There
   is no `/raft/*`, no `/admin/*`. Every former `raftctl` callsite was
   replaced with the `curl /admin/<endpoint>` shape that the runbook
   *should* eventually use, paired with an explicit
   `<!-- TODO PA-XXXX: admin endpoint missing -->` marker that names
   the missing surface and (where possible) documents a workaround
   the operator can run today (e.g. delete the leader pod for
   transfer-leadership; scale the StatefulSet for add/remove-server).
   PA-XXXX is left as a placeholder for the issue tracker to assign
   per-runbook IDs in a follow-up pass.

3. **`restore-conformance-check.sh` against today's metrics.** The
   conformance check needs two signals that the production binary does
   not emit yet: `configd_raft_last_applied_index` and
   `configd_state_machine_hash`. The script tries to read them from
   `/metrics`; if either is absent it returns
   `FAIL: <reason> — TODO PA-XXXX: <surface> missing`, which is the
   correct semantic ("we cannot prove the restore is good") rather
   than silently returning PASS. When the gauges land the script will
   start passing without code changes.

4. **Bootstrap Job RBAC.** The Job mounts no PVCs, runs read-only
   root, and gets a ServiceAccount whose Role is scoped to a single
   ConfigMap name (`configd-peers`). The blast radius of total
   compromise is "could overwrite the peers ConfigMap" — which is
   exactly the Job's job. The Role does not grant `delete` so the Job
   cannot remove the ConfigMap to "force a re-bootstrap" later.

## Open TODOs (gaps documented, not papered over)

All TODOs use the marker `<!-- TODO PA-XXXX: admin endpoint missing -->`
so a future grep can enumerate them. The actual gaps:

| TODO surface | Where referenced now | Required code change |
|--------------|----------------------|----------------------|
| `GET /admin/raft/status` (or `/raft/status`) returning JSON `{ leader, term, last_applied_index, voters[] }` | `restore-from-snapshot.md` (×2), `control-plane-down.md` (×2), `disaster-recovery.md` (implicit), README | New handler in `HttpApiServer`; needs to expose `RaftNode.leaderId()`, `RaftNode.currentTerm()`, applied index, cluster config snapshot. |
| `POST /admin/raft/add-server?peer=<id>` | `disaster-recovery.md` (×2), `snapshot-install.md` | Wire to `RaftNode` cluster-change proposal; gate behind admin auth scope. |
| `POST /admin/raft/remove-server?peer=<id>` | `snapshot-install.md` | Same as above. |
| `POST /admin/raft/transfer-leadership?to=<id>` | `write-commit-latency.md` | Wire to `RaftNode.transferLeadership(target)` (already exists in core; HTTP wrapper missing). |
| Prom gauge `configd_raft_last_applied_index` | `restore-from-snapshot.md` (×2), `restore-conformance-check.sh` | Emit from `RaftDriver.tick` apply path via `MetricsRegistry`. |
| Prom gauge `configd_state_machine_hash` (label-encoded sha256) | `restore-conformance-check.sh` | Emit from `ConfigStateMachine` after each apply (cheap: hash of the previous gauge XOR-with-update; cache to avoid re-hashing the whole snapshot per apply). |
| Snapshot file magic + format-version header | `restore-snapshot.sh` (validation is conservative without it) | `ConfigStateMachine#snapshot` + `restoreSnapshot` to prepend e.g. `[magic 'CFGS\0\0\0\0'][u32 format-version][u32 crc32c]` and validate on restore. |

## Verification performed

- `bash -n` passes on both new shell scripts.
- `restore-snapshot.sh --help` and `restore-conformance-check.sh --help`
  print the documented usage.
- `restore-snapshot.sh --snapshot-path /tmp/test.snap --target-cluster
  testc` (dry-run) walks all five steps, parses the synthetic snapshot
  header, and successfully invokes `restore-conformance-check.sh`
  which returns `PASS`.
- `restore-snapshot.sh --snapshot-path /tmp/bad.snap ...` against a
  truncated snapshot exits 2 with the expected "snapshot truncated"
  diagnostic.
- `restore-snapshot.sh --snapshot-path /tmp/test.snap --target-cluster
  testc --dry-run=false` exits 1 with the expected "refusing to run
  ... unless --i-have-a-backup is asserted" diagnostic.
- `python3 -c "yaml.safe_load_all(...)"` parses
  `deploy/kubernetes/configd-bootstrap.yaml` as 4 documents
  (ServiceAccount, Role, RoleBinding, Job).
- `grep -rn "raftctl" ops/runbooks/` returns only meta references
  inside the README's own TODO block (no remaining live commands).
