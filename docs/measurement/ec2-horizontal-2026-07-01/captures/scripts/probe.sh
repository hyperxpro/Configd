#!/usr/bin/env bash
# probe.sh <plain|mtls> <ready|dist|elections|leaders_present|health1>
# Runs ON the load box (reaches cp1/cp2/cp3:8281). Uses shared server cert for mTLS.
set -u
MODE="$1"; CMD="$2"; N="${3:-}"
S=/data/secrets
if [ "$MODE" = "mtls" ]; then
  SCHEME=https
  AUTH=(--cacert "$S/server.pem" --cert "$S/server-ks.p12:" --cert-type P12)
else
  SCHEME=http
  AUTH=()
fi
get() { curl -s --max-time 4 "${AUTH[@]}" "$SCHEME://$1:8281/$2" 2>/dev/null; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 4 "${AUTH[@]}" "$SCHEME://$1:8281/$2" 2>/dev/null; }
node_leaders() { get "$1" metrics | awk '/^raft_shard_leader_[0-9]+ /{ if ($2>=1) c++ } END{ print c+0 }'; }
case "$CMD" in
  ready)
    ok=0; for h in cp1 cp2 cp3; do c=$(code "$h" health/ready); echo "$h=$c"; [ "$c" = "200" ] && ok=$((ok+1)); done
    echo "READY_COUNT=$ok/3" ;;
  dist)
    tot=0; out=""
    for h in cp1 cp2 cp3; do v=$(node_leaders "$h"); out="$out $h=$v"; tot=$((tot+${v:-0})); done
    echo "LEADER_DIST:$out  TOTAL_LED=$tot" ;;
  elections)
    m=0; for h in cp1 cp2 cp3; do v=$(get "$h" metrics | grep -E '^configd_raft_elections_total' | awk '{print $NF}' | sort -nr | head -1); v=${v%%.*}; [ -z "$v" ] && v=0; [ "$v" -gt "$m" ] 2>/dev/null && m=$v; done; echo "MAX_ELECTIONS=$m" ;;
  health1)
    echo "cp1 /health/ready -> $(code cp1 health/ready)  body: $(get cp1 health/ready)" ;;
esac
