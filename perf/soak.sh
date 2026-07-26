#!/usr/bin/env bash
# soak.sh — real-cluster soak workload (leak / drift detector)
#
# Wires a real 3-node control-plane cluster (same launch shape as
# gates/smoke-multinode.sh / perf/wsB-live-write.sh), drives it at a
# box-sustainable write rate, and every ~SAMPLE_SEC appends a trend line so
# heap creep, fd leaks, thread leaks, GC degradation, and commit-latency
# drift are visible over the run.
#
# Honesty: this is a real-duration workload. A short run is a smoke, never a
# soak — pass --duration=300 (5 min) to validate the harness; production runs
# go for 24h+ so they outlive any single session.
#
# Why not drive at the top-line write SLO: the box's sustainable end-to-end
# commit rate tops out around 136-172/s (see wsB-calibrate.txt), so a much
# higher target rate is env-blocked on this hardware. A soak detects leaks
# and drift, which are rate-independent — a steady ~100/s with headroom over
# that floor is the right, sustainable soak rate. This harness does not
# measure throughput; it watches for resource creep at a constant,
# comfortably-sustainable load.
#
# Usage:
#   perf/soak.sh [--duration=<sec>] [--rate=<commits/s>] [--seed=<int>] \
#                [--sample=<sec>] [--out=<dir>]
#
# Examples:
#   perf/soak.sh --duration=300                                  # 5-min smoke (validation)
#   perf/soak.sh --duration=86400 --out=perf/results/soak-24h    # 24h run
#
# Requires: freshly-built shaded server jar + benchmarks.jar.
#   ./mvnw -pl configd-server -am package
#   ./mvnw -pl configd-testkit -am package
# Idempotent: cleans up its own ports/dirs on entry + exit.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="$ROOT/configd-testkit/target/benchmarks.jar"

DURATION_SEC=$((24 * 3600))   # default 24 h; override for a real run
RATE=100                      # commits/s — box-sustainable (~136/s floor observed)
SEED=42
SAMPLE_SEC=30                 # trend-line cadence
OUT_DIR=""
VALUE_BYTES=256
CONCURRENCY=8                 # in-flight workers for the open-loop driver

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration=*) DURATION_SEC="${1#*=}" ;;
    --rate=*)     RATE="${1#*=}" ;;
    --seed=*)     SEED="${1#*=}" ;;
    --sample=*)   SAMPLE_SEC="${1#*=}" ;;
    --out=*)      OUT_DIR="${1#*=}" ;;
    -h|--help)    sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

OUT_DIR="${OUT_DIR:-$ROOT/perf/results/soak-$(date -u +%Y%m%dT%H%M%SZ)}"
# SOAK_BASE: data+WAL root. Defaults to /tmp for local/dev runs; for a
# fsync-honest measurement, set SOAK_BASE=/mnt/nvme/... (real NVMe path).
BASE="${SOAK_BASE:-/tmp/configd-soak-$$}"
RAFT_BASE=9290
API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
HEAP="${SOAK_HEAP:--Xmx1g -Xms1g}"
GCFLAGS="${SOAK_GC:--XX:+UseZGC}"
# SOAK_JVM_EXTRA: extra per-node JVM flags (default none). For a real
# measurement run, add -XX:NativeMemoryTracking=summary and
# -XX:+HeapDumpOnOutOfMemoryError so an off-heap/direct-buffer leak or an
# OOM is captured, not just inferred.
JVM_EXTRA="${SOAK_JVM_EXTRA:-}"
PIDS=()

mkdir -p "$OUT_DIR" "$BASE"
TREND="$OUT_DIR/trend.csv"
RESULT="$OUT_DIR/result.txt"
GCLOG_DIR="$OUT_DIR/gclogs"; mkdir -p "$GCLOG_DIR"

