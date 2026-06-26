#!/usr/bin/env bash
# Phase V — provision ONE m6i.4xlarge on-demand in ap-south-1 (operator-approved spend).
# Auto-installs Corretto 25 + strace via user-data. Writes state to ec2-state.env for run/teardown.
# Run only AFTER the dev-box dry-run is green (operator gate). Tear down immediately after capture.
set -euo pipefail
S=/tmp/claude-1000/-home-ubuntu-Code-Configd/c179f584-8700-4017-bcb0-fe3a7c1d86ff/scratchpad
REGION=ap-south-1
TYPE=m6i.4xlarge
MYIP="$(curl -s --max-time 10 https://checkip.amazonaws.com)/32"
SUFFIX="phasev-$$"
KEY="$S/${SUFFIX}.pem"
STATE="$S/ec2-state.env"

echo "Region=$REGION Type=$TYPE SSH-from=$MYIP"

AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)
echo "AMI=$AMI (latest AL2023)"

SG=$(aws ec2 create-security-group --region $REGION --group-name "$SUFFIX-sg" \
  --description "Phase V ephemeral SSH" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --region $REGION --group-id "$SG" \
  --protocol tcp --port 22 --cidr "$MYIP" >/dev/null
echo "SG=$SG (22 from $MYIP)"

aws ec2 create-key-pair --region $REGION --key-name "$SUFFIX-key" \
  --query KeyMaterial --output text > "$KEY"
chmod 600 "$KEY"
echo "KEY=$KEY"

# user-data: install JDK 25 + strace; flag readiness
UD=$(base64 -w0 <<'EOF'
#!/bin/bash
exec > /var/log/phasev-bootstrap.log 2>&1
set -x
dnf install -y strace tar gzip >/dev/null 2>&1 || yum install -y strace tar gzip
sysctl -w kernel.io_uring_disabled=0 2>/dev/null || true
mkdir -p /opt/jdk
curl -fsSL -o /opt/corretto.tar.gz https://corretto.aws/downloads/latest/amazon-corretto-25-x64-linux-jdk.tar.gz
tar xzf /opt/corretto.tar.gz -C /opt/jdk --strip-components=1
echo 'export JAVA_HOME=/opt/jdk'        > /etc/profile.d/jdk.sh
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile.d/jdk.sh
/opt/jdk/bin/java -version 2>> /var/log/phasev-bootstrap.log
touch /opt/PHASEV_READY
EOF
)

IID=$(aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type "$TYPE" \
  --key-name "$SUFFIX-key" --security-group-ids "$SG" \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3}' \
  --user-data "$UD" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$SUFFIX},{Key=purpose,Value=phase-v-io-uring}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "INSTANCE=$IID — waiting for running + status-ok ..."

cat > "$STATE" <<EOF
REGION=$REGION
IID=$IID
SG=$SG
KEYNAME=$SUFFIX-key
KEY=$KEY
SUFFIX=$SUFFIX
EOF

aws ec2 wait instance-running --region $REGION --instance-ids "$IID"
IP=$(aws ec2 describe-instances --region $REGION --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "IP=$IP" >> "$STATE"
echo "INSTANCE running at $IP — waiting for status checks ..."
aws ec2 wait instance-status-ok --region $REGION --instance-ids "$IID"
echo "=== PROVISIONED: $IID @ $IP (state → $STATE) ==="
echo "Next: wait for /opt/PHASEV_READY (bootstrap), then run ec2-run.sh"
