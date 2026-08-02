#!/usr/bin/env bash
# nxknee.sh — multi-Raft N x knee: aggregate write-throughput scaling vs shard count N.
# Two design points for honest measurement:
#   (1) StaticShardMap routes (scope,key)->shard; keys spread uniformly; no edge-port (write/consensus plane).
#   (2) Symmetric realistic election timeouts (150/300/50 ms defaults); leaders scatter naturally.
#       Asymmetric timeouts were rejected — short leader timeout makes leader fragile at knee.
# ShardAwareWriteDriver replicates StaticShardMap + learns per-shard leaders from X-Leader-Hint.
# Knee is where 16 vCPU + shared NVMe fsync saturate.
#
# Usage:  perf/nxknee.sh <N> [outdir]
# Env:    NXK_RATES (default auto ~N*800/s)  NXK_DUR (default 20)  NXK_CONC (default auto)
#         NXK_VALBYTES (default 512)  NXK_HEAP (default "-Xmx4g -Xms4g")  NXK_GC (default ZGC)
#         NXK_TRANSPORT (default epoll)  NXK_BASE (default /mnt/nvme/run/...)
#         NXK_DRYRUN (default 0)  CONFIGD_JAR  CONFIGD_BENCH  NXK_SIGNKEY

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="${CONFIGD_BENCH:-$ROOT/configd-testkit/target/benchmarks.jar}"

N="${1:?usage: nxknee.sh <N> [outdir]}"
DRYRUN="${NXK_DRYRUN:-0}"
BASE="${NXK_BASE:-/mnt/nvme/run/nxk-N${N}-$$}"
RAFT_BASE=9290; API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
HEAP="${NXK_HEAP:--Xmx4g -Xms4g}"
GCFLAGS="${NXK_GC:--XX:+UseZGC}"
TRANSPORT="${NXK_TRANSPORT:-epoll}"
DUR="${NXK_DUR:-20}"
VALBYTES="${NXK_VALBYTES:-512}"
CONC="${NXK_CONC:-$(( N*64 < 256 ? 256 : (N*64 > 512 ? 512 : N*64) ))}"
if [ -n "${NXK_RATES:-}" ]; then RATES="$NXK_RATES"; else
  k=$((N*800)); RATES="$((k/2)) $((k*3/4)) $k $((k*5/4)) $((k*3/2)) $((k*2))"
fi
OUT="${2:-$ROOT/docs/measurement/captures/nxknee-N${N}}"
PIDS=(); SAMPLERS=()
mkdir -p "$BASE" "$OUT"
SIGNKEY="$BASE/cluster-signing-key.bin"
PREGEN_KEY="${NXK_SIGNKEY:-$ROOT/deploy/compose/secrets/signing-key.bin}"

fail() { echo "nxk FAIL: $*" >&2; teardown_cluster; exit 1; }
teardown_cluster() {
  for pid in "${SAMPLERS[@]:-}"; do kill "$pid" 2>/dev/null; done; SAMPLERS=()
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done; PIDS=()
  pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null
  sleep 1
}
trap 'teardown_cluster; rm -rf "$BASE" 2>/dev/null' EXIT

