# configd-edge-node — idiomatic-Java quality proposals (REVIEW ONLY, NOT APPLIED)

Scope: the pre-EC2 conservative idiomatic-Java quality pass. The items below were **reviewed and
deliberately NOT applied** because they touch a §2 no-touch zone (measured/proven/frozen) or because
they are behavioral changes that need a benchmark / the io_uring tier to validate. Each is recorded
with the reason it was withheld and a byte-identity caveat. The only change actually landed in this
pass is the `NettyEdgeHttpServer.start()` failure-path resource-leak fix (see the agent report); it is
not in this doc because it was applied, not proposed.

Files in §2 for this module: `EdgeReadHandler.java` (read hot path), `EdgeStreamClient.java` (stream
hot path), `EdgeNodeMetrics.java` (metric series frozen), `EdgeNodeConfig.java` (config defaults
frozen).

---

## §2 — `EdgeReadHandler.java` (read hot path — propose only)

### P1 (doc defect, safe, but §2 → propose). Dangling javadoc `@link` to a removed class.
- **Lines 31, 109.** The class javadoc says *"The control flow mirrors
  `{@link EdgeHttpServer.ConfigReadHandler}` exactly"* and the section comment repeats
  *"mirrors EdgeHttpServer.ConfigReadHandler.handle exactly"*. `EdgeHttpServer` no longer declares a
  `ConfigReadHandler` (it was slimmed to a thin JDK adapter delegating to `EdgeReadHandler`; it now
  declares only `EdgeHttpServer` + the `JdkSink` inner class). The `{@link …ConfigReadHandler}` is
  therefore a **dangling reference**.
- **Suggested fix:** retarget both references to `EdgeHttpServer` / `EdgeHttpServer.JdkSink`, or drop
  the now-circular "mirrors X exactly" wording (the two adapters delegate to *this* class, so there is
  no second implementation to mirror anymore).
- **Why withheld:** `EdgeReadHandler` is a §2 read-hot-path file → review-only. The change is
  javadoc-only and cannot move the measured floor, but the brief makes §2 files read-only without
  exception. (Latent risk: a future `-Xdoclint:all` javadoc build would error on the dangling link.)

### P2 (allocation reduction — moves the measured floor → defer). Hoist constant response bodies.
- **Lines 93, 99, 105, 114, 133, 144, 153, 175, 180, 197, 208 (`bytes(String)` call sites).** Every
  constant-bodied response (`{"live":true}`, `"Not Found"`, `"Method Not Allowed"`,
  `"Missing config key in path"`, `"Invalid X-Configd-Cursor header"`, `"Unauthorized"`) re-encodes its
  UTF-8 `byte[]` on **every** request that hits that branch via `bytes(...)`. These could be
  `private static final byte[]` constants computed once, removing per-request allocation on the
  health/metrics/404/405/400 paths.
- **Why withheld:** this **lowers** the measured allocation profile the EC2 gc-proof is baselined on
  (the class javadoc pins "the gc-proof's 1,716 B/req must hold"). Any change to the read path's
  allocation — even a reduction — must be re-baselined by the divergence-analyst, not slipped in during
  a quality pass (brief §2: "an idiom here can move the measured floor"). The dynamic `/v1/config`
  hit/refusal bodies are unaffected (they interpolate the key/version) — only the constant bodies are
  hoistable.
- **Caveat to weigh if ever applied:** a `static final byte[]` is shared mutable state escaping to
  `Sink.commit(...)`. The two in-tree sinks (`JdkSink` → `os.write`, Netty `ReadHandler` →
  `buf.writeBytes`) both **copy** and never mutate the array, so it is safe today; but it widens the
  contract (any future `Sink` must not mutate the body). Prefer documenting `Sink.commit`'s body as
  read-only if hoisting.

---

## §2 — `EdgeStreamClient.java` (stream hot path — propose only)

These three cleanliness items were flagged by the C2 sign-off review as "for whoever touches it next"
and are **still present**; all are behavioral/ordering nuances in the socket shell, hence propose-only.

- **`runConnection` frame-before-CLOSED interleaving (lines ~327-335).** The inner drain loop processes
  every already-queued frame and only then checks the `CLOSED` sentinel position; a `CLOSED` posted
  while a batch is queued is observed after the batch. This is **intentional and correct** (the chain
  is cursor-resumable; stale deltas are idempotently discarded) but reads as surprising — worth a
  one-line comment rather than a code change.
- **Interrupted-join early return in `close()` (lines 247-252) and `runConnection` (lines 321-324).**
  On `InterruptedException` the methods re-assert the interrupt and return early; a caller that
  interrupts during shutdown gets a best-effort (not guaranteed-joined) teardown. Acceptable for a
  daemon shell; flagged only so a future reader does not mistake it for a bug.
