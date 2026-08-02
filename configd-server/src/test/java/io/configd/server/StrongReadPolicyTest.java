package io.configd.server;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit checks for {@link StrongReadPolicy} - the key-class assignment
 * that decides which keys MUST be served via the fail-closed linearizable path. These need no server
 * and so run once (the server-driven strong-read behaviour is re-proven on every transport in
 * {@link AbstractAdminApiServerContract}).
 */
class StrongReadPolicyTest {

    @Test
    void blankPrefixRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StrongReadPolicy(Set.of("")));
    }

    @Test
    void emptyPolicyDisablesEnforcement() {
        StrongReadPolicy none = new StrongReadPolicy(Set.of());
        assertFalse(none.isStrongReadKey("secure/killswitch"));
    }

    @Test
    void customPrefixHonored() {
        StrongReadPolicy policy = new StrongReadPolicy(Set.of("global/", "acl/"));
        assertTrue(policy.isStrongReadKey("global/region-map"));
        assertTrue(policy.isStrongReadKey("acl/tenant-7"));
        assertFalse(policy.isStrongReadKey("secure/killswitch"));
        assertFalse(policy.isStrongReadKey("app/feature"));
    }
}
