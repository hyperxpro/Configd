#!/usr/bin/env bash
# =============================================================================
# wsC-ladder.sh — Multi-Raft Workstream C: re-threaded SINGLE-GROUP throughput
#                 ceiling (decides multi-Raft v1-vs-v2).
# -----------------------------------------------------------------------------
# Measures the HONEST sustained write-throughput knee of the re-threaded single
# Raft group (Phase 0: owner-executor pool + coalesced heartbeats; ADR-0043
# Netty consensus wire) and compares it to the §7.5 ~800/s baseline measured on
# the SAME instance type (m6id.4xlarge). The delta is attributable to Phase 0's
# re-threading (hardware held constant); the transport is forced + verified so
# the io_uring axis (Phase V: ~2× worse for consensus) cannot confound it.
#
# Derived from perf/s75-throughput.sh (the §7.5 harness) — SAME cluster launch
# (3 co-located nodes, shared pre-generated signing key, data+WAL on /mnt/nvme,
# ZGC 4g heaps), SAME open-loop CO-corrected OpenLoopWriteDriver, SAME per-phase
# iostat/mpstat/pidstat instrumentation. The ONE change: it climbs a rate LADDER
# with a FRESH cluster per rate (a collapsed cluster from a high rate must not
# poison the next point — §7.5 §C did this), reading configd_raft_elections_total
# per rate as the direct heartbeat-starvation signal.
#
#   Usage:  perf/wsC-ladder.sh [outdir]
#   Env:    WSC_RATES   (default "200 400 600 800 1000 1200 2000 4000 8000")  — the §7.5 ladder
#           WSC_DUR     (default 15)    per-rate run seconds (§7.5 used 15)
#           WSC_CONC    (default 256)   driver concurrency (§7.5 used 256)
#           WSC_VALBYTES(default 512)   value size  (§7.5 used 512B)
#           WSC_HEAP    (default "-Xmx4g -Xms4g")   per-node heap
#           WSC_GC      (default "-XX:+UseZGC")     ADR-0041 generational ZGC
#           WSC_TRANSPORT (default epoll)  forced consensus tier (epoll|nio|io_uring)
#           WSC_JVM_EXTRA (default "")   extra per-node JVM flags, e.g.
#                          "-Dconfigd.write.maxInflightProposals=16"  (admission axis)
#                          "-Dconfigd.raft.ownerPoolSize=4"           (no effect at 1 group)
#           WSC_BASE    (default /mnt/nvme/run/wsc-<pid>)   data+WAL root (MUST be /mnt/nvme)
#           WSC_DRYRUN  (default 0)      1 = dev-box smoke (skip /mnt/nvme assert; NOT a measurement)
#           CONFIGD_JAR (shaded server jar)   CONFIGD_BENCH (benchmarks.jar)
# =============================================================================
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="${CONFIGD_BENCH:-$ROOT/configd-testkit/target/benchmarks.jar}"
DRYRUN="${WSC_DRYRUN:-0}"
BASE="${WSC_BASE:-/mnt/nvme/run/wsc-$$}"
RAFT_BASE=9290
API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
HEAP="${WSC_HEAP:--Xmx4g -Xms4g}"
GCFLAGS="${WSC_GC:--XX:+UseZGC}"
TRANSPORT="${WSC_TRANSPORT:-epoll}"
JVM_EXTRA="${WSC_JVM_EXTRA:-}"
RATES="${WSC_RATES:-200 400 600 800 1000 1200 2000 4000 8000}"
DUR="${WSC_DUR:-15}"
CONC="${WSC_CONC:-256}"
VALBYTES="${WSC_VALBYTES:-512}"
OUT="${1:-$ROOT/docs/multiraft/captures/wsC-ladder}"
PIDS=(); SAMPLERS=()

fail() { echo "wsC FAIL: $*" >&2; teardown_cluster; exit 1; }
teardown_cluster() {
  for pid in "${SAMPLERS[@]:-}"; do kill "$pid" 2>/dev/null; done; SAMPLERS=()
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done; PIDS=()
  pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null
  sleep 1
}
trap 'teardown_cluster; rm -rf "$BASE" 2>/dev/null' EXIT

