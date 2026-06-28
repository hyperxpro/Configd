package io.configd.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Per-key-prefix ACL enforcement.
 * Controls which principals can read/write config under specific prefixes.
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
 * Thread safety: a {@link ConcurrentSkipListMap} holds the prefix → (principal → {@link GrantEntry})
 * map; each {@link GrantEntry} is immutable and is swapped wholesale on {@link #grant}/{@link #deny},
 * so a concurrent {@link #isAllowed} always observes a consistent (allow, deny) pair.
 */
public final class AclService {

    /**
     * Permission types for config operations. The {@code DENY} effect is expressed via {@link #deny}
     * (an effect on a rule), not a permission — {@code LIST}/{@code WATCH} are deliberately out of
     * scope for this capability set.
     */
    public enum Permission { READ, WRITE, ADMIN }

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
     * Checks if a principal has the given permission for a key, evaluated as union-of-ancestors with
     * absolute deny-precedence and default-deny (A5-4; see the class doc).
     * <p>
     * Walks <b>every</b> ancestor prefix matching the key — {@code floorKey(key)} then {@code lowerKey}
     * back through the sorted prefix set — accumulating the ALLOW and DENY capability unions, then
     * returns {@code permission ∈ allow ∧ permission ∉ deny}. The walk length is bounded by the number
     * of stored prefixes ≤ the key; for a control-plane policy set (a small number of grants; exactly
     * one in the deployed config) this is negligible.
     *
     * @param principal  the principal name (non-null)
     * @param key        the config key (non-null)
     * @param permission the required permission (non-null)
     * @return true if the principal is authorized for the permission on the key
     */
    public boolean isAllowed(String principal, String key, Permission permission) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
        EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);

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

        // Deny has absolute precedence (subtract it from allow); default-deny falls out of the empty
        // initial allow set.
        allow.removeAll(deny);
        return allow.contains(permission);
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