# Early failures (e.g. cluster never ready) happen before finish()/the trend
# state exist, so fail() just reports and exits — the EXIT trap runs cleanup.
fail() { echo "SOAK FAIL: $*" >&2; exit 1; }
api() { echo "127.0.0.1:$((API_BASE + $1))"; }
# Live PID of node k, re-discovered by its unique --data-dir marker. Robust to
# fault-injection kill+restart (companion perf/soak-faults.sh): a restarted node
# gets a new PID, so a fixed launch-time PID would sample a dead process. Prefers
# comm==java so a clock-skew LD_PRELOAD wrapper (if any) is never mistaken for the
# node. Empty (=>0) for a killed, not-yet-restarted node across that fault window.
node_pid() {
  local p
  for p in $(pgrep -f -- "--data-dir $BASE/n$1 " 2>/dev/null); do
    [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && { echo "$p"; return; }
  done
}

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null
  rm -rf "$BASE" 2>/dev/null
}
trap cleanup EXIT

[ -f "$JAR" ]   || fail "shaded jar not found: $JAR (build: ./mvnw -pl configd-server -am package)"
[ -f "$BENCH" ] || fail "benchmarks.jar not found (build: ./mvnw -pl configd-testkit -am package)"

# Launch the 3-node ZGC cluster, each with its own gc log for cumulative pause.
# Shared cluster signing key, mounted OUTSIDE every node data dir. Required
# because the fail-closed guard (ADR-0043) derives the at-rest integrity key
# from the signing key, so a key co-located inside the data dir it protects
# is refused at boot. Defaults to the pre-generated key from
# deploy/compose/setup-secrets.sh; override with SOAK_SIGNKEY.
SIGNKEY="${SOAK_SIGNKEY:-$ROOT/deploy/compose/secrets/signing-key.bin}"
[ -s "$SIGNKEY" ] || fail "shared signing key not found: $SIGNKEY (run deploy/compose/setup-secrets.sh)"
echo "[soak] launching 3-node cluster ($GCFLAGS $HEAP) under $BASE; out=$OUT_DIR; signing-key=$SIGNKEY"
for k in 1 2 3; do
  peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  dd="$BASE/n$k"; mkdir -p "$dd"
  java $GCFLAGS $HEAP $JVM_EXTRA --enable-preview \
    -Xlog:gc:"$GCLOG_DIR/n$k.gc.log" \
    -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" \
    --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
done

# Wait for all 3 readiness endpoints == 200 (leader elected => ready). Generous
# window: 3 co-located ZGC JVMs committing their heaps on a small box can take
# well over a minute to all report ready.
READY_TIMEOUT_SEC="${SOAK_READY_TIMEOUT_SEC:-180}"
ready=0
for i in $(seq 1 $((READY_TIMEOUT_SEC * 2))); do
  ok=0
  for k in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)
    [ "$code" = "200" ] && ok=$((ok + 1))
  done
  [ "$ok" -eq 3 ] && { ready=1; break; }
  sleep 0.5
done
[ "$ready" -eq 1 ] || fail "cluster not ready within ${READY_TIMEOUT_SEC}s"
echo "[soak] all 3 nodes ready"

NODEMAP="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"

# Trend sampler: RSS, heap-used, open FD count, thread count, cumulative GC
# (cycles + cumulative cycle-seconds from the ZGC log), and a sampled commit
# latency p50/p99 (from the driver window in flight). One CSV row per sample.
# All three node PIDs are summed where a fleet-wide number is meaningful (RSS,
# FDs, threads); per-node columns let a single-node leak stand out.
proc_rss_kb() { awk '/VmRSS/{print $2}' "/proc/$1/status" 2>/dev/null || echo 0; }
proc_threads() { awk '/Threads/{print $2}' "/proc/$1/status" 2>/dev/null || echo 0; }
proc_fds() { ls "/proc/$1/fd" 2>/dev/null | wc -l; }
# jstat heap-used (KB): header-name-keyed sum of the used columns for the live PID.
# ZGC reports S0U/S1U as '-' (no survivor split), so we key by COLUMN NAME and sum
# only the numeric used columns (EU eden, OU old, MU metaspace, CCSU comp-class-space),
# which is robust to the dashes a positional parse would choke on.
heap_used_kb() {
  jstat -gc "$1" 2>/dev/null | awk '
    NR==1{ for(i=1;i<=NF;i++) col[$i]=i; next }
    NR==2{
      s=0;
      for(name in col){
        if(name=="EU"||name=="OU"||name=="MU"||name=="CCSU"){
          v=$(col[name]); if(v ~ /^[0-9.]+$/) s+=v;
        }
      }
      printf "%d", s+0;
    }' 2>/dev/null || echo 0
}
# ZGC gc-log cumulative: count completed GC cycles + sum their trailing N.NNNs duration.
gc_cycles() { grep -cE '\)[^(]*[0-9]+M\([0-9]+%\)->' "$1" 2>/dev/null || echo 0; }
gc_cumsec() {
  grep -oE '[0-9]+\.[0-9]+s$' "$1" 2>/dev/null | sed 's/s$//' \
    | awk '{s+=$1} END{printf "%.3f", s+0}'
}

