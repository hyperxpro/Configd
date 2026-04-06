package io.configd.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Per-key-prefix ACL enforcement.
 * Controls which principals can read/write config under specific prefixes.
 * <p>
 * Permissions are stored per prefix, per principal. When checking access
 * for a key, the longest prefix matching the key is found via
 * {@link ConcurrentSkipListMap#floorKey(Object)} in O(log N) time.
 * <p>
 * Thread safety: uses {@link ConcurrentSkipListMap} for the prefix map
 * and {@link ConcurrentHashMap} for the per-prefix principal maps.
 */
public final class AclService {

    /**
     * Permission types for config operations.
     */
    public enum Permission { READ, WRITE, ADMIN }

    // prefix -> (principal -> permissions)
    // Sorted in natural (lexicographic) order for efficient longest-prefix matching.
    private final ConcurrentSkipListMap<String, ConcurrentHashMap<String, Set<Permission>>> acls =
            new ConcurrentSkipListMap<>();

    /**
     * Grants permissions to a principal for a key prefix.
     *
     * @param prefix      the key prefix (non-null)
     * @param principal   the principal name (non-null)
     * @param permissions the permissions to grant (non-null, non-empty)
     */
    public void grant(String prefix, String principal, Set<Permission> permissions) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");

        acls.compute(prefix, (k, principalMap) -> {
            if (principalMap == null) {
                principalMap = new ConcurrentHashMap<>();
            }
            principalMap.put(principal, EnumSet.copyOf(permissions));
            return principalMap;
        });
    }

    /**
     * Revokes all permissions for a principal on a prefix.
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
     * Checks if a principal has the given permission for a key.
     * <p>
     * Uses {@code floorKey()} on a {@link ConcurrentSkipListMap} to find
     * the longest matching prefix in O(log N) time. Only the longest
     * matching prefix is consulted — shorter prefixes are not considered.
     *
     * @param principal  the principal name (non-null)
     * @param key        the config key (non-null)
     * @param permission the required permission (non-null)
     * @return true if the principal has the required permission
     */
    public boolean isAllowed(String principal, String key, Permission permission) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        // Find the longest prefix that matches the key using O(log N) navigation.
        // floorKey(key) returns the greatest key <= key in sorted order.
        // We walk backward until we find a prefix that key.startsWith(prefix).
        String candidate = acls.floorKey(key);
        while (candidate != null) {
            if (key.startsWith(candidate)) {
                // Found the longest matching prefix
                ConcurrentHashMap<String, Set<Permission>> principalMap = acls.get(candidate);
                if (principalMap == null) {
                    return false;
                }
                Set<Permission> granted = principalMap.get(principal);
                return granted != null && granted.contains(permission);
            }
            candidate = acls.lowerKey(candidate);
        }

        return false;
    }
}
