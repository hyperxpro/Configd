#!/usr/bin/env bash
# restore-snapshot.sh — operator entry point for restoring a Configd cluster
#
# Snapshot layout, as written by ConfigStateMachine#snapshot:
#     [8-byte sequence counter][4-byte entry count]
#     per entry: [4-byte key length][key bytes][4-byte value length][value bytes]

set -euo pipefail

log() {
  printf '%s [restore-snapshot] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

die() {
  local code="$1"; shift
  log "FATAL: $*"
  exit "$code"
}

SNAPSHOT_PATH=""
TARGET_CLUSTER=""
DRY_RUN="true"
HAVE_BACKUP="false"
NAMESPACE="${CONFIGD_NAMESPACE:-configd}"
STATEFULSET="${CONFIGD_STATEFULSET:-configd}"
REPLICAS="${CONFIGD_REPLICAS:-3}"
DATA_DIR="${CONFIGD_DATA_DIR:-/var/lib/configd}"
SNAPSHOT_DIR="${DATA_DIR}/snapshots"
CONFORMANCE_SCRIPT="$(cd "$(dirname "$0")" && pwd)/restore-conformance-check.sh"

readonly MAX_SNAPSHOT_ENTRIES=100000000
readonly MAX_SNAPSHOT_KEY_LEN=1048576
readonly MAX_SNAPSHOT_VALUE_LEN=1048576
readonly MIN_HEADER_BYTES=12

usage() {
  cat >&2 <<'EOF'
Usage: restore-snapshot.sh
  --snapshot-path <path>     Path to the snapshot file on local disk.
  --target-cluster <name>    Logical cluster identifier (used for audit logs;
                             must match the cluster the runbook authorised).
  --namespace, -n <ns>       Kubernetes namespace (default: configd; or
                             $CONFIGD_NAMESPACE).
  --replicas <n>             Replica count to scale back to after restore
                             (default: 3; or $CONFIGD_REPLICAS).
  --dry-run [true|false]     Default: true. If true, no mutation is performed.
  --i-have-a-backup          Required when --dry-run=false. Without this
                             explicit assertion the script refuses to proceed.
  -h | --help                Show this help.

Environment overrides:
  CONFIGD_DATA_DIR           Defaults to /var/lib/configd.
  CONFIGD_NAMESPACE          Defaults to configd.
  CONFIGD_STATEFULSET        Defaults to configd.
  CONFIGD_REPLICAS           Defaults to 3.
EOF
}

while (( "$#" )); do
  case "$1" in
    --snapshot-path)
      [ "$#" -ge 2 ] || die 1 "--snapshot-path requires a value"
      SNAPSHOT_PATH="$2"; shift 2 ;;
    --snapshot-path=*)
      SNAPSHOT_PATH="${1#*=}"; shift ;;
    --target-cluster)
      [ "$#" -ge 2 ] || die 1 "--target-cluster requires a value"
      TARGET_CLUSTER="$2"; shift 2 ;;
    --target-cluster=*)
      TARGET_CLUSTER="${1#*=}"; shift ;;
    --namespace|-n)
      [ "$#" -ge 2 ] || die 1 "--namespace requires a value"
      NAMESPACE="$2"; shift 2 ;;
    --namespace=*)
      NAMESPACE="${1#*=}"; shift ;;
    --replicas)
      [ "$#" -ge 2 ] || die 1 "--replicas requires a value"
      REPLICAS="$2"; shift 2 ;;
    --replicas=*)
      REPLICAS="${1#*=}"; shift ;;
    --dry-run)
      [ "$#" -ge 2 ] || die 1 "--dry-run requires true|false"
      DRY_RUN="$2"; shift 2 ;;
    --dry-run=*)
      DRY_RUN="${1#*=}"; shift ;;
    --i-have-a-backup)
      HAVE_BACKUP="true"; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      usage
      die 1 "unknown argument: $1" ;;
  esac
done

[ -n "$SNAPSHOT_PATH" ]   || { usage; die 1 "--snapshot-path is required"; }
[ -n "$TARGET_CLUSTER" ]  || { usage; die 1 "--target-cluster is required"; }

case "$DRY_RUN" in
  true|false) ;;
  *) die 1 "--dry-run must be 'true' or 'false', got: $DRY_RUN" ;;
esac

if ! [[ "$REPLICAS" =~ ^[0-9]+$ ]] || [ "$REPLICAS" -lt 1 ]; then
  die 1 "--replicas must be a positive integer, got: $REPLICAS"
fi

KUBECTL_BIN="$(command -v kubectl 2>/dev/null || true)"
if [ -z "$KUBECTL_BIN" ] && [ "$DRY_RUN" != "true" ]; then
  die 3 "kubectl not found on PATH; the reference Configd deployment is Kubernetes (see deploy/kubernetes/configd-statefulset.yaml). Install kubectl on the bastion before retrying (not needed for --dry-run=true)."
fi

log "snapshot-path=$SNAPSHOT_PATH target-cluster=$TARGET_CLUSTER namespace=$NAMESPACE statefulset=$STATEFULSET replicas=$REPLICAS dry-run=$DRY_RUN kubectl=$KUBECTL_BIN"

if [ "$DRY_RUN" = "false" ]; then
  if [ "$HAVE_BACKUP" != "true" ]; then
    die 1 "refusing to run with --dry-run=false unless --i-have-a-backup is asserted"
  fi
  log "DESTRUCTIVE MODE: --dry-run=false and --i-have-a-backup acknowledged."
fi

log "step 1: validating snapshot file"

