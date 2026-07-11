#!/usr/bin/env bash
# setup-secrets.sh generates the mTLS and signing key material for the
# Docker-Compose E2E topology into deploy/compose/secrets/.
#
# Pattern: the FanOutServerMtlsTest / EdgeNodeIntegrationTest keytool fixture
# (EC secp256r1 self-signed certs, cross-imported trust), adapted for the
# production CLI TLS path: TlsConfig.mtls hard-codes an empty store password
# and keytool refuses passwords < 6 chars, so every keytool-built store is
# repacked to an empty-password PKCS12 via SecretsTool.java (which also
# verifies each artifact loads exactly the way TlsManager will load it).
#
# Identity model: the mTLS client-cert Subject DN is the authoritative edge
# identity -- each edge gets CN=edge-N and passes the same string as
# --edge-id. The single server cert carries SANs for cp1/cp2/cp3 (the edge
# client enforces HTTPS endpoint identification) and is also trusted by the
# server trust store (CP nodes present it as the client cert in Raft peer
# mTLS).
#
# The Ed25519 signing key is minted once and mounted into all three CP nodes
# (--signing-key-file): each node signs its own fan-out stream at apply time,
# so a per-node key would break edge verification at the first failover.
#
# Idempotent: a complete secrets/ dir is left alone; FORCE=1 regenerates.
# Requires: keytool + java (JDK 25) on PATH, and the configd-server shaded jar
# (pass via SERVER_JAR or build with: ./mvnw -pl configd-server -am clean package).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
SECRETS="$HERE/secrets"
PASS=changeit               # keytool scratch password; the shipped stores are empty-password
EDGES="${EDGES:-4}"         # edge-1..edge-3 steady-state + edge-4 (bootstrap phase)

SERVER_JAR="${SERVER_JAR:-$(ls "$REPO_ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1 || true)}"
[ -n "$SERVER_JAR" ] && [ -f "$SERVER_JAR" ] || {
    echo "setup-secrets: server shaded jar not found — build first:" >&2
    echo "  ./mvnw -pl configd-server -am clean package -DskipTests" >&2
    exit 1
}

if [ -f "$SECRETS/.complete" ] && [ "${FORCE:-0}" != "1" ]; then
    echo "setup-secrets: $SECRETS already complete (FORCE=1 to regenerate)"
    exit 0
fi

rm -rf "$SECRETS"
mkdir -p "$SECRETS"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

gen_keypair() { # <scratch-ks> <alias> <dname> <san>
    keytool -genkeypair -alias "$2" -keyalg EC -groupname secp256r1 \
        -sigalg SHA256withECDSA -validity 30 -dname "$3" -ext "san=$4" \
        -storetype PKCS12 -keystore "$1" -storepass "$PASS" -keypass "$PASS" >/dev/null
}

export_pem() { # <scratch-ks> <alias> <out.pem>
    keytool -exportcert -alias "$2" -keystore "$1" -storepass "$PASS" -rfc -file "$3" >/dev/null 2>&1
}

echo "[secrets] server keypair (CN=configd-cp; SANs cover cp1..cp3 + loopback)"
gen_keypair "$SCRATCH/server-ks.p12" server "CN=configd-cp" \
    "dns:cp1,dns:cp2,dns:cp3,dns:localhost,ip:127.0.0.1"
export_pem "$SCRATCH/server-ks.p12" server "$SECRETS/server.pem"
java "$HERE/SecretsTool.java" repack "$SCRATCH/server-ks.p12" "$SECRETS/server-ks.p12" "$PASS"

EDGE_TRUST_ARGS=()
for n in $(seq 1 "$EDGES"); do
    echo "[secrets] edge-$n keypair (CN=edge-$n — the authoritative mTLS identity)"
    gen_keypair "$SCRATCH/edge$n-ks.p12" "edge$n" "CN=edge-$n" "dns:edge$n"
    export_pem "$SCRATCH/edge$n-ks.p12" "edge$n" "$SECRETS/edge$n.pem"
    java "$HERE/SecretsTool.java" repack "$SCRATCH/edge$n-ks.p12" "$SECRETS/edge$n-ks.p12" "$PASS"
    EDGE_TRUST_ARGS+=("edge$n=$SECRETS/edge$n.pem")
done

echo "[secrets] trust stores (empty-password PKCS12, built directly from PEMs)"
# CP trusts: every edge cert (fan-out clients) + its own server cert (Raft peer mTLS).
java "$HERE/SecretsTool.java" truststore "$SECRETS/server-ts.p12" \
    "server=$SECRETS/server.pem" "${EDGE_TRUST_ARGS[@]}"
# Edges trust: the server cert only.
java "$HERE/SecretsTool.java" truststore "$SECRETS/edge-ts.p12" "server=$SECRETS/server.pem"

echo "[secrets] shared Ed25519 signing key + exported verify key (production classes)"
java -cp "$SERVER_JAR" "$HERE/SecretsTool.java" signing-key \
    "$SECRETS/signing-key.bin" "$SECRETS/verify-key.der"

# Containers run as root in this test topology; keep host-side perms tight anyway.
chmod 600 "$SECRETS"/*.p12 "$SECRETS/signing-key.bin"
chmod 644 "$SECRETS"/*.pem "$SECRETS/verify-key.der"
touch "$SECRETS/.complete"
echo "setup-secrets: done — $(ls "$SECRETS" | wc -l) artifacts under $SECRETS"
