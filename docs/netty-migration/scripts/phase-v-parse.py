#!/usr/bin/env python3
"""Parse a phase-v-results-<profile> dir into syscall-reduction + throughput tables.
Usage: phase-v-parse.py <results-dir> <edge_R> <fan_C>  (R/C = the x1 measured count)."""
import sys, os, glob, re

OUT = sys.argv[1]
EDGE_R = int(sys.argv[2])
FAN_C = int(sys.argv[3])
ST = os.path.join(OUT, "strace")
CL = os.path.join(OUT, "client")

SYS = ["read","write","writev","readv","recvfrom","sendto","recvmsg","sendmsg",
       "epoll_wait","epoll_pwait","io_uring_enter","accept","accept4"]
IOSET = set(SYS)

def calls(path):
    d = {}
    if not os.path.exists(path):
        return None
    for ln in open(path):
        f = ln.split()
        if len(f) >= 5 and f[-1] in SYS:
            d[f[-1]] = int(f[3])   # field 4 = calls (always)
    return d

def io_per_op(tag_x1, tag_x2, n):
    """2-batch socket-IO syscalls/op."""
    a, b = calls(f"{ST}/{tag_x1}.txt"), calls(f"{ST}/{tag_x2}.txt")
    if a is None or b is None:
        return None, {}
    breakdown = {}
    tot = 0
    for k in set(a) | set(b):
        dd = b.get(k, 0) - a.get(k, 0)
        if k in IOSET and abs(dd) >= 3:
            breakdown[k] = dd / n
            tot += dd
    return tot / n, breakdown

def grep1(path, pat):
    if not os.path.exists(path):
        return ""
    for ln in open(path):
        m = re.search(pat, ln)
        if m:
            return m.group(0)
    return ""

def conn_points():
    s = set()
    for p in glob.glob(f"{ST}/edge-sys-io_uring-c*-x1.txt"):
        s.add(int(re.search(r"-c(\d+)-", p).group(1)))
    return sorted(s)

def sub_points():
    s = set()
    for p in glob.glob(f"{ST}/fan-sys-io_uring-s*-x1.txt"):
        s.add(int(re.search(r"-s(\d+)-", p).group(1)))
    return sorted(s)

print("="*70)
print(f"PHASE V RESULTS — {OUT}")
prov = os.path.join(OUT, "provenance.txt")
if os.path.exists(prov):
    print(open(prov).read().strip())
print("="*70)

print("\n### EDGE-READ — socket-IO syscalls/op (2-batch) + throughput ###")
print(f"{'conns':>6} | {'io_uring':>9} {'epoll':>8} {'reduction':>10} | {'iou tp/s':>9} {'epo tp/s':>9} | {'iou p99us':>9} {'epo p99us':>9}")
for c in conn_points():
    io, iob = io_per_op(f"edge-sys-io_uring-c{c}-x1", f"edge-sys-io_uring-c{c}-x2", EDGE_R)
    ep, epb = io_per_op(f"edge-sys-epoll-c{c}-x1",   f"edge-sys-epoll-c{c}-x2",   EDGE_R)
    red = f"{ep/io:.2f}x" if (io and io > 0) else "n/a"
    itp = grep1(f"{CL}/edge-tp-io_uring-c{c}.log", r"throughputReqPerSec=\d+").split("=")[-1]
    etp = grep1(f"{CL}/edge-tp-epoll-c{c}.log",   r"throughputReqPerSec=\d+").split("=")[-1]
    ip99 = grep1(f"{CL}/edge-tp-io_uring-c{c}.log", r"p99=[\d.]+").split("=")[-1]
    ep99 = grep1(f"{CL}/edge-tp-epoll-c{c}.log",   r"p99=[\d.]+").split("=")[-1]
    iov = f"{io:.3f}" if io is not None else "  -  "
    epv = f"{ep:.3f}" if ep is not None else "  -  "
    print(f"{c:>6} | {iov:>9} {epv:>8} {red:>10} | {itp:>9} {etp:>9} | {ip99:>9} {ep99:>9}")

print("\n### FAN-OUT — socket-IO syscalls/delivery (2-batch) + delivery throughput ###")
print(f"{'subs':>6} | {'io_uring':>9} {'epoll':>8} {'reduction':>10} | {'iou notif/s':>11} {'epo notif/s':>11}")
for s in sub_points():
    io, iob = io_per_op(f"fan-sys-io_uring-s{s}-x1", f"fan-sys-io_uring-s{s}-x2", FAN_C*s)
    ep, epb = io_per_op(f"fan-sys-epoll-s{s}-x1",   f"fan-sys-epoll-s{s}-x2",   FAN_C*s)
    red = f"{ep/io:.2f}x" if (io and io > 0) else "n/a"
    itp = grep1(f"{CL}/fan-tp-io_uring-s{s}.log", r"deliveryThroughputNotifPerSec=\d+").split("=")[-1]
    etp = grep1(f"{CL}/fan-tp-epoll-s{s}.log",   r"deliveryThroughputNotifPerSec=\d+").split("=")[-1]
    iov = f"{io:.3f}" if io is not None else "  -  "
    epv = f"{ep:.3f}" if ep is not None else "  -  "
    print(f"{s:>6} | {iov:>9} {epv:>8} {red:>10} | {itp:>11} {etp:>11}")

print("\n### CONSENSUS (1 connection, brief) — socket-IO syscalls/frame (2-batch) ###")
# consensus x1 count is its own; infer from cons-send log SENT
def cons_n(tr):
    s = grep1(f"{OUT}/cons-send-{tr}-x1.log", r"SENT \d+")
    return int(s.split()[-1]) if s else None
for tr in ["io_uring", "epoll"]:
    n = cons_n(tr)
    if n:
        v, b = io_per_op(f"cons-{tr}-x1", f"cons-{tr}-x2", n)
        bd = " ".join(f"{k}={x:.3f}" for k, x in sorted(b.items(), key=lambda kv: -kv[1]))
        print(f"  {tr:9} = {v:.3f}/frame   [{bd}]" if v is not None else f"  {tr}: missing")
print("="*70)
