#!/usr/bin/env bash
###############################################################################
# phase-v-matrix.sh — THE LOCKED Phase V measurement matrix.
#
# The SAME script runs on the dev box (PROFILE=dev, small params: proves every harness green +
# emits correct artifacts) and on the m6i.4xlarge (PROFILE=m6i, full scale). No improvisation on
# the paid box: the m6i runs this verbatim.
#
# Discipline (hard rules): foreground; PID-kill ONLY (never pkill -f — self-match trap); every
# server's tier is asserted == the forced tier (anti-silent-fallback); strace -c exact counts via
# 2-batch delta (warmup/startup/teardown cancel); throughput runs are UNTRACED (ptrace would
# penalise the higher-syscall transport). Surfaces: edge-read, fan-out, consensus(brief).
#
# Usage: JAR=<benchmarks.jar> PROFILE=dev|m6i bash phase-v-matrix.sh
###############################################################################
set -u
JAR="${JAR:?set JAR=/path/to/benchmarks.jar}"
PROFILE="${PROFILE:-dev}"
OUT="${OUT:-$(dirname "$JAR")/phase-v-results-$PROFILE}"
mkdir -p "$OUT/strace" "$OUT/client"
J="java --enable-preview"
PORT=30000   # incremented per run to avoid TIME_WAIT collisions

# Mutates the GLOBAL PORT (must NOT be called in $(...) — a subshell increment would be lost,
# causing every run to reuse the same port and collide). Use: next_port; hp=$PORT; cp=$((PORT+1))
next_port() { PORT=$((PORT+4)); }

# ---- parameter profiles ----
# SYS counts (strace 2-batch) are kept SMALL — the per-op ratio stabilises quickly and ptrace at
# 1024 connections is slow; TP counts (untraced throughput) are LARGE for a stable wall-clock window.
if [ "$PROFILE" = m6i ]; then
  EDGE_CONNS="1 8 64 256 1024"; EDGE_WARMUP=20000; EDGE_R_SYS=20000; EDGE_R_TP=200000
  FAN_SUBS="8 64 256 1024";     FAN_WARMUP=10000;  FAN_C_SYS=20000; FAN_C_TP=80000; FAN_RATE=4000
  CONS_C=40000; CONS_RATE=5000
else
  EDGE_CONNS="1 8 64";          EDGE_WARMUP=1500;  EDGE_R_SYS=4000;  EDGE_R_TP=4000
  FAN_SUBS="8 32";              FAN_WARMUP=1000;   FAN_C_SYS=4000;   FAN_C_TP=4000;  FAN_RATE=1000
  CONS_C=4000; CONS_RATE=2000
fi
CLIENT_WAIT_TICKS=1200   # max 0.5s ticks to wait for a client to finish (m6i big runs)

provenance() {
  { echo "profile=$PROFILE date=$(date -u +%FT%TZ)"
    echo "kernel=$(uname -r) nproc=$(nproc)"
    echo "io_uring_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo NA)"
    echo "jar=$JAR jar_sha=$(sha256sum "$JAR" | cut -c1-16)"
    grep -m1 netty.version pom.xml 2>/dev/null | tr -d ' ' || true
  } | tee "$OUT/provenance.txt"
}

# Wait for a client to finish: poll its log for the completion marker, OR the client PID to die.
# (The edge/fan-out client JVMs can linger on non-daemon threads after printing, so we NEVER block
#  on their exit — we detect the printed marker and move on, then kill them by PID.)
wait_client() {  # clientPid  clientLog  marker
  local cp=$1 log="$2" mark="$3" i
  for i in $(seq 1 $CLIENT_WAIT_TICKS); do
    grep -q "$mark" "$log" 2>/dev/null && return 0
    kill -0 $cp 2>/dev/null || return 0
    sleep 0.5
  done
  return 0
}

