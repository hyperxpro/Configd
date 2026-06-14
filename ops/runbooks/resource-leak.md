# Runbook: Runtime Resource Leak (FD / thread / heap)

**Alerts:** `ConfigdFileDescriptorLeak`, `ConfigdThreadLeak`, `ConfigdHeapPressure`
**Severity:** warn (all three)

A monotonic resource climb crossed a generous ceiling derived from the S5
soak flats (FD ~69, threads ~93, heap ~220–290 MB over a 3.45 h clean run).
These are leak detectors, not capacity alarms — the margins are wide so the
GC sawtooth does not false-positive.

## Symptom

| Alert | Fires when | S5 baseline |
|---|---|---|
| `ConfigdFileDescriptorLeak` | `max(process_open_fds) > 500` for 15m | ~69 |
| `ConfigdThreadLeak` | `max(jvm_threads_current) > 400` for 15m | ~93 |
| `ConfigdHeapPressure` | `max(jvm_heap_used_bytes)/max(jvm_heap_max_bytes) > 0.9` for 30m | ~220–290 MB used |

The operator sees one resource trending up-and-to-the-right on the
`Configd Runtime` dashboard while load is flat.

## Diagnosis

Open the `Configd Runtime` dashboard (`ops/dashboards/configd-runtime.json`).
Identify which resource is climbing and whether it is monotonic (leak) or
sawtooth (GC, benign).

1. **Open FDs** — panel **"Open file descriptors"** (`process_open_fds`).
   A steady climb usually means leaked sockets (edge fan-out connections
   not closed) or leaked file handles (snapshot/WAL streams). Confirm and
   classify on the affected pod:
   ```sh
   pid=$(kubectl -n configd exec <pod> -- pgrep -f configd-server)
   kubectl -n configd exec <pod> -- ls -l /proc/$pid/fd | \
     awk '{print $NF}' | sed -E 's/[0-9]+$//' | sort | uniq -c | sort -rn
   ```
   A growing `socket:` count points at fan-out; a growing file count points
   at the storage layer.
2. **Threads** — panel **"Threads"** (`jvm_threads_current`). A climb means
   a thread pool is not bounded or threads are not being joined. Dump and
   group by name:
   ```sh
   kubectl -n configd exec <pod> -- jcmd $pid Thread.print | \
     grep -oE '"[^"]+"' | sed -E 's/[0-9]+//g' | sort | uniq -c | sort -rn | head
   ```
   The dominant growing name is the leaking pool.
3. **Heap** — panels **"Heap used vs max"** (`jvm_heap_used_bytes` /
   `jvm_heap_max_bytes`) and **"GC collections"**. ZGC should reclaim on
   each cycle; if used-heap floor rises cycle-over-cycle it is a real leak,
   not pressure. Capture a histogram:
   ```sh
   kubectl -n configd exec <pod> -- jcmd $pid GC.class_histogram | head -30
   ```
   The top growing class names the leak (e.g. retained envelopes, an
   unbounded cache/queue).

## Resolution steps

1. **Recycle the affected pod to restore service first.** In a multi-replica
   tier this is safe; the replacement comes up at the S5 baseline.
   ```sh
   kubectl -n configd delete pod <pod>
   ```
   For a control-plane voter, confirm quorum is healthy before deleting
   (see [control-plane-down.md](control-plane-down.md)); the StatefulSet
   respawns the ordinal and it catches up via log/snapshot.
2. **Capture evidence before recycling if the leak is slow** (the climb is
   the bug report): save the FD-class breakdown, `Thread.print`, and
   `GC.class_histogram` from steps 1–3 to the incident ticket. Recycling
   resets the counter and destroys the evidence.
3. **File against the owning component** using the dominant class/thread/
   socket name: fan-out socket leak → `configd-edge-node` fan-out server;
   thread-pool leak → the pool's owner; heap retention → the retaining
   structure. The S5 soak harness (`perf/soak.sh`) is the regression
   reproducer — attach the trend CSV.
4. **Do not** raise `-Xmx` or the FD `ulimit` to "fix" a monotonic leak —
   that only delays the crash (RR-112: the S5 soak ended in a box-OOM at
   3.45 h, which was host memory exhaustion, not a Configd heap leak; rule
   that out via the host `free -m` / cgroup memory before blaming the JVM).

## Verification

- The leaking series (`process_open_fds` / `jvm_threads_current` /
  heap-used floor) returns to and holds at the S5 baseline after recycle.
- The corresponding alert clears after its window (15m / 15m / 30m).
- A follow-up `perf/soak.sh` run holds the resource flat for the soak
  duration — that is the closure criterion, not just a clean recycle.

## Escalation

- Page the next tier if the resource climbs back to threshold within one
  soak interval after recycle (the leak is fast enough to threaten
  availability) or if heap-used floor rises on **every** voter
  simultaneously (a shared code-path leak, not a single bad pod).

## Validation (fault injection)

No fault harness *injects* a leak — leaks are emergent. The detector is
`perf/soak.sh` (run `perf/soak.sh --duration=300` for a smoke; the lead's
real run is `--duration=86400`), which samples `process_open_fds`,
`jvm_threads_current`, RSS/heap and GC every 30 s and emits a trend line.
Recovery-verified = the trend lines stay flat at the S5 baseline for the
soak duration. The alert *firing/quiet* behaviour is proven by
`ops/alerts/configd-slo-alerts.test.yaml` (`promtool test rules`).

## Related

- S5 soak flats + RR-112 box-OOM (host memory, not a heap leak).
- `ops/dashboards/configd-runtime.json` — the leak dashboard.
- `docs/decisions/adr-0041-zgc-collector.md` — ZGC reclaim assumption the
  heap alert depends on.
