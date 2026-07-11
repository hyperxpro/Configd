# ADR-0037: Edge data-plane transport reuses the JDK-socket/TlsManager/FrameCodec stack (no Netty)

- **Status:** Superseded by [ADR-0043](adr-0043-netty-transport-platform.md) - superseded in principle
  2026-06-23; the migration to Netty completed 2026-06-25 with the consensus-wire cutover, so all four
  transport surfaces (edge-read, admin, fan-out, and the inter-node consensus wire this ADR's stack
  carried) now run on Netty 4.2 in production. The JDK-socket/`TlsManager`/`FrameCodec` consensus
  transport (`TcpRaftTransport`) is replaced by `NettyRaftTransport`; the JDK class is retained only as
  the migration's JDK baseline and documented fast-revert path. The rationale below is unchanged
  (io_uring, uniformity, and the measured edge-read win; measured-neutral - roughly 0 B/op - on the wire
  codecs). The connection-scale math below is not invalidated (it correctly says today's per-node
  connection count does not *force* Netty); ADR-0043 overrides on different, forward-looking grounds and
  pays the dependency cost this ADR priced.

  _Original status:_ Accepted, with a required wording fix to the scale-envelope section applied below.
- **Date:** 2026-06-11
- **Interacts with:** ADR-0010 (named "Netty gRPC transport" - a documented fiction: no `io.netty`
  dependency exists in any pom; the real control-plane transport is JDK sockets), ADR-0034 (the boundary
  the fan-out service drains), and the project rule that no new external runtime dependency may be added
  without an ADR

## Context

The edge node needs to be a separately runnable process that speaks a streaming protocol to
the fan-out service, with mTLS "consistent with the control plane's". Early design wording called
for a "Netty transport", echoing ADR-0010. Reality, established by audit and re-verified now (`grep
io.netty` across all pom.xml and imports -> zero hits): **Netty has never been a dependency**. The
control plane's actual, hardened transport is:

- JDK sockets (`SSLSocket`/`SSLServerSocket` via `TlsManager`, TLSv1.3, mTLS with
  `setNeedClientAuth(true)`), bounded connect/handshake timeouts, establishment off the
  hot threads on a dedicated connector;
- `FrameCodec` wire discipline (length-prefixed frames, version byte, CRC32C trailer,
  16 MiB frame cap, `peekLength` bounds-check before allocation);
- virtual threads for inbound connection handling (JDK 25).

## Decision

The edge data plane (the fan-out service endpoint and the edge node client) is built on the
**same stack**: JDK sockets, `TlsConfig.mtls` / `TlsManager` (the identical classes, so
"mTLS consistent with the control plane's" holds by construction), a length-prefixed
CRC32C-checked frame codec following the `FrameCodec` pattern (a **separate codec and
version byte for the edge protocol** - see below), virtual-thread-per-subscriber on the
server side with **bounded** per-subscriber outbound queues, and a single-threaded apply
loop on the edge (the existing `DeltaApplier` single-writer model).

**No Netty.** Rationale:

1. **No new dependency without justification.** Netty is a large new runtime dependency
   with its own threading model, buffer lifecycle (refcounted pools), and security-review
   surface. The project's rule against new external runtime dependencies exists to prevent
   exactly this; an ADR importing one needs a positive case, and there is none at the actual scale:
2. **Scale honesty.** The system-wide edge count is 10k baseline / 1M ceiling - but no
   single fan-out node ever serves that population. The tree fan-out design amortizes it:
   with k=16 at tier 1 and k=64 at tier 2, each fan-out node serves at most its own
   branching factor of direct subscribers - **tens to low hundreds of long-lived
   connections per node**, each a slow streaming consumer. That per-node bound, not the
   system-wide edge count, is what the transport must handle. Virtual-thread-per-connection
   with blocking JDK sockets handles it with headroom; Netty's advantage (10k+
   ephemeral connections per process, syscall amortization) is not this workload.
3. **Consistency and review economy.** The bounded-connect/bounded-handshake/mTLS
   discipline of this stack was already adversarially verified; reusing it inherits that
   verification. A second, Netty-based TLS configuration would have to re-prove every mTLS
   property (including the associated fixture work).
4. **Doc-fiction correction, not perpetuation.** ADR-0010's "Netty" was audit-identified
   fiction. Matching code to that fiction would repeat that failure mode in
   reverse; this ADR amends the record to match a deliberately chosen reality.

**Separate edge frame codec / wire version.** The edge streaming protocol gets its own
codec class and version byte (`EdgeFrameCodec`, `EDGE_WIRE_VERSION`) in
`configd-distribution-service`, not new message types inside `FrameCodec`: the Raft wire
format (ADR-0029, golden-fixture-guarded in CI) and the edge protocol evolve on different
cadences, and coupling them would put every edge-protocol change through the Raft
wire-compat fixture gate. The edge codec follows the same structural discipline
(length prefix bounds-checked before allocation, version byte, type byte, CRC32C
trailer, explicit frame cap) and gets its own golden-fixture test from day one.

## Consequences

- `configd-edge-node` (the new edge-node module) depends on `configd-transport` (TlsConfig/
  TlsManager reuse), `configd-edge-cache`, `configd-distribution-service`,
  `configd-common`, `configd-observability` - zero new third-party runtime dependencies.
- The original design requirement is satisfied in substance (separately runnable process,
  streaming transport, control-plane-consistent mTLS) with one named deviation: the
  transport library, recorded here per the project's own "where reality forces
  deviation, ADR first" rule.
- **Performance check:** virtual threads plus bounded per-subscriber queues introduce no
  global lock and no O(subscribers) work on the publish path (the drain is per-subscriber
  pull from the shared `readSince` cursor).
- If a real >1k-subscribers-per-node requirement emerges later, swapping the server
  socket loop for an NIO/Netty endpoint is localized behind the fan-out session
  abstraction; this ADR does not foreclose it, it prices it.