echo "ts_utc,elapsed_s,rss_total_kb,rss_n1_kb,rss_n2_kb,rss_n3_kb,heap_used_total_kb,fd_total,fd_n1,fd_n2,fd_n3,threads_total,gc_cycles_total,gc_cumsec_total,commit_p50_us,commit_p99_us,committed,rejected" > "$TREND"

t_start=$(date +%s)
deadline=$((t_start + DURATION_SEC))

echo "soak harness" > "$RESULT"
{
  echo "  jar=$JAR"
  echo "  gc=$GCFLAGS heap=$HEAP rate=${RATE}/s seed=$SEED sample=${SAMPLE_SEC}s"
  echo "  requested_duration_sec=$DURATION_SEC"
  echo "  start_utc=$(date -u +%FT%TZ)"
  if [ "$DURATION_SEC" -le 600 ]; then
    echo "  label=SMOKE (<=600s — harness validation, NOT a soak)"
  else
    echo "  label=SOAK (real-duration leak/drift run)"
  fi
} >> "$RESULT"

# Drive in SAMPLE_SEC windows: each window runs the open-loop CO-corrected
# driver for SAMPLE_SEC at RATE, then we read its p50/p99 from ATRATE-HISTOGRAM
# and append a trend row. Looping the driver (vs one long invocation) gives a
# per-window latency sample so drift is visible across the run; the cluster
# stays up the whole time, so leaks accumulate.
sample_idx=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  win_start=$(date +%s)
  remaining=$((deadline - win_start))
  win=$SAMPLE_SEC
  [ "$remaining" -lt "$win" ] && win=$remaining
  [ "$win" -lt 5 ] && break

  # One driver window (open-loop, CO-corrected, follows leader hints).
  drv_out="$BASE/drv-$sample_idx.txt"
  java $GCFLAGS $HEAP --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
    atrate "$NODEMAP" "$RATE" "$win" "$CONCURRENCY" "$VALUE_BYTES" \
    > "$drv_out" 2>/dev/null

  hist=$(grep "ATRATE-HISTOGRAM" "$drv_out" | tail -1)
  res=$(grep "ATRATE-RESULT" "$drv_out" | tail -1)
  p50=$(echo "$hist" | grep -oE 'p50=[0-9]+' | cut -d= -f2); p50=${p50:-0}
  p99=$(echo "$hist" | grep -oE 'p99=[0-9]+' | cut -d= -f2); p99=${p99:-0}
  committed=$(echo "$res" | grep -oE 'committed_200=[0-9]+' | cut -d= -f2); committed=${committed:-0}
  rejected=$(echo "$res" | grep -oE 'rejected_backpressure=[0-9]+' | cut -d= -f2); rejected=${rejected:-0}

  # Resource snapshot (summed over the 3 node PIDs; per-node for n1..n3).
  rss_t=0; fd_t=0; thr_t=0; heap_t=0; cyc_t=0
  cum_t="0.000"
  declare -a rss_n=(0 0 0); declare -a fd_n=(0 0 0)
  for idx in 0 1 2; do
    pid="$(node_pid $((idx + 1)))"; pid="${pid:-0}"
    r=$(proc_rss_kb "$pid"); f=$(proc_fds "$pid"); th=$(proc_threads "$pid"); hu=$(heap_used_kb "$pid")
    rss_n[$idx]=$r; fd_n[$idx]=$f
    rss_t=$((rss_t + r)); fd_t=$((fd_t + f)); thr_t=$((thr_t + th)); heap_t=$((heap_t + hu))
    gl="$GCLOG_DIR/n$((idx + 1)).gc.log"
    c=$(gc_cycles "$gl"); cyc_t=$((cyc_t + c))
    cs=$(gc_cumsec "$gl")
    cum_t=$(awk -v a="$cum_t" -v b="$cs" 'BEGIN{printf "%.3f", a+b}')
  done

  elapsed=$(( $(date +%s) - t_start ))
  echo "$(date -u +%FT%TZ),$elapsed,$rss_t,${rss_n[0]},${rss_n[1]},${rss_n[2]},$heap_t,$fd_t,${fd_n[0]},${fd_n[1]},${fd_n[2]},$thr_t,$cyc_t,$cum_t,$p50,$p99,$committed,$rejected" >> "$TREND"
  echo "[soak] t+${elapsed}s rss=${rss_t}kb fd=$fd_t thr=$thr_t gc_cycles=$cyc_t gc_cumsec=$cum_t commit_p50=${p50}us p99=${p99}us committed=$committed rejected=$rejected"

  sample_idx=$((sample_idx + 1))
