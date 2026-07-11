package io.configd.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.LIST;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AclService}.
 * <p>
 * Authorization is evaluated as <b>union of all matching ancestor grants</b> + <b>absolute
 * deny-precedence</b> + <b>default-deny</b>.
 * The {@link UnionOfAncestors}, {@link DenyPrecedence}, {@link DefaultDeny}, and
 * {@link GrantDenyIndependence} suites <b>prove</b> these semantics adversarially (they fail under a
 * longest-match-only evaluation); {@link ProductionByteIdentity} pins the deployed
 * single-root-grant configuration to decisions identical to longest-match.
 * <p>
 * The capability suites prove the vocabulary and its relationships: {@link ListIndependentOfRead}
 * ({@code LIST} independent of {@code READ}), {@link WatchRequiresRead} (effective-{@code WATCH} =
 * {@code WATCH} AND {@code READ}), and {@link AdminIsNotSuperCapability}. Each is written to
 * <b>fail</b> if the coupling were wrong (e.g. {@code READ}->{@code LIST}, {@code READ}->{@code WATCH},
 * or {@code ADMIN} super-capability).
 */
class AclServiceTest {

    private AclService acl;

    @BeforeEach
    void setUp() {
        acl = new AclService();
    }

    // Basic grant and check (single-level grants - byte-identical to longest-match)

    @Nested
    class BasicGrantAndCheck {

        @Test
        void grantedPermissionIsAllowed() {
            acl.grant("db.", "alice", Set.of(READ));

            assertTrue(acl.isAllowed("alice", "db.host", READ));
        }

        @Test
        void ungrantedPermissionIsDenied() {
            acl.grant("db.", "alice", Set.of(READ));

            assertFalse(acl.isAllowed("alice", "db.host", WRITE));
        }

        @Test
        void unknownPrincipalIsDenied() {
            acl.grant("db.", "alice", Set.of(READ));

            assertFalse(acl.isAllowed("bob", "db.host", READ));
        }

        @Test
        void noMatchingPrefixIsDenied() {
            acl.grant("db.", "alice", Set.of(READ));

            assertFalse(acl.isAllowed("alice", "cache.ttl", READ));
        }

        @Test
        void emptyAclsDenyAll() {
            assertFalse(acl.isAllowed("alice", "any.key", READ));
        }

        @Test
        void multiplePermissions() {
            acl.grant("db.", "alice", Set.of(READ, WRITE));

            assertTrue(acl.isAllowed("alice", "db.host", READ));
            assertTrue(acl.isAllowed("alice", "db.host", WRITE));
            assertFalse(acl.isAllowed("alice", "db.host", ADMIN));
        }
    }

    // Union of ancestors - PROVES composition; FAILS under longest-match-only

    @Nested
    class UnionOfAncestors {

        /**
         * The canonical proof: a READ grant on an ancestor and a WRITE grant on a descendant compose to
         * READ+WRITE on a key under the descendant. Longest-match-only would
         * return ONLY the descendant's caps (WRITE), dropping the ancestor READ - this asserts the
         * union keeps both.
         */
        @Test
        void ancestorReadUnionsWithDescendantWrite() {
            acl.grant("/a/", "alice", Set.of(READ));
            acl.grant("/a/b/", "alice", Set.of(WRITE));

            assertTrue(acl.isAllowed("alice", "/a/b/x", READ),
                    "ancestor READ must survive the descendant grant (union, not longest-match)");
            assertTrue(acl.isAllowed("alice", "/a/b/x", WRITE),
                    "descendant WRITE applies");
        }

        /**
         * The discriminating direction: the descendant grants WRITE only (no READ). Longest-match-only
         * would deny READ under the descendant (the descendant lacks it); the union grants it from the
         * ancestor.
         */
        @Test
        void descendantCapDoesNotShadowAncestorCap() {
            acl.grant("db.", "alice", Set.of(READ));
            acl.grant("db.conn.", "alice", Set.of(WRITE)); // WRITE only - no READ at this level

            // db.conn.pool matches both "db." (READ) and "db.conn." (WRITE) -> union READ+WRITE
            assertTrue(acl.isAllowed("alice", "db.conn.pool", READ),
                    "ancestor READ must NOT be shadowed by a descendant grant lacking READ");
            assertTrue(acl.isAllowed("alice", "db.conn.pool", WRITE));
        }

