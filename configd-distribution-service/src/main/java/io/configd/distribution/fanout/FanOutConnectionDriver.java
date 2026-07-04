package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * The transport-agnostic brain of one edge subscriber connection - the multi-shard fan-out/fan-in
 * coordinator. It owns one {@link FanOutSessionCore} <b>per shard</b> ({@code Map<gid, core>}) plus
 * the security-relevant logic that is shared by all transports and must NOT be re-implemented per
 * transport:
 * <ul>
 *   <li><b>Inbound routing</b> ({@link #onInboundFrame}): the first frame decides the connection
 *       type. A {@code SUBSCRIBE}-first connection is the legacy fan-out path (SUBSCRIBE-must-come-
 *       first, CURSOR_ACK -> session command, anything else -> protocol-violation teardown) - it
 *       drives the single primary-shard core, entirely unchanged. A {@code WATCH_CREATE}-first
 *       connection is the RFC section 2 watch path (the multiplex/filter/authz veneer): many
 *       {@code WATCH_CREATE}/{@code WATCH_CANCEL} multiplexed by {@code watch_id}, each authorized
 *       before any data frame, each fanned across the N per-shard cores. Frames are decoded by the
 *       transport and handed here already typed.</li>
 *   <li><b>Cert-identity binding</b> ({@link #bindIdentity}): over mTLS the verified client-cert
 *       principal is authoritative and the wire {@code edgeId} is advisory; plaintext uses the wire
 *       {@code edgeId}. The transport supplies only the verified principal. The same
 *       {@code edgeIdentity} is the watch-authorization principal (W8-6).</li>
 *   <li><b>Admission</b> at SUBSCRIBE: authorize the whole-store feed (whole-store READ via the
 *       optional {@link WatchAuthorizer}; deny -> {@link ErrorCode#NOT_AUTHORIZED} teardown with zero
 *       data frames; a {@code null} authorizer admits it as an auth-off deployment), then the governor
 *       gate (quarantine/unhealthy REFUSE -> teardown with the on-wire {@link ErrorCode#QUARANTINED};
 *       post-cooldown ALLOW_FORCE_SNAPSHOT rebinds the cursor to 0 so {@code decideMode} forces the
 *       re-bootstrap) and the demotion -> QUARANTINED/UNHEALTHY teardown arm
 *       ({@link #onDemotionEvent}).</li>
 *   <li><b>The multi-shard watch veneer</b> (RFC section 2): the connection turns to a watch
 *       connection on the first {@code WATCH_CREATE}, which materializes one {@link FanOutSessionCore}
 *       for <b>every</b> shard in {@code allGids} (each with its own gid-stamped
 *       {@link WatchMultiplexSink} and {@link FilteringReplaySource}). {@code WATCH_CREATE} is
 *       validated (path grammar, W2-4), <b>authorized</b> via the optional {@link WatchAuthorizer} SPI
 *       <b>once over the whole logical target before any shard leg streams a byte</b> - a single
 *       indivisible whole-target decision, all N legs served or none, fail-closed when the authorizer
 *       is absent/unauthenticated/throwing - then registered. The
 *       first authorized watch seeds all N cores; each shard's decorator translates that core's
 *       structured output into per-watch {@code WATCH_*} frames tagged with the real gid, and the
 *       coordinator coalesces the two cross-shard frames ({@code WATCH_CREATED} vector,
 *       {@code WATCH_PROGRESS} vector).</li>
 *   <li><b>The session loop</b> ({@link #runSessionLoop}): drain commands -> sweep the cores in
 *       ascending-gid order (one virtual thread; single-writer, no per-shard locks - {@code readSince}
 *       is lock-free) -> the governor feed (ack-progress / queue-pressure edges / {@code <= 1 Hz}
 *       time-driven evaluation while warned) -> the bounded revocation poll -> adaptive idle backoff.
 *       Single-writer: every call into the (non-thread-safe) cores <b>and</b> the (non-thread-safe)
 *       {@link WatchRegistry} happens only on the session-loop thread; the reader posts commands
 *       through a concurrent queue.</li>
 * </ul>
 *
 * <p><b>The v1 shared-fate boundary (W8-6 / W10-8).</b> The N shard cores share one connection-level
 * {@code CURSOR_ACK} scalar (broadcast to every core) and one {@link SlowConsumerGovernor} fate: a
 * single slow/greedy shard substream can demote its siblings (cross-shard head-of-line blocking). The
 * min-frontier makes the lag visible (the lagging component freezes while {@code server_now}
 * advances); per-shard flow control is the named v2 extension W10-8. The per-shard cores make that
 * later isolation a localized change.
 *
 * <p>The transport provides a {@link TransportSink} (its bounded outbound), the verified edge
 * identity, the {@link SlowConsumerGovernor}, an optional {@link WatchAuthorizer}, and a
 * {@code teardownHook} that performs the transport-specific close. The hook MUST be idempotent - the
 * loop's {@code finally} always requests a teardown, and a QUARANTINED arm may have requested one
 * already.
 */
public final class FanOutConnectionDriver implements WatchMultiplexSink.Coordinator {

    private static final Logger LOG = Logger.getLogger(FanOutConnectionDriver.class.getName());

    /** The connection type, decided by the first inbound frame (reader-thread-only). */
    private enum ConnType {
        /** No frame seen yet - the next frame decides. */
        UNDECIDED,
        /** {@code SUBSCRIBE}-first: the legacy connection-level fan-out path (unchanged). */
        LEGACY,
        /** {@code WATCH_CREATE}-first: the the watch protocol multiplexed watch path. */
        WATCH
    }

    /** The single-shard resolver used by the back-compat constructors: every target covers gid 0. */
    private static final ShardResolver SINGLE_SHARD = target -> new int[]{0};

    /** The per-shard commit sources (the {@link CommitNotificationSource} each core drains). */
    private final Map<Integer, CommitNotificationSource> sources;

    /** The per-shard replay sources (each core's catch-up snapshot floor). */
    private final Map<Integer, ReplaySource> replaySources;

    /** The connection's shard set, ascending (StaticShardMap: {@code [0, N)}). */
    private final int[] allGids;

    /** Fast membership test for the cursor gid-spoof guard: a component gid MUST be a known gid. */
    private final Set<Integer> knownGids;

    /** The primary shard - core built eagerly (legacy byte-identity + the {@link #session()} accessor). */
    private final int primaryGid;

    /** Resolves a watch target to its covered shard set (KEY -> one shard; PREFIX/FULL -> all). */
    private final ShardResolver shardResolver;

    /**
     * The server's current topology epoch ({@code ShardMap.epoch()}, from the authenticated
     * {@code topology-descriptor.dat}; v1 = {@link WatchCursor#INITIAL_TOPOLOGY_EPOCH}). Every
     * inbound resume token (WATCH_CREATE cursor / legacy SUBSCRIBE) is checked against it (A4): a
     * mismatch is {@link ErrorCode#STALE_TOPOLOGY} (the client must re-hydrate), and every outbound
     * cursor (WATCH_PROGRESS / a WATCH_CANCELED oldest) is stamped with it. Static-N never changes it,
     * so at v1 the check is always satisfied - byte-identical behavior.
     */
    private final long topologyEpoch;

    /** The connection's real outbound - shared by every per-shard decorator; router frames go here. */
    private final TransportSink transportSink;

    private final SlowConsumerGovernor governor;
    private final FanOutConfig config;
    private final FanOutSessionMetrics metrics;
    private final Clock clock;
    private final String edgeIdentity;
    private final BiConsumer<ErrorCode, String> teardownHook;

    /**
     * The authorization gate (W7), or {@code null} when no principal model is wired. It gates both
     * {@code WATCH_CREATE} (per-target) and the legacy full-store {@code SUBSCRIBE} (whole-store
     * READ). A {@code null} authorizer fails CLOSED for watches (every {@code WATCH_CREATE} rejected
     * {@code NOT_AUTHORIZED}) but admits {@code SUBSCRIBE} (auth-off deployment): the existing
     * constructors pass {@code null}, so the JDK/Netty/sim callers behave as an unauthenticated
     * deployment until the server wiring threads a real authorizer.
     */
    private final WatchAuthorizer authorizer;

    /** The per-connection watch table (session-thread-confined). */
    private final WatchRegistry watchRegistry;

    /** gid -> that shard's core (core {@link #primaryGid} eager; the rest lazy on the first WATCH). */
    private final Map<Integer, FanOutSessionCore> cores = new LinkedHashMap<>();

    /** gid -> that shard's translating decorator (one per core, over the shared transport sink). */
    private final Map<Integer, WatchMultiplexSink> sinks = new LinkedHashMap<>();

    /** gid -> that shard's snapshot filter (narrows the drain-owner watch's catch-up, W5-10/W7-4). */
    private final Map<Integer, FilteringReplaySource> filteringReplaySources = new LinkedHashMap<>();

    /**
     * Max simultaneously-live watches per connection - bounds the live registry against a
     * {@code WATCH_CREATE} flood from one authenticated connection (W8-6 abuse control).
     */
    private static final int MAX_LIVE_WATCHES_PER_CONNECTION = 1024;

    /**
     * Max distinct {@code watch_id}s per connection lifetime - bounds the never-shrinking no-reuse
     * budget ({@code everUsed}; W2-8). A connection that exhausts it must reconnect to reset.
     */
    private static final int MAX_WATCH_IDS_PER_CONNECTION = 16_384;

    /**
     * Cadence (ms) for the governor's time-driven HEALTHY->SLOW evaluation while a session's queue
     * is at/above warn - coarse on purpose (the policy windows are tens of seconds; the busy loop
     * pays at most one {@code long} comparison per iteration). Capped below the warn window so a
     * short test window stays promotable.
     */
    private final long governorEvalCadenceMs;
    private final int warnThreshold;

    private final Queue<Runnable> sessionCommands = new ConcurrentLinkedQueue<>();

    /** Reader-thread-only: the connection type decided by the first inbound frame. */
    private ConnType connType = ConnType.UNDECIDED;

    /**
     * Session-thread-only: whether the shared per-shard drains have been started (by the first
     * authorized watch's seed loop). A subsequent authorized watch rides them and is acknowledged
     * immediately instead of re-seeding the cores.
     */
    private boolean coreSubscribed;

    /**
     * Session-thread-only: the {@link WatchAuthorizer#policyVersion() policy version} the connection's
     * live watches were last (re-)authorized at - the bounded-revocation cursor (W7-7).
     * Initialized at the first watch create; the session loop re-authorizes all live watches whenever
     * the authorizer's version advances past this, then updates it. {@code Long.MIN_VALUE} until the
     * first watch (no watch connection => no re-auth).
     */
    private long lastReauthVersion = Long.MIN_VALUE;

    /**
     * Session-thread-only: the per-shard {@link EdgeFrame.ShardMode}s collected during the first
     * watch's seed loop (each core's {@code SUBSCRIBE_OK} lands here via {@link #onShardCreated}), then
     * drained into the ONE coalesced {@code WATCH_CREATED}. Keyed by gid; cleared before each seed.
     */
    private final Map<Integer, EdgeFrame.ShardMode> pendingShardModes = new LinkedHashMap<>();

    /**
     * Session-thread-only: whether the coalesced {@code WATCH_PROGRESS} has already been emitted this
     * sweep. Several idle shard cores heartbeat in one sweep; the coordinator emits ONE progress frame
     * per live watch, so subsequent idle heartbeats in the same sweep are swallowed. Reset at the top
     * of {@link #sweep}.
     */
    private boolean progressEmittedThisSweep;

    /**
     * The governor identity (cert principal over mTLS; wire edgeId over plaintext). Set by the
     * reader at SUBSCRIBE, read by the session thread - {@code null} until subscribed, and a
     * demotion is impossible before the subscribe command has run.
     */
    private volatile String governorIdentity;

    // Session-thread-only governor-feed state.
    private long lastAckSeen;
    private boolean aboveWarn;
    // MIN_VALUE sentinel compared with `>=` directly, NEVER by subtraction (the C4-A overflow bug).
    private long nextGovernorEvalMillis = Long.MIN_VALUE;

    /** Back-compat constructor (no watch capability): delegates with a {@code null} authorizer. */
    public FanOutConnectionDriver(CommitNotificationSource source, ReplaySource replaySource,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook) {
        this(source, replaySource, sink, config, metrics, clock, governor, edgeIdentity, teardownHook, null);
    }

    /**
     * Single-shard constructor: the N=1 form and the shape every existing caller (JDK
     * {@code FanOutServer}, Netty, sim, tests) uses. Delegates to the multi-shard constructor with a
     * one-entry {@code {0 -> source}} map and the {@link #SINGLE_SHARD} resolver, so one core == the
     * single-shard drain and the output is byte-identical.
     *
     * @param authorizer the authorization gate (W7), or {@code null} when no principal model is wired.
     */
    public FanOutConnectionDriver(CommitNotificationSource source, ReplaySource replaySource,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook, WatchAuthorizer authorizer) {
        this(Map.of(0, Objects.requireNonNull(source, "source")),
                Map.of(0, Objects.requireNonNull(replaySource, "replaySource")),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH, sink, config, metrics,
                clock, governor, edgeIdentity, teardownHook, authorizer);
    }

    /**
     * The multi-shard constructor. Builds core {@code allGids[0]} eagerly (legacy byte-identity + the
     * {@link #session()} accessor); the remaining shard cores are built lazily when the connection
     * turns to a watch connection (the first {@code WATCH_CREATE}).
     *
     * @param sources       gid -> that shard's {@link CommitNotificationSource}; MUST contain every gid
     *                      in {@code allGids} (fail-loud otherwise - a boot/wiring invariant)
     * @param replaySources gid -> that shard's {@link ReplaySource}; MUST contain every gid in
     *                      {@code allGids}
     * @param allGids       the connection's shard set, ascending (StaticShardMap: {@code [0, N)})
     * @param shardResolver resolves a watch target to its covered shard set (target-driven coverage)
     * @param topologyEpoch the server's current topology epoch ({@code ShardMap.epoch()}); every resume
     *                      token is checked/stamped against it (A4). Must be non-zero (0 reserved-illegal)
     * @param authorizer    the authorization gate (W7), or {@code null} when no principal model is wired
     */
    public FanOutConnectionDriver(Map<Integer, CommitNotificationSource> sources,
                                  Map<Integer, ReplaySource> replaySources,
                                  int[] allGids, ShardResolver shardResolver, long topologyEpoch,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook, WatchAuthorizer authorizer) {
        this.sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        this.replaySources = Map.copyOf(Objects.requireNonNull(replaySources, "replaySources"));
        this.allGids = Objects.requireNonNull(allGids, "allGids").clone();
        if (this.allGids.length == 0) {
            throw new IllegalArgumentException("allGids must not be empty");
        }
        this.shardResolver = Objects.requireNonNull(shardResolver, "shardResolver");
        if (topologyEpoch <= WatchCursor.EPOCH_UNSET) {
            throw new IllegalArgumentException(
                    "topologyEpoch must be in [1, 2^63) (0 is reserved-illegal): " + topologyEpoch);
        }
        this.topologyEpoch = topologyEpoch;
        this.transportSink = Objects.requireNonNull(sink, "sink");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.governor = Objects.requireNonNull(governor, "governor");
        this.edgeIdentity = Objects.requireNonNull(edgeIdentity, "edgeIdentity");
        this.teardownHook = Objects.requireNonNull(teardownHook, "teardownHook");
        this.authorizer = authorizer; // nullable => no watch capability => fail-closed
        this.knownGids = new java.util.HashSet<>(this.allGids.length);
        for (int g : this.allGids) {
            // cores.keySet() == allGids by construction (below) requires a source for every gid;
            // fail loud at build (a boot invariant, never a runtime degrade) if one is absent.
            if (!this.sources.containsKey(g) || !this.replaySources.containsKey(g)) {
                throw new IllegalArgumentException("missing source/replaySource for shard gid " + g);
            }
            knownGids.add(g);
        }
        this.primaryGid = this.allGids[0];
        this.watchRegistry = new WatchRegistry();
        // Build the primary-shard core eagerly (legacy path + session() accessor). The rest are
        // materialized lazily when the connection turns to WATCH (materializeAllCores).
        buildCore(primaryGid);
        this.governorEvalCadenceMs = Math.max(1L,
                Math.min(1_000L, governor.config().queueWarnWindowMs() / 2));
        this.warnThreshold = config.queueWarnThresholdFrames();
        this.lastAckSeen = cores.get(primaryGid).lastAckedSeq();
    }

    /**
     * Builds one shard's core + its gid-stamped translating decorator + its snapshot filter. The
     * decorator wraps the shared transport sink (always installed; passthrough until the connection is
     * a watch connection). Idempotent per gid (a second call is a no-op).
     */
    private void buildCore(int g) {
        if (cores.containsKey(g)) {
            return;
        }
        FilteringReplaySource frs = new FilteringReplaySource(replaySources.get(g));
        WatchMultiplexSink sink = new WatchMultiplexSink(transportSink, watchRegistry, g, this);
        FanOutSessionCore core = new FanOutSessionCore(sources.get(g), frs, sink, config, metrics,
                clock, this::onDemotionEvent);
        cores.put(g, core);
        sinks.put(g, sink);
        filteringReplaySources.put(g, frs);
    }

    /** The primary-shard session this driver owns (diagnostics / tests / legacy plane). */
    public FanOutSessionCore session() {
        return cores.get(primaryGid);
    }

    // -----------------------------------------------------------------------
    // Inbound routing (reader thread) - NEVER touches the cores/registry directly
    // -----------------------------------------------------------------------

    /**
     * Routes one decoded inbound frame from the transport. The first frame decides the connection
     * type: {@code SUBSCRIBE} => the legacy fan-out path (unchanged); {@code WATCH_CREATE} => the
     * RFC section 2 multi-shard watch path. Watch-table mutation, seeding, and authorization are posted
     * as session commands so they run on the single session thread (the {@link WatchRegistry} and the
     * cores are not thread-safe). Called only on the transport's reader thread (single-threaded
     * inbound).
     */
    public void onInboundFrame(EdgeFrame frame) {
        switch (connType) {
            case UNDECIDED -> routeFirstFrame(frame);
            case LEGACY -> routeLegacy(frame);
            case WATCH -> routeWatch(frame);
        }
    }

    private void routeFirstFrame(EdgeFrame frame) {
        if (frame instanceof EdgeFrame.Subscribe sub) {
            // A4 topology-epoch gate (etcd ErrCompacted model): a SUBSCRIBE resume token bound to a
            // superseded topology generation is refused STALE_TOPOLOGY (ERROR_CLOSE) - the client's
            // only correct recovery is to drop the cursor and fully re-hydrate. Checked FIRST so a
            // stale token yields STALE_TOPOLOGY (re-hydrate) rather than the N>1 partial-view
            // BAD_SUBSCRIBE below. Epoch 0 is already FRAME_CORRUPT at decode. At v1 static-N (one
            // deploy-time epoch) this never fires - byte-identical.
            if (sub.topologyEpoch() != topologyEpoch) {
                teardownHook.accept(ErrorCode.STALE_TOPOLOGY,
                        "SUBSCRIBE resume token is from a superseded topology epoch "
                                + sub.topologyEpoch() + " (current " + topologyEpoch
                                + "); drop the cursor and re-hydrate from a fresh SUBSCRIBE");
                return;
            }
            // The legacy whole-store SUBSCRIBE (edge hydration) is served from the PRIMARY shard core
            // only (handleSubscribe drives cores.get(primaryGid)); at N>1 that is a SILENT PARTIAL
            // keyspace view. Refuse fail-closed, per connection, unless the operator explicitly accepts
            // the primary-only edge view. Zero data frames flow. The multi-shard WATCH path is complete
            // and is NOT gated here (a WatchCreate-first connection routes below). At N=1 (one shard)
            // this never trips - byte-identical to a non-sharded build.
            //
            // allGids.length equals the cluster shard count N only because every node hosts a replica of
            // ALL N groups (the aggregating-endpoint topology). A future disjoint sharded-edge topology
            // (a node holding only a SUBSET of the groups) would make allGids a subset of N, so this
            // predicate would need revisiting - a legacy SUBSCRIBE could then be whole-keyspace over the
            // node's own shards yet still not the cluster whole store.
            if (allGids.length > 1 && !config.allowPartialShardView()) {
                LOG.info(() -> "edge_fanout_bad_subscribe_refused reason=N>1 legacy SUBSCRIBE without"
                        + " allowPartialShardView identity=" + edgeIdentity + " shards=" + allGids.length);
                teardownHook.accept(ErrorCode.BAD_SUBSCRIBE,
                        "legacy whole-store SUBSCRIBE serves the primary shard only at N>1 (partial "
                              + "keyspace); use a WATCH (multi-shard-complete) or set "
                              + "-Dconfigd.edge.allowPartialShardView=true to accept the primary-only view");
                return;
            }
            connType = ConnType.LEGACY;
            admitLegacySubscribe(sub);
        } else if (frame instanceof EdgeFrame.WatchCreate create) {
            // A WATCH_CREATE-first connection is a watch connection regardless of whether an
            // authorizer is wired: a null authorizer fails CLOSED (every create rejected
            // NOT_AUTHORIZED) rather than tearing the connection down. Flip the primary decorator so
            // the outbound version is 0x02 and the core's frames are translated; the other shard
            // decorators are flipped as they are materialized on the session thread.
            connType = ConnType.WATCH;
            // Admit BEFORE any watch (W8-6): refuse a QUARANTINED/UNHEALTHY identity and set the
            // governor identity so the slow-consumer ladder covers this connection. A reconnect storm
            // cannot dodge its cooldown by dialing WATCH_CREATE-first.
            if (!admitWatchConnection()) {
                return; // refused -> torn down (QUARANTINED)
            }
            sinks.get(primaryGid).setWatchConnection(true);
            sessionCommands.add(() -> handleWatchCreate(create));
        } else {
            teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "expected SUBSCRIBE or WATCH_CREATE first, got " + frame.type());
        }
    }

    /**
     * The legacy SUBSCRIBE admission path. The order is load-bearing: bind the cert identity,
     * <b>authorize the whole-store feed</b>, then run governor admission. A full-store SUBSCRIBE is a
     * streaming read of everything, so it must never expose what a whole-store READ could not; the
     * authorization gate runs BEFORE any session command is posted, so a denied feed emits zero data
     * frames.
     */
    private void admitLegacySubscribe(EdgeFrame.Subscribe sub) {
        EdgeFrame.Subscribe bound = bindIdentity(sub);
        // Authorize the whole-store feed on the verified identity BEFORE governor admission and before
        // any session command is posted. A denied feed never enters the governor ledger and never
        // drains, so not a single data frame flows. A null authorizer means the deployment wired no
        // principal model (auth off), so the feed is admitted exactly as it was before this gate.
        if (!authorizeSubscribe()) {
            teardownHook.accept(ErrorCode.NOT_AUTHORIZED,
                    "subscribe refused: " + edgeIdentity + " lacks whole-store READ");
            return;
        }
        // Admission rules on the BOUND identity (the cert principal - a reconnect storm cannot
        // dodge it by re-dialing). REFUSE -> QUARANTINED + teardown; post-cooldown readmission
        // rebinds resume cursor to 0 so decideMode forces the snapshot.
        governorIdentity = bound.edgeId();
        SlowConsumerGovernor.Admission admission =
                governor.admit(bound.edgeId(), clock.currentTimeMillis());
        switch (admission.decision()) {
            case REFUSE -> {
                teardownHook.accept(ErrorCode.QUARANTINED, "subscribe refused: identity is "
                        + admission.state() + "; cooldown remaining "
                        + admission.cooldownRemainingMs() + " ms");
                return;
            }
            case ALLOW_FORCE_SNAPSHOT -> bound = new EdgeFrame.Subscribe(
                    bound.fullStore(), bound.prefixes(), bound.topologyEpoch(), 0L, -1L, bound.edgeId(),
                    bound.acceptsFiltered());
            case ALLOW -> { /* admit as requested */ }
        }
        EdgeFrame.Subscribe admitted = bound;
        // On a filtered legacy session, narrow the catch-up snapshot to the same prefix-plus-
        // strong-read predicate the drain filters the live tail with (ADR-0045), so a re-snapshot
        // does not stream the whole store to a narrow edge. Set on the session thread, before the
        // core's onSubscribe, mirroring the watch path's setTarget discipline. Off / full-store
        // leaves the predicate null => whole-store passthrough => byte-identical. The legacy plane is
        // the primary-shard core alone (it never materializes the other shard cores).
        sessionCommands.add(() -> {
            if (ServerPrefixFilter.isActive(config, admitted)) {
                filteringReplaySources.get(primaryGid).setPredicate(new ServerPrefixFilter(
                        admitted.prefixes(), config.strongReadPrefixes()).keyPredicate());
            }
            cores.get(primaryGid).onSubscribe(admitted);
        });
    }

    /**
     * Admission for a WATCH-first connection (W8-6). Sets the governor identity (the cert-DN
     * principal) so the slow-consumer demotion-to-quarantine ladder covers watch connections, and
     * refuses a QUARANTINED/UNHEALTHY identity before any watch is created. ALLOW and
     * ALLOW_FORCE_SNAPSHOT both proceed - a watch's catch-up is governed per-watch by the cursor
     * logic (a forced connection-level re-bootstrap is not a multiplex concept).
     *
     * @return true to proceed; false if the connection was refused and torn down
     */
    private boolean admitWatchConnection() {
        governorIdentity = edgeIdentity;
        SlowConsumerGovernor.Admission admission =
                governor.admit(edgeIdentity, clock.currentTimeMillis());
        switch (admission.decision()) {
            case REFUSE -> {
                teardownHook.accept(ErrorCode.QUARANTINED, "watch subscribe refused: identity is "
                        + admission.state() + "; cooldown remaining "
                        + admission.cooldownRemainingMs() + " ms");
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private void routeLegacy(EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.CursorAck ack -> sessionCommands.add(() -> broadcastCursorAck(ack.seq()));
            default -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "unexpected frame for state: " + frame.type());
        }
    }

    private void routeWatch(EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.WatchCreate create -> sessionCommands.add(() -> handleWatchCreate(create));
            case EdgeFrame.WatchCancel cancel -> sessionCommands.add(() -> handleWatchCancel(cancel));
            // CURSOR_ACK is the connection-level flow-control scalar shared by all watches AND all
            // shards (W8-6): broadcast it to every shard core (W10-8 defers per-(watch,shard) acks).
            case EdgeFrame.CursorAck ack -> sessionCommands.add(() -> broadcastCursorAck(ack.seq()));
            // SUBSCRIBE cannot be mixed onto a watch connection (W5-12 keeps the two distinct).
            case EdgeFrame.Subscribe ignored -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "cannot mix SUBSCRIBE on a watch connection");
            default -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "unexpected frame for watch connection: " + frame.type());
        }
    }

    /**
     * Binds the wire SUBSCRIBE's edgeId to the authoritative cert identity (mTLS) - the wire field
     * stays advisory. Over plaintext the wire edgeId is used as-is.
     */
    private EdgeFrame.Subscribe bindIdentity(EdgeFrame.Subscribe wire) {
        if ("plaintext".equals(edgeIdentity)) {
            return wire;
        }
        // Carry acceptsFiltered AND the topologyEpoch through the identity rebind - dropping either
        // would silently disable server-side filtering / lose the resume token's topology binding.
        return new EdgeFrame.Subscribe(wire.fullStore(), wire.prefixes(), wire.topologyEpoch(),
                wire.resumeCursor(), wire.failoverResumeCursor(), edgeIdentity, wire.acceptsFiltered());
    }

    // -----------------------------------------------------------------------
    // Watch handling (session thread - via posted commands; registry-confining)
    // -----------------------------------------------------------------------

    /**
     * Handles one {@code WATCH_CREATE} on the session thread. The order is load-bearing for the
     * security contract - one whole-target authorization decision, all N shard legs served or none:
     * <ol>
     *   <li><b>validate</b> the target (path grammar / scope+kind range, W2-4) -> {@code BAD_SUBSCRIBE}
     *       on a malformed target;</li>
     *   <li><b>authorize</b> the WHOLE target ONCE (W7) -> {@code NOT_AUTHORIZED} on deny, fail-closed
     *       (null/unauthenticated/throwing authorizer all deny). <b>No data frame precedes a reject</b>
     *       (W7-5): NO shard core is seeded/tailed until every check passes;</li>
     *   <li><b>reject id-reuse</b> (W2-8) -> {@code BAD_SUBSCRIBE}; a {@code watch_id} is never
     *       reused;</li>
     *   <li><b>reject a gid-spoofed cursor</b>: a cursor component naming a gid not in the shard
     *       set -> {@code BAD_SUBSCRIBE} (fail-closed); an in-range but irrelevant component is ignored
     *       (the target, never the cursor, sets coverage);</li>
     *   <li><b>register</b> with the target-driven covered set, then either seed all N cores (first
     *       authorized watch) or synthesize the {@code WATCH_CREATED} ack from the current per-shard
     *       frontier (subsequent watch).</li>
     * </ol>
     */
    private void handleWatchCreate(EdgeFrame.WatchCreate create) {
        long watchId = create.watchId();
        byte[] pathBytes = create.path(); // defensive clone (pathUnsafe is wire-package-private)

        // (1) validate target grammar + scope/kind ranges + known flag bits -> BAD_SUBSCRIBE
        String error = WatchTargetValidator.validate(
                create.scope(), create.targetKind(), pathBytes, create.flags());
        if (error != null) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE, error);
            return;
        }
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        WatchTarget target = new WatchTarget(create.scope(), create.targetKind(), path,
                create.fullChainVerify());

        // Snapshot the policy version BEFORE the authorize gate (W7-7 seed correctness): the gate
        // below authorizes against a snapshot at version >= this, so seeding lastReauthVersion from
        // this read guarantees any LATER revoking reload (version > the gate's snapshot) is caught by
        // re-auth. Reading it AFTER authorize would let a reload that lands in the
        // authorize->seed window seed past its own revocation, so the first watch would never be
        // re-checked until the next reload (a narrow but unbounded W7-7 miss). 0 when no authorizer
        // (the create is then rejected below, so the value is unused).
        long versionAtCreate = (authorizer != null) ? authorizer.policyVersion() : 0L;

        // (2) AUTHORIZE the whole target ONCE - the security crux. Zero data frames, and NO shard leg
        // seeded/tailed, precede a reject.
        if (!authorize(target)) {
            rejectWatch(watchId, ErrorCode.NOT_AUTHORIZED,
                    "watch not authorized: principal lacks READ ∧ WATCH over the whole target");
            return;
        }

        // (3) reject id-reuse (W2-8) - a watch_id is never reused (live OR canceled)
        if (watchRegistry.isUsed(watchId)) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "watch_id already used on this connection (no reuse, W2-8): " + watchId);
            return;
        }

        // (3b) per-connection watch caps (W8-6 abuse control) - bound the live registry and the
        // never-shrinking no-reuse budget so one authenticated connection cannot exhaust edge memory
        // with a WATCH_CREATE flood.
        if (watchRegistry.liveCount() >= MAX_LIVE_WATCHES_PER_CONNECTION) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "too many live watches on this connection (max " + MAX_LIVE_WATCHES_PER_CONNECTION + ")");
            return;
        }
        if (watchRegistry.totalUsed() >= MAX_WATCH_IDS_PER_CONNECTION) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "watch_id budget exhausted on this connection (max " + MAX_WATCH_IDS_PER_CONNECTION
                            + "); reconnect to reset");
            return;
        }

        // (3b-topology) A4 topology-epoch gate (etcd ErrCompacted model): a cursor bound to a
        // superseded topology generation is refused STALE_TOPOLOGY (WATCH_CANCELED) - the client must
        // drop the cursor and re-hydrate. Checked BEFORE the gid-spoof guard so a resharding cursor (a
        // cursor from the OLD topology, whose gids may also be out of the new shard set) yields
        // STALE_TOPOLOGY, not the BAD_SUBSCRIBE gid-spoof reject. Epoch 0 is already FRAME_CORRUPT at
        // decode. At v1 static-N (one deploy-time epoch) this never fires - byte-identical.
        if (create.cursor().topologyEpoch() != topologyEpoch) {
            rejectWatch(watchId, ErrorCode.STALE_TOPOLOGY,
                    "watch cursor is from a superseded topology epoch "
                            + create.cursor().topologyEpoch() + " (current " + topologyEpoch
                            + "); drop the cursor and re-hydrate");
            return;
        }

        // (3c) gid-spoof guard: a cursor component naming a gid outside the shard set is
        // unroutable - under static-N it can only be a cursor from a different deployment, and a
        // silent drop would let the client believe it is covered. Fail closed. An in-range-but-
        // irrelevant component is IGNORED (the target sets coverage).
        for (WatchCursor.Component c : create.cursor().components()) {
            if (!knownGids.contains(c.gid())) {
                rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                        "cursor names a shard gid outside this deployment's shard set: "
                                + c.gidUnsigned());
                return;
            }
        }

        // (4) register with the target-driven covered set, then seed all cores or ack a subsequent
        // watch. Coverage is shardIds()-driven (never cursor-inferred): a KEY covers one shard, a
        // PREFIX/FULL scatters to all.
        int[] coveredGids = coveredGidsFor(target);
        watchRegistry.register(new WatchRegistry.WatchEntry(
                watchId, edgeIdentity, Set.of(), target, coveredGids,
                startCursorS(create.cursor(), primaryGid), create.flags()));
        if (!coreSubscribed) {
            coreSubscribed = true;
            // Seed the bounded-revocation cursor (W7-7) from the version read BEFORE the authorize
            // gate (<= the version the gate authorized against), so any later revoking reload advances
            // past it and triggers re-auth. The authorizer is non-null here (the watch passed the gate).
            lastReauthVersion = versionAtCreate;
            materializeAllCores();
            seedAllCores(create, target, coveredGids);
            emitCoalescedWatchCreated(watchId, coveredGids);
        } else {
            // A subsequent watch rides the shared per-shard drains (W8-6): acknowledge immediately,
            // TAIL from the current per-shard frontier over its covered shards. Independent resume
            // positions need a separate connection (the v1 single-shared-drain boundary).
            emitSubsequentWatchCreated(watchId, coveredGids);
        }
    }

    /**
     * Materializes a core for EVERY shard in {@code allGids} (the primary is already built) and flips
     * every decorator to watch translation. All N cores exist from the first watch, so
     * {@code cores.keySet() == allGids}: every covered shard has a drain (completeness by
     * construction) and there is no late-opened leg that could ride a since-revoked authorization -
     * every leg exists from the one authorized create.
     */
    private void materializeAllCores() {
        for (int g : allGids) {
            buildCore(g);
            sinks.get(g).setWatchConnection(true);
        }
    }

    /**
     * Seeds every shard core for the first authorized watch (the drain owner for all shards). Each
     * covered shard is positioned at its demuxed resume component; a shard not covered by this watch
     * still drains (for a later scatter watch) but from its current tip (from-now, TAIL). Each core's
     * {@code onSubscribe} synchronously emits {@code SUBSCRIBE_OK}, collected via
     * {@link #onShardCreated} into {@link #pendingShardModes}.
     */
    private void seedAllCores(EdgeFrame.WatchCreate create, WatchTarget firstTarget, int[] coveredGids) {
        pendingShardModes.clear();
        for (int g : allGids) {
            FanOutSessionCore core = cores.get(g);
            boolean covered = contains(coveredGids, g);
            // The drain-owner's target is BOTH this shard's snapshot content filter
            // (FilteringReplaySource, W5-10/W7-4 - a narrow watch never receives the whole store) AND
            // its WATCH_SNAPSHOT_* tag. A non-covered shard seeds from its tip (TAIL) and so does not
            // snapshot at subscribe; its rare later re-snapshot is filtered to the drain-owner target
            // and empty for a key not on that shard (the v1 shared-drain boundary, per-shard).
            filteringReplaySources.get(g).setTarget(firstTarget);
            sinks.get(g).setSnapshotOwner(create.watchId());
            long coreCursor = coreCursorFor(create, g, covered);
            core.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), coreCursor, -1L, edgeIdentity));
        }
    }

    /**
     * Maps the first watch's request to one shard's core-subscribe cursor (W3-4):
     * <ul>
     *   <li>a shard NOT covered by this watch => TAIL from its current tip (ready for a later scatter
     *       watch; no snapshot);</li>
     *   <li>{@code with_initial_snapshot} => 0 => the covered shard SNAPSHOT_FIRSTs (per-shard, NOT a
     *       consistent cut, W5-4b);</li>
     *   <li>from-now (demuxed component 0, no flag) => TAIL from that shard's frontier - 0 means "from
     *       now per shard", NOT "replay all" (W3-4);</li>
     *   <li>resume (demuxed component > 0) => TAIL if in-window, else a per-shard SNAPSHOT_FIRST.</li>
     * </ul>
     */
    private long coreCursorFor(EdgeFrame.WatchCreate create, int g, boolean covered) {
        if (!covered) {
            return Math.max(0L, sources.get(g).latestSeq());
        }
        if (create.withInitialSnapshot()) {
            return 0L;
        }
        long requested = startCursorS(create.cursor(), g);
        if (requested == 0L) {
            return Math.max(0L, sources.get(g).latestSeq());
        }
        return requested;
    }

    /**
     * Emits the ONE coalesced {@code WATCH_CREATED} for the first watch, narrowed to its covered
     * shards (KEY -> one ShardMode, PREFIX/FULL -> N). The ShardModes were collected from each seeded
     * core's {@code SUBSCRIBE_OK}. Ascending-gid order satisfies the {@code WatchCursor}-style
     * strict-ascending expectation naturally.
     */
    private void emitCoalescedWatchCreated(long watchId, int[] coveredGids) {
        List<EdgeFrame.ShardMode> shards = new ArrayList<>(coveredGids.length);
        for (int g : coveredGids) {
            EdgeFrame.ShardMode sm = pendingShardModes.get(g);
            if (sm == null) {
                // Unreachable: every covered gid is in allGids and was seeded above. Fail loud rather
                // than silently drop a shard from the client's initial mode vector.
                throw new IllegalStateException("no ShardMode collected for covered shard gid " + g);
            }
            shards.add(sm);
        }
        transportSink.offer(new EdgeFrame.WatchCreated(watchId, shards));
    }

    /**
     * Emits the {@code WATCH_CREATED} ack for a subsequent watch: TAIL from the current per-shard
     * frontier over its covered shards (the shared drains are already positioned). At {@code N = 1}
     * this is one {@code ShardMode(0, latest, TAIL)} - byte-identical to the single-shard path.
     */
    private void emitSubsequentWatchCreated(long watchId, int[] coveredGids) {
        List<EdgeFrame.ShardMode> shards = new ArrayList<>(coveredGids.length);
        for (int g : coveredGids) {
            long latest = Math.max(0L, sources.get(g).latestSeq());
            shards.add(new EdgeFrame.ShardMode(g, latest, EdgeFrame.Mode.TAIL));
        }
        transportSink.offer(new EdgeFrame.WatchCreated(watchId, shards));
    }

    /**
     * Handles one {@code WATCH_CANCEL} on the session thread (W5-8). Deregisters the watch (its id
     * stays burned in {@code everUsed} - no reuse, W2-8) and acknowledges it with a per-watch
     * terminal. Because the registry is shared by every shard decorator, one removal instantly stops
     * all N shard legs fanning out to it (multiplex isolation - the connection and sibling watches are
     * unaffected). Canceling an unknown / already-canceled id is a no-op.
     */
    private void handleWatchCancel(EdgeFrame.WatchCancel cancel) {
        WatchRegistry.WatchEntry removed = watchRegistry.cancel(cancel.watchId());
        if (removed != null) {
            // Orderly per-watch close. The closed ErrorCode taxonomy has no dedicated "canceled"
            // code; SERVER_SHUTDOWN (9) is the orderly-close code (a CANCELED code is a v2 taxonomy
            // addition). The connection and other watches survive.
            transportSink.offer(new EdgeFrame.WatchCanceled(
                    cancel.watchId(), ErrorCode.SERVER_SHUTDOWN, null, "canceled"));
        }
    }

    /**
     * The bounded-revocation tick step (W7-7): re-authorizes the connection's live watches iff
     * the authorizer's {@link WatchAuthorizer#policyVersion() policy version} has advanced since the
     * last check, then records the new version. A single volatile-acquire comparison on the common
     * (unchanged-policy) path. Runs only for a watch connection ({@code coreSubscribed} => a non-null
     * authorizer). Extracted as the deterministic test seam (mirrors {@link #drainInboundCommands}):
     * the session loop calls it each iteration, and a test acting as the session thread calls it
     * directly after publishing a new policy version.
     */
    void maybeReauthorizeWatches() {
        if (coreSubscribed && authorizer != null) {
            long policyV = authorizer.policyVersion();
            if (policyV != lastReauthVersion) {
                reauthorizeLiveWatches();
                lastReauthVersion = policyV;
            }
        }
    }

    /**
     * Re-authorizes every live watch against the CURRENT ACL state and force-closes -
     * {@code WATCH_CANCELED(NOT_AUTHORIZED)}, which a driver treats as terminal and does not retry
     * (W7-6) - any whose principal no longer holds {@code READ ∧ WATCH} over its whole target. One
     * logical re-check per watch cuts all N of its shard legs atomically (the registry removal stops
     * every shard decorator fanning out to it). The connection and surviving watches are unaffected
     * (multiplex isolation). Called by {@link #maybeReauthorizeWatches} ONLY when the policy version
     * has advanced. Bounded by the live-watch count x the authorizer's per-call cost; non-blocking on
     * the session thread.
     */
    private void reauthorizeLiveWatches() {
        // Snapshot the live entries - we cancel during iteration. Each entry's principal is
        // edgeIdentity and roles Set.of(), so authorize(target) re-runs the SAME whole-target gate the
        // create used, now against the reloaded ACL snapshot (fail-closed on any throwable).
        for (WatchRegistry.WatchEntry e : List.copyOf(watchRegistry.liveEntries())) {
            if (!authorize(e.target())) {
                transportSink.offer(new EdgeFrame.WatchCanceled(e.watchId(),
                        ErrorCode.NOT_AUTHORIZED, null,
                        "watch revoked: ACL policy no longer grants READ ∧ WATCH over the target"));
                watchRegistry.cancel(e.watchId());
            }
        }
    }

    /** Emits a per-watch terminal reject directly (bypassing translation) - no data frame precedes it. */
    private void rejectWatch(long watchId, ErrorCode code, String message) {
        transportSink.offer(new EdgeFrame.WatchCanceled(watchId, code, null, message));
    }

    /**
     * The watch-authorization gate (W7) - fail-closed on every doubt: a {@code null}
     * authorizer (no capability wired), an unauthenticated principal ({@code "plaintext"}), a
     * {@code false} verdict, or <b>any throwable</b> from the SPI all deny. The asserted-roles set
     * is empty on the cert-DN edge path; the adapter resolves ACL-static / config-bound roles
     * internally.
     */
    private boolean authorize(WatchTarget target) {
        if (authorizer == null || "plaintext".equals(edgeIdentity)) {
            return false;
        }
        try {
            return authorizer.authorizeWatch(edgeIdentity, Set.of(), target);
        } catch (Throwable t) {
            return false; // fail-closed on ANY throwable (W7 / SPI contract)
        }
    }

    /**
     * The legacy full-store SUBSCRIBE authorization gate. Unlike the watch {@link #authorize} gate this
     * does NOT short-circuit the {@code "plaintext"} identity: a {@code null} authorizer (no principal
     * model wired) admits the feed as an unauthenticated deployment, and a wired authorizer evaluates
     * {@code "plaintext"} against its grants like any other principal (normally none => denied). The
     * verified transport identity {@code edgeIdentity} is authoritative - over mTLS it is the cert
     * principal, so an attacker cannot self-assert an authorized {@code edgeId} in the wire frame. A
     * {@code false} verdict or any throwable denies (fail-closed, the same contract as the watch gate).
     */
    private boolean authorizeSubscribe() {
        if (authorizer == null) {
            return true; // auth off: no principal model to evaluate, admit as before this gate existed
        }
        try {
            return authorizer.authorizeSubscribe(edgeIdentity, Set.of());
        } catch (Throwable t) {
            return false; // fail-closed on ANY throwable (same contract as the watch gate)
        }
    }

    /**
     * The resume seq one shard's drain starts from: the component whose {@code gid == g}, or 0 for
     * "from now for that shard" (W3-4) when the vector has no component for {@code g}. At {@code N = 1}
     * with {@code g = 0} this is the single-shard single-component demux.
     */
    private static long startCursorS(WatchCursor cursor, int g) {
        for (WatchCursor.Component c : cursor.components()) {
            if (c.gid() == g) {
                return c.s();
            }
        }
        return 0L;
    }

    /**
     * The target-driven covered shard set (never cursor-inferred, §2.1). Validates it is a non-empty
     * ascending subset of the connection's shard set (a boot/wiring invariant - the server's resolver
     * returns {@code shardFor}/{@code shardIds()} over the same {@code allGids}).
     */
    private int[] coveredGidsFor(WatchTarget target) {
        int[] covered = shardResolver.coveredGids(target);
        if (covered == null || covered.length == 0) {
            throw new IllegalStateException("shard resolver returned no coverage for target " + target);
        }
        for (int g : covered) {
            if (!knownGids.contains(g)) {
                throw new IllegalStateException(
                        "shard resolver returned gid " + g + " outside the connection shard set");
            }
        }
        return covered;
    }

    private static boolean contains(int[] gids, int g) {
        for (int x : gids) {
            if (x == g) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // WatchMultiplexSink.Coordinator - the cross-shard coalescing seam (session thread)
    // -----------------------------------------------------------------------

    @Override
    public void onShardCreated(int gid, long latestSeq, EdgeFrame.Mode mode) {
        // Collected during the first watch's seed loop; drained into the one coalesced WATCH_CREATED.
        pendingShardModes.put(gid, new EdgeFrame.ShardMode(gid, latestSeq, mode));
    }

    @Override
    public boolean onIdleProgress(long serverNowMillis) {
        // Several idle shards heartbeat in one sweep; emit ONE coalesced WATCH_PROGRESS per live watch.
        if (progressEmittedThisSweep) {
            return true; // already advertised this sweep - swallow (accepted, no wire frame)
        }
        progressEmittedThisSweep = true;
        return emitCoalescedProgress(serverNowMillis);
    }

    /**
     * Emits ONE coalesced {@code WATCH_PROGRESS} per live watch: an N-component vector over the
     * watch's covered shards, each component that shard's DRAINED cursor (the W5-7 no-silent-gap
     * clamp is structural - the component IS the drained cursor, never the raw buffer tip). An
     * idle-but-healthy shard's component advances over non-matching commits (the core drains the whole
     * shard); a lagging shard's freezes while {@code server_now} advances - quiet distinguishable from
     * lagging (§6). A refused offer is the shared-fate backpressure (W8-6): return false so the
     * triggering core reads it as transport-gone (the prior close-on-refused-heartbeat behavior).
     */
    private boolean emitCoalescedProgress(long serverNowMillis) {
        for (WatchRegistry.WatchEntry e : watchRegistry.liveEntries()) {
            WatchCursor cursor = progressVector(e.coveredGids());
            if (!transportSink.offer(new EdgeFrame.WatchProgress(e.watchId(), cursor, serverNowMillis))) {
                return false; // shared-fate backpressure (W8-6)
            }
        }
        return true;
    }

    /** The per-covered-shard drained-cursor vector (ascending gid => strict-ascending invariant). */
    private WatchCursor progressVector(int[] coveredGids) {
        List<WatchCursor.Component> comps = new ArrayList<>(coveredGids.length);
        for (int g : coveredGids) {
            long drainedS = Math.max(0L, cores.get(g).cursor());
            comps.add(new WatchCursor.Component(g, drainedS));
        }
        // Stamp the server's current topology epoch (A4) so the client can detect a superseded cursor.
        return new WatchCursor(topologyEpoch, comps);
    }

    /** Broadcasts one connection-level {@code CURSOR_ACK} to every shard core (W8-6 shared scalar). */
    private void broadcastCursorAck(long seq) {
        for (FanOutSessionCore core : cores.values()) {
            core.onCursorAck(seq);
        }
    }

    // -----------------------------------------------------------------------
    // Demotion listener (session thread, inside a core's demote callback)
    // -----------------------------------------------------------------------

    private void onDemotionEvent(DemotionEvent event) {
        String id = governorIdentity;
        if (id == null) {
            return; // defensive: governorIdentity is set on the first SUBSCRIBE / WATCH_CREATE, both
                    // of which precede any drain (and thus any demotion); it cannot be null here.
        }
        SlowConsumerGovernor.ConsumerState state =
                governor.onDemotion(id, event, clock.currentTimeMillis());
        if (state == SlowConsumerGovernor.ConsumerState.QUARANTINED
                || state == SlowConsumerGovernor.ConsumerState.UNHEALTHY) {
            teardownHook.accept(ErrorCode.QUARANTINED, "slow-consumer policy: " + state
                    + " (" + event.reason() + ", cursor=" + event.cursor()
                    + ", lastAckedSeq=" + event.lastAckedSeq() + ")");
        }
    }

    // -----------------------------------------------------------------------
    // Session loop (session thread)
    // -----------------------------------------------------------------------

    /**
     * Drives the session - drain inbound commands, sweep the shard cores, feed the governor, run the
     * revocation poll, back off when idle - until {@code alive} goes false or any core reaches
     * {@code CLOSED}. Requests a teardown in its {@code finally} (idempotent on the transport side).
     * Runs on the transport's dedicated session thread.
     *
     * @param alive liveness gate composed by the transport (connection alive AND server running)
     */
    public void runSessionLoop(BooleanSupplier alive) {
        long idleParkNanos = 0;
        try {
            while (alive.getAsBoolean()) {
                // Drain inbound session commands FIRST so every core/registry mutation happens on
                // THIS thread (single-writer; neither is thread-safe). A posted command is progress.
                boolean drainedCommand = drainInboundCommands();

                int beforeDepth = aggregateInFlight();
                long beforeCursor = aggregateCursor();
                long nowMillis = clock.currentTimeMillis();
                sweep(nowMillis);
                if (anyCoreClosed()) {
                    return;
                }

                // Governor feed: ack progress, queue-pressure EDGES, and the <= 1 Hz time-driven
                // evaluation while warned - all edge/cadence-gated, never per-frame. Aggregated across
                // the shard cores (Σ in-flight; Σ acked as the connection-level progress signal); at
                // N=1 these reduce to the single core, byte-identical.
                String id = governorIdentity;
                if (id != null && alive.getAsBoolean()) {
                    long acked = aggregateAcked();
                    if (acked > lastAckSeen) {
                        lastAckSeen = acked;
                        governor.onAckProgress(id, aggregateCursor(), acked, nowMillis);
                    }
                    boolean above = warnThreshold > 0 && aggregateInFlight() >= warnThreshold;
                    if (above != aboveWarn) {
                        aboveWarn = above;
                        governor.onQueuePressure(id, above, aggregateCursor(), acked, nowMillis);
                    }
                    if (above && nowMillis >= nextGovernorEvalMillis) {
                        nextGovernorEvalMillis = nowMillis + governorEvalCadenceMs;
                        governor.evaluate(id, nowMillis);
                    }
                }

                // Bounded watch revocation (W7-7) - the version-gated re-auth tick step.
                maybeReauthorizeWatches();

                boolean madeProgress = drainedCommand
                        || aggregateCursor() != beforeCursor
                        || aggregateInFlight() != beforeDepth;
                if (madeProgress) {
                    idleParkNanos = 0; // active: immediate re-poll
                } else {
                    long capNanos = config.idlePollMs() * 1_000_000L;
                    idleParkNanos = Math.min(capNanos,
                            idleParkNanos == 0 ? 100_000L : idleParkNanos * 2);
                    LockSupport.parkNanos(idleParkNanos);
                }
            }
        } finally {
            teardownHook.accept(ErrorCode.SERVER_SHUTDOWN, "session ended");
        }
    }

    /**
     * Sweeps the shard cores in ascending-gid order on the session thread - the single-writer
     * discipline that lets the N cores + the shared registry stay lock-free ({@code readSince} is
     * lock-free multi-reader). Resets the per-sweep progress-dedup flag first (a shard core's idle
     * heartbeat during the sweep triggers the one coalesced {@code WATCH_PROGRESS}). Stops at the first
     * core that reaches {@code CLOSED} (a terminal, e.g. GAP_UNRECOVERABLE, already surfaced its
     * per-watch cancels via that shard's decorator); {@link #runSessionLoop} then tears the connection
     * down. Package-private: the deterministic test seam (a test acting as the session thread drains
     * commands, then calls this).
     */
    void sweep(long nowMillis) {
        progressEmittedThisSweep = false;
        for (int g : allGids) {
            FanOutSessionCore core = cores.get(g);
            if (core == null) {
                continue; // not materialized (a legacy connection drives only the primary core)
            }
            core.tick(nowMillis);
            if (core.state() == FanOutSessionCore.SessionState.CLOSED) {
                return; // terminal - stop the sweep; runSessionLoop tears down
            }
        }
    }

    private int aggregateInFlight() {
        int sum = 0;
        for (FanOutSessionCore core : cores.values()) {
            sum += core.inFlightFrames();
        }
        return sum;
    }

    private long aggregateCursor() {
        long sum = 0;
        for (FanOutSessionCore core : cores.values()) {
            sum += core.cursor();
        }
        return sum;
    }

    private long aggregateAcked() {
        long sum = 0;
        for (FanOutSessionCore core : cores.values()) {
            sum += core.lastAckedSeq();
        }
        return sum;
    }

    private boolean anyCoreClosed() {
        for (FanOutSessionCore core : cores.values()) {
            if (core.state() == FanOutSessionCore.SessionState.CLOSED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drains every queued inbound command onto the calling (session) thread. The session loop's first
     * step; also the deterministic test seam (a test acting as the session thread calls this, then
     * {@link #sweep(long)}, to advance without spinning up the real loop thread).
     *
     * @return true iff at least one command ran (the loop's progress signal)
     */
    boolean drainInboundCommands() {
        boolean drained = false;
        Runnable cmd;
        while ((cmd = sessionCommands.poll()) != null) {
            cmd.run();
            drained = true;
        }
        return drained;
    }
}
