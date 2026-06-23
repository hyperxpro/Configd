#!/usr/bin/env bash
# Reverify the JDK e2e send allocation WITHOUT JFR (exact getTotalThreadAllocatedBytes), high N,
# both payloads, both jdk variants. Expect ~0 B/msg (the profiled "40" was a JFR jdk.SocketWrite
# InetSocketAddress artifact, JDK-sockets-only, absent without JFR).
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
PORT=19094
OUT=jdk-reverify.txt
: > "$OUT"
pkill -9 -f ConsensusDrainServerMain 2>/dev/null
java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain "$PORT" > drain-prof.txt 2>&1 &
D=$!
for _ in $(seq 1 50); do grep -q DRAIN_READY drain-prof.txt 2>/dev/null && break; sleep 0.2; done
for variant in jdk jdk-batched; do
  for payload in 0 4096; do
    java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusSendE2EMain \
      127.0.0.1 "$PORT" "$payload" 200000 2000000 "$variant" 2>&1 | grep '^E2E' >> "$OUT"
  done
done
kill -9 "$D" 2>/dev/null
echo "== done ==" >> "$OUT"
exit 0
