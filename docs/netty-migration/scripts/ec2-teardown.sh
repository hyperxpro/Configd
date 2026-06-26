#!/usr/bin/env bash
# Phase V — TEAR DOWN the m6i immediately after capture + 2nd-agent reproduction. Idle billing is waste.
set -uo pipefail
S=/tmp/claude-1000/-home-ubuntu-Code-Configd/c179f584-8700-4017-bcb0-fe3a7c1d86ff/scratchpad
source "$S/ec2-state.env"
echo "=== terminating $IID in $REGION ==="
aws ec2 terminate-instances --region "$REGION" --instance-ids "$IID" --query 'TerminatingInstances[0].CurrentState.Name' --output text
aws ec2 wait instance-terminated --region "$REGION" --instance-ids "$IID"
echo "instance terminated"
# SG can only be deleted after the ENI is released (instance fully gone)
for i in $(seq 1 10); do
  if aws ec2 delete-security-group --region "$REGION" --group-id "$SG" 2>/dev/null; then echo "SG $SG deleted"; break; fi
  sleep 10
done
aws ec2 delete-key-pair --region "$REGION" --key-name "$KEYNAME" && echo "key-pair $KEYNAME deleted"
rm -f "$KEY"
echo "=== TORN DOWN. Record the billed duration (launch→terminate) for the cost line. ==="
mv "$S/ec2-state.env" "$S/ec2-state.env.terminated" 2>/dev/null || true