# strace a server JVM, drive a client, kill server by PID → strace summary in $OUT/strace/$tag.txt
# args: serverCmd readyGrep tierForce clientCmd clientMarker tag
traced_run() {
  local scmd="$1" rgrep="$2" force="$3" ccmd="$4" mark="$5" tag="$6"
  local sf="$OUT/strace/$tag.txt" slog="$OUT/srv-$tag.log" clog="$OUT/client/$tag.log"
  rm -f "$sf" "$slog" "$clog"
  strace -f -c -o "$sf" $scmd > "$slog" 2>&1 &
  local SP=$! i
  for i in $(seq 1 300); do grep -q "$rgrep" "$slog" 2>/dev/null && break; kill -0 $SP 2>/dev/null || break; sleep 0.2; done
  local tier; tier=$(grep -oE 'tier=[a-z_]+' "$slog" | head -1)
  if [ -n "$force" ] && [ "$tier" != "tier=$force" ]; then
    echo "  [$tag] TIER MISMATCH forced=$force got='$tier' — ABORT"; kill $(pgrep -P $SP) 2>/dev/null; kill $SP 2>/dev/null; return 1; fi
  $ccmd > "$clog" 2>&1 &
  local CP=$!
  wait_client $CP "$clog" "$mark"
  kill $CP 2>/dev/null
  kill $(pgrep -P $SP) 2>/dev/null            # java server → strace writes -c summary
  for i in $(seq 1 60); do [ -s "$sf" ] && break; sleep 0.5; done
  kill $SP 2>/dev/null; wait $SP 2>/dev/null
}

# untraced server+client (throughput); args: serverCmd readyGrep force clientCmd clientMarker tag
untraced_run() {
  local scmd="$1" rgrep="$2" force="$3" ccmd="$4" mark="$5" tag="$6"
  local slog="$OUT/srv-$tag.log" clog="$OUT/client/$tag.log"
  rm -f "$slog" "$clog"
  $scmd > "$slog" 2>&1 &
  local SP=$! i
  for i in $(seq 1 300); do grep -q "$rgrep" "$slog" 2>/dev/null && break; kill -0 $SP 2>/dev/null || break; sleep 0.2; done
  local tier; tier=$(grep -oE 'tier=[a-z_]+' "$slog" | head -1)
  if [ -n "$force" ] && [ "$tier" != "tier=$force" ]; then
    echo "  [$tag] TIER MISMATCH forced=$force got='$tier' — ABORT"; kill $(pgrep -P $SP) 2>/dev/null; kill $SP 2>/dev/null; return 1; fi
  $ccmd > "$clog" 2>&1 &
  local CP=$!
  wait_client $CP "$clog" "$mark"
  kill $CP 2>/dev/null; kill $(pgrep -P $SP) 2>/dev/null; kill $SP 2>/dev/null; wait $SP 2>/dev/null
}

echo "=================== PHASE V MATRIX ($PROFILE) ==================="
provenance

# ---------- 0. io_uring ACTIVE confirmation (production path, fail-loud) ----------
echo "--- 0. io_uring ACTIVE confirmation (production selector, fail-loud) ---"
next_port; hp=$PORT; cp=$((PORT+1))
$J -Dconfigd.netty.transport=io_uring -cp "$JAR" io.configd.edge.node.EdgeReadAllocServerMain netty-prod $hp $cp 4 64 > "$OUT/srv-confirm.log" 2>&1 &
P0=$!; for i in $(seq 1 100); do grep -q READY "$OUT/srv-confirm.log" 2>/dev/null && break; kill -0 $P0 2>/dev/null || break; sleep 0.2; done
CONFIRM_TIER=$(grep -oE 'tier=[a-z_]+' "$OUT/srv-confirm.log" | head -1)
echo "  forced io_uring → $CONFIRM_TIER $( [ "$CONFIRM_TIER" = tier=io_uring ] && echo '✓ ACTIVE (not silent epoll fallback)' || echo '✗ NOT ACTIVE — io_uring unavailable on this host!')"
kill $(pgrep -P $P0) 2>/dev/null; kill $P0 2>/dev/null; wait $P0 2>/dev/null

