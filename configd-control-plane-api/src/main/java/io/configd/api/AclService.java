package io.configd.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-key-prefix ACL enforcement.
 * Controls which principals may {@code READ}, {@code LIST}, {@code WRITE}, {@code WATCH}, or
 * {@code ADMIN}ister config under specific key prefixes (the v1 capability set — see {@link Permission}).
 * <p>
 * <b>Evaluation model (RFC §01 A5-4; namespace-model {@code access-control.md} §4; DL-N-08).</b>
 * Authorization is the <b>union of all matching ancestor grants</b> with <b>absolute deny-precedence</b>
 * and <b>default-deny</b> — the Vault model:
 * <pre>
 *   allow = ⋃ { rule.caps : rule matches key, rule.effect = ALLOW }
 *   deny  = ⋃ { rule.caps : rule matches key, rule.effect = DENY  }
 *   authorized(C)  ⟺  C ∈ allow  AND  C ∉ deny
 * </pre>
 * A rule "matches" a key when its prefix is an ancestor of (or equals) the key
 * ({@code key.startsWith(prefix)}). <b>Every</b> matching ancestor contributes — not just the
 * longest. So a {@code READ} grant on {@code "a."} and a {@code WRITE} grant on {@code "a.b."}
 * give a principal {@code READ+WRITE} on {@code "a.b.x"} (the natural hierarchical composition).
 * A {@code DENY} for a capability at <b>any</b> matching ancestor removes that capability regardless
 * of any {@code ALLOW}, including more-specific paths and including {@code ADMIN} — deny is absolute.
 * No matching {@code ALLOW} ⇒ denied.
 * <p>
 * <b>One capability-relationship refinement (RFC §01 A5-2; {@code access-control.md} §2.1).</b> Writing
 * the effective set {@code eff = allow − deny}, {@link #isAllowed} decides {@code C ∈ eff} for every
 * capability {@code C} <b>except</b> {@code WATCH}, for which it returns the floored decision
 * {@code WATCH ∈ eff ∧ READ ∈ eff} — a watch is a streaming read and <b>MUST never expose what a read
 * could not</b> (INV-WATCH-READ). {@code LIST} is independent of {@code READ}, and {@code ADMIN} is
 * <b>not</b> a super-capability; both fall out of plain per-capability membership with no extra logic.
 * <p>
 * This <b>supersedes</b> the historical longest-match-only evaluation (which consulted only the single
 * longest matching prefix — across <i>all</i> principals — and silently dropped ancestor grants, a
 * hierarchy footgun). The two evaluations are <b>byte-identical precisely when the set of stored
 * prefixes forms an antichain</b> (no stored prefix is an ancestor of another): then at most one prefix
 * matches any key, so the union has a single term and equals longest-match. The deployed
 * single-root-grant production config ({@code ""} only) is a trivial antichain, so production decisions
 * are byte-identical. They differ only when ancestor-related prefixes both match a key — which only the
 * tests construct. (Note the precondition is the global prefix set, not "one rule per principal": a
 * longer prefix granted to a <i>different</i> principal could shadow this principal's shorter grant
 * under the old longest-match, but not under the union.)
 * <p>
 * <b>Role layer (RFC §01 A5-3; O-6 Seam 1 — additive, dormant in production).</b> Beyond a principal's
 * own per-prefix grants, authorization also unions the grants of the principal's <b>roles</b>. A
 * {@link Role} bundles {@link Policy policies}, each a set of {@link PolicyRule}s
 * ({@code prefix → allow/deny}); roles are defined via {@link #defineRole}. A principal's effective
 * roles are the <b>union</b> of two additive, empty-by-default sources: the <b>authn-asserted</b> roles
 * passed to {@link #isAllowed(String, Set, String, Permission)} and the <b>ACL-static</b> bindings added
 * via {@link #assignRole}. Each resolved role's matching {@link PolicyRule}s contribute into the
 * <b>same</b> {@code (allow, deny)} accumulators as the own grants, so the identical
 * union / absolute-deny-precedence / default-deny / effective-{@code WATCH} = {@code WATCH} ∧
 * {@code READ} rules (A5-4) apply across own and role grants alike — in particular a {@code DENY} from a
 * role (or own grant) is subtracted with absolute precedence over an {@code ALLOW} from a role (or own
 * grant). Both role maps are <b>empty by default</b>; when empty the role layer contributes nothing and
 * {@link #isAllowed} reduces <b>exactly</b> to the historical own-grants-only evaluation (the deployed
 * config defines no roles, so production decisions are byte-identical). The legacy 3-arg
 * {@link #isAllowed(String, String, Permission)} delegates to the 4-arg form with no authn-asserted
 * roles.
 * <p>
 * Thread safety: a {@link ConcurrentSkipListMap} holds the prefix → (principal → {@link GrantEntry})
 * map; each {@link GrantEntry} is immutable and is swapped wholesale on {@link #grant}/{@link #deny},
 * so a concurrent {@link #isAllowed} always observes a consistent (allow, deny) pair. The role layer is
 * held in two {@link ConcurrentHashMap}s — role definitions ({@code roleName → Role}) and per-principal
 * role bindings ({@code principal → role names}) — storing immutable {@link Role} records and immutable
 * role-name sets, each swapped wholesale on {@link #defineRole}/{@link #assignRole}; {@link #isAllowed}
 * reads them lock-free. Both maps are typically populated once at boot.
 * <p>
 * <b>Config-policy layer (RFC §01 A5-3; O-6 Seam 2a — additive, empty in production).</b> Beyond the
 * imperative role layer, authorization also unions a <b>config-sourced</b> {@link ConfigPolicy} — role
 * definitions and principal→role bindings loaded by the server from the reserved {@code _acl/} key subtree.
 * It is held behind a <b>single volatile reference</b> ({@link #publishConfigPolicy}); {@link #isAllowed}
 * reads it <b>exactly once</b>, so a concurrent reload (a whole-snapshot swap, never an in-place mutation)
 * is observed entirely-old or entirely-new — never torn. The config layer folds its matching rules into the
 * <b>same</b> {@code (allow, deny)} accumulators (same union / absolute-deny-precedence / default-deny /
 * effective-{@code WATCH} = {@code WATCH} ∧ {@code READ} rules), so a config {@code DENY} composes with
 * absolute precedence across all layers. It is {@link ConfigPolicy#EMPTY} by default; the deployed config
 * defines no {@code _acl/} keys, so the config layer contributes nothing and decisions are byte-identical.
 */
public final class AclService {

    /**
     * Config-operation capabilities — the v1 capability set (RFC §01 A5-1;
     * {@code access-control.md} §2), declared in RFC A5-1 order:
     * <ul>
     *   <li>{@code READ}  — read the value at a concrete path ({@code get}).</li>
     *   <li>{@code LIST}  — enumerate the children/descendants of a path ({@code list}); a distinct
     *       privilege because knowing a key <i>exists</i> can be sensitive even without its value.</li>
     *   <li>{@code WRITE} — put or delete at a concrete path.</li>
     *   <li>{@code WATCH} — subscribe to a change stream on a path/subtree.</li>
     *   <li>{@code ADMIN} — manage policies/roles for a subtree; reach the reserved {@code /_acl/},
     *       {@code /_system/} subtrees.</li>
     * </ul>
     * {@code DENY} is <b>not</b> a permission — it is an effect on a rule, expressed via {@link #deny}
     * and subtracted with absolute precedence (see the class doc).
     * <p>
     * <b>Capability relationships (RFC A5-2 — the only normative relationships; both honored here).</b>
     * <ul>
     *   <li><b>{@code LIST} is independent of {@code READ}</b> (R-CAP-1): neither implies the other.
     *       Holding {@code READ} never confers {@code LIST}, nor vice-versa. This falls out of evaluating
     *       each capability by exact membership in the effective set — <b>no special code</b>.</li>
     *   <li><b>{@code WATCH} requires {@code READ}</b> (R-CAP-2 / INV-WATCH-READ): a watch is a streaming
     *       read, so it must <b>never expose what a read could not</b>. {@code WATCH} is its own grantable
     *       capability but is <b>ineffective without {@code READ}</b> over the same target —
     *       {@link #isAllowed} enforces <b>effective-{@code WATCH} = {@code WATCH} ∧ {@code READ}</b> for a
     *       <b>single key</b>. Because {@link #isAllowed} unions only a key's <i>ancestor</i> grants it
     *       cannot observe a {@code READ} deny on a <i>descendant</i>; a future watch endpoint must apply
     *       this floor over the <b>whole target</b> — per delivered key, or via a whole-target cover-check
     *       (cf. {@code WatchAuthz.authorizeWatch}, RFC A6-2/A6-3) — <b>not</b> with a single
     *       {@code isAllowed(p, subtreeRoot, WATCH)} call, which would over-expose a denied descendant.</li>
     * </ul>
     * {@code ADMIN} is deliberately <b>not</b> a super-capability: an {@code ADMIN}-only principal is
     * authorized for {@code ADMIN} alone, not for {@code READ}/{@code LIST}/{@code WRITE}/{@code WATCH}
     * (RFC A5-2 names no "{@code ADMIN} implies others" relationship).
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

    // O-6 Seam 2a: the CONFIG-SOURCED policy (roles + principal→role bindings loaded from the reserved
    // `_acl/` subtree) plus the store version it was derived from, in ONE immutable holder published via a
    // SINGLE AtomicReference swap — the atomic-swap point that fixes the torn-read window (a get() is a
    // volatile-acquire read; isAllowed reads it EXACTLY ONCE so a concurrent reload is never observed
    // half-applied). This is a SEPARATE, additive layer; the static imperative layer above (acls /
    // roleDefinitions / principalRoles) is untouched. EMPTY by default ⇒ the config layer contributes
    // nothing ⇒ byte-identical. The version makes the versioned publish MONOTONIC: an out-of-order rebuild
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
     * are independent effects per A5-4).
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
     * capability at any matching ancestor removes that capability regardless of any ALLOW (A5-4). This
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
     * {@link PolicyRule}s into the same allow/deny accumulators as the principal's own grants (RFC §01
     * A5-3/A5-4). No role is defined in the deployed production config (the role maps are empty, so
     * Seam 1 is byte-identical), making this dormant there.
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
     * Unconditionally publishes a new {@link ConfigPolicy} snapshot, superseding the current one — the
     * atomic-swap point for config-sourced policy (O-6 Seam 2a). A concurrent {@link #isAllowed} reads the
     * reference <b>exactly once</b> and therefore observes either the entire old or the entire new policy,
     * never a torn (half-applied) mix. The snapshot is deeply immutable. This overload carries no store
     * version (each call simply supersedes the prior); production reload goes through the
     * <b>version-ordered</b> {@link #publishConfigPolicy(long, ConfigPolicy)} instead, so a stale rebuild
     * cannot clobber a newer policy. Passing {@link ConfigPolicy#EMPTY} clears the config layer (the
     * production default ⇒ byte-identical).
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
     * <b>monotonically</b>: if a snapshot with a version &ge; {@code derivedFromVersion} has already been
     * published, this call is <b>ignored</b> (O-6 Seam 2a). This closes the out-of-order-write window — an
     * idempotent whole-subtree rebuild that read an OLDER store snapshot (e.g. a slow boot seed racing a
     * concurrent apply-thread rebuild) cannot clobber a newer policy with stale state. The single volatile
     * swap already prevents torn READS; this prevents stale WRITES. Same exactly-once read contract for
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
                return; // stale-or-equal — a newer (or same-version) policy is already published; ignore
            }
        } while (!configPolicyRef.compareAndSet(cur, next));
    }

    /**
     * Returns the current config-policy snapshot — the last value published via
     * {@link #publishConfigPolicy} (or {@link ConfigPolicy#EMPTY} if none has been published).
     *
     * @return the current config-policy snapshot (never null)
     */
    public ConfigPolicy configPolicy() {
        return configPolicyRef.get().policy();
    }

    /**
     * Checks if a principal has the given permission for a key, with <b>no authn-asserted roles</b> — a
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
     * with its role grants</b> (A5-4; see the class doc).
     * <p>
     * Accumulates capabilities from two additive sources into a <b>single</b> shared
     * {@code (allow, deny)} pair:
     * <ol>
     *   <li><b>Own grants</b> — walks <b>every</b> ancestor prefix matching the key
     *       ({@code floorKey(key)} then {@code lowerKey} back through the sorted prefix set;
     *       {@link #accumulateOwnGrants}).</li>
     *   <li><b>Role grants</b> — resolves the principal's <b>effective roles</b> (the union of the
     *       {@code roles} argument and the {@link #assignRole ACL-static} bindings) against the
     *       {@link #defineRole defined} roles, folding each matching {@link PolicyRule}'s allow/deny into
     *       the same accumulators.</li>
     * </ol>
     * Deny is then subtracted <b>once</b> over the combined set ({@code eff = allow − deny}), so absolute
     * deny-precedence holds <b>through roles</b> as well as own grants. Returns {@code permission ∈ eff}
     * for every capability <b>except</b> {@code WATCH}, for which it returns the floored decision
     * {@code WATCH ∈ eff ∧ READ ∈ eff} (INV-WATCH-READ, RFC A5-2 — see below). With empty role maps and
     * an empty {@code roles} argument this reduces exactly to the historical own-grants-only evaluation.
     * The walk length is bounded by the number of stored prefixes ≤ the key plus the principal's role
     * rules; for a control-plane policy set (a small number of grants; exactly one and no roles in the
     * deployed config) this is negligible.
     *
     * @param principal  the principal name (non-null)
     * @param roles      the authn-asserted role names for this request (non-null; may be empty; must
     *                   contain no null element — a null role name would NPE at role lookup; the
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

        // (1) The principal's OWN per-prefix grants — the historical union-of-ancestors walk, unchanged.
        accumulateOwnGrants(principal, key, allow, deny);

        // (2) Role grants. Effective roles = authn-asserted (the `roles` argument) ∪ ACL-static bindings
        // (assignRole / principalRoles); both empty by default. Each resolved, DEFINED role's flattened
        // PolicyRules whose literal prefix matches the key fold ALLOW/DENY into the SAME accumulators, so
        // a role ALLOW composes with own ALLOWs and a role DENY is subtracted with the same absolute
        // precedence below. When both sources are empty (the deployed config) this adds nothing.
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

        // (3) Config-sourced role grants (O-6 Seam 2a) — an ADDITIVE layer over the static imperative role
        // layer (2), held behind a single volatile snapshot. Read the reference EXACTLY ONCE (cp) so a
        // concurrent reload is observed all-old or all-new, never torn. Effective config roles =
        // authn-asserted (`roles`) ∪ this principal's CONFIG bindings; each DEFINED config role's matching
        // PolicyRules fold ALLOW/DENY into the SAME accumulators (config ALLOW composes; config DENY keeps
        // the absolute precedence applied below). The outer guard short-circuits on the EMPTY snapshot
        // (production defines no _acl/ keys) ⇒ this block contributes nothing ⇒ byte-identical to the
        // pre-Seam-2a own+static-role evaluation. (The config role layer and the imperative role layer (2)
        // are independent additive sub-layers — a name is resolved against the source it was bound through,
        // while an authn-asserted name is resolved against both — see ConfigPolicy / the increment-5 doc.)
        ConfigPolicy cp = this.configPolicyRef.get().policy();   // single acquire read — never torn
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

        // INV-WATCH-READ enforcement point (RFC §01 A5-2 R-CAP-2 / access-control.md §2.1,§6; DL-O3-03).
        // A watch is a streaming read, so effective WATCH is floored by READ: WATCH is authorized only
        // when BOTH WATCH and READ survive in eff. Consequences — a WATCH-without-READ grant yields no
        // watch authz; a deny of READ (or of WATCH) at any matching ancestor (own OR role) also removes
        // effective WATCH. NOTE this floors a SINGLE KEY: the accumulation above unions only `key`'s
        // ANCESTOR grants, so it cannot see a READ/WATCH deny on a DESCENDANT of `key`. A subtree/FULL
        // watch is therefore NOT authorized by one isAllowed(p, subtreeRoot, WATCH) call — the O-5
        // subscribe path must apply this floor over the WHOLE target (per delivered key, or a
        // whole-target cover-check à la WatchAuthz.authorizeWatch / RFC A6-2/A6-3), else it would
        // over-expose a denied descendant. Every other capability (READ/LIST/WRITE/ADMIN) is decided by
        // exact membership.
        if (permission == Permission.WATCH) {
            return allow.contains(Permission.WATCH) && allow.contains(Permission.READ);
        }
        return allow.contains(permission);
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
     * empty {@code EnumSet} (stored as a no-op empty set — harmless: an empty allow grants nothing, an
     * empty deny denies nothing); so the {@code non-empty} javadoc on {@link #grant}/{@link #deny} is a
     * caller expectation, not a guarantee enforced for that one input shape.
     */
    private static Set<Permission> immutable(Set<Permission> permissions) {
        return Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }
}
