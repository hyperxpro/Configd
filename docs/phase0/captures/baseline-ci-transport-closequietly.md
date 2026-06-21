# Baseline CI repair — `TcpRaftTransport.closeQuietly(Socket)` NPE

> **Scope:** PRE-EXISTING baseline failure, **not** Phase 0 consensus work. Repaired so the
> "CI-green-on-the-baseline-before-the-harness" gate (RR-107: local-green ≠ CI-green) can be met.
> Red/green captured; second-agent replayed.

## Symptom

`main` CI red for ≥6 commits (every run ~4m38s — a fast, consistent early failure). Jobs
`build-and-test`, `gate-1`, `gate-2` all fail; the consensus surface is fine
(`jcstress curated subset: OK — 308 passed`). Root failure is in `configd-transport` (reactor
module 3 of 14), which cascades (`-rf :configd-transport`).

## Root cause

`io.configd.transport.TcpRaftTransportTest` — 2–3 errors (varies by run), all in **`tearDown`**,
not the test bodies:

```
java.lang.NullPointerException: Cannot invoke "java.net.Socket.close()" because "s" is null
    at io.configd.transport.TcpRaftTransport.closeQuietly(TcpRaftTransport.java:344)
    at io.configd.transport.TcpRaftTransport$PeerConnection.close(TcpRaftTransport.java:819)
    at io.configd.transport.TcpRaftTransport.close(TcpRaftTransport.java:325)
    at io.configd.transport.TcpRaftTransportTest.tearDown(TcpRaftTransportTest.java:97)
```

The `(certificate_unknown) No subject alternative names matching IP address 127.0.0.2` lines are a
**red herring** — that is `find0051_clientHandshakeRejectsCertWithWrongHostname` correctly rejecting
a wrong-hostname cert. When a handshake fails (rejected cert, or a concurrent connect that lost the
race), the `PeerConnection.socket` field is legitimately **null**. `PeerConnection.close()` then
calls `closeQuietly(socket)` → the `closeQuietly(Socket)` overload (`:342`) did `s.close()` with **no
null guard** → NPE. Its sibling `closeQuietly(AutoCloseable)` (`:593`) *already* null-guards — the
`Socket` overload simply diverged.

## Fix (minimal)

Add the null guard to `closeQuietly(Socket)` so it matches the `AutoCloseable` sibling. "Quietly"
must tolerate null. **No change to handshake/TLS logic — the bad-cert rejection still happens; only
the cleanup crash is removed. Zero security impact.**

## Evidence

| | Result |
|---|---|
| RED (pre-fix, JDK 25 local repro) | `Tests run: 9, Failures: 0, Errors: 2` — NPE at `closeQuietly:344` |
| GREEN (post-fix, JDK 25 local) | `Tests run: 9, Failures: 0, Errors: 0` — BUILD SUCCESS |
| Second-agent replay | root cause + completeness + green re-confirmed independently |

Raw logs: `/tmp/transport-test.log` (red), `/tmp/transport-test-green.log` (green).

## Note

The CI `build-and-test` job stopped at module 3, so modules 4–14 were never exercised on these
commits. **CI (post-push) is the oracle** for whether the baseline is now fully green; this fix
clears the first (and cascading) blocker. The local full-reactor compile check is the pre-push
guard.