done

# Closeout: flat-trend check (first vs last sample) so the run self-reports
# leak signals. Thresholds are deliberately generous for a smoke; review the
# CSV directly for the real run.
finish() {
  local status="${1:-DONE}"
  local t_end; t_end=$(date +%s)
  local elapsed=$((t_end - t_start))
  {
    echo "  end_utc=$(date -u +%FT%TZ)"
    echo "  measured_elapsed_sec=$elapsed"
    echo "  samples=$sample_idx"
    echo "  status=$status"
  } >> "$RESULT"

  if [ "$sample_idx" -ge 2 ]; then
    # Memory leak/drift verdict — steady-state only, keyed on wall-clock elapsed.
    # The JVM heap-commit ramp (ZGC committing toward -Xms/-Xmx, plus JIT warmup) runs
    # well past 170s on a throttled 2-vCPU box, so a sample-fraction split is fragile on
    # a short run. Instead we discard every sample inside the warmup floor
    # (WARMUP_FLOOR_SEC, default 180s) and compare the first-half median vs the
    # second-half median of the post-warmup samples. A genuine leak shows as
    # 2nd-half-median > 1st-half-median; a transient throttle spike cannot dominate a
    # median. If there are too few post-warmup samples for a stable median (a short
    # smoke), the memory verdict is observational — the harness reports the trend but
    # does not assert leak/no-leak, because a 5-minute smoke is too short to separate
    # heap warmup from a slow leak (that is exactly what the long run is for). FD and
    # thread leaks are meaningful from t0 (they don't warm up), so those keep the
    # first-vs-last comparison and are asserted.
    WARMUP_FLOOR_SEC="${SOAK_WARMUP_FLOOR_SEC:-180}"
    local first last npost
    first=$(sed -n '2p' "$TREND")
    last=$(tail -1 "$TREND")
    npost=$(awk -F, -v w="$WARMUP_FLOOR_SEC" 'NR>1 && $2>=w {c++} END{print c+0}' "$TREND")
    # median of a CSV column over the post-warmup samples, first-half vs second-half.
    med_post_half() { # $1=half(1|2) $2=colnum
      awk -F, -v w="$WARMUP_FLOOR_SEC" -v half="$1" -v c="$2" '
        NR>1 && $2>=w { n++; v[n]=$c }
        END{
          if(n==0){ print 0; exit }
          mid=int(n/2);
          if(half==1){ lo=1; hi=(mid>=1?mid:1) } else { lo=(mid+1<=n?mid+1:n); hi=n }
          m=0; for(i=lo;i<=hi;i++){ m++; w2[m]=v[i] }
          # sort w2
          for(i=1;i<=m;i++) for(j=i+1;j<=m;j++) if(w2[j]<w2[i]){t=w2[i];w2[i]=w2[j];w2[j]=t}
          print (m>0 ? w2[int((m+1)/2)] : 0)
        }' "$TREND"
    }
    local rssB rssN heapB heapN fd0 fdN thr0 thrN
    rssB=$(med_post_half 1 3);  rssN=$(med_post_half 2 3)
    heapB=$(med_post_half 1 7); heapN=$(med_post_half 2 7)
    fd0=$(echo "$first"  | cut -d, -f8); fdN=$(echo "$last"  | cut -d, -f8)
    thr0=$(echo "$first" | cut -d, -f12); thrN=$(echo "$last" | cut -d, -f12)
    {
      echo "  --- leak/drift check (post-warmup >=${WARMUP_FLOOR_SEC}s: 1st-half-median vs 2nd-half-median) ---"
      echo "  post_warmup_samples=$npost  (first cold sample rss was $(echo "$first" | cut -d, -f3) kb — JVM heap-commit ramp excluded)"
      echo "  rss_total_kb:       1stH-median $rssB -> 2ndH-median $rssN"
      echo "  heap_used_total_kb: 1stH-median $heapB -> 2ndH-median $heapN  (jstat — the DEFINITIVE Java-heap-leak signal)"
      echo "  fd_total:           $fd0 -> $fdN  (first sample vs last — FD leaks show from t0)"
      echo "  threads_total:      $thr0 -> $thrN  (first sample vs last — thread leaks show from t0)"
      # MEMORY verdict: only ASSERTED with >=4 post-warmup samples; else OBSERVATIONAL.
      if [ "$npost" -ge 4 ]; then
        awk -v a="$heapB" -v b="$heapN" 'BEGIN{
          if(a>0 && b>a*1.25) print "  VERDICT: jstat heap-used 2nd-half-median grew > 25% over 1st-half — INVESTIGATE (Java heap leak)";
          else print "  VERDICT: jstat heap-used flat across post-warmup window — no Java heap-leak signal";
        }'
        awk -v a="$rssB" -v b="$rssN" -v ha="$heapB" -v hb="$heapN" 'BEGIN{
          if(a>0 && b>a*1.10){
            if(ha>0 && hb<=ha*1.25)
              print "  VERDICT: RSS grew > 10% post-warmup BUT jstat heap-used flat — native-footprint/throttle (likely ZGC lazy-uncommit on a 2-vCPU box), NOT a Java heap leak; confirm on the long run";
            else
              print "  VERDICT: RSS grew > 10% AND heap-used grew post-warmup — INVESTIGATE (possible leak)";
          } else print "  VERDICT: RSS flat within 10% across post-warmup window — no leak signal";
        }'
      else
        echo "  VERDICT: memory (heap/RSS) — OBSERVATIONAL ONLY ($npost post-warmup samples < 4)."
        echo "           A short SMOKE cannot separate heap warmup from a slow leak; the trend is"
        echo "           recorded for inspection but no heap/RSS leak verdict is asserted here."
        echo "           The LONG run (LEAD-launched) renders the asserted memory verdict."
      fi
      awk -v a="$fd0" -v b="$fdN" 'BEGIN{
        if(a>0 && b>a*1.25) print "  VERDICT: FD count grew > 25% — INVESTIGATE (possible FD leak)";
        else print "  VERDICT: FD count flat — no FD leak signal";
      }'
      awk -v a="$thr0" -v b="$thrN" 'BEGIN{
        if(a>0 && b>a*1.25) print "  VERDICT: thread count grew > 25% — INVESTIGATE (possible thread leak)";
        else print "  VERDICT: thread count flat — no thread leak signal";
      }'
    } >> "$RESULT"
  fi
  echo "[soak] $status — elapsed ${elapsed}s, $sample_idx samples — see $RESULT / $TREND"
}

finish "DONE"
