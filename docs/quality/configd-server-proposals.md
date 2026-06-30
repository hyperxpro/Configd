# configd-server — §2 NO-TOUCH review (PROPOSE-ONLY)

Idiomatic-Java quality pass, pre-EC2-measurement. The items below were found in the **§2
no-touch zones** (wire codecs, consensus/transport I/O, ACL/auth decision paths, measured
fan-out hot path). They are **NOT applied** — each would change an observable property a
measurement/oracle test pins, or needs a benchmark to know it helps. They are recorded here
for a deliberate, separately-reviewed change. The safe nits that WERE applied are listed in
the agent's final report (none in §2 files).

Legend: **OBS** = observability/correctness-of-logging; **PERF** = deferred perf hypothesis
(needs a benchmark); **DOC** = comment/impl mismatch (benign).

---

## 1. RaftTransportAdapter.java — inbound decode failure swallowed to `System.err` (OBS)

`RaftTransportAdapter.java:113-115` (the inbound `registerHandler` decode path):

```java
} catch (Exception e) {
    System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage());
}
```

A frame that fails to decode on the consensus inbound path is logged with `System.err.println`
— no cause stack trace, no JUL record (so it is invisible to log aggregation), and no metric.
This is inconsistent with the elaborate structured-logging the same server already applies to
sibling failures on the *routing* side: `ConfigdServer.handleInboundRoutingThrowable` /
`handleTickLoopThrowable` emit a `Level.SEVERE` JUL record **with the throwable attached** plus a
cardinality-bounded Prometheus counter, specifically to replace exactly this "invisible stderr"
mode (the RR-008 / H-009 lesson). A peer sending corrupt/incompatible frames is silently
un-observable here.

**Proposed (NOT applied):** add a `private static final java.util.logging.Logger LOG` and replace
the `println` with `LOG.log(Level.WARNING, "...", e)` (cause attached), and consider a
cardinality-bounded `configd_raft_decode_failed_total{class}` counter mirroring the routing-side
handlers. Keep the swallow semantics (a bad frame must not kill the inbound handler) — only the
*sink* changes.

**Why propose, not apply:** this file is §2 (Raft transport adapter, consensus wire I/O), and the
change alters the log sink/format and adds a metric series — an observable behavior change that a
divergence-analyst must sign off and that may need a `ConfigdMetrics` catalog entry +
`EdgeMetricsContractTest` update (the anti-blind-dashboard gate) if a counter is added.

---

## 2. EdgeFrameToByteEncoder.java / ByteBufFrameSink.java — per-encode sink holder allocation (PERF)

`EdgeFrameToByteEncoder.encode` (the **hot** server→edge NOTIFY / WATCH_EVENT path) allocates a
fresh `ByteBufFrameSink` wrapper on every frame:

```java
EdgeFrameCodec.encodeInto(frame, new ByteBufFrameSink(out), wireVersion.currentWireVersion());
```

`ByteBufFrameSink` is a 1-field holder over the per-call pipeline `ByteBuf out`. This is the
measured `~240 B/op` pooled-holder delta over the 25,520 B/op message-building floor
(`prodEncodeIntoByteBufPooled = 25,760` vs `prodEncodeIntoHeapReused = 25,520`, per the M3
`FanOutEncodeIntoBenchmark`). It is the *known, accounted-for* part of the floor — not a leak.

**Proposed (NOT applied):** make the encoder hold ONE mutable `ByteBufFrameSink` per connection
(the encoder instance is already per-channel) whose `buf` field is reset to `out` at the top of
each `encode` call, eliminating the per-frame holder allocation. `MessageToByteEncoder` calls
`encode` serially on the event loop, so a per-connection mutable sink is single-threaded-safe.

**Why propose, not apply:** this is the **measured fan-out hot path** (§2). Mutating the sink's
`buf` per call changes the allocation profile the EC2 measurement and `FanOutEncodeIntoBenchmark`
assert against; whether it actually moves the floor (vs. being escape-analyzed away by C2 already)
must be confirmed by re-running `FanOutEncodeIntoBenchmark -prof gc` on the bench host BEFORE
landing. Exactly the "needs a benchmark to know it helps" case the brief says to defer.

---

## 3. StrongReadPolicy.java — "order-preserving copy" comment vs `Set.copyOf` (DOC)

`StrongReadPolicy.java:52-63` (constructor):

```java
// Defensive, order-preserving copy; reject blank prefixes ...
Set<String> copy = new LinkedHashSet<>();
for (String p : prefixes) { ... copy.add(p); }
this.prefixes = Set.copyOf(copy);
```

The comment claims an **order-preserving** copy, but `Set.copyOf(LinkedHashSet)` returns an
*unordered* immutable set (an `ImmutableCollections.SetN`), so the `LinkedHashSet` insertion order
is discarded into `prefixes()` / `toString()`. The intermediate `LinkedHashSet` therefore buys
nothing order-wise.

This is **benign** — `isStrongReadKey` is a `startsWith`-any scan whose boolean result is
order-independent, and nothing depends on `prefixes()` iteration order — so it is purely a
comment/impl mismatch, not a defect.

**Proposed (NOT applied), pick one:**
- if order is genuinely wanted for `prefixes()` / `toString()` stability:
  `this.prefixes = Collections.unmodifiableSet(new LinkedHashSet<>(copy));` (keep the order), or
- if not (the actual situation): drop the word "order-preserving" from the comment and let
  `Set.copyOf(prefixes)` build directly (the `LinkedHashSet` is then unnecessary, though it is
  still doing the per-element blank-rejection scan, so keep the loop).

**Why propose, not apply:** `StrongReadPolicy` is §2 (read-consistency *decision* type whose
exposed `prefixes()` collection feeds the fail-closed linearizable-read class). Even a no-op-looking
change to how the decision collection is built is exactly what the byte-identity proof on this type
covers — leave it to a deliberate review.

---

### Reviewed §2 files with NO proposal (clean as-is)
`RaftMessageCodec.java` (wire codec; collections already presized, e.g.
`new LinkedHashMap<>(Math.max(4, n*2))`, `new ArrayList<>(numEntries)`; byte layout golden-pinned),
`AclConfigPolicyLoader.java` (predicate-alignment security proof — deliberately verbatim-shared
`validateReserved`), `AdminApiHandler.java` (auth/routing decision core; catches are targeted —
`PolicyParseException`→400, `IllegalArgumentException` handled),
`fanout/AclServiceWatchAuthorizer.java` (the asymmetric KEY-floor vs subtree-cover branch is the
security crux), `fanout/ByteToEdgeFrameDecoder.java` (cold inbound; `peekLength`-before-allocate
discipline intact).
