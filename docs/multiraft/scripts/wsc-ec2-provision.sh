#!/usr/bin/env bash
# Workstream C — provision ONE m6id.4xlarge ON-DEMAND in ap-south-1 (operator-approved spend, ≤$5).
# m6id (NOT m6i): the 'd' = local instance-store NVMe, mounted at /mnt/nvme for the fsync-honest
# data+WAL path AND comparability with the §7.5 ~800/s baseline (SAME instance type).
# Least-privilege: SSH 22 from THIS box's /32 only; ephemeral key pair; no key in git.
# user-data: mount instance-store NVMe @ /mnt/nvme, install Corretto 25 + sysstat + fio.
set -euo pipefail
S="${STATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
REGION=ap-south-1
TYPE=m6id.4xlarge
MYIP="$(curl -s --max-time 10 https://checkip.amazonaws.com)/32"
SUFFIX="wsc-$$"
KEY="$S/${SUFFIX}.pem"
STATE="$S/wsc-ec2-state.env"

echo "Region=$REGION Type=$TYPE SSH-from=$MYIP"

AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)
echo "AMI=$AMI (latest AL2023)"

SG=$(aws ec2 create-security-group --region $REGION --group-name "$SUFFIX-sg" \
  --description "Workstream C ephemeral SSH" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --region $REGION --group-id "$SG" \
  --protocol tcp --port 22 --cidr "$MYIP" >/dev/null
echo "SG=$SG (22 from $MYIP)"

aws ec2 create-key-pair --region $REGION --key-name "$SUFFIX-key" \
  --query KeyMaterial --output text > "$KEY"
chmod 600 "$KEY"
echo "KEY=$KEY"

# user-data: mount the instance-store NVMe @ /mnt/nvme; install JDK 25 + sysstat + fio; flag readiness.
UD=$(base64 -w0 <<'EOF'
#!/bin/bash
exec > /var/log/wsc-bootstrap.log 2>&1
set -x
dnf install -y sysstat fio tar gzip >/dev/null 2>&1 || yum install -y sysstat fio tar gzip
# Mount the local instance-store NVMe (model "Amazon EC2 NVMe Instance Storage") at /mnt/nvme.
INST=""
for i in $(seq 1 30); do
  INST=$(lsblk -dno NAME,MODEL | grep -i "Instance Storage" | awk '{print "/dev/"$1}' | head -1)
  [ -n "$INST" ] && break
  sleep 2
done
if [ -n "$INST" ]; then
  mkfs.xfs -f "$INST"
  mkdir -p /mnt/nvme
  mount "$INST" /mnt/nvme
  chown ec2-user:ec2-user /mnt/nvme
  chmod 1777 /mnt/nvme
  echo "MOUNTED $INST -> /mnt/nvme"
else
  echo "NO INSTANCE-STORE NVMe FOUND" ; lsblk
fi
mkdir -p /opt/jdk
curl -fsSL -o /opt/corretto.tar.gz https://corretto.aws/downloads/latest/amazon-corretto-25-x64-linux-jdk.tar.gz
tar xzf /opt/corretto.tar.gz -C /opt/jdk --strip-components=1
echo 'export JAVA_HOME=/opt/jdk'        > /etc/profile.d/jdk.sh
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile.d/jdk.sh
/opt/jdk/bin/java -version 2>> /var/log/wsc-bootstrap.log
findmnt /mnt/nvme >> /var/log/wsc-bootstrap.log 2>&1
touch /opt/WSC_READY
EOF
)

IID=$(aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type "$TYPE" \
  --key-name "$SUFFIX-key" --security-group-ids "$SG" \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30,VolumeType=gp3,DeleteOnTermination=true}' \
  --user-data "$UD" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$SUFFIX},{Key=purpose,Value=multiraft-workstream-c}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "INSTANCE=$IID — waiting for running + status-ok ..."

cat > "$STATE" <<EOF
REGION=$REGION
TYPE=$TYPE
IID=$IID
SG=$SG
KEYNAME=$SUFFIX-key
KEY=$KEY
SUFFIX=$SUFFIX
LAUNCH_EPOCH=$(date +%s)
EOF

aws ec2 wait instance-running --region $REGION --instance-ids "$IID"
IP=$(aws ec2 describe-instances --region $REGION --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "IP=$IP" >> "$STATE"
echo "INSTANCE running at $IP — waiting for status checks ..."
aws ec2 wait instance-status-ok --region $REGION --instance-ids "$IID"
echo "=== PROVISIONED: $IID @ $IP (state -> $STATE) ==="
echo "Next: wait for /opt/WSC_READY (bootstrap mounts NVMe + installs JDK), then run wsc-ec2-run.sh"
