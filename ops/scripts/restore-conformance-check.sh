#!/usr/bin/env bash
# restore-conformance-check.sh — verifies restored cluster matches snapshot
#
# Snapshot layout, as written by ConfigStateMachine#snapshot — the parser below depends on it:
#     [8-byte sequence counter][4-byte entry count]
#     per entry: [4-byte key length][key bytes][4-byte value length][value bytes]
# The 8-byte counter equals the lastIncludedIndex the snapshot represents, which is what makes
# comparing it against the post-restore applied index a valid check.

set -euo pipefail

log() {
  printf '%s [restore-conformance] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

emit_pass() {
  printf 'PASS\n'
  exit 0
}

emit_fail() {
  printf 'FAIL: %s\n' "$*"
  exit 1
}

SNAPSHOT_PATH=""
TARGET_CLUSTER=""
CLUSTER_ENDPOINT="${CONFIGD_CLUSTER_ENDPOINT:-http://localhost:8080}"
DRY_RUN="false"
PROBE_KEYS_LIMIT=10

usage() {
  cat >&2 <<'EOF'
Usage: restore-conformance-check.sh
  --snapshot-path <path>      Path to the snapshot file used for restore.
  --target-cluster <name>     Cluster identifier (audit only).
  --cluster-endpoint <url>    Default http://localhost:8080. Override via
                              CONFIGD_CLUSTER_ENDPOINT.
  --dry-run                   Skip live cluster checks; validate snapshot only.
  -h | --help                 Show this help.
EOF
}

# -----------------------------------------------------------------------------
# Argument parsing
# -----------------------------------------------------------------------------
while (( "$#" )); do
  case "$1" in
    --snapshot-path)
      [ "$#" -ge 2 ] || emit_fail "--snapshot-path requires a value"
      SNAPSHOT_PATH="$2"; shift 2 ;;
    --snapshot-path=*)
      SNAPSHOT_PATH="${1#*=}"; shift ;;
    --target-cluster)
      [ "$#" -ge 2 ] || emit_fail "--target-cluster requires a value"
      TARGET_CLUSTER="$2"; shift 2 ;;
    --target-cluster=*)
      TARGET_CLUSTER="${1#*=}"; shift ;;
    --cluster-endpoint)
      [ "$#" -ge 2 ] || emit_fail "--cluster-endpoint requires a value"
      CLUSTER_ENDPOINT="$2"; shift 2 ;;
    --cluster-endpoint=*)
      CLUSTER_ENDPOINT="${1#*=}"; shift ;;
    --dry-run)
      DRY_RUN="true"; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      usage
      emit_fail "unknown argument: $1" ;;
  esac
done

[ -n "$SNAPSHOT_PATH" ]  || { usage; emit_fail "--snapshot-path is required"; }
[ -n "$TARGET_CLUSTER" ] || { usage; emit_fail "--target-cluster is required"; }
[ -r "$SNAPSHOT_PATH" ]  || emit_fail "snapshot file not readable: $SNAPSHOT_PATH"

log "snapshot=$SNAPSHOT_PATH cluster=$TARGET_CLUSTER endpoint=$CLUSTER_ENDPOINT dry-run=$DRY_RUN"

