package io.configd.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link AclService#configPolicyVersion} — the monotonic config-policy ({@code _acl/} reload)
 * version that triggers bounded watch revocation (RFC §2 W7-7). It is {@link Long#MIN_VALUE} for the
 * EMPTY default (production, no {@code _acl/} keys ⇒ never changes ⇒ no re-auth cost), advances on each
 * publish, and never regresses on a stale versioned publish.
 */
@DisplayName("AclService.configPolicyVersion — the bounded-revocation trigger (W7-7)")
class AclServiceConfigPolicyVersionTest {

    @Test
    @DisplayName("the EMPTY default is Long.MIN_VALUE (never changes ⇒ zero re-auth cost)")
    void emptyDefaultIsMinValue() {
        assertEquals(Long.MIN_VALUE, new AclService().configPolicyVersion());
    }

    @Test
    @DisplayName("the unversioned publish increments the version each call")
    void unversionedPublishIncrements() {
        AclService acl = new AclService();
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);
        assertEquals(Long.MIN_VALUE + 1, acl.configPolicyVersion());
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);
        assertEquals(Long.MIN_VALUE + 2, acl.configPolicyVersion());
    }

    @Test
    @DisplayName("the versioned publish installs the store version monotonically; a stale version is ignored")
    void versionedPublishIsMonotonic() {
        AclService acl = new AclService();
        acl.publishConfigPolicy(100L, ConfigPolicy.EMPTY);
        assertEquals(100L, acl.configPolicyVersion());
        acl.publishConfigPolicy(150L, ConfigPolicy.EMPTY);
        assertEquals(150L, acl.configPolicyVersion());
        acl.publishConfigPolicy(120L, ConfigPolicy.EMPTY); // stale (≤ current) ⇒ ignored
        assertEquals(150L, acl.configPolicyVersion(), "the version does not regress on a stale publish");
    }
}
