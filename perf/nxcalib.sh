#!/usr/bin/env bash
# nxcalib.sh — multi-Raft N x knee via closed-loop calibration (the honest ceiling).
#
# The open-loop nxknee.sh ladder is contaminated by driver backpressure when the
# offered rate exceeds what a single open-loop driver can submit (the worker pool
# saturates and the run measures the driver, not the cluster — observed: servers
# and NVMe idle, yet throughput capped). This harness instead drives closed-loop
# (calibrate-sharded: C workers each send-as-fast-as-possible, routed per shard),
# so the measured sustained 200/s is the cluster's true throughput ceiling at
# that concurrency. It sweeps concurrency to find the plateau, and captures
# iostat/mpstat/pidstat (including the driver PID) so the bottleneck (NVMe fsync
# vs CPU vs driver) is visible at the knee.
#
#   Usage:  perf/nxcalib.sh <N> [outdir]
#   Env:    NXC_CONCS (default "128 256 512 1024")  NXC_DUR (default 20)
#           NXC_VALBYTES (default 512)  NXC_HEAP (default "-Xmx4g -Xms4g")
#           NXC_GC (default ZGC)  NXC_TRANSPORT (default epoll)
#           NXC_BASE (default /mnt/nvme/run/nxc-N<N>-<pid>)  NXC_DRYRUN (default 0)
#           NXC_JVM_EXTRA (per-node extra flags)  CONFIGD_JAR / CONFIGD_BENCH / NXC_SIGNKEY
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="${CONFIGD_BENCH:-$ROOT/configd-testkit/target/benchmarks.jar}"
N="${1:?usage: nxcalib.sh <N> [outdir]}"
DRYRUN="${NXC_DRYRUN:-0}"
BASE="${NXC_BASE:-/mnt/nvme/run/nxc-N${N}-$$}"
RAFT_BASE=9290; API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
HEAP="${NXC_HEAP:--Xmx4g -Xms4g}"; GCFLAGS="${NXC_GC:--XX:+UseZGC}"; TRANSPORT="${NXC_TRANSPORT:-epoll}"
DUR="${NXC_DUR:-20}"; VALBYTES="${NXC_VALBYTES:-512}"; CONCS="${NXC_CONCS:-128 256 512 1024}"
OUT="${2:-$ROOT/docs/measurement/captures/nxcalib-N${N}}"
PIDS=(); SAMPLERS=(); DRVPID=""
mkdir -p "$BASE" "$OUT"
SIGNKEY="$BASE/cluster-signing-key.bin"
PREGEN_KEY="${NXC_SIGNKEY:-$ROOT/deploy/compose/secrets/signing-key.bin}"

