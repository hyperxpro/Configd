#!/usr/bin/env bash
MODE="${1:-plain}"
line=$(/home/ubuntu/configd-horiz-20260701/sshx.sh load "./probe.sh $MODE dist" 2>/dev/null)
a=$(echo "$line" | grep -oE 'cp1=[0-9]+' | cut -d= -f2)
b=$(echo "$line" | grep -oE 'cp2=[0-9]+' | cut -d= -f2)
c=$(echo "$line" | grep -oE 'cp3=[0-9]+' | cut -d= -f2)
echo "${a:-0} ${b:-0} ${c:-0}"
