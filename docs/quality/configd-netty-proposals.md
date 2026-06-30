# configd-netty — idiomatic-Java quality pass: §2 proposals (REVIEW-ONLY, NOT APPLIED)

Pre-EC2-measurement conservative quality pass. The entire module (4 source files) is transport /
wire / consensus-hot-path and is listed §2 NO-TOUCH, so this pass landed exactly **one** edit (an
unused-import removal in `NettyRaftTransport.java` — see the run report; provably byte-identical) and
records the items below as **proposals/observations only**. Nothing here is applied. Each is in a
no-touch file, so it is recorded for the team rather than changed under the byte-identity constraint.

The two byte-pinned files — `NettyConsensusFrameEncoder.java` and `RaftFrameDecoder.java` — were
reviewed in full and have **no proposals**. They are pristine: exact-size buffer allocation, a reused
event-loop-confined `CRC32C` `ThreadLocal`, CRC over the buffer's *cached* `internalNioBuffer` view,
single-allocation `[senderId || frame]` fold, and bounds-before-allocation in the decoder are all
deliberate, measured, golden-fixture-pinned choices. Touching them is pure downside.

---

## OBS-1 — `newServerSslHandler` hardcodes `setNeedClientAuth(true)`, ignoring `TlsConfig.requireClientAuth()`
**File:** `NettyRaftTransport.java` (`newServerSslHandler`, ~line 301)
**Category:** TLS / security-behavior parity — **observation, NO CHANGE RECOMMENDED.**
**Status:** intentional and documented; recorded so a divergence-analyst does not re-flag it as a surprise.

The Netty server engine always sets `setNeedClientAuth(true)`, whereas the JDK twin
`TcpRaftTransport.createServerSocket` honours `TlsConfig.requireClientAuth()`. This is a real
behavioral divergence, but it is (a) **documented as intentional** — class javadoc §"mTLS (DR-N18)"
and the inline comment "mTLS REQUIRED" — on the rationale that the consensus plane always wants mutual
auth; (b) in the **more-secure** direction (forced mTLS vs. optional); and (c) **identical in
production**, where `TlsConfig.mtls()` sets the flag true anyway. It only differs for a hypothetical
`requireClientAuth=false` consensus config (one-way TLS on JDK, forced mTLS on Netty).

Because this drives an authentication decision (squarely §2) and is a deliberate design choice, it
must **not** be changed in a quality pass. The only exact-parity alternative would be to read the flag
(`engine.setNeedClientAuth(cfg.requireClientAuth())`) or assert it — but both are behavior changes and
need a design owner, not a cleanup. Leave as-is; this note exists only to mark it "reviewed,
intentional." (Consistent with the prior M4 review's NIT.)

---

## OBS-2 — `availabilityReport()` re-evaluates `IoUring.isAvailable()` / `Epoll.isAvailable()` several times
**File:** `NettyTransport.java` (`availabilityReport`, ~lines 138–150)
**Category:** minor readability / micro-redundancy — **low value, NOT APPLIED.**

The diagnostic startup-log builder calls `IoUring.isAvailable()` up to twice and `Epoll.isAvailable()`
twice. Hoisting each to a local (`boolean uring = IoUring.isAvailable();` …) would be marginally
cleaner and is behavior-preserving (the availability checks are idempotent, JVM-constant native
probes). Not applied because: (a) it is in a §2 file (`NettyTransport.java`, the MEASURED selector),
and even though this is the **cold** startup-log path and *not* the `select()` decision logic, the
brief scopes the whole file no-touch; (b) the value is negligible — it is a once-per-boot log string.
Recorded for completeness; safe to fold in opportunistically during a future non-frozen edit to this
file, but not worth a standalone change before the measurement.

---

## Reviewed, no action
- `NettyConsensusFrameEncoder.java` — pristine (see header). No proposal.
- `RaftFrameDecoder.java` — pristine; bounds-before-alloc + decode-first desync→`CorruptedFrameException`
  discipline is exactly the JDK reader's contract. No proposal.
- `NettyTransport.select()` / `forced()` / tier factories — the Epoll-auto / io_uring-opt-in default is
  a MEASURED decision (ADR-0043 Phase V). Reviewed; correct and well-documented. No proposal.
- `NettyRaftTransport` send/drain/connect lifecycle — the per-peer bounded-queue drop-oldest, CAS-gated
  event-loop drain, awaited listen-FD close (io_uring leak fix), and the `scheduleDrain`
  `RejectedExecutionException` guard (a previously-latent send/close race, now fixed in the code) are
  all sound and behavior-pinned by the 3-transport contract. No proposal.