[ -f "$JAR" ]   || fail "shaded jar not found: $JAR"
[ -f "$BENCH" ] || fail "benchmarks.jar not found: $BENCH"
if [ "$DRYRUN" != "1" ]; then case "$BASE" in /mnt/nvme/*) : ;; *) fail "NXK_BASE must be /mnt/nvme (got $BASE)"; esac; fi

api()      { echo "127.0.0.1:$((API_BASE + $1))"; }
nvme_dev() { df --output=source /mnt/nvme 2>/dev/null | tail -1 | xargs -r basename || echo nvme1n1; }

launch_node() {
  local k="$1"
  local peers; peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  local dd="$BASE/n$k"; mkdir -p "$dd"
  java $GCFLAGS $HEAP -Dconfigd.netty.transport="$TRANSPORT" -Dconfigd.raft.shardCount="$N" \
    ${NXK_JVM_EXTRA:-} --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
}

launch_cluster() {
  PIDS=()
  [ -s "$SIGNKEY" ] || cp "$PREGEN_KEY" "$SIGNKEY" || fail "pre-gen signing key missing: $PREGEN_KEY"
  launch_node 1; launch_node 2; launch_node 3
  local ready=0
  for i in $(seq 1 120); do
    local ok=0
    for k in 1 2 3; do
      [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)" = "200" ] && ok=$((ok + 1))
    done
    [ "$ok" -eq 3 ] && { ready=1; break; }
    sleep 0.5
  done
  [ "$ready" -eq 1 ] || { tail -n 20 "$BASE"/n*.log; fail "cluster not ready"; }
}

leaders_present() {
  local t=0 k v
  for k in 1 2 3; do
    v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | awk '/^raft_shard_leader_[0-9]+ /{ if ($2>=1) c++ } END{ print c+0 }')
    t=$((t + ${v:-0}))
  done; echo "$t"
}
leader_dist() {
  local out="" k v
  for k in 1 2 3; do
    v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | awk '/^raft_shard_leader_[0-9]+ /{ if ($2>=1) c++ } END{ print c+0 }')
    out="$out n$k=${v:-0}"
  done; echo "${out# }"
}
wait_all_shards_led() {
  for _t in $(seq 1 80); do [ "$(leaders_present)" -eq "$N" ] 2>/dev/null && return 0; sleep 0.5; done
  return 1
}

assert_data_on_nvme() {
  [ "$DRYRUN" = "1" ] && { echo "[nxk] DRYRUN: skip /mnt/nvme assert"; return 0; }
  local wal; wal=$(find "$BASE" -name "*.wal" 2>/dev/null | head -1)
  [ -n "$wal" ] || fail "no .wal under $BASE"
  local real; real=$(readlink -f "$wal")
  case "$real" in /mnt/nvme/*) echo "[nxk] DATA-ON-NVMe: $real" | tee "$OUT/data-on-nvme.txt" ;;
    *) fail "WAL off /mnt/nvme: $real" ;; esac
}

read_elections() {
  local maxv=0 k v
  for k in 1 2 3; do
    v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | grep -E '^configd_raft_elections_total' | awk '{print $NF}' | sort -nr | head -1)
    v=${v%%.*}; [ -z "$v" ] && v=0; [ "$v" -gt "$maxv" ] 2>/dev/null && maxv=$v
  done; echo "$maxv"
}

start_samplers() {
  local tag="$1"; local pidcsv; pidcsv=$(IFS=,; echo "${PIDS[*]}")
  pidstat -h -u -p "$pidcsv" 1 > "$OUT/$tag.pidstat.txt" 2>/dev/null & SAMPLERS+=("$!")
  iostat -x -d "$(nvme_dev)" 1 > "$OUT/$tag.iostat.txt" 2>/dev/null & SAMPLERS+=("$!")
  mpstat -P ALL 1 > "$OUT/$tag.mpstat.txt" 2>/dev/null & SAMPLERS+=("$!")
}
stop_samplers() { for pid in "${SAMPLERS[@]:-}"; do kill "$pid" 2>/dev/null; done; SAMPLERS=(); }

driver() { java $GCFLAGS -Xmx2g --enable-preview -cp "$BENCH" io.configd.bench.ShardAwareWriteDriver "$@" 2>&1 | grep -v "WARNING\|Unsafe\|sun.misc\|native-access"; }

LADDER="$OUT/ladder.tsv"
echo -e "N\toffered\tachieved\telections\tcode_200\tcode_503\tcode_504\tcode_429\tretargets\tleader_dist\tp50_us\tp99_us\tp999_us\tstate" > "$LADDER"
echo "[nxk] N=$N jar=$JAR transport=$TRANSPORT heap=$HEAP dur=${DUR}s conc=$CONC val=${VALBYTES}B"
echo "[nxk] rates: $RATES  (fresh cluster per rate, symmetric timeouts, shard-aware driver)  out=$OUT"
first=1
for RATE in $RATES; do
  echo ""
  echo "=================== N=$N RATE ${RATE}/s ==================="
  launch_cluster
  if ! wait_all_shards_led; then echo "[nxk] not all $N shards have a leader ($(leaders_present)/$N) at ${RATE}/s; skipping"; teardown_cluster; rm -rf "$BASE"/n* 2>/dev/null; continue; fi
  DIST=$(leader_dist); echo "[nxk] leader distribution: $DIST"
  [ "$first" = "1" ] && assert_data_on_nvme
  first=0
  NODEMAP="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"
  e0=$(read_elections)
  start_samplers "rate-$RATE"
  RAW="$OUT/rate-$RATE.txt"
  driver atrate-sharded "$NODEMAP" "$N" "$RATE" "$DUR" "$CONC" "$VALBYTES" | tee "$RAW"
  stop_samplers
  e1=$(read_elections)
  DIST_END=$(leader_dist)
  curl -s --max-time 3 "http://$(api 1)/metrics" 2>/dev/null > "$OUT/rate-$RATE.metrics.txt"

  ach=$(grep ATRATE-RESULT "$RAW" | sed -n 's/.*achieved_commit_rate=\([0-9]*\).*/\1/p')
  rtg=$(grep ATRATE-RESULT "$RAW" | sed -n 's/.*retargets=\([0-9]*\).*/\1/p')
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
  if [ "${c503:-0}" -gt $((RATE/2)) ] 2>/dev/null || [ "${elec:-0}" -ge 8 ] 2>/dev/null; then state="collapsed"; fi
  echo -e "${N}\t${RATE}\t${ach:-?}\t${elec}\t${c200}\t${c503}\t${c504}\t${c429}\t${rtg:-0}\t${DIST_END// /,}\t${p50:-?}\t${p99:-?}\t${p999:-?}\t${state}" >> "$LADDER"
  echo "[nxk] N=$N ${RATE}/s -> achieved=${ach:-?}/s 200=$c200 503=$c503 retargets=$rtg leaders[$DIST_END] elections=$elec state=$state"
  teardown_cluster
  rm -rf "$BASE"/n* 2>/dev/null
done

echo ""
echo "================= N=$N LADDER ================="
column -t "$LADDER" 2>/dev/null || cat "$LADDER"
echo "nxk N=$N COMPLETE (out=$OUT)"
