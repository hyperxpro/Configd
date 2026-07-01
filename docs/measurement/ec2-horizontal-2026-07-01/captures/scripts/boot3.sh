#!/usr/bin/env bash
# boot3.sh <N> <plain|mtls> [ownerPool]  -- launches node k on cpk in parallel
N="$1"; MODE="$2"; OP="${3:-$N}"
/home/ubuntu/configd-horiz-20260701/sshx.sh cp1 "./launch-node.sh 1 $N $MODE $OP 1" >/dev/null 2>&1 &
/home/ubuntu/configd-horiz-20260701/sshx.sh cp2 "./launch-node.sh 2 $N $MODE $OP 1" >/dev/null 2>&1 &
/home/ubuntu/configd-horiz-20260701/sshx.sh cp3 "./launch-node.sh 3 $N $MODE $OP 1" >/dev/null 2>&1 &
wait
echo "boot3 N=$N mode=$MODE launched"