# ---------- 1+2. EDGE-READ: syscalls (2-batch) + throughput, per connection ----------
echo "--- 1+2. EDGE-READ (conns: $EDGE_CONNS) ---"
for tr in io_uring epoll; do
  for conn in $EDGE_CONNS; do
    for mult in 1 2; do
      R=$((EDGE_R_SYS*mult)); next_port; hp=$PORT; cp=$((PORT+1))
      traced_run \
        "$J -Dconfigd.netty.transport=$tr -cp $JAR io.configd.edge.node.EdgeReadAllocServerMain netty-prod $hp $cp 16 256" \
        READY "$tr" \
        "$J -cp $JAR io.configd.edge.node.EdgeReadLoadClientMain 127.0.0.1 $hp $cp 16 256 $conn $EDGE_WARMUP $R" \
        "CLIENT serverSide" "edge-sys-$tr-c$conn-x$mult"
      echo "  [edge-sys $tr c$conn x$mult] $(grep -oE 'throughputReqPerSec=[0-9]+' $OUT/client/edge-sys-$tr-c$conn-x$mult.log)"
    done
    # untraced throughput at this conn
    next_port; hp=$PORT; cp=$((PORT+1))
    untraced_run \
      "$J -Dconfigd.netty.transport=$tr -cp $JAR io.configd.edge.node.EdgeReadAllocServerMain netty-prod $hp $cp 16 256" \
      READY "$tr" \
      "$J -cp $JAR io.configd.edge.node.EdgeReadLoadClientMain 127.0.0.1 $hp $cp 16 256 $conn $EDGE_WARMUP $EDGE_R_TP" \
      "CLIENT serverSide" "edge-tp-$tr-c$conn"
    echo "  [edge-tp  $tr c$conn] $(grep -E 'throughputReqPerSec|latencyMicros' $OUT/client/edge-tp-$tr-c$conn.log | tr '\n' ' ')"
  done
done

# ---------- 3+4. FAN-OUT: syscalls (2-batch) + throughput, per subscriber count ----------
echo "--- 3+4. FAN-OUT (subs: $FAN_SUBS) ---"
for tr in io_uring epoll; do
  for subs in $FAN_SUBS; do
    for mult in 1 2; do
      C=$((FAN_C_SYS*mult)); next_port; ep=$PORT; cp=$((PORT+1))
      traced_run \
        "$J -Dconfigd.netty.transport=$tr -cp $JAR io.configd.fanout.FanOutPushServerMain $ep $cp" \
        READY "$tr" \
        "$J -cp $JAR io.configd.fanout.FanOutLoadClientMain 127.0.0.1 $ep $cp $subs 256 $FAN_WARMUP $C $FAN_RATE" \
        "CLIENT oneWayLatency" "fan-sys-$tr-s$subs-x$mult"
      echo "  [fan-sys $tr s$subs x$mult] $(grep -oE 'deliveryThroughputNotifPerSec=[0-9]+' $OUT/client/fan-sys-$tr-s$subs-x$mult.log) demo=$(grep -ciE 'to=CATCHUP|QUARANTIN' $OUT/srv-fan-sys-$tr-s$subs-x$mult.log)"
    done
    next_port; ep=$PORT; cp=$((PORT+1))
    untraced_run \
      "$J -Dconfigd.netty.transport=$tr -cp $JAR io.configd.fanout.FanOutPushServerMain $ep $cp" \
      READY "$tr" \
      "$J -cp $JAR io.configd.fanout.FanOutLoadClientMain 127.0.0.1 $ep $cp $subs 256 $FAN_WARMUP $FAN_C_TP $FAN_RATE" \
      "CLIENT oneWayLatency" "fan-tp-$tr-s$subs"
    echo "  [fan-tp  $tr s$subs] $(grep -E 'deliveryThroughput|oneWayLatency' $OUT/client/fan-tp-$tr-s$subs.log | tr '\n' ' ')"
  done
done

# ---------- 5. CONSENSUS (brief): syscalls (2-batch), 1 connection ----------
echo "--- 5. CONSENSUS (1 connection, brief) ---"
next_port; dport=$PORT
$J -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain $dport > "$OUT/cons-drain.log" 2>&1 &
DP=$!; for i in $(seq 1 60); do grep -q DRAIN_READY "$OUT/cons-drain.log" 2>/dev/null && break; sleep 0.2; done
for tr in io_uring epoll; do
  for mult in 1 2; do
    n=$((CONS_C*mult))
    sf="$OUT/strace/cons-$tr-x$mult.txt"; rm -f "$sf"
    strace -f -c -o "$sf" $J -Dconfigd.netty.transport=$tr -cp "$JAR" \
      io.configd.consensus.ConsensusSendMain 127.0.0.1 $dport 64 $n $CONS_RATE > "$OUT/cons-send-$tr-x$mult.log" 2>&1
    echo "  [cons $tr x$mult] $(grep -oE 'tier=[a-z_]+|SENT [0-9]+' $OUT/cons-send-$tr-x$mult.log | tr '\n' ' ')"
  done
done
kill $DP 2>/dev/null

echo "=================== MATRIX COMPLETE → $OUT ==================="