        /** A key matching only the ancestor gets exactly the ancestor's caps. */
        @Test
        void keyMatchingOnlyAncestorGetsAncestorCaps() {
            acl.grant("db.", "alice", Set.of(READ));
            acl.grant("db.conn.", "alice", Set.of(READ, WRITE));

            // db.host matches only "db." -> READ, not WRITE
            assertTrue(acl.isAllowed("alice", "db.host", READ));
            assertFalse(acl.isAllowed("alice", "db.host", WRITE));
        }

        /** Three nested ancestors all contribute to the union. */
        @Test
        void allNestedAncestorsContributeToUnion() {
            acl.grant("/", "alice", Set.of(READ));
            acl.grant("/a/", "alice", Set.of(WRITE));
            acl.grant("/a/b/", "alice", Set.of(ADMIN));

            assertTrue(acl.isAllowed("alice", "/a/b/c", READ));
            assertTrue(acl.isAllowed("alice", "/a/b/c", WRITE));
            assertTrue(acl.isAllowed("alice", "/a/b/c", ADMIN));
        }
    }

    // Deny precedence (deny absolute) - PROVES both directions

    @Nested
    class DenyPrecedence {

        /** Deny at a LESS-specific ancestor beats an allow at a MORE-specific descendant. */
        @Test
        void denyAtAncestorOverridesAllowAtDescendant() {
            acl.deny("/a/", "alice", Set.of(WRITE));
            acl.grant("/a/b/", "alice", Set.of(WRITE));

            assertFalse(acl.isAllowed("alice", "/a/b/x", WRITE),
                    "a DENY at an ancestor must win over an ALLOW at a descendant");
        }

        /**
         * Deny at a MORE-specific descendant beats an allow at a LESS-specific ancestor.
         * <p>
         * Under a longest-match-only evaluation a longer READ-only grant would "restrict" a shorter
         * READ+WRITE grant as a side effect of only-longest-wins. Under union that side effect is gone
         * (the shorter grant's WRITE would survive), so restricting a sensitive child must be expressed
         * <b>explicitly</b> with a DENY - and that DENY wins.
         */
        @Test
        void denyAtDescendantOverridesAllowAtAncestor() {
            // Short prefix ALLOWs READ+WRITE across the app subtree...
            acl.grant("app.", "alice", Set.of(READ, WRITE));
            // ...and the sensitive child explicitly DENIES WRITE (allowing READ to remain).
            acl.deny("app.secret.", "alice", Set.of(WRITE));

            assertTrue(acl.isAllowed("alice", "app.name", WRITE),
                    "outside the deny carve-out, the subtree ALLOW still grants WRITE");
            assertFalse(acl.isAllowed("alice", "app.secret.key", WRITE),
                    "a DENY at the sensitive child must win over the ancestor ALLOW");
            assertTrue(acl.isAllowed("alice", "app.secret.key", READ),
                    "the carve-out denied only WRITE; READ (allowed by the ancestor) remains");
        }

        /** Deny and allow of the same capability at the SAME prefix: deny wins. */
        @Test
        void denyAtSamePrefixOverridesAllowAtSamePrefix() {
            acl.grant("app.", "alice", Set.of(READ, WRITE));
            acl.deny("app.", "alice", Set.of(WRITE));

            assertTrue(acl.isAllowed("alice", "app.name", READ));
            assertFalse(acl.isAllowed("alice", "app.name", WRITE),
                    "deny at the same prefix removes the co-located allow");
        }

        /** Deny is absolute even for ADMIN ("deny beats sudo"). */
        @Test
        void denyIsAbsoluteEvenForAdmin() {
            acl.grant("/", "alice", Set.of(READ, WRITE, ADMIN));
            acl.deny("/a/", "alice", Set.of(ADMIN));

            assertTrue(acl.isAllowed("alice", "/a/x", READ));
            assertTrue(acl.isAllowed("alice", "/a/x", WRITE));
            assertFalse(acl.isAllowed("alice", "/a/x", ADMIN),
                    "a DENY removes even ADMIN, regardless of any ALLOW");
        }