- **Backoff applied even after a clean disconnect (`sessionLoop` → `backoff`, lines 303-306).**
  `consecutiveFailures` only increments when `!sawInbound`, so a connection that delivered frames then
  ended cleanly resets the counter and backs off the **base** delay — a tiny avoidable pause before an
  immediate clean re-subscribe. Behavioral; needs the failover timing model to validate, so defer.

No idiomatic/`final`/generics nits here — the file is clean. `closeQuietly` swallows with a
justifying comment (best-effort), which is correct for teardown.

---

## §2 — `EdgeNodeMetrics.java` (metric series frozen — propose only)

### P3 (micro-nit, safe under single-writer → defer). Redundant double reads in `syncFromCore`.
- **Lines 171-178.** Each pumped counter is read twice, e.g.
  `pump(applied, core.appliedCount() - lastApplied); lastApplied = core.appliedCount();`. Caching the
  value in a local (`long applied = core.appliedCount();`) would halve the diagnostic reads.
- **Why safe / why withheld:** `syncFromCore` runs only on the core's single writer (the session
  thread), which is also the only writer of these counters, so the two reads always return the same
  value — there is **no correctness gap** today, only a redundant read. It is a measured-path micro-opt
  with no observable effect → not worth the §2 risk in a quality pass. Series names/labels are frozen
  and untouched by this suggestion.

`EdgeNodeMetrics` is otherwise idiomatic (eager registration, `LongAdder`-backed counters, switch with
a fail-fast `default`). No changes proposed to any series name, label encoding, or pump arithmetic.

---

## §2 — `EdgeNodeConfig.java` (config defaults frozen — propose only)

Reviewed; **no proposals**. The record is clean and idiomatic: compact-constructor validation,
defensive `List.copyOf`, fail-closed partial-TLS-triple rejection, enhanced-`switch` arg parsing,
unresolved `InetSocketAddress` for DNS-on-reconnect. All defaults (`DEFAULT_API_PORT=8081`,
`DEFAULT_RECONNECT_BACKOFF_MS=100`, `DEFAULT_HEARTBEAT_SILENCE_FACTOR=8`,
`DEFAULT_POISON_MAX_RETRIES=3`) are frozen and were not touched.

---

## Editable-file deferred BEHAVIORAL observations (NOT applied — outside the byte-identical envelope)

These live in editable files (`NettyEdgeHttpServer`, `EdgeHttpServer`) but are **behavioral** changes
on a normal/measured path, so they were deferred rather than landed in this byte-identical pass.

### D1 (latent FD leak on the io_uring shutdown path). `NettyEdgeHttpServer.stop()` should await the
server-channel close before shutting the event-loop groups.
- **Lines 147-157.** `stop()` does `serverChannel.close()` **fire-and-forget**, then immediately
  `boss.shutdownGracefully(...)`. With the io_uring transport the listen-socket FD can be **leaked**
  unless `close()` completion is awaited before the owning event loop is torn down (controlled
  A/B/C-proven Netty behavior; `nio`/`epoll` tolerate fire-and-forget). Fix would be
  `serverChannel.close().awaitUninterruptibly()` (or `.syncUninterruptibly()`) before the group
  shutdowns.
- **Why withheld:** (1) the auto-default transport is **Epoll** (io_uring is opt-in via
  `-Dconfigd.netty.transport=io_uring`), so the default path is already safe; (2) awaiting the close
  changes the **success** shutdown path for *every* transport and every test's `@AfterEach stop()` — it
  is not byte-identical on a path that runs in normal operation; (3) validating the fix requires the
  io_uring tier. Per the brief this is "needs a benchmark/tier to know it helps" → propose. Strongly
  recommend applying it as a dedicated follow-up if io_uring is ever promoted off opt-in.

### D2 (minor, test-only reference). `EdgeHttpServer` leaks its virtual-thread executor on `stop`.
- **Lines 99, 113-115.** The constructor sets `server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())`
  but `stop(int)` only calls `server.stop(...)`, which does **not** shut down a caller-supplied
  executor (`ExecutorService`). The executor is never closed.
- **Why low priority / withheld:** `EdgeHttpServer` is the **test-only equivalence reference** (M1
  swapped production to `NettyEdgeHttpServer`); a virtual-thread-per-task executor pins no platform
  threads, so the practical leak is negligible. Applying it would mean hoisting the executor to a field
  and closing it in `stop` — a real (if tiny) behavior change to the pinned reference adapter, so it is
  deferred rather than slipped into a byte-identical pass.

---

## Summary

- **Applied this pass:** 1 change (`NettyEdgeHttpServer.start()` failure-path group cleanup — success
  path byte-identical; see agent report).
- **Proposed / deferred:** P1 (dangling `@link`), P2 (hoist constant bodies — moves measured floor),
  P3 (redundant metric reads), 3 `EdgeStreamClient` cleanliness notes, D1 (io_uring `stop()` close
  await), D2 (`EdgeHttpServer` executor close). None applied.
</content>
</invoke>
