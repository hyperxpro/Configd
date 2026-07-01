#!/usr/bin/env bash
# drive.sh <plain|mtls> calibrate <N> <dur> <conc> [val]
# drive.sh <plain|mtls> atrate    <N> <rate> <dur> <conc> [val]
# Runs ON the load box. Constructs the cross-box NODEMAP (cp1/cp2/cp3:8281).
set -u
export JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH
MODE="$1"; KIND="$2"; N="$3"; shift 3
BENCH=/data/Configd/configd-testkit/target/benchmarks.jar
if [ "$MODE" = "mtls" ]; then
  SCHEME=https
  TLS="-Djavax.net.ssl.keyStore=/data/secrets/server-ks.p12 -Djavax.net.ssl.keyStorePassword= -Djavax.net.ssl.keyStoreType=PKCS12 -Djavax.net.ssl.trustStore=/data/secrets/server-ts.p12 -Djavax.net.ssl.trustStorePassword= -Djavax.net.ssl.trustStoreType=PKCS12"
else SCHEME=http; TLS=""; fi
NODEMAP="1=$SCHEME://cp1:8281,2=$SCHEME://cp2:8281,3=$SCHEME://cp3:8281"
if [ "$KIND" = "calibrate" ]; then
  DUR="$1"; C="$2"; VAL="${3:-512}"
  ARGS="calibrate-sharded $NODEMAP $N $DUR $C $VAL"
else
  RATE="$1"; DUR="$2"; C="$3"; VAL="${4:-512}"
  ARGS="atrate-sharded $NODEMAP $N $RATE $DUR $C $VAL"
fi
exec java -XX:+UseZGC -Xmx3g $TLS --enable-preview -cp "$BENCH" io.configd.bench.ShardAwareWriteDriver $ARGS 2>&1 | grep -vE "WARNING|Unsafe|sun\.misc|native-access"
