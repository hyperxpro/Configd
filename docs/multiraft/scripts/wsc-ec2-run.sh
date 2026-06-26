#!/usr/bin/env bash
# Workstream C — ship jars+harness to the m6id, confirm box (16 vCPU + NVMe + fsync baseline +
# transport tier), run the rate LADDER DETACHED (survives ssh blips), poll, capture off-box.
# Does NOT teardown — that waits for the 2nd-agent reproduction.
set -uo pipefail
S="${STATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
source "$S/wsc-ec2-state.env"
REPO="${REPO:-/home/ubuntu/Code/Configd}"
SERVER_JAR="${SERVER_JAR:-$(ls "$REPO"/configd-server/target/configd-server-*.jar | grep -v original- | head -1)}"
BENCH_JAR="${BENCH_JAR:-$REPO/configd-testkit/target/benchmarks.jar}"
LADDER="${LADDER:-$REPO/perf/wsC-ladder.sh}"
SSHO="-i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 -o ServerAliveInterval=15"
SSH="ssh $SSHO ec2-user@$IP"
SCP="scp $SSHO"

echo "=== waiting for bootstrap (/opt/WSC_READY + java + /mnt/nvme) on $IP ==="
for i in $(seq 1 90); do
  if $SSH 'test -f /opt/WSC_READY && /opt/jdk/bin/java -version && findmnt /mnt/nvme' >/dev/null 2>&1; then echo "bootstrap OK"; break; fi
  sleep 10
done
$SSH '/opt/jdk/bin/java -version' 2>&1 | head -3 || { echo "JAVA NOT READY — check /var/log/wsc-bootstrap.log"; exit 1; }

echo "=== BOX ENV (16 vCPU + NVMe mount + kernel) ===" | tee "$S/wsc-box-env.txt"
$SSH 'echo "nproc=$(nproc)"; echo "kernel=$(uname -r)"; echo "mem=$(free -g | awk "/Mem:/{print \$2}")g"; findmnt -o SOURCE,TARGET,FSTYPE /mnt/nvme; lsblk -dno NAME,SIZE,MODEL | grep -i "Instance Storage"' 2>&1 | tee -a "$S/wsc-box-env.txt"

echo "=== FSYNC BASELINE (fio fdatasync on /mnt/nvme; §7.5 saw ~8,300-14,300 IOPS) ===" | tee "$S/wsc-fsync-baseline.txt"
$SSH 'cd /mnt/nvme && fio --name=fsbase --directory=/mnt/nvme --rw=write --bs=4k --size=256m --numjobs=1 \
        --fdatasync=1 --runtime=10 --time_based --group_reporting --output-format=json 2>/dev/null' > "$S/wsc-fsync.json" 2>/dev/null || true
python3 - "$S/wsc-fsync.json" <<'PY' 2>/dev/null | tee -a "$S/wsc-fsync-baseline.txt" || echo "(fio parse skipped)"
import json,sys
try:
    d=json.load(open(sys.argv[1])); j=d["jobs"][0]
    print(f"write_iops={j['write']['iops']:.0f}  write_bw_MBps={j['write']['bw']/1024:.1f}")
    s=j.get("sync",{}).get("lat_ns",{})
    if s: print(f"fdatasync_lat_us: mean={s.get('mean',0)/1000:.1f} p99={s.get('percentile',{}).get('99.000000',0)/1000:.1f}")
except Exception as e: print("parse-error",e)
PY

echo "=== transfer jars + harness ==="
$SSH 'mkdir -p ~/wsc/perf ~/wsc/out'
$SCP "$SERVER_JAR" ec2-user@$IP:~/wsc/configd-server.jar
$SCP "$BENCH_JAR"  ec2-user@$IP:~/wsc/benchmarks.jar
$SCP "$LADDER"     ec2-user@$IP:~/wsc/perf/wsC-ladder.sh

echo "=== LAUNCH the rate ladder DETACHED (production defaults; transport=epoll forced) — the paid measurement ==="
$SSH "cd ~/wsc && PATH=/opt/jdk/bin:\$PATH nohup env \
  CONFIGD_JAR=\$HOME/wsc/configd-server.jar CONFIGD_BENCH=\$HOME/wsc/benchmarks.jar \
  WSC_BASE=/mnt/nvme/run/wsc WSC_TRANSPORT=epoll \
  stdbuf -oL bash perf/wsC-ladder.sh \$HOME/wsc/out > \$HOME/wsc/ladder.out 2>&1 & echo detached-pid \$!"

echo "=== polling for LADDER COMPLETE (fresh ssh each poll; ladder survives ssh drops) ==="
for i in $(seq 1 240); do   # up to 40 min
  out=$($SSH 'tail -1 ~/wsc/ladder.out 2>/dev/null; grep -c "LADDER COMPLETE" ~/wsc/ladder.out 2>/dev/null; grep -c "wsC FAIL" ~/wsc/ladder.out 2>/dev/null' 2>/dev/null || echo "ssh-blip")
  done=$(echo "$out" | sed -n '2p'); fails=$(echo "$out" | sed -n '3p')
  echo "[poll $i] last='$(echo "$out"|sed -n '1p')' complete=$done fails=$fails"
  [ "${done:-0}" = "1" ] && { echo "LADDER COMPLETE on box"; break; }
  sleep 10
done

echo "=== capture artifacts OFF-box ==="
mkdir -p "$S/wsc-capture"
$SCP -r ec2-user@$IP:~/wsc/out      "$S/wsc-capture/" || true
$SCP    ec2-user@$IP:~/wsc/ladder.out "$S/wsc-capture/" || true
echo "=== ladder.tsv ==="
column -t "$S/wsc-capture/out/ladder.tsv" 2>/dev/null || cat "$S/wsc-capture/out/ladder.tsv" 2>/dev/null || echo "(no ladder.tsv yet)"
echo "=== CAPTURED -> $S/wsc-capture ; do NOT teardown until 2nd-agent reproduces on-box ==="