HEADER_HEX="$(od -An -N12 -tx1 "$SNAPSHOT_PATH" | tr -d ' \n')"
[ "${#HEADER_HEX}" -eq 24 ] || emit_fail "could not read 12-byte snapshot header"
SNAPSHOT_LAST_INDEX=$(( 16#${HEADER_HEX:0:16} ))
SNAPSHOT_ENTRY_COUNT=$(( 16#${HEADER_HEX:16:8} ))

log "snapshot lastIncludedIndex=$SNAPSHOT_LAST_INDEX entries=$SNAPSHOT_ENTRY_COUNT"

SNAPSHOT_PAYLOAD_HASH="$(tail -c +13 "$SNAPSHOT_PATH" | sha256sum | awk '{print $1}')"
log "snapshot payload sha256=$SNAPSHOT_PAYLOAD_HASH"

if [ "$DRY_RUN" = "true" ]; then
  log "dry-run: skipping live cluster checks; snapshot header is valid"
  emit_pass
fi

fetch() {
  local path="$1"
  local extra=()
  if [ -n "${CONFIGD_AUTH_TOKEN:-}" ]; then
    extra=(-H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}")
  fi
  curl -sS --max-time 10 "${extra[@]}" "${CLUSTER_ENDPOINT}${path}"
}

fetch_status() {
  local path="$1"
  local extra=()
  if [ -n "${CONFIGD_AUTH_TOKEN:-}" ]; then
    extra=(-H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}")
  fi
  curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
    "${extra[@]}" "${CLUSTER_ENDPOINT}${path}"
}

log "check 1: GET /health/ready"
READY_CODE="$(fetch_status /health/ready || true)"
if [ "$READY_CODE" != "200" ]; then
  emit_fail "cluster is not ready (HTTP $READY_CODE from /health/ready)"
fi

log "check 2: post-restore applied index"
METRICS_CODE="$(fetch_status /metrics || true)"
if [ "$METRICS_CODE" != "200" ]; then
  emit_fail "cannot read /metrics (HTTP $METRICS_CODE)"
fi

METRICS_TEXT="$(fetch /metrics || true)"
APPLIED_INDEX="$(printf '%s\n' "$METRICS_TEXT" \
  | awk '/^configd_raft_last_applied_index([{ ]|$)/ {print $2; exit}')"

if [ -z "$APPLIED_INDEX" ]; then
  emit_fail "metric configd_raft_last_applied_index not present on /metrics"
fi

APPLIED_INDEX_INT="${APPLIED_INDEX%.*}"
if ! [[ "$APPLIED_INDEX_INT" =~ ^[0-9]+$ ]]; then
  emit_fail "could not parse applied index value: '$APPLIED_INDEX'"
fi

if [ "$APPLIED_INDEX_INT" -lt "$SNAPSHOT_LAST_INDEX" ]; then
  emit_fail "post-restore applied index $APPLIED_INDEX_INT < snapshot lastIncludedIndex $SNAPSHOT_LAST_INDEX"
fi

log "applied index $APPLIED_INDEX_INT >= snapshot lastIncludedIndex $SNAPSHOT_LAST_INDEX"

log "check 3: post-restore state-machine hash"
LIVE_HASH="$(printf '%s\n' "$METRICS_TEXT" \
  | awk -F'"' '/^configd_state_machine_hash\{/ {print $2; exit}')"

if [ -z "$LIVE_HASH" ]; then
  emit_fail "metric configd_state_machine_hash not present on /metrics"
fi

if [ "$LIVE_HASH" != "$SNAPSHOT_PAYLOAD_HASH" ]; then
  emit_fail "state-machine hash mismatch: live=$LIVE_HASH snapshot=$SNAPSHOT_PAYLOAD_HASH"
fi
log "state-machine hash matches snapshot payload"

log "check 4: read traffic probe (up to $PROBE_KEYS_LIMIT keys)"

probe_one_key() {
  local key="$1"
  # '/' is part of the key name per URL path segment; don't escape it.
  local code
  code="$(fetch_status "/v1/config/${key}")"
  if [ "$code" = "200" ] || [ "$code" = "404" ]; then
    return 0
  fi
  return 1
}

extract_keys_python() {
  # Parse the snapshot in Python to avoid fragile bash byte-twiddling.
  python3 - "$SNAPSHOT_PATH" "$PROBE_KEYS_LIMIT" <<'PY'
import struct, sys
path, limit = sys.argv[1], int(sys.argv[2])
with open(path, "rb") as fh:
    data = fh.read()
off = 0
seq, count = struct.unpack_from(">qI", data, off)
off += 12
emitted = 0
for _ in range(count):
    if emitted >= limit:
        break
    (klen,) = struct.unpack_from(">i", data, off); off += 4
    key = data[off:off+klen].decode("utf-8", errors="strict")
    off += klen
    (vlen,) = struct.unpack_from(">i", data, off); off += 4
    off += vlen
    print(key)
    emitted += 1
PY
}

if [ "$SNAPSHOT_ENTRY_COUNT" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    emit_fail "python3 required to parse snapshot keys for read probe"
  fi
  KEY_LIST="$(extract_keys_python || true)"
  if [ -z "$KEY_LIST" ]; then
    emit_fail "could not extract probe keys from snapshot"
  fi
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    if ! probe_one_key "$key"; then
      emit_fail "read probe failed for key: $key"
    fi
  done <<< "$KEY_LIST"
  log "read probe succeeded for sampled keys"
else
  log "snapshot is empty; skipping read probe"
fi

emit_pass