        /** A deny removes only the named capabilities, not the rest of the union. */
        @Test
        void denyRemovesOnlyNamedCapabilities() {
            acl.grant("/", "alice", Set.of(READ, WRITE, ADMIN));
            acl.deny("/a/", "alice", Set.of(WRITE));

            assertTrue(acl.isAllowed("alice", "/a/x", READ));
            assertFalse(acl.isAllowed("alice", "/a/x", WRITE));
            assertTrue(acl.isAllowed("alice", "/a/x", ADMIN));
        }

        /** A global deny at the empty prefix removes the capability everywhere, overriding a root allow. */
        @Test
        void globalDenyAtEmptyPrefixOverridesRootAllow() {
            acl.grant("", "alice", Set.of(READ, WRITE, ADMIN));
            acl.deny("", "alice", Set.of(ADMIN));

            assertTrue(acl.isAllowed("alice", "anything/at/all", READ));
            assertFalse(acl.isAllowed("alice", "anything/at/all", ADMIN));
        }

        /** A deny outside the queried key's ancestor chain does not apply. */
        @Test
        void denyOnSiblingSubtreeDoesNotApply() {
            acl.grant("/a/", "alice", Set.of(READ, WRITE));
            acl.deny("/a/secret/", "alice", Set.of(WRITE));

            // /a/public/x is not under /a/secret/ -> the deny does not match it
            assertTrue(acl.isAllowed("alice", "/a/public/x", WRITE));
            // ...but /a/secret/x is -> denied
            assertFalse(acl.isAllowed("alice", "/a/secret/x", WRITE));
        }

        /** Deny for one principal must not leak to another. */
        @Test
        void denyIsPerPrincipal() {
            acl.grant("/a/", "alice", Set.of(READ, WRITE));
            acl.grant("/a/", "bob", Set.of(READ, WRITE));
            acl.deny("/a/", "alice", Set.of(WRITE));

            assertFalse(acl.isAllowed("alice", "/a/x", WRITE));
            assertTrue(acl.isAllowed("bob", "/a/x", WRITE),
                    "alice's deny must not affect bob");
        }
    }

    // Default-deny - no ALLOW means denied; a lone DENY never grants

    @Nested
    class DefaultDeny {

        @Test
        void emptyAclsDenyEverything() {
            assertFalse(acl.isAllowed("alice", "/a/b", READ));
            assertFalse(acl.isAllowed("alice", "/a/b", WRITE));
            assertFalse(acl.isAllowed("alice", "/a/b", ADMIN));
        }

        @Test
        void unrelatedGrantDoesNotLeak() {
            acl.grant("/other/", "alice", Set.of(READ, WRITE, ADMIN));

            assertFalse(acl.isAllowed("alice", "/a/b", READ),
                    "a grant on an unrelated subtree must not authorize a different subtree");
        }

        /** A DENY with no matching ALLOW anywhere must still be denied (deny never implies allow). */
        @Test
        void loneDenyNeverGrants() {
            acl.deny("/a/", "alice", Set.of(READ));

            assertFalse(acl.isAllowed("alice", "/a/x", READ),
                    "a DENY rule must never produce an ALLOW (default-deny holds)");
            assertFalse(acl.isAllowed("alice", "/a/x", WRITE));
        }
    }

    // ALLOW / DENY independence - grant() and deny() are orthogonal effects at a prefix

    @Nested
    class GrantDenyIndependence {

        /** Adding an ALLOW does not clear a previously-set DENY at the same prefix. */
        @Test
        void grantDoesNotClearExistingDeny() {
            acl.deny("/a/", "alice", Set.of(WRITE));
            acl.grant("/a/", "alice", Set.of(READ, WRITE));

            assertTrue(acl.isAllowed("alice", "/a/x", READ));
            assertFalse(acl.isAllowed("alice", "/a/x", WRITE),
                    "a later grant() must not silently wipe a standing deny()");
        }

