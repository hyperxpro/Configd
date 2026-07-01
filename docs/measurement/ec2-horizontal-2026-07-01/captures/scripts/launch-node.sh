#!/usr/bin/env bash
# launch-node.sh <node-id> <N> <plain|mtls> [ownerPool=N] [wipe=1]
# Runs ON a cp box. One JVM per box; peer-addresses use cp1/cp2/cp3 (/etc/hosts + cert SANs).
set -u
export JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH
ID="$1"; N="$2"; MODE="${3:-plain}"; OP="${4:-$N}"; WIPE="${5:-1}"
JAR=$(ls /data/Configd/configd-server/target/configd-server-*.jar | grep -v original- | head -1)
SK=/data/secrets/signing-key.bin; S=/data/secrets
DD=/data/run/n$ID
PEERS=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$ID$" | paste -sd,)
PEERS_ADDR="1=cp1:9291,2=cp2:9291,3=cp3:9291"
pkill -9 -f -- "--node-id $ID --data-dir $DD" 2>/dev/null; sleep 1
[ "$WIPE" = "1" ] && rm -rf "$DD"
mkdir -p "$DD"
TLS=""
[ "$MODE" = "mtls" ] && TLS="--tls-cert $S/server.pem --tls-key $S/server-ks.p12 --tls-trust-store $S/server-ts.p12"
nohup java -XX:+UseZGC -Xmx4g -Xms4g -Dconfigd.netty.transport=epoll \
  -Dconfigd.raft.shardCount="$N" -Dconfigd.raft.ownerPoolSize="$OP" --enable-preview -jar "$JAR" \
  --node-id "$ID" --data-dir "$DD" --peers "$PEERS" --signing-key-file "$SK" \
  --bind-address 0.0.0.0 --bind-port 9291 --api-port 8281 --peer-addresses "$PEERS_ADDR" $TLS \
  > "$DD.log" 2>&1 &
echo "NODE-$ID-STARTED pid=$! N=$N mode=$MODE ownerPool=$OP wipe=$WIPE"