[ -e "$SNAPSHOT_PATH" ] || die 2 "snapshot file does not exist: $SNAPSHOT_PATH"
[ -f "$SNAPSHOT_PATH" ] || die 2 "snapshot path is not a regular file: $SNAPSHOT_PATH"
[ -r "$SNAPSHOT_PATH" ] || die 2 "snapshot file is not readable: $SNAPSHOT_PATH"

SNAPSHOT_SIZE="$(stat -c '%s' "$SNAPSHOT_PATH" 2>/dev/null \
  || stat -f '%z' "$SNAPSHOT_PATH")"

if [ "$SNAPSHOT_SIZE" -lt "$MIN_HEADER_BYTES" ]; then
  die 2 "snapshot is too small ($SNAPSHOT_SIZE bytes < $MIN_HEADER_BYTES required header)"
fi

# Read the 12-byte header: 8-byte big-endian sequence counter, 4-byte
# big-endian entry count (Java ByteBuffer default is big-endian).
HEADER_HEX="$(od -An -N12 -tx1 "$SNAPSHOT_PATH" | tr -d ' \n')"
if [ "${#HEADER_HEX}" -ne 24 ]; then
  die 2 "could not read 12-byte snapshot header (got '${HEADER_HEX}')"
fi
SEQ_HEX="${HEADER_HEX:0:16}"
COUNT_HEX="${HEADER_HEX:16:8}"

SEQUENCE_COUNTER=$(( 16#$SEQ_HEX ))
ENTRY_COUNT=$(( 16#$COUNT_HEX ))

log "snapshot header: sequence=$SEQUENCE_COUNTER entry-count=$ENTRY_COUNT size=$SNAPSHOT_SIZE bytes"

if [ "$ENTRY_COUNT" -lt 0 ] || [ "$ENTRY_COUNT" -gt "$MAX_SNAPSHOT_ENTRIES" ]; then
  die 2 "snapshot entry-count out of range: $ENTRY_COUNT (max $MAX_SNAPSHOT_ENTRIES)"
fi

MIN_PAYLOAD=$(( ENTRY_COUNT * 8 ))
if [ "$SNAPSHOT_SIZE" -lt $(( MIN_HEADER_BYTES + MIN_PAYLOAD )) ]; then
  die 2 "snapshot truncated: header claims $ENTRY_COUNT entries but file has $SNAPSHOT_SIZE bytes"
fi

log "step 2: scaling StatefulSet $NAMESPACE/$STATEFULSET to 0 replicas"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE scale statefulset $STATEFULSET --replicas=0"
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE wait --for=delete pod -l app=$STATEFULSET --timeout=120s"
else
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" scale statefulset "$STATEFULSET" --replicas=0; then
    die 3 "failed to scale statefulset $NAMESPACE/$STATEFULSET to 0"
  fi
  # Must wait for pods to terminate before PVC operations to avoid race.
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" wait --for=delete pod \
         -l "app=$STATEFULSET" --timeout=120s; then
    die 3 "pods did not terminate within 120s after scaling $STATEFULSET to 0"
  fi
  log "$NAMESPACE/$STATEFULSET scaled to 0 and all pods terminated"
fi

log "step 3: staging snapshot into $SNAPSHOT_DIR"

DEST_FILE="$SNAPSHOT_DIR/restore-$(date -u +%Y%m%dT%H%M%SZ).snap"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would create dir: $SNAPSHOT_DIR"
  log "[dry-run] would copy: $SNAPSHOT_PATH -> $DEST_FILE"
else
  install -d -m 0750 "$SNAPSHOT_DIR"
  cp -f "$SNAPSHOT_PATH" "${DEST_FILE}.partial"
  sync "${DEST_FILE}.partial" 2>/dev/null || true
  mv "${DEST_FILE}.partial" "$DEST_FILE"
  chmod 0640 "$DEST_FILE"
  log "snapshot staged at $DEST_FILE"
fi

log "step 4: scaling StatefulSet $NAMESPACE/$STATEFULSET back to $REPLICAS replicas"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE scale statefulset $STATEFULSET --replicas=$REPLICAS"
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE rollout status statefulset/$STATEFULSET --timeout=300s"
else
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" scale statefulset "$STATEFULSET" \
         --replicas="$REPLICAS"; then
    die 3 "failed to scale statefulset $NAMESPACE/$STATEFULSET to $REPLICAS"
  fi
  # Conformance reads applied index and hash off /metrics; requires live cluster.
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" rollout status \
         "statefulset/$STATEFULSET" --timeout=300s; then
    die 3 "$NAMESPACE/$STATEFULSET did not become Ready within 300s"
  fi
  log "$NAMESPACE/$STATEFULSET is back at $REPLICAS replicas"
fi

log "step 5: invoking conformance check against the restored cluster"

if [ ! -x "$CONFORMANCE_SCRIPT" ]; then
  die 3 "conformance script not found or not executable: $CONFORMANCE_SCRIPT"
fi

CONFORMANCE_ARGS=(
  --snapshot-path "$SNAPSHOT_PATH"
  --target-cluster "$TARGET_CLUSTER"
)
if [ "$DRY_RUN" = "true" ]; then
  CONFORMANCE_ARGS+=(--dry-run)
fi

if ! "$CONFORMANCE_SCRIPT" "${CONFORMANCE_ARGS[@]}"; then
  die 4 "restore-conformance-check.sh reported FAIL"
fi

log "conformance check passed"

log "restore-snapshot.sh completed (dry-run=$DRY_RUN)"
exit 0
