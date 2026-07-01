#!/usr/bin/env bash
# Box setup for horizontal-scale measurement: data dir + toolchain + /etc/hosts + repo + build.
set -uxo pipefail
CP1_PRIV=172.31.4.81; CP2_PRIV=172.31.4.250; CP3_PRIV=172.31.2.84; LOAD_PRIV=172.31.3.195

# 1. /etc/hosts cross-box names (existing server cert SAN covers cp1/cp2/cp3 -> cross-box mTLS)
sudo sed -i '/ cp1$/d;/ cp2$/d;/ cp3$/d;/ loadbox$/d' /etc/hosts
echo "$CP1_PRIV cp1
$CP2_PRIV cp2
$CP3_PRIV cp3
$LOAD_PRIV loadbox" | sudo tee -a /etc/hosts >/dev/null

# 2. data dir on the gp3 root volume (m6i.xlarge has no instance-store NVMe)
sudo mkdir -p /data && sudo chown -R ec2-user:ec2-user /data
df -h /data

# 3. packages
sudo dnf -y -q install git sysstat tar gzip >/dev/null
command -v iostat mpstat pidstat git >/dev/null && echo "PKGS-OK"

# 4. Corretto 25
if [ ! -x /opt/jdk25/bin/java ]; then
  curl -fsSL https://corretto.aws/downloads/latest/amazon-corretto-25-x64-linux-jdk.tar.gz -o /tmp/c25.tar.gz
  sudo mkdir -p /opt/jdk25
  sudo tar -xzf /tmp/c25.tar.gz -C /opt/jdk25 --strip-components=1
fi
sudo tee /etc/profile.d/jdk25.sh >/dev/null <<'EOF'
export JAVA_HOME=/opt/jdk25
export PATH=$JAVA_HOME/bin:$PATH
EOF
/opt/jdk25/bin/java -version

# 5. clone @ the branch head (server code byte-identical to main; carries the harnesses + driver)
export JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH
if [ ! -d /data/Configd/.git ]; then
  git clone --branch ec2-measurement-2026-06-30 --depth 1 https://github.com/hyperxpro/Configd /data/Configd
fi
cd /data/Configd
git rev-parse --short HEAD

# 6. build server + testkit (server jar + benchmarks.jar) in one reactor pass
rm -f /data/build.status
{
  echo "=== $(date -u +%FT%TZ) build ==="
  ./mvnw -B -pl configd-server,configd-testkit -am clean package -DskipTests
  rc=$?
  SJAR=$(ls configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)
  BJAR=configd-testkit/target/benchmarks.jar
  if [ $rc -eq 0 ] && [ -f "$SJAR" ] && [ -f "$BJAR" ]; then
    echo "OK server=$SJAR bench=$BJAR" > /data/build.status
  else
    echo "FAIL rc=$rc server=$SJAR bench=$BJAR" > /data/build.status
  fi
} > /data/build.log 2>&1
echo "SETUP-DONE $(cat /data/build.status)"
