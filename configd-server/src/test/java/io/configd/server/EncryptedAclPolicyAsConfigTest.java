package io.configd.server;

import io.configd.api.AclService;
import io.configd.common.Clock;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Encryption at rest composed with ACL policy-as-config, proven at the policy loader /
 * {@link AclService} level, deliberately BELOW the auth wire (no edge SUBSCRIBE plane, no
 * principal-on-the-wire). An {@code _acl/} policy (an ALLOW plus a carving DENY) is committed through a
 * REAL raft group built with node-local <b>encryption ON</b> (the production {@link ConfigdServer#buildRaftGroup}
 * seam, an encrypting {@link IntegrityEnvelope}); {@link AclConfigPolicyLoader} then rebuilds the policy from
 * that group's applied store and {@link AclService#isAllowed} enforces it for a <b>non-root</b> principal.
 *
 * <h2>Why the loader/AclService level (not the edge)</h2>
 * Against an auth-OFF edge the SUBSCRIBE plane has no principal to bind an {@code _acl/} deny to, so an
 * edge-driven deny is murky. Enforcement is unambiguous one layer down: a non-root principal id is passed
 * straight to {@code isAllowed}, so a ROOT-under-auth-off bypass cannot mask the deny, and the assertion is
 * about the policy machinery itself, exactly what {@code AclConfigPolicyLoaderMultiShardTest} exercises,
 * here composed with the encrypted state machine that {@code AclConfigPolicyLoaderMultiShardTest} lacks (it
 * drives plain in-memory stores).
 *
 * <h2>The two halves (a genuine at-rest differential, mirroring {@code RealClusterEncryptionIT})</h2>
 * <ul>
 *   <li><b>Encryption ON</b> ({@link #encryptedAclPolicyLoadsEnforcesAndStaysOffDiskInCleartext}): the
 *       {@code _acl/} policy round-trips through the ENCRYPTED group; the loader publishes it and
 *       {@code isAllowed} enforces (the config ALLOW authorizes, the config DENY carves the hole). Then a
 *       walk of the group's data dir proves the distinctive canary embedded in the DENY rule does NOT appear
 *       in cleartext on disk: the policy value is AES-GCM-scrambled at rest yet drove the live decision.</li>
 *   <li><b>Plaintext control</b> ({@link #plaintextControlLeavesTheAclCanaryOnDiskProvingTheWalkIsSensitive}):
 *       the SAME policy through a plain HMAC-integrity envelope enforces IDENTICALLY (encryption is
 *       transparent to the ACL semantics) and the SAME walk FINDS the canary, proving the ON-side absence
 *       is caused by encryption, not a blind spot in the walk or a value that never reached durable storage.</li>
 * </ul>
 *
 * <p>Single node ({@code --peers ""} equivalent: {@code RaftConfig.of(NODE, Set.of())}) self-elects and
 * commits synchronously ({@code groupCommit=false}), so a just-applied {@code _acl/} write is durably on disk
 * for the canary walk. Everything is deadline-bounded on the throttled 2-vCPU box.
 */
@Timeout(120)
class EncryptedAclPolicyAsConfigTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final Set<String> RESERVED_ROLES = Set.of("admin");
    private static final Set<String> RESERVED_PRINCIPALS = Set.of("root");

    // A NON-ROOT, non-reserved principal bound (via _acl/bindings) to a config role: ROOT-under-auth-off
    // cannot mask the deny at this level because the id is passed straight to isAllowed.
    private static final String PRINCIPAL = "svcagent";
    private static final String ROLE = "apppolicy";

    // The canary lives inside the DENY rule's prefix, so it is part of the _acl/ policy VALUE persisted to
    // the WAL: absent on disk under encryption (GCM), present under HMAC (integrity-only) - the differential.
    private static final String CANARY = "ACL-SECRETCANARY-7c1e9a3f-configd-groupd-DO-NOT-PERSIST";
    private static final String ALLOW_PREFIX = "app.";
    private static final String DENY_PREFIX = "app." + CANARY + ".";
    private static final String ROLE_VALUE =
            "allow READ,WATCH " + ALLOW_PREFIX + "\ndeny READ,WATCH " + DENY_PREFIX;
    private static final String ALLOWED_KEY = ALLOW_PREFIX + "public";   // matches ALLOW only
    private static final String DENIED_KEY = DENY_PREFIX + "x";          // matches ALLOW and DENY

    private final List<OwnerExecutorPool> pools = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (OwnerExecutorPool p : pools) {
            p.shutdown();
        }
        pools.clear();
    }

    @Test
    void encryptedAclPolicyLoadsEnforcesAndStaysOffDiskInCleartext(@TempDir Path dataDir) throws Exception {
        loadAclPolicyThroughGroupAndAssertEnforced(encryptingEnvelope(), dataDir);

        // AT-REST PROOF: the _acl/ policy value (canary in the DENY rule) drove the live decision above yet
        // must NOT appear in cleartext on disk: it is AES-GCM-scrambled at rest.
        String hit = firstFileContaining(dataDir, CANARY.getBytes(StandardCharsets.UTF_8));
        assertTrue(hit == null,
                "encryption ON: the _acl/ policy value must not appear in cleartext on disk, but found it in " + hit);
        System.out.println("[GD-ACL-ENC-ON] _acl/ policy loaded + enforced (allow authorizes, deny carves) while "
                + "AES-GCM-scrambled at rest (canary absent under " + dataDir + ")");
    }

    @Test
    void plaintextControlLeavesTheAclCanaryOnDiskProvingTheWalkIsSensitive(@TempDir Path dataDir) throws Exception {
        loadAclPolicyThroughGroupAndAssertEnforced(hmacEnvelope(), dataDir);

        // CONTROL (HMAC integrity, no encryption): enforcement was IDENTICAL above (encryption is transparent
        // to the ACL semantics), and the SAME committed policy value MUST be findable on disk in cleartext,
        // proving the walk is sensitive, so the ON-side absence is genuinely encryption.
        String hit = firstFileContaining(dataDir, CANARY.getBytes(StandardCharsets.UTF_8));
        assertTrue(hit != null,
                "control (HMAC, no encryption): the committed _acl/ policy value must appear in cleartext on disk "
                        + "so the walk is proven sensitive; scanned " + dataDir + " and found none");
        System.out.println("[GD-ACL-ENC-OFF] control: the _acl/ policy canary IS present in cleartext at rest ("
                + hit + ") — the walk is sensitive, so the ON-side absence is genuinely encryption");
    }

    // the shared body: commit _acl/ through an encrypted (or control) group, load + enforce

    private void loadAclPolicyThroughGroupAndAssertEnforced(IntegrityEnvelope envelope, Path dataDir)
            throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        pools.add(pool);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);
        Storage nodeStorage = Storage.file(dataDir);

        ConfigdServer.RaftGroupRuntime rt = bringUpLeader(driver, envelope, dataDir, nodeStorage);

        // Commit the _acl/ policy through real consensus on the encrypting state machine: a role carrying an
        // ALLOW plus a carving DENY, and a binding of the non-root principal to that role.
        proposeAndAwaitApply(driver, rt, "_acl/roles/" + ROLE, ROLE_VALUE.getBytes(StandardCharsets.UTF_8));
        proposeAndAwaitApply(driver, rt, "_acl/bindings/" + PRINCIPAL, ROLE.getBytes(StandardCharsets.UTF_8));

        AclService acl = new AclService();
        // Precondition: with no policy loaded, the fresh AclService denies by default, so the ALLOW below is
        // sourced from the loaded _acl/ config, not a pre-existing grant.
        assertFalse(acl.isAllowed(PRINCIPAL, ALLOWED_KEY, AclService.Permission.READ),
                "precondition: an empty policy denies by default (the ALLOW must come from the _acl/ config)");

        try (AclConfigPolicyLoader loader = new AclConfigPolicyLoader(
                acl, rt.configStore(), RESERVED_ROLES, RESERVED_PRINCIPALS, new MetricsRegistry())) {
            loader.rebuild(); // reads _acl/ from the (encrypted-at-rest) applied store and publishes the policy
        }

        // The policy loaded and ENFORCES for the non-root principal: the config ALLOW authorizes an in-prefix
        // key, and the config DENY carves the hole. This is the _acl/-through-encrypted-store composition.
        assertTrue(acl.configPolicy().roles().containsKey(ROLE),
                "the _acl/ role committed through the encrypted store is in the published policy snapshot");
        assertTrue(acl.isAllowed(PRINCIPAL, ALLOWED_KEY, AclService.Permission.READ),
                "the config ALLOW authorizes READ on an in-prefix key after the encrypted _acl/ round-trip");
        assertTrue(acl.isAllowed(PRINCIPAL, ALLOWED_KEY, AclService.Permission.WATCH),
                "the config ALLOW authorizes WATCH (READ ∧ WATCH both granted) on an in-prefix key");
        assertFalse(acl.isAllowed(PRINCIPAL, DENIED_KEY, AclService.Permission.READ),
                "the config DENY carves READ on the hole — the loaded _acl/ deny enforces (non-root principal)");
        assertFalse(acl.isAllowed(PRINCIPAL, DENIED_KEY, AclService.Permission.WATCH),
                "the config DENY carves WATCH on the hole");
    }

    // group bring-up (mirrors MultiShardIntegratedSweepTest / MultiGroupBringupTest)

    private ConfigdServer.RaftGroupRuntime bringUpLeader(
            MultiRaftDriver driver, IntegrityEnvelope integrity, Path dataDir, Storage nodeStorage)
            throws Exception {
        ConfigdServer.RaftGroupRuntime rt = ConfigdServer.buildRaftGroup(
                /*gid=*/0, /*shardCount=*/1, dataDir, nodeStorage, integrity, Clock.system(),
                /*signer=*/null, ConfigStateMachine.InvariantChecker.NOOP,
                RaftNode.InvariantChecker.NOOP, testMetrics(),
                RaftConfig.of(NODE, Set.of()), NODE, /*tcpTransport=*/null,
                /*groupCommit=*/false, 4096, 0L, driver);
        driver.addGroup(0, rt.raftNode());
        ScheduledExecutorService owner = driver.ownerExecutor(0);
        owner.submit(() -> {
            rt.raftNode().bindOwnerThread();
            for (int i = 0; i < 500; i++) {
                rt.raftNode().tick();
            }
        }).get(10, TimeUnit.SECONDS);
        assertTrue(rt.raftNode().role() == RaftRole.LEADER,
                "the single encrypted node must self-elect to LEADER before serving writes");
        return rt;
    }

    private void proposeAndAwaitApply(MultiRaftDriver driver, ConfigdServer.RaftGroupRuntime rt,
                                      String key, byte[] value) throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value);
        ScheduledExecutorService owner = driver.ownerExecutor(0);
        ProposeOutcome outcome = owner.submit(() -> driver.propose(0, cmd)).get(5, TimeUnit.SECONDS);
        assertTrue(outcome.result() == ProposalResult.ACCEPTED, "the leader must accept the _acl/ write: " + key);
        for (int i = 0; i < 500; i++) {
            owner.submit(() -> rt.raftNode().tick()).get(5, TimeUnit.SECONDS);
            if (rt.configStore().get(key).found()) {
                return;
            }
        }
        fail("the group did not apply _acl/ key '" + key + "' within the budget");
    }

    private static ConfigdMetrics testMetrics() {
        return new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
    }

    // envelopes + on-disk cleartext walk

    /** An AES-256-GCM encrypting envelope over an in-memory term-1 root (the SegmentKeyManager holds it). */
    private static IntegrityEnvelope encryptingEnvelope() {
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 0x7E);
        RootKey root = new RootKey(material, new KeyId("local", "kid", 1));
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root));
    }

    /** The control: HMAC integrity only (no confidentiality), so a committed value stays cleartext at rest. */
    private static IntegrityEnvelope hmacEnvelope() {
        return new IntegrityEnvelope(new SecretKeySpec(new byte[32], "HmacSHA256"));
    }

    /** Returns the path of the first regular file under {@code dir} whose bytes contain {@code needle}, or null. */
    private static String firstFileContaining(Path dir, byte[] needle) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(dir)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            if (indexOf(Files.readAllBytes(p), needle) >= 0) {
                return p.toString();
            }
        }
        return null;
    }

    /** Naive byte-array search (files are tiny in a test); returns the first index of {@code needle} or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
