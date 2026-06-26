#!/usr/bin/env bash
# Phase V — transfer harness to the m6i, run the LOCKED matrix DETACHED (survives ssh blips),
# poll to completion, parse, capture results off-box. Robust to network drops over the ~30-min run.
set -uo pipefail
S=/tmp/claude-1000/-home-ubuntu-Code-Configd/c179f584-8700-4017-bcb0-fe3a7c1d86ff/scratchpad
source "$S/ec2-state.env"
SSHO="-i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 -o ServerAliveInterval=15"
SSH="ssh $SSHO ec2-user@$IP"
SCP="scp $SSHO"

echo "=== waiting for bootstrap (/opt/PHASEV_READY + working java) on $IP ==="
for i in $(seq 1 90); do
  if $SSH 'test -f /opt/PHASEV_READY && /opt/jdk/bin/java -version' >/dev/null 2>&1; then echo "bootstrap OK"; break; fi
  sleep 10
done
$SSH '/opt/jdk/bin/java -version' 2>&1 | head -3 || { echo "JAVA NOT READY — check /var/log/phasev-bootstrap.log"; exit 1; }

echo "=== io_uring availability on the box ==="
$SSH 'uname -r; echo io_uring_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo NA); nproc' | tee "$S/m6i-env.txt"

echo "=== transferring jar + scripts ==="
$SSH 'mkdir -p ~/phasev'
$SCP "$S/bench-phasev.jar"  ec2-user@$IP:~/phasev/benchmarks.jar
$SCP "$S/phase-v-matrix.sh" ec2-user@$IP:~/phasev/
$SCP "$S/phase-v-parse.py"  ec2-user@$IP:~/phasev/

echo "=== LAUNCH the locked matrix DETACHED (PROFILE=m6i) — the paid measurement ==="
$SSH "cd ~/phasev && PATH=/opt/jdk/bin:\$PATH nohup env JAR=\$HOME/phasev/benchmarks.jar PROFILE=m6i OUT=\$HOME/phasev/phase-v-results-m6i stdbuf -oL bash phase-v-matrix.sh > \$HOME/phasev/matrix-m6i.out 2>&1 & echo detached-pid \$!"

echo "=== polling for MATRIX COMPLETE (fresh ssh each poll; matrix survives ssh drops) ==="
for i in $(seq 1 360); do   # up to 60 min
  out=$($SSH 'tail -1 ~/phasev/matrix-m6i.out 2>/dev/null; grep -c "MATRIX COMPLETE" ~/phasev/matrix-m6i.out 2>/dev/null; grep -c ABORT ~/phasev/matrix-m6i.out 2>/dev/null' 2>/dev/null || echo "ssh-blip")
  done=$(echo "$out" | sed -n '2p'); aborts=$(echo "$out" | sed -n '3p')
  echo "[poll $i] last='$(echo "$out"|sed -n '1p')' complete=$done aborts=$aborts"
  [ "${done:-0}" = "1" ] && { echo "MATRIX COMPLETE on box"; break; }
  sleep 10
done

echo "=== parse on the box (syscall SYS count = 20000 for m6i) ==="
$SSH "PATH=/opt/jdk/bin:\$PATH python3 ~/phasev/phase-v-parse.py ~/phasev/phase-v-results-m6i 20000 20000 | tee ~/phasev/phase-v-tables.txt" || true

echo "=== capture artifacts OFF-box ==="
mkdir -p "$S/phase-v-m6i-capture"
$SCP -r ec2-user@$IP:~/phasev/phase-v-results-m6i "$S/phase-v-m6i-capture/" || true
$SCP ec2-user@$IP:~/phasev/matrix-m6i.out     "$S/phase-v-m6i-capture/" || true
$SCP ec2-user@$IP:~/phasev/phase-v-tables.txt "$S/phase-v-m6i-capture/" || true
echo "=== CAPTURED → $S/phase-v-m6i-capture ; do NOT teardown until 2nd-agent reproduces on-box ==="
