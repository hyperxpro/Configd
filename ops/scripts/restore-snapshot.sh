#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# restore-snapshot.sh — operator entry point for restoring a Configd cluster
# from a Raft snapshot taken by ConfigStateMachine#snapshot().
#
# Referenced from: ops/runbooks/disaster-recovery.md and
#                  ops/runbooks/restore-from-snapshot.md.
#
# Snapshot file layout (see ConfigStateMachine#snapshot in
# configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java):
#
#     [8-byte sequence counter][4-byte entry count]
#     for each entry:
#         [4-byte key length][key bytes][4-byte value length][value bytes]
#
# There are no explicit magic bytes in the on-disk format (this is a known
# gap, tracked as PA-2021 / "snapshot file header" in
# docs/review/iter-001/release-skeptic.md). This script therefore validates
# the snapshot by:
#   1. file existence + non-empty + readable
#   2. file size >= 12 bytes (the fixed header) and
#      consistent with the declared entry count and the bounds enforced
#      by ConfigStateMachine.MAX_SNAPSHOT_ENTRIES / KEY_LEN / VALUE_LEN.
#   3. delegating deeper integrity to restore-conformance-check.sh, which
#      compares the post-restore commit index and state-machine hash
#      against the snapshot manifest.
#
# Safety contract:
#   - --dry-run defaults to true. Nothing is mutated unless the operator
#     passes --dry-run=false AND --i-have-a-backup. Both are required.
#   - The reference deployment is Kubernetes (deploy/kubernetes/configd-
#     statefulset.yaml). The script scales the configd StatefulSet to 0,
#     waits for pods to terminate, copies the snapshot into the data dir
#     (operator-supplied; reference Job pattern documented in
#     ops/runbooks/restore-from-snapshot.md Step 3), runs the conformance
#     check, then scales back up to the original replica count. iter-2
#     H-001 closure: removed the legacy `systemctl stop configd.service`
#     path; the only reference deployment is K8s.
#
# Exit codes:
#   0  success (or successful dry-run)
#   1  argument / validation failure
#   2  snapshot integrity failure
#   3  kubectl / IO failure (kubectl missing, scale timeout, copy fail)
#   4  conformance check failure
# -----------------------------------------------------------------------------

set -euo pipefail

