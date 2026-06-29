package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigPolicy;
import io.configd.api.PolicyParseException;
import io.configd.api.PolicySerializer;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigMutation;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the config-sourced authorization policy from the reserved {@code _acl/} key subtree into
 * {@link AclService} and keeps it converged as the store changes (O-6 Seam 2a).
 *
 * <h2>Idempotent whole-subtree rebuild</h2>
 * Every (re)load is the SAME operation: re-read the entire {@code _acl/} subtree <b>together with the store
 * version it was scanned at</b> ({@code getPrefixVersioned}), {@link PolicySerializer parse} it, validate
 * reserved names, and {@link AclService#publishConfigPolicy(long, ConfigPolicy) publish} the result tagged
 * with that version. It is NOT a delta apply. Re-reading the same keys yields the same policy, so this
 * converges correctly regardless of how the underlying writes arrived — multi-key policy spread across
 * separate writes, the boot snapshot / WAL-suffix split, follower catch-up via InstallSnapshot, and any
 * overlap between the boot seed and replay. A binding that names a not-yet-loaded role is simply inert
 * until that role's key appears (well-formed-but-incomplete is not an error — see {@link PolicySerializer}).
 *
 * <h2>Fail-closed-to-last-good</h2>
 * If the {@code _acl/} bytes do not parse to a valid policy (or collide with a reserved name), the load is
 * REJECTED: a {@code SEVERE} log + a failure metric, and the <b>current snapshot is kept unchanged</b>. It
 * never deny-alls (lockout) and never allow-alls (open). This mirrors the consistency-preserving abort in
 * {@code ConfigStateMachine.signCommand}, adapted for a policy SOURCE (keep last-good rather than throw).
 *
 * <h2>Threading — non-blocking on the apply/owner thread</h2>
 * {@link #onConfigChange} runs on the Raft apply/owner thread. It first does a cheap O(delta) scan of the
 * mutation list and returns immediately unless an {@code _acl/} key was touched; only then does it run the
 * rebuild. The rebuild scan is <b>O(total store keys)</b> (a full snapshot scan — the HAMT has no ordered
 * prefix iteration), <b>not</b> O(policy size); it runs on the owner thread, so on a very large store an
 * {@code _acl/}-touching apply (or a snapshot install) is a bounded but non-trivial owner-thread cost. In
 * production no {@code _acl/} key is ever written, so the gate always short-circuits and the apply loop
 * carries <b>zero</b> added cost; {@code _acl/} writes are rare admin ops. ({@link #onSnapshotInstalled}
 * rebuilds unconditionally — snapshot installs are rare and carry no per-key signal.) The loader holds no
 * mutable state beyond thread-safe counters; concurrent boot-seed / apply-thread rebuilds are safe AND
 * recency-correct because the publish is <b>version-ordered</b> — a rebuild that scanned an older store
 * version cannot clobber a newer one (see {@link AclService#publishConfigPolicy(long, ConfigPolicy)}).
 * (A secondary {@code _acl/} index / off-owner-thread rebuild for large stores is a 2b item.)
 *
 * <h2>"admin" footgun neutralization (reserved names)</h2>
 * The break-glass root principal's authority is its static {@code acls} grant; a config role could only
 * carve it if root were a SUBJECT of that role. {@code ConfigdServer} now asserts {@code Set.of()} roles
 * for root (so no config role attaches via assertion), and this loader REJECTS a load that binds any role
 * to a reserved principal ({@code root}) or defines a reserved role name ({@code admin}). So no
 * config-loaded role can carve root. (The {@code admin} reservation is forward-compat for 2b's reserved
 * ADMIN role; it is not load-bearing for the proof, since root no longer asserts it.)
 */
final class AclConfigPolicyLoader {

    private static final Logger LOG = Logger.getLogger(AclConfigPolicyLoader.class.getName());

    /** Failure metric: incremented once per rejected (re)load. */
    static final String NAME_POLICY_LOAD_FAILED = "configd.acl.policy.load.failed";
    /** Success metric: incremented once per accepted (re)load (including the boot seed). */
    static final String NAME_POLICY_RELOAD = "configd.acl.policy.reload";

    private final AclService aclService;
    private final VersionedConfigStore store;
    private final Set<String> reservedRoles;
    private final Set<String> reservedPrincipals;
    private final MetricsRegistry.Counter loadFailed;
    private final MetricsRegistry.Counter reloaded;

    /**
     * @param aclService         the ACL service to publish the config-policy snapshot into (non-null)
     * @param store              the primary config store to read the {@code _acl/} subtree from (non-null)
     * @param reservedRoles      role names a config policy may NOT define (e.g. {@code admin}) (non-null)
     * @param reservedPrincipals principals a config policy may NOT bind roles to (e.g. {@code root}) (non-null)
     * @param metricsRegistry    registry for the load-failed / reload counters (non-null)
     */
    AclConfigPolicyLoader(AclService aclService, VersionedConfigStore store,
                          Set<String> reservedRoles, Set<String> reservedPrincipals,
                          MetricsRegistry metricsRegistry) {
        this.aclService = Objects.requireNonNull(aclService, "aclService must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.reservedRoles = Set.copyOf(Objects.requireNonNull(reservedRoles, "reservedRoles must not be null"));
        this.reservedPrincipals =
                Set.copyOf(Objects.requireNonNull(reservedPrincipals, "reservedPrincipals must not be null"));
        Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
        // Eager creation so the first scrape emits "_total 0" (no blind-dashboard series).
        this.loadFailed = metricsRegistry.counter(NAME_POLICY_LOAD_FAILED);
        this.reloaded = metricsRegistry.counter(NAME_POLICY_RELOAD);
    }

    /**
     * Idempotent whole-subtree rebuild: re-read {@code _acl/}, parse + validate, and atomically publish.
     * On any failure, keeps the last-good snapshot (fail-closed; never deny-all / allow-all).
     */
    void rebuild() {
        try {
            // Scan the _acl/ subtree AND the store version it was read at, from ONE consistent snapshot, so
            // the publish below can be ordered monotonically (a stale rebuild never clobbers a newer one).
            VersionedConfigStore.PrefixScan scan = store.getPrefixVersioned(PolicySerializer.ACL_PREFIX);
            Map<String, byte[]> bytes = new HashMap<>(scan.entries().size());
            for (Map.Entry<String, ReadResult> e : scan.entries().entrySet()) {
                bytes.put(e.getKey(), e.getValue().value());
            }
            ConfigPolicy policy = PolicySerializer.parse(bytes);
            validateReserved(policy);
            aclService.publishConfigPolicy(scan.version(), policy);
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

    /**
     * Apply-thread listener. Gated on the delta actually touching {@code _acl/} so the expensive rebuild
     * runs only when policy changed; otherwise returns immediately (zero added apply-loop cost).
     */
    void onConfigChange(List<ConfigMutation> mutations, long version) {
        for (ConfigMutation m : mutations) {
            if (m.key().startsWith(PolicySerializer.ACL_PREFIX)) {
                rebuild();
                return;
            }
        }
    }

    /** Snapshot-install listener: a wholesale store replacement may have changed {@code _acl/}; rebuild. */
    void onSnapshotInstalled() {
        rebuild();
    }

    private void validateReserved(ConfigPolicy policy) {
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
}