fail() { echo "nxc FAIL: $*" >&2; teardown; exit 1; }
teardown() { for p in "${SAMPLERS[@]:-}"; do kill "$p" 2>/dev/null; done; for p in "${PIDS[@]:-}"; do kill -9 "$p" 2>/dev/null; done; pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null; }
trap 'teardown; rm -rf "$BASE" 2>/dev/null' EXIT
[ -f "$JAR" ] || fail "jar not found: $JAR"; [ -f "$BENCH" ] || fail "bench not found: $BENCH"
[ "$DRYRUN" = "1" ] || case "$BASE" in /mnt/nvme/*) : ;; *) fail "NXC_BASE must be /mnt/nvme"; esac
api() { echo "127.0.0.1:$((API_BASE + $1))"; }
nvme_dev() { df --output=source /mnt/nvme 2>/dev/null | tail -1 | xargs -r basename || echo nvme1n1; }

launch_node() {
  local k="$1" peers dd; peers=$(echo "1 2 3"|tr ' ' '\n'|grep -v "^$k$"|paste -sd,); dd="$BASE/n$k"; mkdir -p "$dd"
  java $GCFLAGS $HEAP -Dconfigd.netty.transport="$TRANSPORT" -Dconfigd.raft.shardCount="$N" ${NXC_JVM_EXTRA:-} \
    --enable-preview -jar "$JAR" --node-id "$k" --data-dir "$dd" --peers "$peers" --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
}
launch_cluster() {
  [ -s "$SIGNKEY" ] || cp "$PREGEN_KEY" "$SIGNKEY" || fail "pre-gen key missing: $PREGEN_KEY"
  launch_node 1; launch_node 2; launch_node 3
  local ready=0 i k
  for i in $(seq 1 120); do local ok=0; for k in 1 2 3; do [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)" = "200" ] && ok=$((ok+1)); done; [ "$ok" -eq 3 ] && { ready=1; break; }; sleep 0.5; done
  [ "$ready" -eq 1 ] || { tail -n 20 "$BASE"/n*.log; fail "cluster not ready"; }
}
leaders_present() { local t=0 k v; for k in 1 2 3; do v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | awk '/^raft_shard_leader_[0-9]+ /{ if ($2>=1) c++ } END{ print c+0 }'); t=$((t+${v:-0})); done; echo "$t"; }
leader_dist() { local o="" k v; for k in 1 2 3; do v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | awk '/^raft_shard_leader_[0-9]+ /{ if ($2>=1) c++ } END{ print c+0 }'); o="$o n$k=${v:-0}"; done; echo "${o# }"; }
wait_all_shards_led() { local t; for t in $(seq 1 80); do [ "$(leaders_present)" -eq "$N" ] 2>/dev/null && return 0; sleep 0.5; done; return 1; }
read_elections() { local m=0 k v; for k in 1 2 3; do v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | grep -E '^configd_raft_elections_total' | awk '{print $NF}' | sort -nr | head -1); v=${v%%.*}; [ -z "$v" ] && v=0; [ "$v" -gt "$m" ] 2>/dev/null && m=$v; done; echo "$m"; }
start_samplers() { local tag="$1" pidcsv; pidcsv=$(IFS=,; echo "${PIDS[*]}"); pidstat -h -u 1 > "$OUT/$tag.pidstat.txt" 2>/dev/null & SAMPLERS+=("$!"); iostat -x -d "$(nvme_dev)" 1 > "$OUT/$tag.iostat.txt" 2>/dev/null & SAMPLERS+=("$!"); mpstat 1 > "$OUT/$tag.mpstat.txt" 2>/dev/null & SAMPLERS+=("$!"); }
stop_samplers() { for p in "${SAMPLERS[@]:-}"; do kill "$p" 2>/dev/null; done; SAMPLERS=(); }
driver() { java $GCFLAGS -Xmx2g --enable-preview -cp "$BENCH" io.configd.bench.ShardAwareWriteDriver "$@" 2>&1 | grep -v "WARNING\|Unsafe\|sun.misc\|native-access"; }

RES="$OUT/calib.tsv"
echo -e "N\tconc\tsustained_rate\tcommitted\tnon200\tretargets\telections\tcpu_busy_pct\tnvme_util_pct\tleader_dist" > "$RES"
echo "[nxc] N=$N concurrencies: $CONCS dur=${DUR}s val=${VALBYTES}B out=$OUT"
launch_cluster
wait_all_shards_led || fail "not all $N shards led ($(leaders_present)/$N)"
echo "[nxc] cluster up; leaders: $(leader_dist)"
NODEMAP="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"
[ "$DRYRUN" = "1" ] || { wal=$(find "$BASE" -name "*.wal" | head -1); echo "[nxc] WAL=$(readlink -f "$wal")"; }
for C in $CONCS; do
  echo "=== N=$N concurrency=$C ==="
  e0=$(read_elections)
  start_samplers "c$C"
  RAW="$OUT/c$C.txt"
  driver calibrate-sharded "$NODEMAP" "$N" "$DUR" "$C" "$VALBYTES" | tee "$RAW"
  stop_samplers
  e1=$(read_elections); DIST=$(leader_dist)
  rate=$(grep CALIBRATE-RESULT "$RAW" | sed -n 's/.*sustained_commit_rate_per_sec=\([0-9]*\).*/\1/p')
  comm=$(grep CALIBRATE-RESULT "$RAW" | sed -n 's/.*committed_200=\([0-9]*\).*/\1/p')
  non=$(grep CALIBRATE-RESULT "$RAW" | sed -n 's/.*non200=\([0-9]*\).*/\1/p')
  rtg=$(grep CALIBRATE-RESULT "$RAW" | sed -n 's/.*retargets=\([0-9]*\).*/\1/p')
  # avg CPU busy (100-idle) + avg nvme util across the steady samples
  cpu=$(awk '/all/{i=$NF; if(i+0==i){s+=100-i;n++}} END{if(n)printf "%.0f",s/n; else print "?"}' "$OUT/c$C.mpstat.txt" 2>/dev/null)
  util=$(grep "$(nvme_dev)" "$OUT/c$C.iostat.txt" 2>/dev/null | awk 'NR>2{s+=$NF;n++} END{if(n)printf "%.0f",s/n; else print "?"}')
  echo -e "${N}\t${C}\t${rate:-?}\t${comm:-?}\t${non:-?}\t${rtg:-0}\t$((e1-e0))\t${cpu:-?}\t${util:-?}\t${DIST// /,}" >> "$RES"
  echo "[nxc] N=$N C=$C -> sustained=${rate:-?}/s non200=${non:-0} retargets=${rtg:-0} elections=$((e1-e0)) cpu_busy=${cpu}% nvme_util=${util}% leaders[$DIST]"
done
echo ""; echo "=== N=$N CALIBRATION ==="; column -t "$RES" 2>/dev/null || cat "$RES"
echo "nxc N=$N COMPLETE (out=$OUT)"
