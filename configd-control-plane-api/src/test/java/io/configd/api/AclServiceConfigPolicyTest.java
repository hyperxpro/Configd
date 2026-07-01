package io.configd.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for the CONFIG-SOURCED policy layer in {@link AclService}: additive union with the
 * static layer, absolute deny-precedence ACROSS layers, the effective-WATCH floor through config,
 * the atomic publish-swap, a torn-read concurrency stress, and the carve mechanism that motivates the
 * loader's reserved-name validation. (Reservation itself is the loader's job - see
 * {@code AclConfigPolicyLoaderTest}; {@link AclService} is deliberately layer-agnostic.)
 */
class AclServiceConfigPolicyTest {

    private static Role role(String name, PolicyRule... rules) {
        return new Role(name, List.of(new Policy(name, List.of(rules))));
    }

    private static PolicyRule allow(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, EnumSet.copyOf(Set.of(caps)), Set.of());
    }

    private static PolicyRule deny(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(), EnumSet.copyOf(Set.of(caps)));
    }

    // ---------------- empty-default byte-identity ----------------

    @Test
    void defaultSnapshotIsEmpty() {
        AclService acl = new AclService();
        assertEquals(ConfigPolicy.EMPTY, acl.configPolicy());
    }

    @Test
    void emptyConfigContributesNothing() {
        AclService acl = new AclService();
        acl.grant("a.", "alice", EnumSet.of(READ));
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);
        assertTrue(acl.isAllowed("alice", "a.b", READ));
        assertFalse(acl.isAllowed("alice", "a.b", WRITE));
        assertFalse(acl.isAllowed("bob", "a.b", READ));
    }

    // ---------------- additive grants via config ----------------

    @Test
    void configRoleViaAssertedRoleNameGrantsAdditively() {
        AclService acl = new AclService();
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("reader", role("reader", allow("x.", READ))), Map.of()));
        // principal asserts "reader" (authn) -> resolves against the config role
        assertTrue(acl.isAllowed("alice", Set.of("reader"), "x.y", READ));
        assertFalse(acl.isAllowed("alice", Set.of("reader"), "y.z", READ)); // prefix miss
        assertFalse(acl.isAllowed("alice", Set.of(), "x.y", READ));         // not asserted -> no grant
    }

    @Test
    void configBindingGrantsWithoutAssertedRoles() {
        AclService acl = new AclService();
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("reader", role("reader", allow("x.", READ))),
                Map.of("alice", Set.of("reader"))));
        // 3-arg (no asserted roles): authority flows purely from the CONFIG binding
        assertTrue(acl.isAllowed("alice", "x.y", READ));
        assertFalse(acl.isAllowed("bob", "x.y", READ)); // bob not bound
    }

    @Test
    void configComposesWithOwnGrants() {
        AclService acl = new AclService();
        acl.grant("x.", "alice", EnumSet.of(READ));                 // own grant: READ
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("writer", role("writer", allow("x.", WRITE))),
                Map.of("alice", Set.of("writer"))));                // config: WRITE
        assertTrue(acl.isAllowed("alice", "x.y", READ));            // from own grant
        assertTrue(acl.isAllowed("alice", "x.y", WRITE));           // from config role - additive union
    }

    // ---------------- absolute deny-precedence ACROSS layers ----------------

    @Test
    void configDenyOverridesOwnAllow() {
        AclService acl = new AclService();
        acl.grant("a.", "alice", EnumSet.of(READ, WRITE));         // own grant allows READ+WRITE
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("carve", role("carve", deny("a.", WRITE))),
                Map.of("alice", Set.of("carve"))));                 // config DENY WRITE
        assertTrue(acl.isAllowed("alice", "a.b", READ));           // READ survives
        assertFalse(acl.isAllowed("alice", "a.b", WRITE));         // WRITE denied with absolute precedence
    }

    @Test
    void ownDenyOverridesConfigAllow() {
        AclService acl = new AclService();
        acl.deny("a.", "alice", EnumSet.of(WRITE));                // own DENY WRITE
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("w", role("w", allow("a.", WRITE))),
                Map.of("alice", Set.of("w"))));                     // config ALLOW WRITE
        assertFalse(acl.isAllowed("alice", "a.b", WRITE));         // own deny wins (subtracted once over both)
    }

    // ---------------- effective-WATCH = WATCH AND READ through config ----------------

    @Test
    void configWatchFloorRequiresRead() {
        AclService acl = new AclService();
        // WATCH granted but not READ -> effective WATCH is false.
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("w", role("w", allow("x.", WATCH))), Map.of("alice", Set.of("w"))));
        assertFalse(acl.isAllowed("alice", "x.y", WATCH));
        // WATCH + READ -> effective WATCH true.
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("w", role("w", allow("x.", WATCH, READ))), Map.of("alice", Set.of("w"))));
        assertTrue(acl.isAllowed("alice", "x.y", WATCH));
        // A config READ-deny removes effective WATCH even if WATCH allowed (cross-layer floor).
        acl.grant("x.", "alice", EnumSet.of(READ, WATCH));         // own grant gives READ+WATCH
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("d", role("d", deny("x.", READ))), Map.of("alice", Set.of("d"))));
        assertFalse(acl.isAllowed("alice", "x.y", WATCH));         // READ denied -> WATCH floored off
    }

    // ---------------- the atomic publish-swap ----------------

    @Test
    void publishSwapReplacesPolicyWholesale() {
        AclService acl = new AclService();
        ConfigPolicy grantP = new ConfigPolicy(
                Map.of("r", role("r", allow("x.", READ))), Map.of("alice", Set.of("r")));
        acl.publishConfigPolicy(grantP);
        assertTrue(acl.isAllowed("alice", "x.y", READ));
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);               // swap back to empty
        assertFalse(acl.isAllowed("alice", "x.y", READ));
        acl.publishConfigPolicy(grantP);                            // swap forward again
        assertTrue(acl.isAllowed("alice", "x.y", READ));
    }

    /**
     * Torn-read stress (strong shape). Two snapshots A and B BOTH grant {@code alice READ} on {@code x.} -
     * but via <b>different</b> role names ({@code roleA} vs {@code roleB}). So the only correct decision is
     * {@code true} under <b>either</b> whole snapshot. A torn / read-more-than-once {@link
     * AclService#isAllowed} (e.g. reading {@code alice}'s bindings from one snapshot but the role
     * definitions from the other) would resolve {@code alice} to a role that is undefined in the snapshot
     * the definitions came from -> {@code false}, a value <b>impossible</b> for either whole snapshot. So
     * "always {@code true}" is a genuine torn-read detector (not merely no-throw), and it is
     * <b>deterministic</b> - no scheduler-dependent "observe both decisions" liveness flake. The reader must
     * also never throw (a non-atomic in-place mutation would risk CME/NPE during iteration).
     */
    @Test
    void concurrentReloadIsNeverTorn() throws Exception {
        AclService acl = new AclService();
        ConfigPolicy a = new ConfigPolicy(
                Map.of("roleA", role("roleA", allow("x.", READ))), Map.of("alice", Set.of("roleA")));
        ConfigPolicy b = new ConfigPolicy(
                Map.of("roleB", role("roleB", allow("x.", READ))), Map.of("alice", Set.of("roleB")));
        acl.publishConfigPolicy(a); // seed a granting snapshot so the reader never observes the empty default

        final int swaps = 200_000;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean tornOrInconsistent = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < swaps; i++) {
                    acl.publishConfigPolicy((i & 1) == 0 ? a : b);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.set(true);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                while (!done.get()) {
                    if (!acl.isAllowed("alice", "x.y", READ)) {
                        tornOrInconsistent.set(true);   // false is impossible: BOTH snapshots grant it
                    }
                    reads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        assertNull(failure.get(), () -> "isAllowed threw under concurrent reload: " + failure.get());
        assertFalse(tornOrInconsistent.get(),
                "isAllowed observed a torn/inconsistent decision (false) though BOTH snapshots grant alice READ");
        assertTrue(reads.get() > 0, "reader must actually have run under contention");

        // Deterministic behaviour-change check: the swap really does change the decision wholesale.
        acl.publishConfigPolicy(a);
        assertTrue(acl.isAllowed("alice", "x.y", READ));
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);
        assertFalse(acl.isAllowed("alice", "x.y", READ));
    }

    @Test
    void staleVersionedPublishDoesNotClobberNewerPolicy() {
        AclService acl = new AclService();
        ConfigPolicy newer = new ConfigPolicy(
                Map.of("r", role("r", allow("x.", READ))), Map.of("alice", Set.of("r")));
        // Publish a NEWER store version first.
        acl.publishConfigPolicy(100L, newer);
        assertTrue(acl.isAllowed("alice", "x.y", READ));
        // An out-of-order rebuild that scanned an OLDER store version must be IGNORED (monotonic publish),
        // so it cannot resurrect the empty/older policy over the newer one (the redteam clobber scenario).
        acl.publishConfigPolicy(50L, ConfigPolicy.EMPTY);
        assertTrue(acl.isAllowed("alice", "x.y", READ),
                "a stale (older-version) publish must not clobber the newer policy");
        assertEquals(newer, acl.configPolicy());
        // Equal version is also ignored (idempotent rebuild at the same version).
        acl.publishConfigPolicy(100L, ConfigPolicy.EMPTY);
        assertTrue(acl.isAllowed("alice", "x.y", READ), "an equal-version publish must not clobber");
        // A strictly-newer version DOES supersede.
        acl.publishConfigPolicy(150L, ConfigPolicy.EMPTY);
        assertFalse(acl.isAllowed("alice", "x.y", READ));
    }

    // ---------------- carve mechanism (motivates the loader's reservation) ----------------

    @Test
    void configBindingToRootWouldCarveAtServiceLevel_motivatesLoaderReservation() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class)); // the static break-glass grant
        // Demonstrate the vector: a config role bound to "root" that denies everything WOULD carve root.
        // AclService is layer-agnostic, so this is allowed HERE - which is exactly why the LOADER must
        // reject a config that binds the reserved "root" principal (N2).
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("evil", role("evil", deny("", EnumSet.allOf(AclService.Permission.class)
                        .toArray(new AclService.Permission[0])))),
                Map.of("root", Set.of("evil"))));
        assertFalse(acl.isAllowed("root", Set.of(), "anything", READ),
                "with a config binding root→deny-all, root IS carved at the AclService level (vector is real)");

        // Remove the binding (the production state after loader reservation): root is fully authorized again.
        acl.publishConfigPolicy(ConfigPolicy.EMPTY);
        for (AclService.Permission perm : AclService.Permission.values()) {
            assertTrue(acl.isAllowed("root", Set.of(), "anything", perm),
                    () -> "root must be fully authorized once no config role is bound to it");
        }
    }

    @Test
    void rootAssertingNoRolesIsImmuneToAConfigAdminRole() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        // A config role literally named "admin" that denies everything - but root asserts Set.of() (N1) and
        // is NOT bound to it, so root never holds "admin" and is not carved. (This is the latent footgun N1
        // closes: an operator naturally defining an "admin" role must not silently strip root.)
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("admin", role("admin", deny("", EnumSet.allOf(AclService.Permission.class)
                        .toArray(new AclService.Permission[0])))),
                Map.of()));
        for (AclService.Permission perm : AclService.Permission.values()) {
            assertTrue(acl.isAllowed("root", Set.of(), "anything", perm),
                    () -> "root (asserting no roles) must be immune to an unbound config 'admin' role");
        }
    }

    /**
     * The positive witness for WHY N1 (root asserts {@code Set.of()} in {@code ConfigdServer}) is needed:
     * if root instead asserted the role name {@code "admin"} AND a config role named {@code "admin"} carries
     * a deny, the deny WOULD carve root through the asserted-role vector. This is exactly the footgun N1
     * removes at the source (and N3 - reserving the {@code admin} role name in the loader - backstops). The
     * wired server never reaches this state, but this proves the vector is real, so N1 is not vacuous.
     */
    @Test
    void rootAssertingAdminRoleWouldBeCarvedByAConfigAdminRole_whyN1() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        acl.publishConfigPolicy(new ConfigPolicy(
                Map.of("admin", role("admin", deny("", EnumSet.allOf(AclService.Permission.class)
                        .toArray(new AclService.Permission[0])))),
                Map.of()));
        // Asserting {"admin"} (the PRE-N1 production shape) resolves the config "admin" deny-all -> root carved.
        assertFalse(acl.isAllowed("root", Set.of("admin"), "anything", READ),
                "asserting the 'admin' role with a config 'admin' deny-all role present carves root — this is "
                        + "the vector N1 (root asserts Set.of()) and N3 (reserve 'admin') close");
    }
}
