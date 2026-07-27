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


final class AclConfigPolicyLoader implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AclConfigPolicyLoader.class.getName());

    
    static final String NAME_POLICY_LOAD_FAILED = ConfigdMetrics.NAME_ACL_POLICY_LOAD_FAILED;
    
    static final String NAME_POLICY_RELOAD = ConfigdMetrics.NAME_ACL_POLICY_RELOAD;

    
    static final String RESERVED_ROLE_ADMIN = "admin";
    static final String RESERVED_PRINCIPAL_ROOT = "root";
    static final Set<String> RESERVED_ROLES = Set.of(RESERVED_ROLE_ADMIN);
    static final Set<String> RESERVED_PRINCIPALS = Set.of(RESERVED_PRINCIPAL_ROOT);

    private final AclService aclService;
    
    private final List<VersionedConfigStore> stores;
    private final boolean multiShard;
    
    private final ExecutorService worker;
    
    private final AtomicLong nodeLocalVersion;
    private final Set<String> reservedRoles;
    private final Set<String> reservedPrincipals;
    private final MetricsRegistry.Counter loadFailed;
    private final MetricsRegistry.Counter reloaded;

    
    AclConfigPolicyLoader(AclService aclService, VersionedConfigStore store,
                          Set<String> reservedRoles, Set<String> reservedPrincipals,
                          MetricsRegistry metricsRegistry) {
        this(aclService, List.of(Objects.requireNonNull(store, "store must not be null")), false,
                reservedRoles, reservedPrincipals, metricsRegistry);
    }

    
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

    
    private void requestRebuild() {
        if (multiShard) {
            worker.execute(this::rebuild);
        } else {
            rebuild();
        }
    }

    
    void onConfigChange(List<ConfigMutation> mutations, long version) {
        for (ConfigMutation m : mutations) {
            if (m.key().startsWith(PolicySerializer.ACL_PREFIX)) {
                requestRebuild();
                return;
            }
        }
    }

    
    void onSnapshotInstalled() {
        requestRebuild();
    }

    
    void bootSeed() {
        if (multiShard) {
            getUninterruptibly(worker.submit(this::rebuild));
        } else {
            rebuild();
        }
    }

    
    void awaitQuiescence() {
        if (multiShard) {
            getUninterruptibly(worker.submit(() -> { }));
        }
    }

    
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

    
    static void validateAclWrite(String key, byte[] value) {
        ConfigPolicy policy = PolicySerializer.parse(Map.of(key, value));
        validateReserved(policy, RESERVED_ROLES, RESERVED_PRINCIPALS);
    }
}
