#!/usr/bin/env bash
# Phase V — transfer harness to the m6i, run the LOCKED matrix (PROFILE=m6i), capture results back.
set -euo pipefail
S=/tmp/claude-1000/-home-ubuntu-Code-Configd/c179f584-8700-4017-bcb0-fe3a7c1d86ff/scratchpad
source "$S/ec2-state.env"
SSH="ssh -i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 ec2-user@$IP"
SCP="scp -i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

echo "=== waiting for bootstrap (/opt/PHASEV_READY) on $IP ==="
for i in $(seq 1 60); do
  if $SSH 'test -f /opt/PHASEV_READY' 2>/dev/null; then echo "bootstrap done"; break; fi
  sleep 10
done
$SSH '/opt/jdk/bin/java -version' 2>&1 | head -3

echo "=== transferring jar + scripts ==="
$SSH 'mkdir -p ~/phasev'
$SCP "$S/bench-phasev.jar"   ec2-user@$IP:~/phasev/benchmarks.jar
$SCP "$S/phase-v-matrix.sh"  ec2-user@$IP:~/phasev/
$SCP "$S/phase-v-parse.py"   ec2-user@$IP:~/phasev/

echo "=== io_uring availability on the box (kernel + sysctl) ==="
$SSH 'uname -r; echo io_uring_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo NA); grep -c io_uring /proc/kallsyms 2>/dev/null || echo "kallsyms hidden"'

echo "=== RUN the locked matrix (PROFILE=m6i) — this is the paid measurement ==="
$SSH "export PATH=/opt/jdk/bin:\$PATH; cd ~/phasev && JAR=~/phasev/benchmarks.jar PROFILE=m6i OUT=~/phasev/phase-v-results-m6i stdbuf -oL bash phase-v-matrix.sh 2>&1 | tee ~/phasev/matrix-m6i.out"

echo "=== parse on the box ==="
$SSH "export PATH=/opt/jdk/bin:\$PATH; cd ~/phasev && python3 phase-v-parse.py ~/phasev/phase-v-results-m6i 20000 20000 | tee ~/phasev/phase-v-tables.txt" || true

echo "=== capture artifacts OFF-box ==="
mkdir -p "$S/phase-v-m6i-capture"
$SCP -r ec2-user@$IP:~/phasev/phase-v-results-m6i "$S/phase-v-m6i-capture/" || true
$SCP ec2-user@$IP:~/phasev/matrix-m6i.out         "$S/phase-v-m6i-capture/" || true
$SCP ec2-user@$IP:~/phasev/phase-v-tables.txt     "$S/phase-v-m6i-capture/" || true
echo "=== captured → $S/phase-v-m6i-capture (do NOT teardown until 2nd-agent reproduces on-box) ==="
