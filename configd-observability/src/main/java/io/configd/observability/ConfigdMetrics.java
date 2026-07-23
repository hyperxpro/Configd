package io.configd.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Eagerly registers the SLO-cited metrics in {@link MetricsRegistry} and
 * publishes the matching {@link PrometheusExporter.BucketSchedule}s.
 *
 * <p>Without this class, every metric referenced by
 * {@code ops/alerts/configd-slo-alerts.yaml} and {@code ops/runbooks/*.md}
 * would be created lazily on first emission. That makes the SLO pipeline
 * decorative: the alerts query series that never exist (no time series = no
 * alert).
 *
 * <p>The constructor:
 * <ol>
 *   <li>Creates every counter / histogram / gauge name the alert YAML
 *       references, so the {@link PrometheusExporter} emits non-empty
 *       {@code # TYPE} lines on the very first scrape.</li>
 *   <li>Holds typed handles to those metrics for the production wire-up
 *       sites (state-machine apply, edge read, install-snapshot).</li>
 *   <li>Exposes the per-histogram bucket schedule that aligns the
 *       {@code _bucket{le=...}} output with the alert {@code le}
 *       thresholds in fractional seconds.</li>
 * </ol>
 *
 * <p>Thread-safety: holds final references to thread-safe counter and
 * histogram instances. Safe to share across server threads.
 */
public final class ConfigdMetrics {

    // ---- Registry-level metric names (canonical, dot-separated). -----
    // PrometheusExporter sanitizes these to underscores at scrape time.

    public static final String NAME_WRITE_COMMIT_TOTAL = "configd.write.commit";
    public static final String NAME_WRITE_COMMIT_FAILED = "configd.write.commit.failed";
    public static final String NAME_WRITE_COMMIT_SECONDS = "configd.write.commit.seconds";
    public static final String NAME_APPLY_SECONDS = "configd.apply.seconds";
    public static final String NAME_EDGE_READ_TOTAL = "configd.edge.read";
    public static final String NAME_EDGE_READ_SECONDS = "configd.edge.read.seconds";
    public static final String NAME_PROPAGATION_DELAY_SECONDS = "configd.propagation.delay.seconds";
    public static final String NAME_RAFT_PENDING_APPLY = "configd.raft.pending.apply.entries";
    /**
     * The highest Raft log index this node has applied to its state machine (gauge). Paired with
     * {@link #NAME_STATE_MACHINE_HASH} to let the restore-conformance check verify a restored node has
     * replayed the log at least as far as the snapshot it was bootstrapped from and reached the matching
     * state. Published from the tick thread (a plain long touched only there needs an off-owner snapshot).
     */
    public static final String NAME_RAFT_LAST_APPLIED_INDEX = "configd.raft.last.applied.index";
    /**
     * The state-machine digest (info gauge), rendered {@code configd_state_machine_hash{hash="<hex>"} 1}.
     * The {@code <hex>} is a SHA-256 over the snapshot payload region ({@code ConfigStateMachine
     * .stateMachineHashHex()}), equal to the hash the restore-conformance check computes over the
     * snapshot file - so a restored node's live state can be byte-compared against its bootstrap snapshot.
     */
    public static final String NAME_STATE_MACHINE_HASH = "configd.state.machine.hash";
    /** Label the {@link #NAME_STATE_MACHINE_HASH} info gauge carries its hex digest in. */
    public static final String STATE_MACHINE_HASH_LABEL = "hash";
    public static final String NAME_SNAPSHOT_INSTALL_FAILED = "configd.snapshot.install.failed";
    public static final String NAME_SNAPSHOT_REBUILD = "configd.snapshot.rebuild";
    /**
     * Malformed-committed-command alarm counter. Incremented on the state-machine apply thread when
     * {@code ConfigStateMachine.apply} decodes a committed command that is grammatically malformed
     * (framed cleanly but fails {@link io.configd.store.CommandCodec#decode} - a poison-pill entry
     * from a cert-valid-but-Byzantine leader, or WAL corruption). The entry is skipped deterministically
     * as non-mutating rather than crash-looping the apply loop; a non-zero value here is a
     * security/integrity event worth alerting on. Eager-created so it emits {@code _total 0} from the
     * first scrape.
     */
    public static final String NAME_COMMAND_MALFORMED = "configd.command.malformed";
    /**
     * Raft peer-identity mismatch alarm counter. Incremented on a consensus-transport
     * reader / event-loop thread (or the inbound-routing thread) when a peer's authenticated TLS
     * certificate identity does not authorize the {@code senderId} / in-body {@code leaderId} /
     * {@code candidateId} it presents - a cert-valid-but-Byzantine cluster member impersonating another
     * member. The connection is dropped; a non-zero value here is a security event worth alerting on.
     * Counted only when a peer-identity allow-list is configured (enforce-when-configured). Eager-created
     * so it emits {@code _total 0} from the first scrape.
     */
    public static final String NAME_RAFT_PEER_IDENTITY_MISMATCH = "configd.raft.peer.identity.mismatch";
    /**
     * Inbound Raft-frame decode-drop counter. Incremented on the inbound-routing thread when a
     * frame that framed and CRC-verified cleanly could not be decoded into an actionable
     * {@code RaftMessage} - a dormant/undecodable {@link io.configd.transport.MessageType} with no
     * consensus codec ({@code PLUMTREE_*}/{@code HYPARVIEW_*}/{@code HEARTBEAT}) or a
     * structurally-malformed payload (truncation, out-of-range blob length, negative field). The frame is
     * dropped and the connection kept; the accompanying WARN log is rate-limited, so this counter is the
     * un-throttled signal of the drop rate - a sustained non-zero value is a version-skew or hostile-peer
     * signal worth alerting on. Eager-created so it emits {@code _total 0} from the first scrape.
     */
    public static final String NAME_RAFT_DECODE_DROPPED = "configd.raft.decode.dropped";
    /**
     * Write-overload reject counter. Backs the {@code Retry-After: 1} 429 path
     * ({@code HttpApiServer}) with an emitted, tested series so the sustained-429-rate
     * alert queries something real. Incremented on the HTTP write thread inside the
     * {@code raftProposer} when a propose is rejected with {@code OVERLOADED}
     * (the bounded-queue 1024 shed).
     */
    public static final String NAME_WRITE_REJECTED_OVERLOADED = "configd.write.rejected.overloaded";
    /**
     * Raft election/term-churn counter (dashboard "leader churn" panel). Incremented on
     * the tick thread by the positive delta of {@code currentTerm()} across ticks; a term
     * bump corresponds to a real election or leadership change.
     */
    public static final String NAME_RAFT_ELECTIONS = "configd.raft.elections";
    /**
     * Subscribed-prefix capacity gauge (dashboard "subscribed prefixes" panel).
     * Late-bound via {@link #bindSubscriptionPrefixGauge} to
     * {@code SubscriptionManager.prefixCount()} (a sampled snapshot; benign-race
     * {@code size()} read, standard gauge semantics).
     */
    public static final String NAME_SUBSCRIPTION_PREFIX_COUNT = "configd.subscription.prefix.count";
    /**
     * Base name for the tick-loop unhandled-throwable counter family. The actual
     * Prometheus series carries a {@code class} label pseudo-encoded into the registry
     * name ({@code base.bucket}) because {@link MetricsRegistry} does not natively
     * support labels; the per-class bucketing is bounded by
     * {@link SafeLog#cardinalityGuard(String)} so the cardinality stays inside
     * Prometheus' safe envelope.
     */
    public static final String NAME_TICK_LOOP_THROWABLE_BASE = "configd.tick.loop.throwable";
    public static final String NAME_INBOUND_ROUTING_THROWABLE_BASE = "configd.inbound.routing.throwable";
    /**
     * ACL config-policy loader counters. {@code load.failed} is incremented on a rejected
     * (fail-closed-to-last-good) {@code _acl/} reload - the series the
     * {@code ConfigdAclPolicyLoadFailed} alert queries; {@code reload} on each accepted
     * (re)load. They are PRODUCED by {@code AclConfigPolicyLoader} (which increments them
     * on this SAME server registry, idempotent), but are catalogued and eager-created here
     * so the control-plane scrape lists them from the first scrape ({@code _total 0}, the
     * anti-blind-dashboard property) even before the loader runs, and so the canonical name
     * has a single home the loader references. This class never increments them (no field).
     */
    public static final String NAME_ACL_POLICY_LOAD_FAILED = "configd.acl.policy.load.failed";
    public static final String NAME_ACL_POLICY_RELOAD = "configd.acl.policy.reload";
    /**
     * Last-produced snapshot size, in bytes (gauge). Set on every {@code ConfigStateMachine.snapshot()}
     * via the state-machine metrics bridge. Lets an operator watch the committed state approach the 4 MiB
     * per-chunk InstallSnapshot wire cap as capacity discipline, rather than discovering it only when a
     * transfer starts chunking. Eager-registered against {@link #lastSnapshotBytes} so it renders
     * {@code configd_snapshot_bytes 0} from the first scrape.
     */
    public static final String NAME_SNAPSHOT_BYTES = "configd.snapshot.bytes";
    /**
     * Consensus-transport connection-decode-drop counter. Incremented when a peer connection is dropped
     * at the frame-envelope decode boundary - an out-of-range frame length, an unrecognised wire version,
     * or a CRC / type / reserved-field failure - in either the JDK or Netty transport (bridged through
     * {@code ServerRaftTransportMetrics}). Distinct from {@link #NAME_RAFT_DECODE_DROPPED}, which
     * keeps the connection: this desync CLOSES it. A sustained non-zero value is a version-skew or
     * hostile-peer signal worth alerting on; eager-created so it emits {@code _total 0} from the first scrape.
     */
    public static final String NAME_RAFT_TRANSPORT_CONNECTION_DECODE_DROPPED =
            "configd.raft.transport.connection.decode.dropped";
    /**
     * Consensus-transport outbound-frame drop gauge (saturation). The transport drops a frame when no
     * connection is established or the bounded per-peer queue is full (Raft tolerates loss - re-sent on the
     * next heartbeat). The count lives on the transport endpoint; a pull gauge over
     * {@code RaftTransportEndpoint.framesDropped()} is registered in {@code ConfigdServer} (only when a
     * consensus transport is configured). The name is catalogued here for a single canonical home.
     */
    public static final String NAME_RAFT_TRANSPORT_FRAMES_DROPPED = "configd.raft.transport.frames_dropped";
    /**
     * Consensus-transport inbound connection-refusal gauge (slowloris / FD-exhaustion guard). The transport
     * refuses an inbound connection once the accepted live-set reaches the admission cap. A pull gauge over
     * {@code RaftTransportEndpoint.inboundConnectionsRefused()} is registered in {@code ConfigdServer}.
     */
    public static final String NAME_RAFT_TRANSPORT_INBOUND_CONNECTIONS_REFUSED =
            "configd.raft.transport.inbound_connections_refused";
    /**
     * Base name for the control-plane HTTP ingress-reject counter family. The concrete series carry a
     * {@code reason} suffix pseudo-encoded into the name ({@code base.reason}) because {@link MetricsRegistry}
     * has no native labels. The reasons are a fixed internal set ({@code bad_request} / {@code payload_too_large}),
     * not adversary-chosen, so no cardinality guard is needed. Both are eager-created here (no field - the
     * {@code NettyHttpApiServer} increments them on the shared registry) so they emit {@code _total 0} from
     * the first scrape. This is the currently-silent 400 / 413 ingress path (the counted 429 write-overload
     * shed is {@link #NAME_WRITE_REJECTED_OVERLOADED}).
     */
    public static final String NAME_HTTP_REQUEST_REJECTED_BASE = "configd.http.request.rejected";
    /** Reason suffix: a malformed request line / header block or an invalid request target (HTTP 400). */
    public static final String HTTP_REJECT_REASON_BAD_REQUEST = "bad_request";
    /** Reason suffix: a request body over the ingress size ceiling (HTTP 413). */
    public static final String HTTP_REJECT_REASON_PAYLOAD_TOO_LARGE = "payload_too_large";

    private final MetricsRegistry registry;

    private final MetricsRegistry.Counter writeCommitTotal;
    private final MetricsRegistry.Counter writeCommitFailed;
    private final MetricsRegistry.Histogram writeCommitSeconds;
    private final MetricsRegistry.Histogram applySeconds;
    private final MetricsRegistry.Counter edgeReadTotal;
    private final MetricsRegistry.Histogram edgeReadSeconds;
    private final MetricsRegistry.Histogram propagationDelaySeconds;
    private final MetricsRegistry.Counter snapshotInstallFailed;
    private final MetricsRegistry.Counter snapshotRebuild;
    private final MetricsRegistry.Counter commandMalformed;
    private final MetricsRegistry.Counter raftPeerIdentityMismatch;
    private final MetricsRegistry.Counter raftDecodeDropped;
    private final MetricsRegistry.Counter writeRejectedOverloaded;
    private final MetricsRegistry.Counter raftElections;
    private final MetricsRegistry.Counter raftConnectionDecodeDropped;

    /** Backs the {@link #NAME_SNAPSHOT_BYTES} gauge; last-writer-wins across shards (a node-level view). */
    private final AtomicLong lastSnapshotBytes = new AtomicLong();

    /**
     * Eagerly registers all SLO metrics in {@code registry}. The optional
     * {@code raftPendingSupplier} backs the
     * {@code configd_raft_pending_apply_entries} gauge - pass
     * {@code null} in pre-wire-up tests to skip the gauge registration.
     */
    public ConfigdMetrics(MetricsRegistry registry, LongSupplier raftPendingSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        // Counters - eager creation so first scrape emits "_total 0".
        this.writeCommitTotal = registry.counter(NAME_WRITE_COMMIT_TOTAL);
        this.writeCommitFailed = registry.counter(NAME_WRITE_COMMIT_FAILED);
        this.edgeReadTotal = registry.counter(NAME_EDGE_READ_TOTAL);
        this.snapshotInstallFailed = registry.counter(NAME_SNAPSHOT_INSTALL_FAILED);
        this.snapshotRebuild = registry.counter(NAME_SNAPSHOT_REBUILD);
        this.commandMalformed = registry.counter(NAME_COMMAND_MALFORMED);
        this.raftPeerIdentityMismatch = registry.counter(NAME_RAFT_PEER_IDENTITY_MISMATCH);
        this.raftDecodeDropped = registry.counter(NAME_RAFT_DECODE_DROPPED);
        this.writeRejectedOverloaded = registry.counter(NAME_WRITE_REJECTED_OVERLOADED);
        this.raftElections = registry.counter(NAME_RAFT_ELECTIONS);
        this.raftConnectionDecodeDropped = registry.counter(NAME_RAFT_TRANSPORT_CONNECTION_DECODE_DROPPED);
        // HTTP ingress-reject counters -- incremented by NettyHttpApiServer on this same registry. Eager
        // so the two known-reason series emit "_total 0" from the first scrape; no field (never incremented
        // here). Mirrors the ACL-loader pattern below.
        registry.counter(NAME_HTTP_REQUEST_REJECTED_BASE + "." + HTTP_REJECT_REASON_BAD_REQUEST);
        registry.counter(NAME_HTTP_REQUEST_REJECTED_BASE + "." + HTTP_REJECT_REASON_PAYLOAD_TOO_LARGE);
        // ACL config-policy loader counters -- PRODUCED and incremented by AclConfigPolicyLoader on this
        // same registry (idempotent re-registration). Catalogued and eager-created here so they emit
        // "_total 0" from the first scrape even before the loader runs; no field (this class never
        // increments them).
        registry.counter(NAME_ACL_POLICY_LOAD_FAILED);
        registry.counter(NAME_ACL_POLICY_RELOAD);

        // Histograms - eager creation so PrometheusExporter emits the
        // # TYPE histogram banner with le=+Inf even before any sample.
        this.writeCommitSeconds = registry.histogram(NAME_WRITE_COMMIT_SECONDS);
        this.applySeconds = registry.histogram(NAME_APPLY_SECONDS);
        this.edgeReadSeconds = registry.histogram(NAME_EDGE_READ_SECONDS);
        this.propagationDelaySeconds = registry.histogram(NAME_PROPAGATION_DELAY_SECONDS);

        // Gauge - only register when a supplier is provided. The supplier
        // must be allocation-free (called on every scrape).
        if (raftPendingSupplier != null) {
            registry.gauge(NAME_RAFT_PENDING_APPLY, raftPendingSupplier);
        }

        // Last-snapshot-size gauge - eager over an AtomicLong seeded at 0 so it renders from the first
        // scrape; recordSnapshotBytes() updates it whenever the state machine produces a snapshot.
        registry.gauge(NAME_SNAPSHOT_BYTES, lastSnapshotBytes::get);
    }

    /** Registers the gauge after construction. Useful when the supplier
     *  is not available at the time {@link ConfigdMetrics} is built (e.g.
     *  RaftNode is created later in the boot sequence). */
    public void bindRaftPendingApplyGauge(LongSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        registry.gauge(NAME_RAFT_PENDING_APPLY, supplier);
    }

    /**
     * Late-binds the {@link #NAME_RAFT_LAST_APPLIED_INDEX} gauge. As with the pending-apply gauge the
     * supplier reads a value published from the tick thread (a plain {@code long} touched only there), so
     * the scrape thread sees a coherent snapshot rather than a torn field.
     */
    public void bindRaftLastAppliedGauge(LongSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        registry.gauge(NAME_RAFT_LAST_APPLIED_INDEX, supplier);
    }

    /**
     * Late-binds the {@link #NAME_STATE_MACHINE_HASH} info gauge to the state machine's digest supplier
     * ({@code ConfigStateMachine::stateMachineHashHex}). The supplier is scrape-safe (immutable store
     * snapshot + volatile epoch) and memoized, so an idle scrape is allocation-free.
     */
    public void bindStateMachineHashGauge(java.util.function.Supplier<String> hexSupplier) {
        Objects.requireNonNull(hexSupplier, "hexSupplier must not be null");
        registry.infoGauge(NAME_STATE_MACHINE_HASH, STATE_MACHINE_HASH_LABEL, hexSupplier);
    }

    /** Late-binds the subscribed-prefix capacity gauge. The supplier is a sampled snapshot
     *  (e.g. {@code SubscriptionManager.prefixCount()}); a benign data race on a plain
     *  {@code size()} read is acceptable gauge semantics. */
    public void bindSubscriptionPrefixGauge(LongSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        registry.gauge(NAME_SUBSCRIPTION_PREFIX_COUNT, supplier);
    }

    public MetricsRegistry registry() { return registry; }
    public MetricsRegistry.Counter writeCommitTotal() { return writeCommitTotal; }
    public MetricsRegistry.Counter writeCommitFailed() { return writeCommitFailed; }
    public MetricsRegistry.Histogram writeCommitSeconds() { return writeCommitSeconds; }
    public MetricsRegistry.Histogram applySeconds() { return applySeconds; }
    public MetricsRegistry.Counter edgeReadTotal() { return edgeReadTotal; }
    public MetricsRegistry.Histogram edgeReadSeconds() { return edgeReadSeconds; }
    public MetricsRegistry.Histogram propagationDelaySeconds() { return propagationDelaySeconds; }
    public MetricsRegistry.Counter snapshotInstallFailed() { return snapshotInstallFailed; }
    public MetricsRegistry.Counter snapshotRebuild() { return snapshotRebuild; }
    public MetricsRegistry.Counter commandMalformed() { return commandMalformed; }
    public MetricsRegistry.Counter raftPeerIdentityMismatch() { return raftPeerIdentityMismatch; }
    public MetricsRegistry.Counter raftDecodeDropped() { return raftDecodeDropped; }
    public MetricsRegistry.Counter writeRejectedOverloaded() { return writeRejectedOverloaded; }
    public MetricsRegistry.Counter raftElections() { return raftElections; }
    public MetricsRegistry.Counter raftConnectionDecodeDropped() { return raftConnectionDecodeDropped; }

    /**
     * Records the byte length of the snapshot just produced by a state machine (backs the
     * {@link #NAME_SNAPSHOT_BYTES} gauge). Called from the state-machine metrics bridge on the
     * snapshot-producing thread; last-writer-wins is the intended node-level "most recent snapshot" view.
     */
    public void recordSnapshotBytes(long bytes) {
        lastSnapshotBytes.set(bytes);
    }

    /**
     * Increments the tick-loop unhandled-throwable counter for the given throwable's
     * simple class name. The class label is passed through
     * {@link SafeLog#cardinalityGuard(String)} so that an adversary who can pick the
     * exception class cannot blow up the Prometheus series count. Returns the bucketed
     * label that was used, so callers can include it in the structured log line they emit
     * alongside this metric increment.
     *
     * @param throwableClassName the simple class name of the unhandled throwable
     *                           (may be null - treated as "unknown")
     * @return the bounded label value that was actually used
     */
    public String onTickLoopThrowable(String throwableClassName) {
        String label = SafeLog.cardinalityGuard(throwableClassName);
        registry.counter(NAME_TICK_LOOP_THROWABLE_BASE + "." + label).increment();
        return label;
    }

    /**
     * Increments the inbound-routing unhandled-throwable counter for the given
     * throwable's simple class name. The inbound Raft routing task
     * ({@code driver.routeMessage}) runs on the single tick executor; a Throwable it
     * raises (e.g. a disk write failing during {@code applyCommitted -> apply}) was
     * previously swallowed by the executor with no metric and no structured log - a
     * disk-failing follower became a mute zombie. This counter (mirroring
     * {@link #onTickLoopThrowable}) makes that observable. Class label is
     * {@link SafeLog#cardinalityGuard cardinality-bounded}. Returns the bounded label.
     *
     * @param throwableClassName the simple class name (may be null - treated as "unknown")
     * @return the bounded label value that was actually used
     */
    public String onInboundRoutingThrowable(String throwableClassName) {
        String label = SafeLog.cardinalityGuard(throwableClassName);
        registry.counter(NAME_INBOUND_ROUTING_THROWABLE_BASE + "." + label).increment();
        return label;
    }

    /**
     * Returns the per-histogram bucket schedule map to pass to
     * {@link PrometheusExporter}. Each entry maps a registry-level
     * histogram name to a schedule whose {@code le} labels exactly match
     * the {@code le="X"} thresholds queried in
     * {@code ops/alerts/configd-slo-alerts.yaml}.
     *
     * <p>Cutoffs are nanoseconds because all latency samples are recorded
     * in nanoseconds (System.nanoTime() deltas).
     */
    public static Map<String, PrometheusExporter.BucketSchedule> histogramSchedules() {
        Map<String, PrometheusExporter.BucketSchedule> map = new LinkedHashMap<>();
        map.put(NAME_WRITE_COMMIT_SECONDS, latencySecondsSchedule());
        map.put(NAME_APPLY_SECONDS, latencySecondsSchedule());
        map.put(NAME_EDGE_READ_SECONDS, edgeReadSecondsSchedule());
        map.put(NAME_PROPAGATION_DELAY_SECONDS, propagationSecondsSchedule());
        return Collections.unmodifiableMap(map);
    }

    /**
     * Latency schedule for write-commit / apply paths (covers le="0.150"
     * referenced by the WriteCommitFastBurn / SlowBurn alerts).
     */

    /**
     * The histogram schedule(s) the edge process must publish so its served
     * {@code configd_edge_read_seconds} histogram renders the same {@code le} buckets the
     * edge-read burn-rate alert queries ({@code le="0.001"} / {@code le="0.005"}). The
     * edge registers the histogram in its OWN registry (it is a separate process and does
     * not load this control-plane instance); this exposes the canonical bucket schedule so
     * both processes agree on the buckets.
     */
    public static Map<String, PrometheusExporter.BucketSchedule> edgeProcessHistogramSchedules() {
        Map<String, PrometheusExporter.BucketSchedule> map = new LinkedHashMap<>();
        map.put(NAME_EDGE_READ_SECONDS, edgeReadSecondsSchedule());
        return Collections.unmodifiableMap(map);
    }

    private static PrometheusExporter.BucketSchedule latencySecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.005", 5_000_000L);
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        m.put("0.150", 150_000_000L);
        m.put("0.250", 250_000_000L);
        m.put("0.500", 500_000_000L);
        m.put("1.000", 1_000_000_000L);
        m.put("2.500", 2_500_000_000L);
        m.put("5.000", 5_000_000_000L);
        m.put("10.000", 10_000_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }

    /**
     * Edge-read schedule (covers le="0.001" / le="0.005" referenced by the
     * EdgeReadFastBurn / P999 alerts).
     */
    private static PrometheusExporter.BucketSchedule edgeReadSecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.0001", 100_000L);
        m.put("0.0005", 500_000L);
        m.put("0.001", 1_000_000L);
        m.put("0.0025", 2_500_000L);
        m.put("0.005", 5_000_000L);
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }

    /**
     * Propagation-delay schedule (covers le="0.5" referenced by the
     * PropagationFastBurn alert).
     */
    private static PrometheusExporter.BucketSchedule propagationSecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        m.put("0.250", 250_000_000L);
        m.put("0.500", 500_000_000L);
        m.put("1.000", 1_000_000_000L);
        m.put("2.500", 2_500_000_000L);
        m.put("5.000", 5_000_000_000L);
        m.put("10.000", 10_000_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }
}
