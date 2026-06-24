#!/usr/bin/env bash
# Definitively attribute the encode-only Netty 160 B/op: run both CRC strategies under JFR
# allocation sampling and print per-msg alloc + the allocation type breakdown.
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
OUT=encode-only-attr.txt
: > "$OUT"
for mode in nio internal; do
  JFR="encode-only-${mode}.jfr"
  java --enable-preview --enable-native-access=ALL-UNNAMED \
    -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2 \
    -XX:StartFlightRecording=filename="$JFR",settings=profile,dumponexit=true \
    -cp "$JAR" io.configd.jdkvsnetty.NettyEncodeOnlyProfileMain 256 200000 3000000 "$mode" \
    2>&1 | grep -E '^ENCODE-ONLY' | tee -a "$OUT"
  echo "--- mode=$mode : allocation sample count by type ---" >> "$OUT"
  jfr print --events jdk.ObjectAllocationSample "$JFR" 2>/dev/null \
    | grep 'objectClass =' | sed 's/.*objectClass = //; s/ (classLoader.*//' \
    | sort | uniq -c | sort -rn | head -8 >> "$OUT"
done
echo "== done ==" >> "$OUT"
exit 0