        /** Adding a DENY does not clear the ALLOW for capabilities it does not name. */
        @Test
        void denyDoesNotClearUnrelatedGrant() {
            acl.grant("/a/", "alice", Set.of(READ, WRITE));
            acl.deny("/a/", "alice", Set.of(ADMIN)); // denies a cap that was never allowed

            assertTrue(acl.isAllowed("alice", "/a/x", READ));
            assertTrue(acl.isAllowed("alice", "/a/x", WRITE));
            assertFalse(acl.isAllowed("alice", "/a/x", ADMIN));
        }
    }

    // LIST independent of READ - PROVES non-crossing in BOTH directions

    @Nested
    class ListIndependentOfRead {

        /** Holding READ must NOT confer LIST. Fails if LIST were folded into READ. */
        @Test
        void readGrantDoesNotConferList() {
            acl.grant("a.", "alice", Set.of(READ)); // READ only, no LIST

            assertTrue(acl.isAllowed("alice", "a.x", READ));
            assertFalse(acl.isAllowed("alice", "a.x", LIST),
                    "holding READ must NOT confer LIST (LIST ⊥ READ, R-CAP-1)");
        }

        /** Holding LIST must NOT confer READ. Fails if READ were folded into LIST. */
        @Test
        void listGrantDoesNotConferRead() {
            acl.grant("a.", "alice", Set.of(LIST)); // LIST only, no READ

            assertTrue(acl.isAllowed("alice", "a.x", LIST));
            assertFalse(acl.isAllowed("alice", "a.x", READ),
                    "holding LIST must NOT confer READ (LIST ⊥ READ, R-CAP-1)");
            // LIST without READ also cannot drag in effective WATCH (no READ floor).
            assertFalse(acl.isAllowed("alice", "a.x", WATCH),
                    "LIST does not confer READ, so it cannot confer effective WATCH either");
        }

        /** LIST is an ordinary exact-membership capability: grantable and DENY-able on its own. */
        @Test
        void listIsGrantedAndDeniedIndependently() {
            acl.grant("a.", "alice", Set.of(READ, LIST, WRITE));
            acl.deny("a.secret.", "alice", Set.of(LIST)); // carve out *enumeration* of secrets only

            assertTrue(acl.isAllowed("alice", "a.secret.k", READ),
                    "can still READ a known secret value");
            assertFalse(acl.isAllowed("alice", "a.secret.k", LIST),
                    "cannot LIST/enumerate the secrets subtree (deny LIST)");
            assertTrue(acl.isAllowed("alice", "a.public.k", LIST),
                    "LIST outside the carve-out is unaffected");
        }
    }

    // effective-WATCH = WATCH AND READ - the load-bearing coupling

    @Nested
    class WatchRequiresRead {

        /** WATCH granted but READ absent -> NOT authorized to watch. The core WATCH-requires-READ proof. */
        @Test
        void watchWithoutReadIsNotAuthorized() {
            acl.grant("a.", "alice", Set.of(WATCH)); // WATCH but no READ

            assertFalse(acl.isAllowed("alice", "a.x", WATCH),
                    "WATCH without READ yields NO effective watch authz (INV-WATCH-READ)");
        }

