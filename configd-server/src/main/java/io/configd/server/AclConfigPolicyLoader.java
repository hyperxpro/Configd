package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigPolicy;
import io.configd.api.PolicyParseException;
import io.configd.api.PolicySerializer;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigMutation;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the config-sourced authorization policy from the reserved {@code _acl/} key subtree into
 * {@link AclService} and keeps it converged as the store changes.
 *
 * <h2>Idempotent whole-subtree rebuild</h2>
 * Every (re)load is the SAME operation: re-read the entire {@code _acl/} subtree <b>together with the store
 * version it was scanned at</b> ({@code getPrefixVersioned}), {@link PolicySerializer parse} it, validate
 * reserved names, and {@link AclService#publishConfigPolicy(long, ConfigPolicy) publish} the result tagged
 * with that version. It is NOT a delta apply. Re-reading the same keys yields the same policy, so this
 * converges correctly regardless of how the underlying writes arrived - multi-key policy spread across
 * separate writes, the boot snapshot / WAL-suffix split, follower catch-up via InstallSnapshot, and any
 * overlap between the boot seed and replay. A binding that names a not-yet-loaded role is simply inert
 * until that role's key appears (well-formed-but-incomplete is not an error - see {@link PolicySerializer}).
 *
 * <h2>Fail-closed-to-last-good</h2>
 * If the {@code _acl/} bytes do not parse to a valid policy (or collide with a reserved name), the load is
 * REJECTED: a {@code SEVERE} log + a failure metric, and the <b>current snapshot is kept unchanged</b>. It
 * never deny-alls (lockout) and never allow-alls (open). This mirrors the consistency-preserving abort in
 * {@code ConfigStateMachine.signCommand}, adapted for a policy SOURCE (keep last-good rather than throw).
 *
 * <h2>Threading - non-blocking on the apply/owner thread</h2>
 * {@link #onConfigChange} runs on the Raft apply/owner thread. It first does a cheap O(delta) scan of the
 * mutation list and returns immediately unless an {@code _acl/} key was touched; only then does it request a
 * rebuild. The rebuild scan is <b>O(total store keys)</b> (a full snapshot scan - the HAMT has no ordered
 * prefix iteration), <b>not</b> O(policy size); at N=1 it runs on the owner thread, so on a very large store
 * an {@code _acl/}-touching apply (or a snapshot install) is a bounded but non-trivial owner-thread cost. In
 * production no {@code _acl/} key is ever written, so the gate always short-circuits and the apply loop
 * carries <b>zero</b> added cost; {@code _acl/} writes are rare admin ops. ({@link #onSnapshotInstalled}
 * rebuilds unconditionally - snapshot installs are rare and carry no per-key signal.) The loader holds no
 * mutable state beyond thread-safe counters; concurrent boot-seed / apply-thread rebuilds are safe AND
 * recency-correct because the publish is <b>version-ordered</b> - a rebuild that scanned an older store
 * version cannot clobber a newer one (see {@link AclService#publishConfigPolicy(long, ConfigPolicy)}).
 *
 * <h2>Sharding - single-store at N=1, scatter-gather at N&gt;1</h2>
 * {@code _acl/roles/X} / {@code _acl/bindings/Y} are ordinary keys routed by {@code shardFor(scope, key)},
 * so at N&gt;1 they <b>scatter across all N Raft groups</b>. Every node holds every group's store, so a
 * loader that read only the primary group's store would observe ~1/N of the policy - a role/binding/DENY on
 * a non-primary shard would be silently absent (an under-deny authorization bypass) and its apply would not
 * advance {@link AclService#configPolicyVersion()} (so bounded watch revocation would never fire for it).
 * This loader therefore runs in one of two modes, selected by construction:
 * <ul>
 *   <li><b>Single-store (N=1)</b> - byte-identical to the single-shard implementation: one store, listeners on the primary
 *       state machine, {@link #rebuild} inline on the owner/restore/boot thread, publish ordered by the
 *       scanned {@code store.getPrefixVersioned(...)} version.</li>
 *   <li><b>Multi-shard (N&gt;1)</b> - the loader is constructed with every group's store and its listeners
 *       are registered on every group's state machine. A rebuild is a <b>scatter-gather</b>: read
 *       {@code _acl/} from every store (keys are disjoint across stores by deterministic routing, so the
 *       merge never collides) into one map, then the SAME parse / reserved-validate / publish. Every rebuild
 *       runs on ONE dedicated daemon worker thread ({@code configd-acl-policy-loader}), so all rebuilds are
 *       serialized: the last rebuild after the last {@code _acl/} apply reads every shard's latest committed
 *       state and converges to the union, with no cross-shard version vector and no lost update. Because the
 *       per-shard store versions are incomparable, the publish order is a <b>node-local monotonic counter</b>
 *       instead of a store version; that counter is exactly the {@link AclService#configPolicyVersion()} the
 *       per-connection re-authorization consumes on the same node (nothing compares it across nodes).
 *       The apply-thread listener does only the cheap O(delta) gate then hands the scan to the worker, so it
 *       is strictly lighter than the N=1 inline rebuild - fully aligned with the non-blocking listener
 *       contract.</li>
 * </ul>
 *
 * <h2>"admin" footgun neutralization (reserved names)</h2>
 * The break-glass root principal's authority is its static {@code acls} grant; a config role could only
 * carve it if root were a SUBJECT of that role. {@code ConfigdServer} asserts {@code Set.of()} roles
 * for root (so no config role attaches via assertion), and this loader REJECTS a load that binds any role
 * to a reserved principal ({@code root}) or defines a reserved role name ({@code admin}). So no
 * config-loaded role can carve root.
 */
final class AclConfigPolicyLoader implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AclConfigPolicyLoader.class.getName());

    /** Failure metric (canonical name catalogued in {@link ConfigdMetrics}): once per rejected (re)load. */
    static final String NAME_POLICY_LOAD_FAILED = ConfigdMetrics.NAME_ACL_POLICY_LOAD_FAILED;
    /** Success metric (canonical name catalogued in {@link ConfigdMetrics}): once per accepted (re)load. */
    static final String NAME_POLICY_RELOAD = ConfigdMetrics.NAME_ACL_POLICY_RELOAD;

    /**
     * The reserved role name a config policy may NOT define (forward-compat for a built-in admin role) and
     * the reserved principal a config policy may NOT bind config roles to (the break-glass {@code root}).
     * These are the single source of truth for the reserved sets: {@code ConfigdServer} constructs this
     * loader with them, AND the write-time gate ({@code AdminApiHandler} -> {@link
     * #validateAclWrite}) validates against them - so write-time and reload-time reject the IDENTICAL set of
     * reserved names (never two validators that could drift). See {@link #validateReserved}.
     */
    static final String RESERVED_ROLE_ADMIN = "admin";
    static final String RESERVED_PRINCIPAL_ROOT = "root";
    static final Set<String> RESERVED_ROLES = Set.of(RESERVED_ROLE_ADMIN);
    static final Set<String> RESERVED_PRINCIPALS = Set.of(RESERVED_PRINCIPAL_ROOT);

    private final AclService aclService;
    /** The config stores to scan: exactly one at N=1, or one per group (gid-ordered) at N&gt;1. */
    private final List<VersionedConfigStore> stores;
    private final boolean multiShard;
    /**
     * The serialization worker for multi-shard rebuilds ({@code null} at N=1). One daemon thread runs every
     * rebuild in FIFO order so a fresh scatter-gather always follows the applies that enqueued it.
     */
    private final ExecutorService worker;
    /**
     * The node-local publish-ordering counter for multi-shard mode ({@code null} at N=1). Assigned on the
     * worker thread, strictly increasing, so {@link AclService#publishConfigPolicy(long, ConfigPolicy)}'s
     * monotonic guard never spuriously drops a publish (per-shard store versions are incomparable, so a
     * store version cannot serve as the cross-shard order).
     */
    private final AtomicLong nodeLocalVersion;
    private final Set<String> reservedRoles;
    private final Set<String> reservedPrincipals;
    private final MetricsRegistry.Counter loadFailed;
    private final MetricsRegistry.Counter reloaded;

    /**
     * Single-store loader (N=1) - byte-identical to the single-shard implementation: one store, inline
     * rebuild, publish ordered by the scanned store version.
     *
     * @param aclService         the ACL service to publish the config-policy snapshot into (non-null)
     * @param store              the primary config store to read the {@code _acl/} subtree from (non-null)
     * @param reservedRoles      role names a config policy may NOT define (e.g. {@code admin}) (non-null)
     * @param reservedPrincipals principals a config policy may NOT bind roles to (e.g. {@code root}) (non-null)
     * @param metricsRegistry    registry for the load-failed / reload counters (non-null)
     */
    AclConfigPolicyLoader(AclService aclService, VersionedConfigStore store,
                          Set<String> reservedRoles, Set<String> reservedPrincipals,
                          MetricsRegistry metricsRegistry) {
        this(aclService, List.of(Objects.requireNonNull(store, "store must not be null")), false,
                reservedRoles, reservedPrincipals, metricsRegistry);
    }

    /**
     * Multi-shard loader (N&gt;1): scatter-gather {@code _acl/} across every group's store, serialized on a
     * dedicated worker thread, published under a node-local monotonic counter. The caller registers this
     * loader's {@link #onConfigChange} / {@link #onSnapshotInstalled} on EVERY group's state machine so no
     * shard's {@code _acl/} apply is missed.
     *
     * @param perShardStores     every group's config store, one entry per shard (size &gt;= 2; non-null)
     * @param reservedRoles      role names a config policy may NOT define (e.g. {@code admin}) (non-null)
     * @param reservedPrincipals principals a config policy may NOT bind roles to (e.g. {@code root}) (non-null)
     * @param metricsRegistry    registry for the load-failed / reload counters (non-null)
     * @throws IllegalArgumentException if {@code perShardStores} has fewer than 2 stores (use the
     *                                  single-store constructor at N=1)
     */
    AclConfigPolicyLoader(AclService aclService, List<VersionedConfigStore> perShardStores,
                          Set<String> reservedRoles, Set<String> reservedPrincipals,
                          MetricsRegistry metricsRegistry) {
        this(aclService, requireMultiShardStores(perShardStores), true,
                reservedRoles, reservedPrincipals, metricsRegistry);
    }

    private AclConfigPolicyLoader(AclService aclService, List<VersionedConfigStore> stores, boolean multiShard,
                                  Set<String> reservedRoles, Set<String> reservedPrincipals,
                                  MetricsRegistry metricsRegistry) {
        this.aclService = Objects.requireNonNull(aclService, "aclService must not be null");
        this.stores = List.copyOf(stores);
        this.multiShard = multiShard;
        this.reservedRoles = Set.copyOf(Objects.requireNonNull(reservedRoles, "reservedRoles must not be null"));
        this.reservedPrincipals =
                Set.copyOf(Objects.requireNonNull(reservedPrincipals, "reservedPrincipals must not be null"));
        Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
        if (multiShard) {
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "configd-acl-policy-loader");
                t.setDaemon(true);
                return t;
            });
            this.nodeLocalVersion = new AtomicLong();
        } else {
            this.worker = null;
            this.nodeLocalVersion = null;
        }
        // Eager creation so the first scrape emits "_total 0" (no blind-dashboard series).
        this.loadFailed = metricsRegistry.counter(NAME_POLICY_LOAD_FAILED);
        this.reloaded = metricsRegistry.counter(NAME_POLICY_RELOAD);
    }

    private static List<VersionedConfigStore> requireMultiShardStores(List<VersionedConfigStore> stores) {
        Objects.requireNonNull(stores, "perShardStores must not be null");
        if (stores.size() < 2) {
            throw new IllegalArgumentException(
                    "multi-shard loader requires >= 2 stores (use the single-store constructor at N=1), got "
                            + stores.size());
        }
        return stores;
    }

    /**
     * Idempotent whole-subtree rebuild: re-read {@code _acl/}, parse + validate, and atomically publish.
     * On any failure, keeps the last-good snapshot (fail-closed; never deny-all / allow-all). At N=1 the
     * scan is the single store and the publish is ordered by its store version; at N&gt;1 the scan is a
     * scatter-gather over every group's store, published under the node-local monotonic counter (and this
     * method runs only on the serialization worker). The parse / reserved-validate / publish / metrics /
     * fail-closed catch are shared across both modes.
     */
    void rebuild() {
        try {
            Map<String, byte[]> bytes;
            long singleShardVersion = 0L;
            if (multiShard) {
                // Scatter-gather: merge every group's _acl/ keys. Keys are disjoint across stores by
                // deterministic routing (shardFor), so no put ever overwrites another shard's entry.
                bytes = new HashMap<>();
                for (VersionedConfigStore s : stores) {
                    mergeInto(s.getPrefixVersioned(PolicySerializer.ACL_PREFIX), bytes);
                }
            } else {
                // Scan the _acl/ subtree AND the store version it was read at, from ONE consistent snapshot,
                // so the publish below is ordered monotonically (a stale rebuild never clobbers a newer one).
                VersionedConfigStore.PrefixScan scan = stores.get(0).getPrefixVersioned(PolicySerializer.ACL_PREFIX);
                bytes = new HashMap<>(scan.entries().size());
                mergeInto(scan, bytes);
                singleShardVersion = scan.version();
            }
            ConfigPolicy policy = PolicySerializer.parse(bytes);
            validateReserved(policy);
            // N=1: order by the scanned store version. N>1: the per-shard versions are incomparable, so
            // order by a node-local counter bumped only on a successful reload (strictly increasing).
            long version = multiShard ? nodeLocalVersion.incrementAndGet() : singleShardVersion;
            aclService.publishConfigPolicy(version, policy);
            reloaded.increment();
            LOG.info(() -> "ACL config policy loaded: " + policy.roles().size() + " role(s), "
                    + policy.bindings().size() + " binding(s)");
        } catch (RuntimeException e) {
            // FAIL-CLOSED-TO-LAST-GOOD: do not publish; keep whatever AclService currently holds.
            loadFailed.increment();
            LOG.log(Level.SEVERE,
                    "ACL config policy REJECTED — keeping last-good policy (no swap): " + e.getMessage(), e);
        }
    }

    private static void mergeInto(VersionedConfigStore.PrefixScan scan, Map<String, byte[]> out) {
        for (Map.Entry<String, ReadResult> e : scan.entries().entrySet()) {
            out.put(e.getKey(), e.getValue().value());
        }
    }

    /**
     * Requests a rebuild: inline at N=1 (byte-identical to the single-store path); enqueued onto the
     * serialization worker at N&gt;1 so the expensive scatter-gather runs off the apply/restore thread.
     */
    private void requestRebuild() {
        if (multiShard) {
            worker.execute(this::rebuild);
        } else {
            rebuild();
        }
    }

    /**
     * Apply-thread listener. Gated on the delta actually touching {@code _acl/} so a rebuild runs only when
     * policy changed; otherwise returns immediately (zero added apply-loop cost).
     */
    void onConfigChange(List<ConfigMutation> mutations, long version) {
        for (ConfigMutation m : mutations) {
            if (m.key().startsWith(PolicySerializer.ACL_PREFIX)) {
                requestRebuild();
                return;
            }
        }
    }

    /** Snapshot-install listener: a wholesale store replacement may have changed {@code _acl/}; rebuild. */
    void onSnapshotInstalled() {
        requestRebuild();
    }

    /**
     * Seeds the initial policy before the node serves. At N=1 this is an inline {@link #rebuild} (identical
     * to a single-shard boot's call to {@link #rebuild}); at N&gt;1 it is submitted through the worker and awaited, so the policy
     * is seeded before serving AND is serialized with any apply-triggered rebuilds that fired between
     * listener registration and this call (whichever runs last reads the freshest state - convergence). The
     * wait is uninterruptible: a boot must complete its seed regardless of an interrupt.
     */
    void bootSeed() {
        if (multiShard) {
            getUninterruptibly(worker.submit(this::rebuild));
        } else {
            rebuild();
        }
    }

    /**
     * Test/drain seam: block until every enqueued rebuild has completed (no-op at N=1, where rebuilds run
     * inline). A pure FIFO barrier submitted behind any pending rebuilds, so on return the worker has
     * quiesced. Not part of the runtime contract; used by tests to make the asynchronous path deterministic.
     */
    void awaitQuiescence() {
        if (multiShard) {
            getUninterruptibly(worker.submit(() -> { }));
        }
    }

    /**
     * Waits for a worker task uninterruptibly, restoring the interrupt flag on return. A {@code rebuild}
     * task is itself fail-closed and never throws a {@link RuntimeException}; an {@link Error} (or an
     * unexpected throwable) surfaces here as an {@link ExecutionException} and is rethrown so a boot/seed
     * failure is loud rather than swallowed.
     */
    private static void getUninterruptibly(Future<?> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    future.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new IllegalStateException("ACL config policy worker task failed", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Shuts down the multi-shard worker (best-effort drain). A no-op at N=1. The worker thread is a daemon,
     * so an unclosed loader never blocks JVM exit; {@code close()} gives a deterministic drain.
     */
    @Override
    public void close() {
        if (worker != null) {
            worker.shutdown();
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void validateReserved(ConfigPolicy policy) {
        validateReserved(policy, this.reservedRoles, this.reservedPrincipals);
    }

    /**
     * Validates that {@code policy} defines no reserved role name and binds no reserved principal, throwing
     * {@link PolicyParseException} on a violation. {@code static} and shared so the reload path (above) and
     * the write-time gate ({@link #validateAclWrite}) run the IDENTICAL check against the
     * IDENTICAL reserved sets - a single validator, never two that could drift.
     */
    static void validateReserved(ConfigPolicy policy, Set<String> reservedRoles, Set<String> reservedPrincipals) {
        for (String roleName : policy.roles().keySet()) {
            if (reservedRoles.contains(roleName)) {
                throw new PolicyParseException(
                        "reserved role name '" + roleName + "' may not be defined by config policy");
            }
        }
        for (String principal : policy.bindings().keySet()) {
            if (reservedPrincipals.contains(principal)) {
                throw new PolicyParseException(
                        "reserved principal '" + principal + "' may not be bound to roles by config policy");
            }
        }
    }

    /**
     * Write-time validation of a SINGLE reserved {@code _acl/} write: parses the
     * {@code {key: value}} singleton with the EXACT same {@link PolicySerializer#parse} the reload path
     * runs, then applies {@link #validateReserved} against the shared {@link #RESERVED_ROLES} /
     * {@link #RESERVED_PRINCIPALS}. Returns normally iff the write is acceptable policy; throws
     * {@link PolicyParseException} on a malformed shape / role-line / binding grammar or a reserved name
     * ({@code _acl/roles/admin}, {@code _acl/bindings/root}). Because it shares the loader's exact code and
     * sets, a key that passes here can never freeze a later whole-subtree reload. A well-formed-but-
     * incomplete policy (a binding to a not-yet-defined role) parses successfully and is intentionally NOT
     * rejected - single-key validation is exactly the right granularity.
     * <p>
     * The reserved sets are the {@code static} {@link #RESERVED_ROLES} / {@link #RESERVED_PRINCIPALS} -
     * the canonical pair {@code ConfigdServer} also constructs this loader with - so in the production
     * wiring the write-time gate and the instance reload path validate against the IDENTICAL names. (A
     * loader instance built with different sets - only tests do that, with value-equal sets - would not
     * change the write-time validator, which is anchored to the canonical constants.)
     *
     * @param key   the reserved {@code _acl/}-prefixed config key (verbatim, post-strip)
     * @param value the raw config value bytes
     * @throws PolicyParseException if the singleton {@code {key: value}} is not acceptable config policy
     */
    static void validateAclWrite(String key, byte[] value) {
        ConfigPolicy policy = PolicySerializer.parse(Map.of(key, value));
        validateReserved(policy, RESERVED_ROLES, RESERVED_PRINCIPALS);
    }
}