[ -f "$JAR" ]   || fail "shaded jar not found: $JAR (build: ./mvnw -pl configd-server -am package -DskipTests)"
[ -f "$BENCH" ] || fail "benchmarks.jar not found: $BENCH (build: ./mvnw -pl configd-testkit -am package -DskipTests)"
if [ "$DRYRUN" != "1" ]; then
  case "$BASE" in /mnt/nvme/*) : ;; *) fail "WSC_BASE must be under /mnt/nvme (got $BASE) — the fsync-honest path" ;; esac
fi
mkdir -p "$BASE" "$OUT"
SIGNKEY="$BASE/cluster-signing-key.bin"   # shared, OUTSIDE every node data dir (D-1-safe)

api()      { echo "127.0.0.1:$((API_BASE + $1))"; }
nvme_dev() { df --output=source /mnt/nvme 2>/dev/null | tail -1 | xargs -r basename || lsblk -no NAME / | head -1; }

launch_node() {
  local k="$1"
  local peers; peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  local dd="$BASE/n$k"; mkdir -p "$dd"
  # -Dconfigd.netty.transport forces the consensus tier AND fails loud at startup if
  # that tier is unavailable on the host (NettyTransport.forced()): a clean runtime
  # guarantee that the measured tier is the intended one (no silent io_uring confound).
  java $GCFLAGS $HEAP -Dconfigd.netty.transport="$TRANSPORT" $JVM_EXTRA --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" \
    --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
}

launch_cluster() {
  PIDS=()
  # Pre-generate the shared signing key by launching node 1 alone (the loadOrCreate
  # exists()-then-CREATE_NEW race crashes simultaneous first-boot — §7.5 D-1), then 2,3 LOAD it.
  launch_node 1
  local keyok=0
  for i in $(seq 1 40); do
    [ -s "$SIGNKEY" ] && { keyok=1; break; }
    kill -0 "${PIDS[0]}" 2>/dev/null || { tail -n 30 "$BASE"/n1.log; fail "node 1 exited before creating signing key"; }
    sleep 0.25
  done
  [ "$keyok" -eq 1 ] || { tail -n 30 "$BASE"/n1.log; fail "signing key not created by node 1 within 10s"; }
  launch_node 2
  launch_node 3
  local ready=0
  for i in $(seq 1 80); do
    local ok=0
    for k in 1 2 3; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)
      [ "$code" = "200" ] && ok=$((ok + 1))
    done
    [ "$ok" -eq 3 ] && { ready=1; break; }
    sleep 0.5
  done
  [ "$ready" -eq 1 ] || { tail -n 20 "$BASE"/n*.log; fail "cluster not ready"; }
}

verify_transport() {
  local line; line=$(grep -h "tier=" "$BASE"/n*.log 2>/dev/null | head -1)
  if echo "$line" | grep -q "tier=$TRANSPORT"; then
    echo "[wsC] TRANSPORT CONFIRMED at runtime: $(echo "$line" | sed 's/.*\(\[tier=[^]]*\]\).*/\1/')" | tee -a "$OUT/transport.txt"
  else
    echo "[wsC] WARN: 'tier=$TRANSPORT' not found in node logs; saw: '$line'" | tee -a "$OUT/transport.txt"
  fi
}

resolve_leader() {
  for _try in $(seq 1 40); do
    for k in 1 2 3; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d probe \
             "http://$(api $k)/v1/config/__wsc_probe__" 2>/dev/null)
      [ "$code" = "200" ] && { echo "$k"; return 0; }
    done
    sleep 0.5
  done
  return 1
}

