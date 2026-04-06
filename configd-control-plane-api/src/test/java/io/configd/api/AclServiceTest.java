package io.configd.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AclService}.
 */
class AclServiceTest {

    private AclService acl;

    @BeforeEach
    void setUp() {
        acl = new AclService();
    }

    // -----------------------------------------------------------------------
    // Basic grant and check
    // -----------------------------------------------------------------------

    @Nested
    class BasicGrantAndCheck {

        @Test
        void grantedPermissionIsAllowed() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));

            assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
        }

        @Test
        void ungrantedPermissionIsDenied() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));

            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.WRITE));
        }

        @Test
        void unknownPrincipalIsDenied() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));

            assertFalse(acl.isAllowed("bob", "db.host", AclService.Permission.READ));
        }

        @Test
        void noMatchingPrefixIsDenied() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));

            assertFalse(acl.isAllowed("alice", "cache.ttl", AclService.Permission.READ));
        }

        @Test
        void emptyAclsDenyAll() {
            assertFalse(acl.isAllowed("alice", "any.key", AclService.Permission.READ));
        }

        @Test
        void multiplePermissions() {
            acl.grant("db.", "alice",
                    Set.of(AclService.Permission.READ, AclService.Permission.WRITE));

            assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
            assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.WRITE));
            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.ADMIN));
        }
    }

    // -----------------------------------------------------------------------
    // Longest prefix matching
    // -----------------------------------------------------------------------

    @Nested
    class LongestPrefixMatching {

        @Test
        void longestPrefixTakesPriority() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));
            acl.grant("db.conn.", "alice",
                    Set.of(AclService.Permission.READ, AclService.Permission.WRITE));

            // "db.conn.pool" matches "db.conn." (longest) which has WRITE
            assertTrue(acl.isAllowed("alice", "db.conn.pool", AclService.Permission.WRITE));

            // "db.host" matches "db." (only match) which does NOT have WRITE
            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.WRITE));
            assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
        }

        @Test
        void longestPrefixCanDenyWhatShorterAllows() {
            // Short prefix grants READ+WRITE
            acl.grant("app.", "alice",
                    Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
            // Longer prefix grants only READ (effectively restricting WRITE)
            acl.grant("app.secret.", "alice",
                    Set.of(AclService.Permission.READ));

            assertTrue(acl.isAllowed("alice", "app.name", AclService.Permission.WRITE));
            assertFalse(acl.isAllowed("alice", "app.secret.key", AclService.Permission.WRITE));
            assertTrue(acl.isAllowed("alice", "app.secret.key", AclService.Permission.READ));
        }
    }

    // -----------------------------------------------------------------------
    // Revoke
    // -----------------------------------------------------------------------

    @Nested
    class Revocation {

        @Test
        void revokeRemovesAllPermissions() {
            acl.grant("db.", "alice",
                    Set.of(AclService.Permission.READ, AclService.Permission.WRITE));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.WRITE));
        }

        @Test
        void revokeDoesNotAffectOtherPrincipals() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));
            acl.grant("db.", "bob", Set.of(AclService.Permission.READ));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
            assertTrue(acl.isAllowed("bob", "db.host", AclService.Permission.READ));
        }

        @Test
        void revokeDoesNotAffectOtherPrefixes() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));
            acl.grant("cache.", "alice", Set.of(AclService.Permission.READ));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
            assertTrue(acl.isAllowed("alice", "cache.ttl", AclService.Permission.READ));
        }

        @Test
        void revokeNonexistentPrefixIsNoOp() {
            // Should not throw
            acl.revoke("nonexistent.", "alice");
        }

        @Test
        void revokeNonexistentPrincipalIsNoOp() {
            acl.grant("db.", "alice", Set.of(AclService.Permission.READ));
            // Should not throw
            acl.revoke("db.", "bob");
            // Alice's permissions are unaffected
            assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
        }
    }

    // -----------------------------------------------------------------------
    // Grant overwrite
    // -----------------------------------------------------------------------

    @Test
    void grantOverwritesPreviousPermissions() {
        acl.grant("db.", "alice",
                Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        acl.grant("db.", "alice",
                Set.of(AclService.Permission.READ));

        assertTrue(acl.isAllowed("alice", "db.host", AclService.Permission.READ));
        assertFalse(acl.isAllowed("alice", "db.host", AclService.Permission.WRITE));
    }

    // -----------------------------------------------------------------------
    // Null checks
    // -----------------------------------------------------------------------

    @Nested
    class NullChecks {

        @Test
        void grantNullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant(null, "alice", Set.of(AclService.Permission.READ)));
        }

        @Test
        void grantNullPrincipalThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant("db.", null, Set.of(AclService.Permission.READ)));
        }

        @Test
        void grantNullPermissionsThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant("db.", "alice", null));
        }

        @Test
        void revokeNullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.revoke(null, "alice"));
        }

        @Test
        void revokeNullPrincipalThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.revoke("db.", null));
        }

        @Test
        void isAllowedNullPrincipalThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.isAllowed(null, "db.host", AclService.Permission.READ));
        }

        @Test
        void isAllowedNullKeyThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.isAllowed("alice", null, AclService.Permission.READ));
        }

        @Test
        void isAllowedNullPermissionThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.isAllowed("alice", "db.host", null));
        }
    }
}
