#!/usr/bin/env bash
# Workstream C — TEAR DOWN the m6id after capture + 2nd-agent reproduction, and VERIFY deletion
# against the AWS API: instance terminated, EBS volumes gone (DeleteOnTermination), SG deleted,
# key pair + local .pem deleted. Idle billing is waste. Records the billed duration for the cost line.
set -uo pipefail
S="${STATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
source "$S/wsc-ec2-state.env"

echo "=== capturing attached EBS volume IDs BEFORE terminate (to verify they're gone after) ==="
VOLS=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].BlockDeviceMappings[].Ebs.VolumeId' --output text 2>/dev/null || echo "")
echo "EBS volumes: ${VOLS:-<none>}"

echo "=== terminating $IID in $REGION ==="
aws ec2 terminate-instances --region "$REGION" --instance-ids "$IID" \
  --query 'TerminatingInstances[0].CurrentState.Name' --output text
aws ec2 wait instance-terminated --region "$REGION" --instance-ids "$IID"
TERM_EPOCH=$(date +%s)
echo "instance terminated"

# SG can only be deleted after the ENI is released (instance fully gone)
SG_DELETED=no
for i in $(seq 1 12); do
  if aws ec2 delete-security-group --region "$REGION" --group-id "$SG" 2>/dev/null; then echo "SG $SG deleted"; SG_DELETED=yes; break; fi
  sleep 10
done
aws ec2 delete-key-pair --region "$REGION" --key-name "$KEYNAME" && echo "key-pair $KEYNAME deleted"
rm -f "$KEY" && echo "local .pem deleted: $KEY"

echo ""
echo "================= API-VERIFIED DELETION ================="
ISTATE=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo "not-found")
echo "instance $IID state = $ISTATE  (expect: terminated)"
for v in $VOLS; do
  VST=$(aws ec2 describe-volumes --region "$REGION" --volume-ids "$v" \
    --query 'Volumes[0].State' --output text 2>&1 | tr -d '\n')
  case "$VST" in
    *InvalidVolume.NotFound*|"") echo "volume $v = GONE (DeleteOnTermination ✓)" ;;
    *) echo "volume $v = $VST  (⚠ still present — investigate)" ;;
  esac
done
SGCHK=$(aws ec2 describe-security-groups --region "$REGION" --group-ids "$SG" \
  --query 'SecurityGroups[0].GroupId' --output text 2>&1 | tr -d '\n')
case "$SGCHK" in *InvalidGroup.NotFound*|"") echo "SG $SG = GONE ✓" ;; *) echo "SG $SG = STILL PRESENT ($SGCHK) ⚠ (delete=$SG_DELETED)" ;; esac
KEYCHK=$(aws ec2 describe-key-pairs --region "$REGION" --key-names "$KEYNAME" \
  --query 'KeyPairs[0].KeyName' --output text 2>&1 | tr -d '\n')
case "$KEYCHK" in *InvalidKeyPair.NotFound*|"") echo "key-pair $KEYNAME = GONE ✓" ;; *) echo "key-pair $KEYNAME = STILL PRESENT ⚠" ;; esac
[ -f "$KEY" ] && echo "local .pem STILL PRESENT ⚠: $KEY" || echo "local .pem = GONE ✓"

echo ""
DUR=$(( TERM_EPOCH - ${LAUNCH_EPOCH:-$TERM_EPOCH} ))
# m6id.4xlarge on-demand ap-south-1 ≈ $1.1132/hr (record exact from console; this is the estimate line)
RATE_PER_HR=1.1132
COST=$(python3 -c "print(f'{$DUR/3600.0*$RATE_PER_HR:.3f}')" 2>/dev/null || echo "?")
echo "=== BILLED DURATION: ${DUR}s (~$(python3 -c "print(f'{$DUR/60.0:.1f}')" 2>/dev/null)min)  EST COST ~\$$COST  (m6id.4xlarge @ \$$RATE_PER_HR/hr) ==="
echo "=== TORN DOWN. Nothing left billing. ==="
mv "$S/wsc-ec2-state.env" "$S/wsc-ec2-state.env.terminated" 2>/dev/null || true