assert_data_on_nvme() {
  [ "$DRYRUN" = "1" ] && { echo "[wsC] DRYRUN: skipping /mnt/nvme assertion (smoke only, NOT a measurement)"; return 0; }
  local wal; wal=$(find "$BASE" -name "*.wal" 2>/dev/null | head -1)
  [ -n "$wal" ] || fail "no .wal file under $BASE — cannot confirm data-on-NVMe"
  local real; real=$(readlink -f "$wal")
  case "$real" in
    /mnt/nvme/*) echo "[wsC] DATA-ON-NVMe CONFIRMED: WAL=$real (device $(df --output=source "$real" | tail -1))" | tee "$OUT/data-on-nvme.txt" ;;
    *) fail "WAL resolves OFF /mnt/nvme: $real" ;;
  esac
}

# configd_raft_elections_total (max across the 3 nodes) — the direct heartbeat-starvation signal.
read_elections() {
  local maxv=0
  for k in 1 2 3; do
    local v
    v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null \
        | grep -E '^configd_raft_elections_total' | awk '{print $NF}' | sort -nr | head -1)
    v=${v%%.*}; [ -z "$v" ] && v=0
    if [ "$v" -gt "$maxv" ] 2>/dev/null; then maxv=$v; fi
  done
  echo "$maxv"
}

start_samplers() {
  local tag="$1"; local pidcsv; pidcsv=$(IFS=,; echo "${PIDS[*]}")
  pidstat -h -u -p "$pidcsv" 1 > "$OUT/$tag.pidstat.txt" 2>/dev/null & SAMPLERS+=("$!")
  iostat -x -d "$(nvme_dev)" 1 > "$OUT/$tag.iostat.txt" 2>/dev/null & SAMPLERS+=("$!")
  mpstat -P ALL 1 > "$OUT/$tag.mpstat.txt" 2>/dev/null & SAMPLERS+=("$!")
}
stop_samplers() { for pid in "${SAMPLERS[@]:-}"; do kill "$pid" 2>/dev/null; done; SAMPLERS=(); }

driver() { java $GCFLAGS -Xmx2g --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver "$@" 2>&1 | grep -v "WARNING\|Unsafe\|sun.misc\|native-access"; }

# ----------------------------------------------------------------------------
LADDER="$OUT/ladder.tsv"
echo -e "offered\tachieved\telections\tcode_200\tcode_503\tcode_504\tcode_429\trejected_bp\tp50_us\tp99_us\tp999_us\tstate" > "$LADDER"
echo "[wsC] jar=$JAR"
echo "[wsC] transport=$TRANSPORT  heap=$HEAP  gc=$GCFLAGS  dur=${DUR}s conc=$CONC val=${VALBYTES}B  extra='$JVM_EXTRA'"
echo "[wsC] rates: $RATES  (fresh cluster per rate)  out=$OUT"
first=1
for RATE in $RATES; do
  echo ""
  echo "=================== RATE ${RATE}/s ==================="
  launch_cluster
  [ "$first" = "1" ] && { verify_transport; }
  L=$(resolve_leader) || { echo "[wsC] no leader at ${RATE}/s"; teardown_cluster; rm -rf "$BASE"/n* 2>/dev/null; continue; }
  [ "$first" = "1" ] && assert_data_on_nvme
  first=0
  NODEMAP="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"
  e0=$(read_elections)
  echo "[wsC] leader=node $L  elections(before)=$e0"
  start_samplers "rate-$RATE"
  RAW="$OUT/rate-$RATE.txt"
  driver atrate "$NODEMAP" "$RATE" "$DUR" "$CONC" "$VALBYTES" | tee "$RAW"
  stop_samplers
  e1=$(read_elections)
  curl -s --max-time 3 "http://$(api "$L")/metrics" 2>/dev/null > "$OUT/rate-$RATE.metrics.txt"

  # parse the driver output
  ach=$(grep ATRATE-RESULT "$RAW" | sed -n 's/.*achieved_commit_rate=\([0-9]*\).*/\1/p')
  rej=$(grep ATRATE-RESULT "$RAW" | sed -n 's/.*rejected_backpressure=\([0-9]*\).*/\1/p')
  st=$(grep ATRATE-STATUS "$RAW" | sed 's/ATRATE-STATUS //')
  c200=$(echo "$st" | grep -o '200=[0-9]*' | cut -d= -f2); c200=${c200:-0}
  c503=$(echo "$st" | grep -o '503=[0-9]*' | cut -d= -f2); c503=${c503:-0}
  c504=$(echo "$st" | grep -o '504=[0-9]*' | cut -d= -f2); c504=${c504:-0}
  c429=$(echo "$st" | grep -o '429=[0-9]*' | cut -d= -f2); c429=${c429:-0}
  p50=$(grep ATRATE-HISTOGRAM "$RAW" | sed -n 's/.* p50=\([0-9]*\).*/\1/p')
  p99=$(grep ATRATE-HISTOGRAM "$RAW" | sed -n 's/.* p99=\([0-9]*\).*/\1/p')
  p999=$(grep ATRATE-HISTOGRAM "$RAW" | sed -n 's/.* p999=\([0-9]*\).*/\1/p')
  elec=$((e1 - e0))
  state="stable"
  if [ "${c503:-0}" -gt 100 ] 2>/dev/null || [ "${elec:-0}" -ge 5 ] 2>/dev/null; then state="collapsed"; fi
  echo -e "${RATE}\t${ach:-?}\t${elec}\t${c200}\t${c503}\t${c504}\t${c429}\t${rej:-0}\t${p50:-?}\t${p99:-?}\t${p999:-?}\t${state}" >> "$LADDER"
  echo "[wsC] ${RATE}/s -> achieved=${ach:-?}/s elections(delta)=${elec} 200=$c200 503=$c503 429=$c429 state=$state"

  teardown_cluster
  rm -rf "$BASE"/n* 2>/dev/null   # fresh data dirs next rate; keep signing key + logs dir
done

echo ""
echo "================= LADDER SUMMARY ================="
column -t "$LADDER" 2>/dev/null || cat "$LADDER"
echo "wsC LADDER COMPLETE (out=$OUT)"
