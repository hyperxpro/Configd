# ADR-0037: Edge data-plane transport reuses the JDK-socket/TlsManager/FrameCodec stack (no Netty)

- **Status:** **FULLY SUPERSEDED by [ADR-0043](adr-0043-netty-transport-platform.md)** - superseded in
  principle 2026-06-23; **migration COMPLETED 2026-06-25 with M4 (consensus wire)**, so all four
  transport surfaces (edge-read, admin, fan-out, and the inter-node consensus wire this ADR's stack
  carried) now run on Netty 4.2 in production. The JDK-socket/`TlsManager`/`FrameCodec` consensus
  transport (`TcpRaftTransport`) is replaced by `NettyRaftTransport`; the JDK class is retained only as
  the migration contract's JDK baseline + the documented fast-revert. Rationale unchanged (io_uring +
  uniformity + the measured edge-read win; measured-neutral - proven ~0 B/op idiomatic - on the wire
  codecs). The connection-scale math below is **not** invalidated (it correctly says today's per-node
  connection count does not *force* Netty); ADR-0043 overrides on different, forward-looking grounds
  and pays the dependency cost ADR-0037 priced. _Original status:_
  Accepted (review-architect RATIFY-WITH-CHANGES 2026-06-11 - the required scale-envelope wording fix is applied below; the TransportSink-seam contingency is confirmed at C1 design-note closeout)
- **Date:** 2026-06-11
- **Interacts with:** ADR-0010 (named "Netty gRPC transport" - documented FICTION: no `io.netty` dependency exists in any pom; the real control-plane transport is JDK sockets), ADR-0034 (the boundary the fan-out service drains), hard rule 5 (no new external runtime dependencies without an ADR)

## Context

Component C2 requires a separately runnable edge process speaking a streaming protocol to
the fan-out service (C1) with mTLS "consistent with the control plane's". The charter's
wording says "Netty transport", echoing ADR-0010 / architecture section 1. Reality, established
by audit and re-verified now (`grep io.netty` across all pom.xml and
imports -> zero hits): **Netty has never been a dependency**. The control plane's actual,
RR-002-hardened transport is:

- JDK sockets (`SSLSocket`/`SSLServerSocket` via `TlsManager`, TLSv1.3, mTLS with
  `setNeedClientAuth(true)`), bounded connect/handshake timeouts, establishment off the
  hot threads on a dedicated connector;
- `FrameCodec` wire discipline (length-prefixed frames, version byte, CRC32C trailer,
  16 MiB frame cap, `peekLength` bounds-check before allocation);
- virtual threads for inbound connection handling (JDK 25).

## Decision

The edge data plane (C1 fan-out service endpoint + C2 edge node client) is built on the
**same stack**: JDK sockets, `TlsConfig.mtls` / `TlsManager` (the identical classes, so
"mTLS consistent with the control plane's" holds by construction), a length-prefixed
CRC32C-checked frame codec following the `FrameCodec` pattern (a **separate codec and
version byte for the edge protocol** - see below), virtual-thread-per-subscriber on the
server side with **bounded** per-subscriber outbound queues (the section 11 backpressure rows),
and a single-threaded apply loop on the edge (the existing `DeltaApplier` single-writer
model).

**No Netty.** Rationale:

1. **Hard rule 5 / zero-external-coordination.** Netty is a large new runtime dependency
   with its own threading model, buffer lifecycle (refcounted pools), and security-review
   surface. The rule exists to prevent exactly this; an ADR importing it needs a positive
   case, and there is none at the actual scale:
2. **Scale honesty.** The SYSTEM edge count is 10k baseline / 1M ceiling (section 0.1) - but no
   single fan-out node ever serves that population. Architecture section 12's tree fan-out
   amortizes it: with k=16 at tier 1 and k=64 at tier 2, each fan-out node serves at most
   its own branching factor of direct subscribers - **tens to low hundreds of long-lived
   connections per node**, each a slow streaming consumer. That per-node bound, not the
   system edge count, is what the transport must handle. Virtual-thread-per-connection
   with blocking JDK sockets handles it with headroom; Netty's advantage (10k+
   ephemeral connections per process, syscall amortization) is not this workload.
3. **Consistency and review economy.** The RR-002 review already adversarially verified
   the bounded-connect/bounded-handshake/mTLS discipline of this stack; reusing it
   inherits that verification. A second, Netty-based TLS configuration would have to
   re-prove every mTLS property (RR-094-class fixture work included).
4. **Doc-fiction correction, not perpetuation.** ADR-0010's "Netty" was audit-identified
   fiction. Matching code to that fiction would repeat that failure mode in
   reverse; this ADR amends the record to match a deliberately chosen reality.

**Separate edge frame codec / wire version.** The edge streaming protocol gets its own
codec class and version byte (`EdgeFrameCodec`, `EDGE_WIRE_VERSION`) in
`configd-distribution-service`, NOT new message types inside `FrameCodec`: the Raft wire
format (ADR-0029, golden-fixture-guarded in CI) and the edge protocol evolve on different
cadences, and coupling them would put every edge-protocol change through the Raft
wire-compat fixture gate. The edge codec follows the same structural discipline
(length prefix bounds-checked before allocation, version byte, type byte, CRC32C
trailer, explicit frame cap) and gets its own golden-fixture test from day one.

## Consequences

- `configd-edge-node` (new module, C2) depends on `configd-transport` (TlsConfig/
  TlsManager reuse), `configd-edge-cache`, `configd-distribution-service`,
  `configd-common`, `configd-observability` - zero new third-party runtime dependencies.
- The charter's C2 sentence is satisfied in substance (separately runnable process,
  streaming transport, control-plane-consistent mTLS) with one named deviation (the
  transport library), recorded here per the charter's own "where reality forces
  deviation, ADR first" rule.
- Performance-disqualifying-design screen (hard rule 4): virtual threads + bounded
  per-subscriber queues introduce no global lock and no O(subscribers) work on the
  publish path (the drain is per-subscriber pull from the shared `readSince` cursor);
  the design is screened again at the C1 design review.
- If a future session demonstrates a real >1k-subscribers-per-node requirement, swapping
  the server socket loop for an NIO/Netty endpoint is localized behind the C1 session
  abstraction; this ADR does not foreclose it, it prices it.

## Sign-off

- review-architect: _pending - recorded at C1 design review_
