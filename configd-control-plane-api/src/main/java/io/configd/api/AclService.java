package io.configd.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-key-prefix ACL enforcement.
 * Controls which principals may {@code READ}, {@code LIST}, {@code WRITE}, {@code WATCH}, or
 * {@code ADMIN}ister config under specific key prefixes (the v1 capability set - see {@link Permission}).
 * <p>
 * <b>Evaluation model (namespace-model {@code access-control.md} section 4).</b>
 * Authorization is the <b>union of all matching ancestor grants</b> with <b>absolute deny-precedence</b>
 * and <b>default-deny</b> - the Vault model:
 * <pre>
 *   allow = union { rule.caps : rule matches key, rule.effect = ALLOW }
 *   deny  = union { rule.caps : rule matches key, rule.effect = DENY  }
 *   authorized(C)  iff  C in allow  AND  C not in deny
 * </pre>
 * A rule "matches" a key when its prefix is an ancestor of (or equals) the key
 * ({@code key.startsWith(prefix)}). <b>Every</b> matching ancestor contributes - not just the
 * longest. So a {@code READ} grant on {@code "a."} and a {@code WRITE} grant on {@code "a.b."}
 * give a principal {@code READ+WRITE} on {@code "a.b.x"} (the natural hierarchical composition).
 * A {@code DENY} for a capability at <b>any</b> matching ancestor removes that capability regardless
 * of any {@code ALLOW}, including more-specific paths and including {@code ADMIN} - deny is absolute.
 * No matching {@code ALLOW} -> denied.
 * <p>
 * <b>One capability-relationship refinement ({@code access-control.md} section 2.1).</b> Writing
 * the effective set {@code eff = allow - deny}, {@link #isAllowed} decides {@code C in eff} for every
 * capability {@code C} <b>except</b> {@code WATCH}, for which it returns the floored decision
 * {@code WATCH in eff AND READ in eff} - a watch is a streaming read and <b>MUST never expose what a
 * read could not</b> (INV-WATCH-READ). {@code LIST} is independent of {@code READ}, and {@code ADMIN}
 * is <b>not</b> a super-capability; both fall out of plain per-capability membership with no extra logic.
 * <p>
 * This <b>supersedes</b> the historical longest-match-only evaluation (which consulted only the single
 * longest matching prefix - across <i>all</i> principals - and silently dropped ancestor grants, a
 * hierarchy footgun). The two evaluations are <b>byte-identical precisely when the set of stored
 * prefixes forms an antichain</b> (no stored prefix is an ancestor of another): then at most one prefix
 * matches any key, so the union has a single term and equals longest-match. The deployed
 * single-root-grant production config ({@code ""} only) is a trivial antichain, so production decisions
 * are byte-identical. They differ only when ancestor-related prefixes both match a key - which only the
 * tests construct. (Note the precondition is the global prefix set, not "one rule per principal": a
 * longer prefix granted to a <i>different</i> principal could shadow this principal's shorter grant
 * under the old longest-match, but not under the union.)
 * <p>
 * <b>Role layer (additive, dormant in production).</b> Beyond a principal's own per-prefix grants,
 * authorization also unions the grants of the principal's <b>roles</b>. A {@link Role} bundles
 * {@link Policy policies}, each a set of {@link PolicyRule}s ({@code prefix -> allow/deny}); roles are
 * defined via {@link #defineRole}. A principal's effective roles are the union of two additive,
 * empty-by-default sources: the <b>authn-asserted</b> roles passed to
 * {@link #isAllowed(String, Set, String, Permission)} and the <b>ACL-static</b> bindings added via
 * {@link #assignRole}. Each resolved role's matching {@link PolicyRule}s contribute into the
 * <b>same</b> {@code (allow, deny)} accumulators as the own grants, so the identical
 * union / absolute-deny-precedence / default-deny / effective-{@code WATCH} = {@code WATCH} AND
 * {@code READ} rules apply across own and role grants alike - in particular a {@code DENY} from a
 * role (or own grant) is subtracted with absolute precedence over an {@code ALLOW} from a role (or own
 * grant). Both role maps are <b>empty by default</b>; when empty the role layer contributes nothing and
 * {@link #isAllowed} reduces <b>exactly</b> to the historical own-grants-only evaluation (the deployed
 * config defines no roles, so production decisions are byte-identical). The legacy 3-arg
 * {@link #isAllowed(String, String, Permission)} delegates to the 4-arg form with no authn-asserted
 * roles.
 * <p>
 * Thread safety: a {@link ConcurrentSkipListMap} holds the prefix -> (principal -> {@link GrantEntry})
 * map; each {@link GrantEntry} is immutable and is swapped wholesale on {@link #grant}/{@link #deny},
 * so a concurrent {@link #isAllowed} always observes a consistent (allow, deny) pair. The role layer is
 * held in two {@link ConcurrentHashMap}s - role definitions ({@code roleName -> Role}) and per-principal
 * role bindings ({@code principal -> role names}) - storing immutable {@link Role} records and immutable
 * role-name sets, each swapped wholesale on {@link #defineRole}/{@link #assignRole}; {@link #isAllowed}
 * reads them lock-free. Both maps are typically populated once at boot.
 * <p>
 * <b>Config-policy layer (additive, empty in production).</b> Beyond the imperative role layer,
 * authorization also unions a <b>config-sourced</b> {@link ConfigPolicy} - role definitions and
 * principal-to-role bindings loaded by the server from the reserved {@code _acl/} key subtree. It is
 * held behind a <b>single volatile reference</b> ({@link #publishConfigPolicy}); {@link #isAllowed}
 * reads it <b>exactly once</b>, so a concurrent reload (a whole-snapshot swap, never an in-place mutation)
 * is observed entirely-old or entirely-new - never torn. The config layer folds its matching rules into the
 * <b>same</b> {@code (allow, deny)} accumulators (same union / absolute-deny-precedence / default-deny /
 * effective-{@code WATCH} = {@code WATCH} AND {@code READ} rules), so a config {@code DENY} composes with
 * absolute precedence across all layers. It is {@link ConfigPolicy#EMPTY} by default; the deployed config
 * defines no {@code _acl/} keys, so the config layer contributes nothing and decisions are byte-identical.
 */
public final class AclService {

    /**
     * Config-operation capabilities - the v1 capability set ({@code access-control.md} section 2):
     * <ul>
     *   <li>{@code READ}  - read the value at a concrete path ({@code get}).</li>
     *   <li>{@code LIST}  - enumerate the children/descendants of a path ({@code list}); a distinct
     *       privilege because knowing a key <i>exists</i> can be sensitive even without its value.</li>
     *   <li>{@code WRITE} - put or delete at a concrete path.</li>
     *   <li>{@code WATCH} - subscribe to a change stream on a path/subtree.</li>
     *   <li>{@code ADMIN} - manage policies/roles for a subtree; reach the reserved {@code _acl/},
     *       {@code _system/} subtrees (the flat-key prefixes - <b>no</b> leading slash - that the
     *       reserved-prefix gate requires ADMIN for, on every method).</li>
     * </ul>
     * {@code DENY} is <b>not</b> a permission - it is an effect on a rule, expressed via {@link #deny}
     * and subtracted with absolute precedence (see the class doc).
     * <p>
     * <b>Capability relationships ({@code access-control.md} section 2.1).</b>
     * <ul>
     *   <li><b>{@code LIST} is independent of {@code READ}</b>: neither implies the other.
     *       Holding {@code READ} never confers {@code LIST}, nor vice-versa. This falls out of evaluating
     *       each capability by exact membership in the effective set - <b>no special code</b>.</li>
     *   <li><b>{@code WATCH} requires {@code READ}</b> (INV-WATCH-READ): a watch is a streaming read,
     *       so it must <b>never expose what a read could not</b>. {@code WATCH} is its own grantable
     *       capability but is <b>ineffective without {@code READ}</b> over the same target -
     *       {@link #isAllowed} enforces <b>effective-{@code WATCH} = {@code WATCH} AND {@code READ}</b>
     *       for a <b>single key</b>. Because {@link #isAllowed} unions only a key's <i>ancestor</i>
     *       grants it cannot observe a {@code READ} deny on a <i>descendant</i>; a future watch endpoint
     *       must apply this floor over the <b>whole target</b> - per delivered key, or via a whole-target
     *       cover-check (cf. {@code WatchAuthz.authorizeWatch}) - <b>not</b> with a single
     *       {@code isAllowed(p, subtreeRoot, WATCH)} call, which would over-expose a denied
     *       descendant.</li>
     * </ul>
     * {@code ADMIN} is deliberately <b>not</b> a super-capability: an {@code ADMIN}-only principal is
     * authorized for {@code ADMIN} alone, not for {@code READ}/{@code LIST}/{@code WRITE}/{@code WATCH}
     * (no "{@code ADMIN} implies others" relationship is defined).
     */
    public enum Permission { READ, LIST, WRITE, WATCH, ADMIN }

    /**
     * One principal's effective rule at one prefix: the capabilities {@code ALLOW}ed and the
     * capabilities {@code DENY}ed at that prefix. Both sets are immutable; the whole entry is replaced
     * atomically by {@link #grant}/{@link #deny}, never mutated in place.
     */
    private record GrantEntry(Set<Permission> allow, Set<Permission> deny) {
        static final GrantEntry EMPTY = new GrantEntry(Set.of(), Set.of());

        GrantEntry withAllow(Set<Permission> a) {
            return new GrantEntry(a, this.deny);
        }

        GrantEntry withDeny(Set<Permission> d) {
            return new GrantEntry(this.allow, d);
        }
    }

    // prefix -> (principal -> GrantEntry{allow, deny})
    // Sorted in natural (lexicographic) order so the matching ancestor prefixes of a key can be
    // navigated via floorKey()/lowerKey().
    private final ConcurrentSkipListMap<String, ConcurrentHashMap<String, GrantEntry>> acls =
            new ConcurrentSkipListMap<>();

    // roleName -> Role definition. EMPTY by default; typically populated at boot (see defineRole).
    // When empty the role layer contributes nothing and isAllowed is byte-identical to own-grants-only.
    private final ConcurrentHashMap<String, Role> roleDefinitions = new ConcurrentHashMap<>();

    // principal -> ACL-static role names, additive to the authn-asserted roles passed to isAllowed.
    // EMPTY by default; each value is an immutable snapshot swapped wholesale on assignRole.
    private final ConcurrentHashMap<String, Set<String>> principalRoles = new ConcurrentHashMap<>();

    // The config-sourced policy (roles + principal-to-role bindings loaded from the reserved `_acl/`
    // subtree) plus the store version it was derived from, in ONE immutable holder published via a
    // SINGLE AtomicReference swap - the atomic-swap point that fixes the torn-read window (a get() is a
    // volatile-acquire read; isAllowed reads it EXACTLY ONCE so a concurrent reload is never observed
    // half-applied). This is a SEPARATE, additive layer; the static imperative layer above (acls /
    // roleDefinitions / principalRoles) is untouched. EMPTY by default -> the config layer contributes
    // nothing -> byte-identical. The version makes the versioned publish MONOTONIC: an out-of-order rebuild
    // (e.g. a slow boot seed vs a concurrent apply-thread rebuild) carrying an OLDER store version is
    // ignored, so it can never clobber a newer policy with stale state (the swap fixes torn READS; the
    // version fixes out-of-order WRITES).
    private record VersionedConfigPolicy(long version, ConfigPolicy policy) {
        static final VersionedConfigPolicy EMPTY = new VersionedConfigPolicy(Long.MIN_VALUE, ConfigPolicy.EMPTY);
    }

    private final AtomicReference<VersionedConfigPolicy> configPolicyRef =
            new AtomicReference<>(VersionedConfigPolicy.EMPTY);

    /**
     * Grants (ALLOWs) permissions to a principal for a key prefix. Overwrites this principal's prior
     * ALLOW set at this exact prefix; leaves any DENY set at the same prefix untouched (ALLOW and DENY
     * are independent effects).
     *
     * @param prefix      the key prefix (non-null)
     * @param principal   the principal name (non-null)
     * @param permissions the permissions to ALLOW (non-null, non-empty)
     */
    public void grant(String prefix, String principal, Set<Permission> permissions) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");

        Set<Permission> allow = immutable(permissions);
        acls.compute(prefix, (k, principalMap) -> {
            if (principalMap == null) {
                principalMap = new ConcurrentHashMap<>();
            }
            // The inner compute is the atomic swap point: it replaces this principal's whole GrantEntry,
            // preserving any existing DENY (an orthogonal effect). A concurrent isAllowed reader sees
            // either the old or the new entry, never a torn (allow, deny) pair.
            principalMap.compute(principal,
                    (p, existing) -> (existing == null ? GrantEntry.EMPTY : existing).withAllow(allow));
            return principalMap;
        });
    }

    /**
     * Denies permissions to a principal for a key prefix, with <b>absolute precedence</b>: a DENY of a
     * capability at any matching ancestor removes that capability regardless of any ALLOW. This
     * is what makes "grant the subtree, carve out a sensitive child" expressible. Overwrites this
     * principal's prior DENY set at this exact prefix; leaves any ALLOW set at the same prefix
     * untouched.
     *
     * @param prefix      the key prefix (non-null)
     * @param principal   the principal name (non-null)
     * @param permissions the permissions to DENY (non-null, non-empty)
     */
    public void deny(String prefix, String principal, Set<Permission> permissions) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");

        Set<Permission> deny = immutable(permissions);
        acls.compute(prefix, (k, principalMap) -> {
            if (principalMap == null) {
                principalMap = new ConcurrentHashMap<>();
            }
            // Atomic swap (see grant): replaces the whole GrantEntry, preserving any existing ALLOW.
            principalMap.compute(principal,
                    (p, existing) -> (existing == null ? GrantEntry.EMPTY : existing).withDeny(deny));
            return principalMap;
        });
    }

    /**
     * Revokes all of a principal's permissions (both ALLOW and DENY) on a prefix.
     *
     * @param prefix    the key prefix (non-null)
     * @param principal the principal to revoke (non-null)
     */
    public void revoke(String prefix, String principal) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(principal, "principal must not be null");

        acls.computeIfPresent(prefix, (k, principalMap) -> {
            principalMap.remove(principal);
            return principalMap.isEmpty() ? null : principalMap;
        });
    }

    /**
     * Defines (or replaces) a {@link Role}'s grants. Roles are an <b>additive</b> layer over per-prefix
     * grants: {@link #isAllowed(String, Set, String, Permission)} unions a resolved role's matching
     * {@link PolicyRule}s into the same allow/deny accumulators as the principal's own grants. No role
     * is defined in the deployed production config (the role maps are empty, so the role layer is
     * byte-identical), making this dormant there.
     *
     * @param role the role to define, keyed by {@link Role#name()} (non-null)
     */
    public void defineRole(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        roleDefinitions.put(role.name(), role);
    }

    /**
     * Binds a role to a principal as an <b>ACL-static</b> membership, additive to (and unioned with) any
     * authn-asserted roles passed to {@link #isAllowed(String, Set, String, Permission)}. Empty by
     * default; idempotent. The binding only takes effect once the role itself is defined via
     * {@link #defineRole}; an unbound or undefined role name contributes nothing (default-deny).
     *
     * @param principal the principal to bind the role to (non-null)
     * @param roleName  the role name to add to the principal's static role set (non-null)
     */
    public void assignRole(String principal, String roleName) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(roleName, "roleName must not be null");
        // Swap an immutable snapshot wholesale (mirrors the GrantEntry discipline): a concurrent
        // isAllowed reader observes either the old or the new set, never a half-mutated one.
        principalRoles.compute(principal, (k, v) -> {
            Set<String> updated = new HashSet<>(v == null ? Set.of() : v);
            updated.add(roleName);
            return Set.copyOf(updated);
        });
    }

    /**
     * Unconditionally publishes a new {@link ConfigPolicy} snapshot, superseding the current one - the
     * atomic-swap point for config-sourced policy. A concurrent {@link #isAllowed} reads the reference
     * <b>exactly once</b> and therefore observes either the entire old or the entire new policy, never a
     * torn (half-applied) mix. The snapshot is deeply immutable. This overload carries no store version
     * (each call simply supersedes the prior); production reload goes through the
     * <b>version-ordered</b> {@link #publishConfigPolicy(long, ConfigPolicy)} instead, so a stale rebuild
     * cannot clobber a newer policy. Passing {@link ConfigPolicy#EMPTY} clears the config layer (the
     * production default, byte-identical).
     *
     * @param snapshot the new config-policy snapshot (non-null)
     */
    public void publishConfigPolicy(ConfigPolicy snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        VersionedConfigPolicy cur;
        VersionedConfigPolicy next;
        do {
            cur = configPolicyRef.get();
            next = new VersionedConfigPolicy(cur.version() + 1, snapshot);
        } while (!configPolicyRef.compareAndSet(cur, next));
    }

    /**
     * Publishes a config-policy snapshot tagged with the <b>store version it was derived from</b>, applied
     * <b>monotonically</b>: if a snapshot with a version >= {@code derivedFromVersion} has already been
     * published, this call is <b>ignored</b>. This closes the out-of-order-write window - an idempotent
     * whole-subtree rebuild that read an OLDER store snapshot (e.g. a slow boot seed racing a concurrent
     * apply-thread rebuild) cannot clobber a newer policy with stale state. The single volatile swap already
     * prevents torn READS; this prevents stale WRITES. Same exactly-once read contract for
     * {@link #isAllowed}. (Store versions advance monotonically across applies and forward-only snapshot
     * installs, so a higher version always means strictly newer committed state.)
     *
     * @param derivedFromVersion the store version the snapshot was scanned at (see
     *                           {@code VersionedConfigStore.getPrefixVersioned})
     * @param snapshot           the new config-policy snapshot (non-null)
     */
    public void publishConfigPolicy(long derivedFromVersion, ConfigPolicy snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        VersionedConfigPolicy next = new VersionedConfigPolicy(derivedFromVersion, snapshot);
        VersionedConfigPolicy cur;
        do {
            cur = configPolicyRef.get();
            if (derivedFromVersion <= cur.version()) {
                return; // stale-or-equal - a newer (or same-version) policy is already published; ignore
            }
        } while (!configPolicyRef.compareAndSet(cur, next));
    }

    /**
     * Returns the current config-policy snapshot - the last value published via
     * {@link #publishConfigPolicy} (or {@link ConfigPolicy#EMPTY} if none has been published).
     *
     * @return the current config-policy snapshot (never null)
     */
    public ConfigPolicy configPolicy() {
        return configPolicyRef.get().policy();
    }

    /**
     * The current config-policy version - the monotonic store version the live {@link ConfigPolicy}
     * snapshot was derived from (the {@code _acl/} reload version). It is {@link Long#MIN_VALUE} until
     * the first policy is published (the production default, since no {@code _acl/} keys are deployed),
     * and advances on <b>every</b> {@code _acl/} reload (each {@link #publishConfigPolicy} bumps it;
     * the versioned overload installs the store version monotonically). Because the imperative grant
     * layer is boot-static, this version captures all <b>runtime</b> authorization changes.
     * <p>
     * <b>INVARIANT (trigger completeness).</b> This version advances <b>only</b> on a {@code _acl/}
     * config-policy reload, <b>not</b> on the imperative mutators ({@link #grant} / {@link #deny} /
     * {@link #revoke} / {@link #defineRole} / {@link #assignRole}). That is correct <b>only because
     * those mutators are boot-static</b> (in the deployed wiring the sole runtime call is the boot root
     * grant; every runtime ACL change flows through the versioned {@code _acl/} loader). If a future
     * change ever wires a <b>runtime</b> imperative-ACL mutation path (e.g. an admin endpoint calling
     * {@link #deny}/{@link #revoke}), it <b>MUST</b> also advance a version the watch re-authorization
     * observes - extend this version, or have those mutators bump a parallel counter folded into it -
     * otherwise a revocation through that path would be silently missed by bounded watch revocation.
     * <p>
     * It is the trigger for <b>bounded watch revocation</b>: the watch veneer caches the version a live
     * watch was last authorized at and re-authorizes when it advances, force-closing any watch whose
     * principal no longer holds {@code READ} AND {@code WATCH} over its target. When no {@code _acl/}
     * key is touched the version never changes, so re-authorization costs nothing (a single comparison
     * per tick). A single volatile-acquire read; never torn.
     *
     * @return the current config-policy version ({@link Long#MIN_VALUE} if none published)
     */
    public long configPolicyVersion() {
        return configPolicyRef.get().version();
    }

    /**
     * Checks if a principal has the given permission for a key, with <b>no authn-asserted roles</b> - a
     * thin overload of {@link #isAllowed(String, Set, String, Permission)} that supplies
     * {@code Set.of()} for the roles. Existing callers (and the historical evaluation) reach the
     * role-aware path with an empty role set, so with no roles defined/assigned the decision is
     * byte-identical to the own-grants-only evaluation. See the 4-arg overload for the full contract.
     *
     * @param principal  the principal name (non-null)
     * @param key        the config key (non-null)
     * @param permission the required permission (non-null)
     * @return true if the principal is authorized for the permission on the key
     */
    public boolean isAllowed(String principal, String key, Permission permission) {
        return isAllowed(principal, Set.of(), key, permission);
    }

    /**
     * Checks if a principal has the given permission for a key, evaluated as union-of-ancestors with
     * absolute deny-precedence and default-deny over the principal's <b>own per-prefix grants unioned
     * with its role grants</b> (see the class doc).
     * <p>
     * Accumulates capabilities from two additive sources into a <b>single</b> shared
     * {@code (allow, deny)} pair:
     * <ol>
     *   <li><b>Own grants</b> - walks <b>every</b> ancestor prefix matching the key
     *       ({@code floorKey(key)} then {@code lowerKey} back through the sorted prefix set;
     *       {@link #accumulateOwnGrants}).</li>
     *   <li><b>Role grants</b> - resolves the principal's <b>effective roles</b> (the union of the
     *       {@code roles} argument and the {@link #assignRole ACL-static} bindings) against the
     *       {@link #defineRole defined} roles, folding each matching {@link PolicyRule}'s allow/deny into
     *       the same accumulators.</li>
     * </ol>
     * Deny is then subtracted <b>once</b> over the combined set ({@code eff = allow - deny}), so absolute
     * deny-precedence holds <b>through roles</b> as well as own grants. Returns {@code permission in eff}
     * for every capability <b>except</b> {@code WATCH}, for which it returns the floored decision
     * {@code WATCH in eff AND READ in eff} (INV-WATCH-READ - see below). With empty role maps and an
     * empty {@code roles} argument this reduces exactly to the historical own-grants-only evaluation.
     * The walk length is bounded by the number of stored prefixes <= the key plus the principal's role
     * rules; for a control-plane policy set (a small number of grants; exactly one and no roles in the
     * deployed config) this is negligible.
     *
     * @param principal  the principal name (non-null)
     * @param roles      the authn-asserted role names for this request (non-null; may be empty; must
     *                   contain no null element - a null role name would NPE at role lookup; the
     *                   production path is closed by {@code Authenticated}'s defensive {@code Set.copyOf})
     * @param key        the config key (non-null)
     * @param permission the required permission (non-null)
     * @return true if the principal is authorized for the permission on the key
     */
    public boolean isAllowed(String principal, Set<String> roles, String key, Permission permission) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
        EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);

        // (1) The principal's OWN per-prefix grants - the historical union-of-ancestors walk, unchanged.
        accumulateOwnGrants(principal, key, allow, deny);

        // (2) Role grants. Effective roles = authn-asserted (the `roles` argument) union ACL-static
        // bindings (assignRole / principalRoles); both empty by default. Each resolved, DEFINED role's
        // flattened PolicyRules whose literal prefix matches the key fold ALLOW/DENY into the SAME
        // accumulators, so a role ALLOW composes with own ALLOWs and a role DENY is subtracted with the
        // same absolute precedence below. When both sources are empty (the deployed config) this adds
        // nothing.
        Set<String> staticRoles = principalRoles.getOrDefault(principal, Set.of());
        if (!roles.isEmpty() || !staticRoles.isEmpty()) {
            Set<String> effectiveRoles;
            if (staticRoles.isEmpty()) {
                effectiveRoles = roles;            // common path: no static bindings, no extra allocation
            } else if (roles.isEmpty()) {
                effectiveRoles = staticRoles;
            } else {
                effectiveRoles = new HashSet<>(roles);
                effectiveRoles.addAll(staticRoles);
            }
            for (String roleName : effectiveRoles) {
                Role role = roleDefinitions.get(roleName);
                if (role != null) {
                    for (PolicyRule rule : role.rules()) {
                        if (rule.matches(key)) {
                            allow.addAll(rule.allow());
                            deny.addAll(rule.deny());
                        }
                    }
                }
            }
        }

        // (3) Config-sourced role grants - an ADDITIVE layer over the static imperative role layer (2),
        // held behind a single volatile snapshot. Read the reference EXACTLY ONCE (cp) so a concurrent
        // reload is observed all-old or all-new, never torn. Effective config roles = authn-asserted
        // (`roles`) union this principal's CONFIG bindings; each DEFINED config role's matching PolicyRules
        // fold ALLOW/DENY into the SAME accumulators (config ALLOW composes; config DENY keeps the absolute
        // precedence applied below). The outer guard short-circuits on the EMPTY snapshot (production
        // defines no _acl/ keys) -> this block contributes nothing -> byte-identical to the pre-policy-layer
        // own+static-role evaluation. (The config role layer and the imperative role layer (2) are
        // independent additive sub-layers - a name is resolved against the source it was bound through,
        // while an authn-asserted name is resolved against both - see ConfigPolicy.)
        ConfigPolicy cp = this.configPolicyRef.get().policy();   // single acquire read - never torn
        if (!cp.roles().isEmpty() || !cp.bindings().isEmpty()) {
            Set<String> cfgBindings = cp.bindings().getOrDefault(principal, Set.of());
            if (!roles.isEmpty() || !cfgBindings.isEmpty()) {
                Set<String> effectiveConfigRoles;
                if (cfgBindings.isEmpty()) {
                    effectiveConfigRoles = roles;          // common path: no config bindings for principal
                } else if (roles.isEmpty()) {
                    effectiveConfigRoles = cfgBindings;
                } else {
                    effectiveConfigRoles = new HashSet<>(roles);
                    effectiveConfigRoles.addAll(cfgBindings);
                }
                for (String roleName : effectiveConfigRoles) {
                    Role role = cp.roles().get(roleName);
                    if (role != null) {
                        for (PolicyRule rule : role.rules()) {
                            if (rule.matches(key)) {
                                allow.addAll(rule.allow());
                                deny.addAll(rule.deny());
                            }
                        }
                    }
                }
            }
        }

        // Deny has absolute precedence (subtract it ONCE from the combined own+role allow); default-deny
        // falls out of the empty initial allow set. `allow` is now the effective set eff = allow − deny.
        allow.removeAll(deny);

        // INV-WATCH-READ enforcement point. A watch is a streaming read, so effective WATCH is floored
        // by READ: WATCH is authorized only when BOTH WATCH and READ survive in eff. Consequences - a
        // WATCH-without-READ grant yields no watch authz; a deny of READ (or of WATCH) at any matching
        // ancestor (own OR role) also removes effective WATCH. NOTE this floors a SINGLE KEY: the
        // accumulation above unions only `key`'s ANCESTOR grants, so it cannot see a READ/WATCH deny on
        // a DESCENDANT of `key`. A subtree/FULL watch is therefore NOT authorized by one
        // isAllowed(p, subtreeRoot, WATCH) call - the watch-subscribe path must apply this floor over
        // the WHOLE target (per delivered key, or a whole-target cover-check via
        // WatchAuthz.authorizeWatch), else it would over-expose a denied descendant. Every other
        // capability (READ/LIST/WRITE/ADMIN) is decided by exact membership.
        if (permission == Permission.WATCH) {
            return allow.contains(Permission.WATCH) && allow.contains(Permission.READ);
        }
        return allow.contains(permission);
    }

    /**
     * Assembles the principal's <b>complete effective {@link PolicyRule} set</b> - the union of its own
     * per-prefix grants, its role grants, and its config-sourced grants - the rule collection the dormant
     * whole-target predicates {@link #coversTarget} / {@link #authorizesWatch} consume. This is the
     * gate's rule-assembly step: a PREFIX/FULL watch (or {@code list}) authorization needs the
     * <b>whole-subtree</b> cover-check, which {@link #isAllowed} cannot provide because it unions only a
     * single key's <i>ancestor</i> grants and cannot see an interior (descendant) {@code DENY}.
     * <p>
     * <b>The three sources are resolved EXACTLY as {@link #isAllowed} resolves them</b> (so
     * {@code authorizesWatch(effectiveRules(p, roles), key)} agrees with
     * {@code isAllowed(p, roles, key, ...)} on every concrete key):
     * <ol>
     *   <li><b>Own grants</b> - every prefix at which {@code principal} holds a non-empty
     *       {@link GrantEntry} becomes one {@link PolicyRule}{@code (prefix, allow, deny)}. The
     *       <b>complete</b> set is returned (not just ancestors of some target): {@link #coversTarget}
     *       itself filters by the ancestor-or-equal / interior prefix relationship, so the complete set
     *       is both correct and the source of the interior-{@code DENY} term. (Production holds one
     *       prefix {@code ""}; the walk is over a small control-plane policy set - watch creation is
     *       infrequent, never per-event.)</li>
     *   <li><b>Imperative role grants</b> - effective roles = the authn-asserted {@code roles} union the
     *       {@link #assignRole ACL-static} bindings, resolved against the {@link #defineRole defined}
     *       roles; each resolved role contributes <b>all</b> its {@link Role#rules() rules}.</li>
     *   <li><b>Config-sourced role grants</b> - the {@link #configPolicy() config snapshot}, read
     *       <b>once</b> (never torn): effective config roles = the asserted {@code roles} union this
     *       principal's config bindings, resolved against the config roles; each contributes all its
     *       rules.</li>
     * </ol>
     * Each role contributes <b>all</b> its rules (not only those matching some key) because, again,
     * {@link #coversTarget} does the prefix filtering. With empty role maps and an empty {@code roles}
     * argument the result is exactly the principal's own grants (the deployed config), so a watch over
     * the root {@code ""} is authorized iff that principal holds the root {@code READ} AND {@code WATCH}
     * grant.
     *
     * @param principal the principal whose effective rules to assemble (non-null)
     * @param roles     the authn-asserted role names for this request (non-null; may be empty)
     * @return the principal's complete effective rule set (own union role union config); never null, possibly empty.
     *         The returned collection is a fresh, caller-owned snapshot.
     * @see #authorizesWatch(Collection, String)
     * @see #coversTarget(Collection, String, Permission)
     */
    public Collection<PolicyRule> effectiveRules(String principal, Set<String> roles) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(roles, "roles must not be null");

        List<PolicyRule> rules = new ArrayList<>();

        // (1) Own per-prefix grants - the COMPLETE set of this principal's prefixes (coversTarget filters by
        // the ancestor-or-equal / interior prefix relationship, so completeness is what makes the
        // interior-DENY term observable). Mirrors accumulateOwnGrants' source, lifted from one key to all.
        for (Map.Entry<String, ConcurrentHashMap<String, GrantEntry>> e : acls.entrySet()) {
            GrantEntry entry = e.getValue().get(principal);
            if (entry != null && (!entry.allow().isEmpty() || !entry.deny().isEmpty())) {
                rules.add(new PolicyRule(e.getKey(), entry.allow(), entry.deny()));
            }
        }

        // (2) Imperative role grants - effective roles = asserted union ACL-static bindings, resolved against
        // the defined roles (the SAME resolution isAllowed performs; both empty by default -> no contribution).
        Set<String> staticRoles = principalRoles.getOrDefault(principal, Set.of());
        if (!roles.isEmpty() || !staticRoles.isEmpty()) {
            for (String roleName : unionRoleNames(roles, staticRoles)) {
                Role role = roleDefinitions.get(roleName);
                if (role != null) {
                    rules.addAll(role.rules());
                }
            }
        }

        // (3) Config-sourced role grants - read the snapshot EXACTLY ONCE (never torn). Effective config
        // roles = asserted union config bindings, resolved against the config roles (the SAME resolution
        // isAllowed performs; EMPTY snapshot in production -> no contribution -> own-grants only).
        ConfigPolicy cp = this.configPolicyRef.get().policy();
        if (!cp.roles().isEmpty() || !cp.bindings().isEmpty()) {
            Set<String> cfgBindings = cp.bindings().getOrDefault(principal, Set.of());
            if (!roles.isEmpty() || !cfgBindings.isEmpty()) {
                for (String roleName : unionRoleNames(roles, cfgBindings)) {
                    Role role = cp.roles().get(roleName);
                    if (role != null) {
                        rules.addAll(role.rules());
                    }
                }
            }
        }
        return rules;
    }

    /**
     * The role-name union {@link #isAllowed} computes inline at both the imperative and config layers,
     * extracted so {@link #effectiveRules} resolves roles identically. Avoids an allocation when either
     * source is empty (the common paths).
     */
    private static Set<String> unionRoleNames(Set<String> asserted, Set<String> bound) {
        if (bound.isEmpty()) {
            return asserted;       // common path: no static/config bindings
        }
        if (asserted.isEmpty()) {
            return bound;
        }
        Set<String> union = new HashSet<>(asserted);
        union.addAll(bound);
        return union;
    }

    /**
     * Decides whether an effective {@link PolicyRule rule set} grants capability {@code cap} over the
     * <b>entire</b> subtree rooted at {@code target} - the whole-target cover-check a subtree/FULL watch
     * (or {@code list}) authorization needs, and the one a single-key
     * {@link #isAllowed(String, Set, String, Permission)} structurally <b>cannot</b> provide. It is
     * deliberately {@code static}: it reads <b>no</b> instance ACL state, so it can decide only from the
     * rules handed in - the strongest possible proof it changes no existing behavior.
     * <p>
     * Over the literal-prefix model ({@link PolicyRule#matches} is {@code key.startsWith(prefix)}), in
     * <b>one</b> O(#rules) pass:
     * <pre>
     *   coversTarget(rules, target, cap) is true iff
     *       (exists A in rules:  cap in A.allow AND target.startsWith(A.prefix))            (i)
     *     AND
     *       (for all D in rules: NOT (cap in D.deny AND
     *          (target.startsWith(D.prefix) OR D.prefix.startsWith(target))))               (ii)
     * </pre>
     * <ul>
     *   <li><b>(i) an ancestor-or-equal ALLOW carries {@code cap}.</b> {@code target.startsWith(A.prefix)}
     *       means {@code A.prefix} is an ancestor of (or equals) {@code target}, so its grant blankets the
     *       whole subtree. A union of ALLOWs lying strictly <i>below</i> {@code target} <b>cannot</b> cover
     *       it - an unbounded subtree has descendants none of them reach.</li>
     *   <li><b>(ii) no {@code cap}-DENY intersects the subtree.</b> The first disjunct
     *       {@code target.startsWith(D.prefix)} is an <b>ancestor DENY</b> (at or above {@code target}); the
     *       second disjunct <b>{@code D.prefix.startsWith(target)} is the INTERIOR-DENY term</b> - it
     *       matches a DENY at or below {@code target}, its unique contribution (the case the first disjunct
     *       misses) being a DENY strictly <i>below</i> {@code target} that carves a hole inside the subtree.
     *       <b>This interior term is the whole reason a single-key check is insufficient:</b>
     *       {@link #isAllowed} unions only a key's <i>ancestor</i> grants, so (as its own
     *       "NOTE this floors a SINGLE KEY" comment records) it cannot observe a {@code READ}/{@code WATCH}
     *       deny on a <i>descendant</i> of the key. Evaluating at the target root would therefore miss
     *       exactly this hole and over-expose the denied descendant - which the watch authorization spec
     *       forbids.</li>
     * </ul>
     * Computed as two flags over a single pass returning {@code granted && !denied} - no early return,
     * clarity over micro-optimization for a security-crux predicate. Deny-precedence is absolute: (ii) can
     * reject regardless of (i).
     * <p>
     * <b>This method has no call sites yet.</b> The runtime is byte-identical. The whole-target
     * authorization gate that assembles the principal's unioned rule set and calls this is the
     * watch-subscribe path; see the docs-only {@code WatchAuthz#authorizeWatch} for the shape it realizes
     * in the literal-prefix model.
     * <p>
     * <b>The {@code rules} collection is the principal's unioned rule set</b> - own union role union config,
     * the same sources {@link #isAllowed} accumulates - assembled by that gate (each own
     * {@code (allow, deny)} grant at a prefix becomes one {@link PolicyRule}). {@code coversTarget} is
     * <b>source-agnostic</b>: it sees a single flat rule collection and does not care which layer a rule
     * came from.
     * <p>
     * <b>Literal, not segment-aware.</b> Matching is the same raw {@code startsWith} {@link #isAllowed} and
     * {@link PolicyRule#matches} use, so {@code coversTarget} is <b>exactly faithful to the literal model
     * {@code isAllowed} enforces</b> - it never reports a subtree covered when {@code isAllowed} would deny
     * some key in it, i.e. no exposure beyond {@code isAllowed}. Segment-aware matching is deferred,
     * accepted for V1 and pinned by a test; measured against segment-aware intent the deferral cuts both
     * ways - an ALLOW on {@code "team"} over-grants coverage of {@code "teamX"} while a DENY on
     * {@code "team"} over-denies it - so a segment-confusable carve-out stays fail-closed while a
     * segment-confusable grant over-covers, exactly as the deployed flat-key {@code isAllowed} already does.
     * <p>
     * <b>FULL ({@code target == ""}).</b> {@code "".startsWith(A.prefix)} holds <b>only</b> when
     * {@code A.prefix == ""}, so FULL coverage requires a <b>root-prefix ALLOW</b> carrying {@code cap};
     * and at {@code target == ""} the interior disjunct {@code D.prefix.startsWith("")} is true for
     * <b>every</b> deny, so <b>any</b> {@code cap}-DENY anywhere blocks FULL - exactly
     * "root grant AND no {@code cap}-DENY anywhere." In the deployed config only
     * {@code grant("", "root", allOf)} holds the root grant, so full-scope watch authorization falls
     * straight out of the predicate.
     * <p>
     * <b>Cost.</b> O(#rules), one pass, with <b>no</b> store scan and <b>no</b> key enumeration: the
     * subtree is authorized once, not per delivered key.
     *
     * @param rules  the principal's effective (unioned own union role union config) rule set (non-null; the gate is
     *               responsible for non-null elements - a null element NPEs at {@link PolicyRule#allow})
     * @param target the subtree root to cover - a literal key prefix; {@code ""} is FULL/root (non-null)
     * @param cap    the capability that must cover the whole subtree (non-null)
     * @return whether {@code cap} is granted over <b>all</b> of the {@code target} subtree (an
     *         ancestor-or-equal ALLOW carries it and no intersecting DENY removes it)
     * @see #authorizesWatch(Collection, String)
     * @see #isAllowed(String, Set, String, Permission)
     */
    public static boolean coversTarget(Collection<PolicyRule> rules, String target, Permission cap) {
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(cap, "cap must not be null");

        boolean granted = false;   // (i)  an ancestor-or-equal ALLOW carrying cap exists
        boolean denied  = false;   // (ii) an INTERSECTING DENY carrying cap exists (ancestor OR interior)
        for (PolicyRule rule : rules) {
            if (rule.allow().contains(cap) && target.startsWith(rule.prefix())) {
                granted = true;
            }
            if (rule.deny().contains(cap)
                    && (target.startsWith(rule.prefix()) || rule.prefix().startsWith(target))) {
                // ancestor deny (target.startsWith(D.prefix)) OR interior deny (D.prefix.startsWith(target))
                denied = true;
            }
        }
        return granted && !denied;
    }

    /**
     * The INV-WATCH-READ floor of {@link #isAllowed} (effective {@code WATCH} = {@code WATCH} AND
     * {@code READ}) <b>lifted to the whole {@code target} subtree</b>: a watch over a subtree/FULL target
     * is authorized only when the rule set {@linkplain #coversTarget covers} the entire subtree with
     * <b>both</b> {@code READ} <b>and</b> {@code WATCH}. A watch is a streaming read, so it MUST NEVER
     * expose what a read could not; over a subtree that floor must hold at <b>every</b> key, which is
     * exactly what {@link #coversTarget}'s interior-DENY term enforces and a single
     * {@code isAllowed(p, subtreeRoot, WATCH)} cannot. Pure and {@code static}, like
     * {@link #coversTarget} (null-arg validation is delegated to it).
     * <p>
     * <b>This method has no call sites yet.</b> The whole-target watch gate that assembles the rule set
     * and calls this is the watch-subscribe path; cf. the docs-only {@code WatchAuthz#authorizeWatch}.
     *
     * @param rules  the principal's effective (unioned own union role union config) rule set (non-null)
     * @param target the subtree root to authorize a watch over; {@code ""} is FULL/root (non-null)
     * @return whether the principal may watch <b>all</b> of the {@code target} subtree - {@code READ}
     *         <b>and</b> {@code WATCH} both cover it
     * @see #coversTarget(Collection, String, Permission)
     */
    public static boolean authorizesWatch(Collection<PolicyRule> rules, String target) {
        return coversTarget(rules, target, Permission.READ)
                && coversTarget(rules, target, Permission.WATCH);
    }

    /**
     * Accumulates the principal's OWN per-prefix grants for {@code key} into {@code allow}/{@code deny}:
     * the union of <b>every</b> matching ancestor prefix (not longest-match-only). This is the historical
     * {@link #isAllowed} walk, extracted verbatim so the own-grants contribution is unchanged; the role
     * layer folds into the same two accumulators afterward.
     */
    private void accumulateOwnGrants(String principal, String key,
                                     EnumSet<Permission> allow, EnumSet<Permission> deny) {
        // Union ALL matching ancestor grants (not longest-match-only). floorKey(key) is the greatest
        // prefix <= key; walking back with lowerKey visits every stored prefix <= key in descending
        // order, and key.startsWith(candidate) selects the ones that are ancestors of the key.
        String candidate = acls.floorKey(key);
        while (candidate != null) {
            if (key.startsWith(candidate)) {
                ConcurrentHashMap<String, GrantEntry> principalMap = acls.get(candidate);
                if (principalMap != null) {
                    GrantEntry entry = principalMap.get(principal);
                    if (entry != null) {
                        allow.addAll(entry.allow());
                        deny.addAll(entry.deny());
                    }
                }
            }
            candidate = acls.lowerKey(candidate);
        }
    }

    /**
     * Defensive, immutable copy of a permission set, via {@link EnumSet#copyOf} as the prior contract did.
     * Note {@code EnumSet.copyOf} throws on an empty <i>non-</i>{@code EnumSet} collection but accepts an
     * empty {@code EnumSet} (stored as a no-op empty set - harmless: an empty allow grants nothing, an
     * empty deny denies nothing); so the {@code non-empty} javadoc on {@link #grant}/{@link #deny} is a
     * caller expectation, not a guarantee enforced for that one input shape.
     */
    private static Set<Permission> immutable(Set<Permission> permissions) {
        return Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }
}
