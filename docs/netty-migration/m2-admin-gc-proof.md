# M2 (admin API) — allocation gc-proof

> **Verdict:** the Netty admin server (`NettyHttpApiServer`) allocates **~12–14 KB/req less
> server-side** than the incumbent JDK `HttpApiServer` (`com.sun.net.httpserver`), at both auth off and
> on. The allocation gate (charter / ADR-0043 — *no per-request allocation regression vs. the
> incumbent*) is **met with margin** — this surface is a measured *reduction*, not merely neutral.
> Honest caveats below: the measurement is JVM-wide (client-dominated absolute), the bars are wide on
> the 2-vCPU box, and this is **allocation only** — io_uring's syscall axis is Phase V.

## Method

`AdminHttpAllocBenchmark` (JMH, `-prof gc`, metric `gc.alloc.rate.norm` = B/op — the same instrument
as the S3/S7.5/M1 evidence). It drives a **real admin request over a real loopback connection** and
adds a `serverType ∈ {jdk, netty}` axis: the JDK leg builds `HttpApiServer`, the Netty leg builds
`NettyHttpApiServer`, **everything else identical** (same `HttpClient`, key set, value size, auth
wiring). `-prof gc` is JVM-wide (it sees client + server), so:

- The **absolute** B/op includes the `java.net.http` client — the larger, constant term (the DR-5
  caveat from the head-to-head: the JDK client is plausibly the bigger half of the floor).
- Because the client is **identical** in both legs, the **`jdk − netty` delta is server-side** — it is
  the transport machinery difference (`HttpExchange`/header-map/stream churn vs. Netty's pooled
  buffers + the aggregator/handler path), which is what we are measuring.
- `healthLive` (`GET /health/live`, constant-body) is the **control/shell**; `configGet`
  (`GET /v1/config/{key}`) adds the read path. `authMode ∈ {off, on}` toggles the bearer+ACL gate.

Run (this 2-vCPU box, kernel 7.0 → Netty auto-selects the **io_uring** tier; allocation is
tier-independent, so the tier does not affect B/op): `-f 1 -wi 2 -i 5 -r 3 -w 2`.

```
benchmark             authMode  jdk B/op            netty B/op          server-side Δ (jdk−netty)
configGet             off       37 554 ± 4052       23 865 ± 4080       −13 689  (~36% lower)
configGet             on        38 631 ± 3509       25 406 ± 3148       −13 225  (~34% lower)
healthLive (control)  off       35 593 ± 4179       23 163 ± 3478       −12 430
healthLive (control)  on        35 218 ± 4459       22 941 ± 3884       −12 277
```

(A first shorter pass `-f 1 -wi 2 -i 3 -r 1` gave the same direction and magnitude — jdk ~37–41K,
netty ~24–26K in every leg — so the result is reproducible, not a single-run artifact.)

## Reading the numbers

- The reduction is overwhelmingly in the **shell** (`healthLive`): Netty saves ~12.3–12.4 KB/req on
  the trivial constant-body path. That is the per-request server-side HTTP machinery —
  `com.sun.net.httpserver` allocates a fresh `HttpExchange`, header maps and streams per request (plus
  the virtual-thread-per-task executor's own churn), whereas the Netty pipeline reuses pooled
  `ByteBuf`s and a small set of per-request objects. Same root cause as M1's edge-read 8.7×.
- The **read-path marginal** (`configGet − healthLive`) is small on both — jdk ~2.0K (off) / ~3.4K
  (on), netty ~0.7K (off) / ~2.5K (on) — and is dominated by the store `get` + value response + (on)
  the auth gate. Netty's marginal is *lower* too.
- **Direction is robust; precise magnitude is not.** The ±~3.5–4.5K bars come from loopback
  client+server contention on 2 vCPUs and visible warmup drift (B/op declines across the 5 iterations
  as the JIT settles). The `jdk−netty` gap (~12–14K) is **~3× the bar** and consistent across all four
  independent legs, so "Netty allocates materially less, no regression" is solid; a headline like
  "exactly 13.7 KB" is not — report the band.

## Honest residuals (claim calibration)

- **Allocation only.** No throughput/tail-latency claim — the client and server share 2 vCPUs, so
  timing here is contention noise (the `us/op` bars were ±many-thousands). Not production numbers.
- **JVM-wide absolute.** "Netty admin uses ~24K B/req" would be wrong as a *server* figure — it's
  client+server+shell. The defensible server-side claim is the **~12–14 KB/req reduction vs. the JDK
  server** (the delta on the identical-client harness).
- **io_uring not measured here.** ADR-0043's core admin-surface rationale is uniformity + the io_uring
  substrate (syscall reduction), an axis `-prof gc` cannot see. That verdict is **Phase V** (EC2-gated,
  not yet run). The allocation reduction here is a *measured bonus* on top of the uniformity rationale.
- **ADR-0043 framing update.** The head-to-head left the admin surface *unmeasured* (it measured
  edge-read = win, consensus/fan-out wire = tie). This gc-proof now **measures** admin and finds a
  reduction — unsurprising, since the admin HTTP surface, like edge-read, replaces the allocation-heavy
  `com.sun.net.httpserver`. So admin joins edge-read as a measured allocation win; the "measured-neutral"
  surfaces ADR-0043 prices in are the wire codecs (M3/M4), not this one.

## Reproduce

```
./mvnw -o -pl configd-testkit -am package -DskipTests   # rebuild benchmarks.jar
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    AdminHttpAllocBenchmark -prof gc -f 1 -wi 2 -i 5 -r 3 -w 2
```