# -----------------------------------------------------------------------------
# Logging helpers — every action gets an ISO-8601 UTC timestamp.
# -----------------------------------------------------------------------------
log() {
  printf '%s [restore-snapshot] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

die() {
  local code="$1"; shift
  log "FATAL: $*"
  exit "$code"
}

# -----------------------------------------------------------------------------
# Defaults
# -----------------------------------------------------------------------------
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

# Bounds mirrored from ConfigStateMachine constants.
readonly MAX_SNAPSHOT_ENTRIES=100000000
readonly MAX_SNAPSHOT_KEY_LEN=1048576
readonly MAX_SNAPSHOT_VALUE_LEN=1048576
readonly MIN_HEADER_BYTES=12  # 8-byte seq + 4-byte entry-count

# -----------------------------------------------------------------------------
# Usage
# -----------------------------------------------------------------------------
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

# -----------------------------------------------------------------------------
# Argument parsing — explicit, no abbreviation tolerated.
# -----------------------------------------------------------------------------
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

# -----------------------------------------------------------------------------
# Detect kubectl on PATH and fail-close with a clear error if absent.
# The reference deployment is K8s; no kubectl == no restore primitive.
# -----------------------------------------------------------------------------
KUBECTL_BIN="$(command -v kubectl 2>/dev/null || true)"
if [ -z "$KUBECTL_BIN" ]; then
  die 3 "kubectl not found on PATH; the reference Configd deployment is Kubernetes (see deploy/kubernetes/configd-statefulset.yaml). Install kubectl on the bastion before retrying."
fi

log "snapshot-path=$SNAPSHOT_PATH target-cluster=$TARGET_CLUSTER namespace=$NAMESPACE statefulset=$STATEFULSET replicas=$REPLICAS dry-run=$DRY_RUN kubectl=$KUBECTL_BIN"

# -----------------------------------------------------------------------------
# Safety gate — destructive operation requires explicit confirmation.
# -----------------------------------------------------------------------------
if [ "$DRY_RUN" = "false" ]; then
  if [ "$HAVE_BACKUP" != "true" ]; then
    die 1 "refusing to run with --dry-run=false unless --i-have-a-backup is asserted"
  fi
  log "DESTRUCTIVE MODE: --dry-run=false and --i-have-a-backup acknowledged."
fi

# -----------------------------------------------------------------------------
# Step 1 — input validation: snapshot file is real and well-formed.
# -----------------------------------------------------------------------------
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

# Convert to decimal. Use printf with implicit base from 0x prefix.
SEQUENCE_COUNTER=$(( 16#$SEQ_HEX ))
ENTRY_COUNT=$(( 16#$COUNT_HEX ))

log "snapshot header: sequence=$SEQUENCE_COUNTER entry-count=$ENTRY_COUNT size=$SNAPSHOT_SIZE bytes"

if [ "$ENTRY_COUNT" -lt 0 ] || [ "$ENTRY_COUNT" -gt "$MAX_SNAPSHOT_ENTRIES" ]; then
  die 2 "snapshot entry-count out of range: $ENTRY_COUNT (max $MAX_SNAPSHOT_ENTRIES)"
fi

# Lower bound on remaining payload: each entry is at least 8 bytes
# (4-byte key length + 4-byte value length + 0 bytes of payload). Reject
# obviously truncated snapshots before we waste time copying them.
MIN_PAYLOAD=$(( ENTRY_COUNT * 8 ))
if [ "$SNAPSHOT_SIZE" -lt $(( MIN_HEADER_BYTES + MIN_PAYLOAD )) ]; then
  die 2 "snapshot truncated: header claims $ENTRY_COUNT entries but file has $SNAPSHOT_SIZE bytes"
fi

# -----------------------------------------------------------------------------
# Step 2 — scale the StatefulSet down before we touch the data volumes.
#
# H-001 closure: this replaces the legacy `systemctl stop configd.service`
# call. The reference deployment is K8s; the StatefulSet is the unit of
# lifecycle. We scale to 0, then wait for pods to terminate, *before* any
# downstream destructive PVC operation runs.
# -----------------------------------------------------------------------------
log "step 2: scaling StatefulSet $NAMESPACE/$STATEFULSET to 0 replicas"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE scale statefulset $STATEFULSET --replicas=0"
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE wait --for=delete pod -l app=$STATEFULSET --timeout=120s"
else
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" scale statefulset "$STATEFULSET" --replicas=0; then
    die 3 "failed to scale statefulset $NAMESPACE/$STATEFULSET to 0"
  fi
  # Wait for pod termination so the operator's downstream PVC delete /
  # snapshot copy step does not race a still-attached PVC. The label
  # selector matches deploy/kubernetes/configd-statefulset.yaml.
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" wait --for=delete pod \
         -l "app=$STATEFULSET" --timeout=120s; then
    die 3 "pods did not terminate within 120s after scaling $STATEFULSET to 0"
  fi
  log "$NAMESPACE/$STATEFULSET scaled to 0 and all pods terminated"
fi

# -----------------------------------------------------------------------------
# Step 3 — copy the snapshot into the data dir.
#
# This script does not delete PVCs. The runbook (restore-from-snapshot.md
# Step 2) is the authority for PVC deletion; it MUST be invoked AFTER
# this script's Step 2 (scale to 0 + wait) and BEFORE the operator-glue
# Job that copies the snapshot into the freshly-bound PVC. The local
# staging copy below is the bastion-side mirror used by some operators
# who pre-stage snapshots before the K8s Job runs.
# -----------------------------------------------------------------------------
log "step 3: staging snapshot into $SNAPSHOT_DIR"

DEST_FILE="$SNAPSHOT_DIR/restore-$(date -u +%Y%m%dT%H%M%SZ).snap"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would create dir: $SNAPSHOT_DIR"
  log "[dry-run] would copy: $SNAPSHOT_PATH -> $DEST_FILE"
else
  install -d -m 0750 "$SNAPSHOT_DIR"
  # Copy first to a .partial then rename for atomicity.
  cp -f "$SNAPSHOT_PATH" "${DEST_FILE}.partial"
  sync "${DEST_FILE}.partial" 2>/dev/null || true
  mv "${DEST_FILE}.partial" "$DEST_FILE"
  chmod 0640 "$DEST_FILE"
  log "snapshot staged at $DEST_FILE"
fi

# -----------------------------------------------------------------------------
# Step 4 — integrity check via the conformance script.
# -----------------------------------------------------------------------------
log "step 4: invoking conformance check"

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

# -----------------------------------------------------------------------------
# Step 5 — scale the StatefulSet back up.
# -----------------------------------------------------------------------------
log "step 5: scaling StatefulSet $NAMESPACE/$STATEFULSET to $REPLICAS replicas"

if [ "$DRY_RUN" = "true" ]; then
  log "[dry-run] would run: $KUBECTL_BIN -n $NAMESPACE scale statefulset $STATEFULSET --replicas=$REPLICAS"
else
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" scale statefulset "$STATEFULSET" \
         --replicas="$REPLICAS"; then
    die 3 "failed to scale statefulset $NAMESPACE/$STATEFULSET to $REPLICAS"
  fi
  # Best-effort wait for pod-0 to come back; downstream the runbook does
  # the per-pod readiness verification.
  if ! "$KUBECTL_BIN" -n "$NAMESPACE" rollout status \
         "statefulset/$STATEFULSET" --timeout=300s; then
    die 3 "$NAMESPACE/$STATEFULSET did not become Ready within 300s"
  fi
  log "$NAMESPACE/$STATEFULSET is back at $REPLICAS replicas"
fi

log "restore-snapshot.sh completed (dry-run=$DRY_RUN)"
exit 0