        /** WATCH AND READ -> authorized to watch. */
        @Test
        void watchWithReadIsAuthorized() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));

            assertTrue(acl.isAllowed("alice", "a.x", WATCH),
                    "WATCH ∧ READ over the target → authorized as a streaming read");
        }

        /** READ alone is not WATCH - WATCH is a separate grantable capability. */
        @Test
        void readGrantAloneDoesNotConferWatch() {
            acl.grant("a.", "alice", Set.of(READ)); // READ, no WATCH

            assertFalse(acl.isAllowed("alice", "a.x", WATCH),
                    "READ alone must not confer WATCH (WATCH is separately grantable)");
        }

        /** Denying READ kills effective WATCH (a watch can never out-read a read). */
        @Test
        void denyingReadKillsEffectiveWatch() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));
            acl.deny("a.secret.", "alice", Set.of(READ)); // deny READ on a sensitive child

            assertFalse(acl.isAllowed("alice", "a.secret.k", WATCH),
                    "denying READ must kill effective WATCH — INV-WATCH-READ");
            assertTrue(acl.isAllowed("alice", "a.public.k", WATCH),
                    "outside the READ carve-out, WATCH ∧ READ still holds");
        }

        /** Denying WATCH removes effective WATCH while leaving READ intact. */
        @Test
        void denyingWatchKillsEffectiveWatchButNotRead() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));
            acl.deny("a.secret.", "alice", Set.of(WATCH)); // deny WATCH only

            assertFalse(acl.isAllowed("alice", "a.secret.k", WATCH),
                    "deny(WATCH) removes effective WATCH");
            assertTrue(acl.isAllowed("alice", "a.secret.k", READ),
                    "READ remains — only WATCH was denied");
        }

        /** The WATCH coupling must not perturb the other capabilities' evaluation. */
        @Test
        void watchCouplingDoesNotLeakIntoOtherCaps() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));

            assertTrue(acl.isAllowed("alice", "a.x", READ));
            assertFalse(acl.isAllowed("alice", "a.x", WRITE),
                    "WATCH ∧ READ must not manufacture WRITE");
            assertFalse(acl.isAllowed("alice", "a.x", LIST),
                    "WATCH ∧ READ must not manufacture LIST");
            assertFalse(acl.isAllowed("alice", "a.x", ADMIN),
                    "WATCH ∧ READ must not manufacture ADMIN");
        }
    }

    // ADMIN is NOT a super-capability (no "ADMIN implies others")

    @Nested
    class AdminIsNotSuperCapability {

        /** An ADMIN-only principal is authorized for ADMIN alone - never for the other four caps. */
        @Test
        void adminOnlyPrincipalIsNotAuthorizedForOtherCaps() {
            acl.grant("a.", "alice", Set.of(ADMIN)); // ADMIN only

            assertTrue(acl.isAllowed("alice", "a.x", ADMIN));
            assertFalse(acl.isAllowed("alice", "a.x", READ), "ADMIN does not imply READ");
            assertFalse(acl.isAllowed("alice", "a.x", LIST), "ADMIN does not imply LIST");
            assertFalse(acl.isAllowed("alice", "a.x", WRITE), "ADMIN does not imply WRITE");
            assertFalse(acl.isAllowed("alice", "a.x", WATCH), "ADMIN does not imply WATCH");
        }
    }

    // Production byte-identity - the deployed single root grant (ConfigdServer.java)

    @Nested
    class ProductionByteIdentity {

        /**
         * Replicates the ONLY production grant <b>exactly</b>:
         * {@code grant("", "root", EnumSet.allOf(Permission.class))}. With a single rule there are no
         * overlapping ancestors and no DENY (a trivial one-element antichain), so union+deny decides
         * identically to longest-match for every key. {@code allOf} covers all five caps, so root gains
         * {@code LIST} and effective {@code WATCH} ({@code WATCH} AND {@code READ} both held) - root has
         * everything, which is correct. The load-bearing wiring guarantee is that the
         * {@code READ}/{@code WRITE}/{@code ADMIN} decisions match longest-match for this configuration,
         * and that every non-root principal stays default-denied for <b>every</b> capability.
         */
        @Test
        void singleRootGrantBehavesIdenticallyToLongestMatch() {
            // Model production verbatim: the sole grant is allOf(Permission.class), all five caps.
            acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));

            for (String key : new String[]{"db.host", "app.name", "/a/b/c", "", "x"}) {
                // READ/WRITE/ADMIN decisions match longest-match for the deployed config.
                assertTrue(acl.isAllowed("root", key, READ), key);
                assertTrue(acl.isAllowed("root", key, WRITE), key);
                assertTrue(acl.isAllowed("root", key, ADMIN), key);
                // root holds all of allOf, so LIST and effective WATCH (WATCH AND READ) are also granted.
                assertTrue(acl.isAllowed("root", key, LIST), key);
                assertTrue(acl.isAllowed("root", key, WATCH), key);
            }

            // Every non-root principal remains default-denied for every capability.
            assertFalse(acl.isAllowed("intruder", "db.host", READ));
            assertFalse(acl.isAllowed("intruder", "anything", WRITE));
            assertFalse(acl.isAllowed("intruder", "anything", ADMIN));
            assertFalse(acl.isAllowed("intruder", "db.host", LIST));
            assertFalse(acl.isAllowed("intruder", "anything", WATCH));
        }
    }

    // Revoke

    @Nested
    class Revocation {

        @Test
        void revokeRemovesAllPermissions() {
            acl.grant("db.", "alice", Set.of(READ, WRITE));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", READ));
            assertFalse(acl.isAllowed("alice", "db.host", WRITE));
        }

        @Test
        void revokeDoesNotAffectOtherPrincipals() {
            acl.grant("db.", "alice", Set.of(READ));
            acl.grant("db.", "bob", Set.of(READ));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", READ));
            assertTrue(acl.isAllowed("bob", "db.host", READ));
        }

        @Test
        void revokeDoesNotAffectOtherPrefixes() {
            acl.grant("db.", "alice", Set.of(READ));
            acl.grant("cache.", "alice", Set.of(READ));

            acl.revoke("db.", "alice");

            assertFalse(acl.isAllowed("alice", "db.host", READ));
            assertTrue(acl.isAllowed("alice", "cache.ttl", READ));
        }

        @Test
        void revokeNonexistentPrefixIsNoOp() {
            // Should not throw
            acl.revoke("nonexistent.", "alice");
        }

        @Test
        void revokeNonexistentPrincipalIsNoOp() {
            acl.grant("db.", "alice", Set.of(READ));
            // Should not throw
            acl.revoke("db.", "bob");
            // Alice's permissions are unaffected
            assertTrue(acl.isAllowed("alice", "db.host", READ));
        }

        /** Revoke removes the whole entry - both ALLOW and DENY - so a standing deny is also cleared. */
        @Test
        void revokeClearsDenyToo() {
            acl.grant("/", "alice", Set.of(READ, WRITE));
            acl.deny("/a/", "alice", Set.of(WRITE));
            assertFalse(acl.isAllowed("alice", "/a/x", WRITE)); // deny in effect

            acl.revoke("/a/", "alice");

            assertTrue(acl.isAllowed("alice", "/a/x", WRITE),
                    "revoking the prefix clears its DENY, so the ancestor ALLOW applies again");
        }
    }

    // Grant / deny overwrite

    @Test
    void grantOverwritesPreviousPermissions() {
        acl.grant("db.", "alice", Set.of(READ, WRITE));
        acl.grant("db.", "alice", Set.of(READ));

        assertTrue(acl.isAllowed("alice", "db.host", READ));
        assertFalse(acl.isAllowed("alice", "db.host", WRITE));
    }

    @Test
    void denyOverwritesPreviousDeny() {
        acl.grant("/", "alice", Set.of(READ, WRITE, ADMIN));
        acl.deny("/a/", "alice", Set.of(WRITE, ADMIN));
        acl.deny("/a/", "alice", Set.of(WRITE)); // overwrite: ADMIN no longer denied

        assertFalse(acl.isAllowed("alice", "/a/x", WRITE));
        assertTrue(acl.isAllowed("alice", "/a/x", ADMIN),
                "re-denying with a narrower set releases the previously-denied ADMIN");
    }

    // Null checks

    @Nested
    class NullChecks {

        @Test
        void grantNullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant(null, "alice", Set.of(READ)));
        }

        @Test
        void grantNullPrincipalThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant("db.", null, Set.of(READ)));
        }

        @Test
        void grantNullPermissionsThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.grant("db.", "alice", null));
        }

        @Test
        void denyNullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.deny(null, "alice", Set.of(READ)));
        }

        @Test
        void denyNullPrincipalThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.deny("db.", null, Set.of(READ)));
        }

        @Test
        void denyNullPermissionsThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.deny("db.", "alice", null));
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
                    () -> acl.isAllowed(null, "db.host", READ));
        }

        @Test
        void isAllowedNullKeyThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.isAllowed("alice", null, READ));
        }

        @Test
        void isAllowedNullPermissionThrows() {
            assertThrows(NullPointerException.class,
                    () -> acl.isAllowed("alice", "db.host", null));
        }
    }
}
