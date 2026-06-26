#!/usr/bin/env bash
# On-box (m6id) — runs AFTER the primary ladder. Three things:
#  (1) VARIANCE: 3 fresh passes each at 800 (stable) + 1000 (collapsed) — is the knee stable?
#  (2) ADMISSION axis: 2000/s with maxInflightProposals=16 vs control — reproduce §7.5 §G on re-threaded code?
#  (3) THREAD-LEVEL attribution: sustained 1000/s, top -bH of the leader -> is configd-raft-owner-0 the
#      single pegged thread (1 core ~100%) while the box has 15 idle cores? (the definitive single-thread proof)
set -u
export PATH=/opt/jdk/bin:$PATH
HOME_W=$HOME/wsc
JAR=$HOME_W/configd-server.jar
BENCH=$HOME_W/benchmarks.jar
LADDER=$HOME_W/perf/wsC-ladder.sh

echo "############ (1) VARIANCE: 800,1000 x3 ############"
CONFIGD_JAR=$JAR CONFIGD_BENCH=$BENCH WSC_BASE=/mnt/nvme/run/wscvar WSC_TRANSPORT=epoll \
  WSC_RATES="800 1000 800 1000 800 1000" bash "$LADDER" "$HOME_W/out-var"

echo "############ (2) ADMISSION: control 2000/s, then maxInflightProposals=16 ############"
CONFIGD_JAR=$JAR CONFIGD_BENCH=$BENCH WSC_BASE=/mnt/nvme/run/wscadmc WSC_TRANSPORT=epoll \
  WSC_RATES="2000" bash "$LADDER" "$HOME_W/out-adm-control"
CONFIGD_JAR=$JAR CONFIGD_BENCH=$BENCH WSC_BASE=/mnt/nvme/run/wscadm WSC_TRANSPORT=epoll \
  WSC_JVM_EXTRA="-Dconfigd.write.maxInflightProposals=16" \
  WSC_RATES="2000" bash "$LADDER" "$HOME_W/out-adm-16"

echo "############ (3) THREAD-LEVEL attribution @1000/s ############"
B=/mnt/nvme/run/wscthr
rm -rf "$B"; mkdir -p "$B"
SIGNKEY="$B/key.bin"
RAFT_BASE=9290; API_BASE=8280
PEERS="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
launch() {
  local k=$1 p; p=$(echo "1 2 3"|tr ' ' '\n'|grep -v "^$k$"|paste -sd,); mkdir -p "$B/n$k"
  java -XX:+UseZGC -Xmx4g -Xms4g -Dconfigd.netty.transport=epoll --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$B/n$k" --peers "$p" --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE+k)) --api-port $((API_BASE+k)) \
    --peer-addresses "$PEERS" > "$B/n$k.log" 2>&1 &
  echo $!
}
P1=$(launch 1); for i in $(seq 1 40); do [ -s "$SIGNKEY" ] && break; sleep 0.25; done
P2=$(launch 2); P3=$(launch 3)
for i in $(seq 1 60); do
  ok=0; for k in 1 2 3; do c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://127.0.0.1:$((API_BASE+k))/health/ready"); [ "$c" = 200 ] && ok=$((ok+1)); done
  [ $ok -eq 3 ] && break; sleep 0.5
done
# resolve leader pid
LPID=""; LNODE=""
for k in 1 2 3; do
  c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 -X PUT -d probe "http://127.0.0.1:$((API_BASE+k))/v1/config/__thr__")
  if [ "$c" = 200 ]; then LNODE=$k; case $k in 1) LPID=$P1;; 2) LPID=$P2;; 3) LPID=$P3;; esac; break; fi
done
echo "leader=node $LNODE pid=$LPID"
NODEMAP="1=http://127.0.0.1:8281,2=http://127.0.0.1:8282,3=http://127.0.0.1:8283"
# drive 1000/s for 45s in the background; sample top -bH of the leader JVM during it
java -XX:+UseZGC -Xmx2g --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
  atrate "$NODEMAP" 1000 45 256 512 > "$B/driver.txt" 2>&1 &
DPID=$!
sleep 10
echo "=== top -bH leader (node $LNODE) — per-THREAD %CPU, 12 samples @1s ==="
top -bH -d 1 -n 12 -p "$LPID" | grep -E "configd-raft-owner|%CPU|PID  *USER|java|epoll|raft" > "$B/top-threads.txt" 2>&1
echo "--- threads sorted by CPU (one sample) ---"
top -bH -d 1 -n 2 -p "$LPID" | awk 'f&&NF>=12{print $9, $12} /PID +USER/{f=1}' | sort -rn | head -15 | tee "$B/top-sorted.txt"
jstack "$LPID" > "$B/leader-jstack-1.txt" 2>&1; sleep 2; jstack "$LPID" > "$B/leader-jstack-2.txt" 2>&1
echo "=== owner-thread CPU lines ==="; grep "configd-raft-owner" "$B/top-threads.txt" | head
wait $DPID 2>/dev/null
echo "=== driver result @1000/s (thread-attr run) ==="; grep -E "ATRATE-RESULT|ATRATE-STATUS" "$B/driver.txt"
cp "$B/top-threads.txt" "$B/top-sorted.txt" "$B/driver.txt" "$B/leader-jstack-1.txt" "$HOME_W/out/" 2>/dev/null
kill -9 $P1 $P2 $P3 2>/dev/null
echo "WSC-EXTRA COMPLETE"
