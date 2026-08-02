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
 * {@link AclService} level, deliberately BELOW the auth wire: an auth-OFF edge's SUBSCRIBE plane has
 * no principal to bind an {@code _acl/} deny to, so enforcement is proven one layer down instead,
 * where a non-root principal id is passed straight to {@code isAllowed} and a ROOT-under-auth-off
 * bypass cannot mask the deny.
 *
 * <p>Single node ({@code RaftConfig.of(NODE, Set.of())}) self-elects and commits synchronously
 * ({@code groupCommit=false}), so a just-applied {@code _acl/} write is durably on disk in time for
 * the canary walk.
 */
@Timeout(120)
class EncryptedAclPolicyAsConfigTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final Set<String> RESERVED_ROLES = Set.of("admin");
    private static final Set<String> RESERVED_PRINCIPALS = Set.of("root");

    private static final String PRINCIPAL = "svcagent";
    private static final String ROLE = "apppolicy";

    // The canary lives inside the DENY rule's prefix, so it is part of the _acl/ policy VALUE persisted to
    // the WAL: absent on disk under encryption (GCM), present under HMAC (integrity-only) - the differential.
    private static final String CANARY = "ACL-SECRETCANARY-7c1e9a3f-configd-groupd-DO-NOT-PERSIST";
    private static final String ALLOW_PREFIX = "app.";
    private static final String DENY_PREFIX = "app." + CANARY + ".";
    private static final String ROLE_VALUE =
            "allow READ,WATCH " + ALLOW_PREFIX + "\ndeny READ,WATCH " + DENY_PREFIX;
    private static final String ALLOWED_KEY = ALLOW_PREFIX + "public";
    private static final String DENIED_KEY = DENY_PREFIX + "x";

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

        String hit = firstFileContaining(dataDir, CANARY.getBytes(StandardCharsets.UTF_8));
        assertTrue(hit == null,
                "encryption ON: the _acl/ policy value must not appear in cleartext on disk, but found it in " + hit);
        System.out.println("[GD-ACL-ENC-ON] _acl/ policy loaded + enforced (allow authorizes, deny carves) while "
                + "AES-GCM-scrambled at rest (canary absent under " + dataDir + ")");
    }

    @Test
    void plaintextControlLeavesTheAclCanaryOnDiskProvingTheWalkIsSensitive(@TempDir Path dataDir) throws Exception {
        loadAclPolicyThroughGroupAndAssertEnforced(hmacEnvelope(), dataDir);

        String hit = firstFileContaining(dataDir, CANARY.getBytes(StandardCharsets.UTF_8));
        assertTrue(hit != null,
                "control (HMAC, no encryption): the committed _acl/ policy value must appear in cleartext on disk "
                        + "so the walk is proven sensitive; scanned " + dataDir + " and found none");
        System.out.println("[GD-ACL-ENC-OFF] control: the _acl/ policy canary IS present in cleartext at rest ("
                + hit + ") — the walk is sensitive, so the ON-side absence is genuinely encryption");
    }


    private void loadAclPolicyThroughGroupAndAssertEnforced(IntegrityEnvelope envelope, Path dataDir)
            throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        pools.add(pool);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);
        Storage nodeStorage = Storage.file(dataDir);

        ConfigdServer.RaftGroupRuntime rt = bringUpLeader(driver, envelope, dataDir, nodeStorage);

        proposeAndAwaitApply(driver, rt, "_acl/roles/" + ROLE, ROLE_VALUE.getBytes(StandardCharsets.UTF_8));
        proposeAndAwaitApply(driver, rt, "_acl/bindings/" + PRINCIPAL, ROLE.getBytes(StandardCharsets.UTF_8));

        AclService acl = new AclService();
        assertFalse(acl.isAllowed(PRINCIPAL, ALLOWED_KEY, AclService.Permission.READ),
                "precondition: an empty policy denies by default (the ALLOW must come from the _acl/ config)");

        try (AclConfigPolicyLoader loader = new AclConfigPolicyLoader(
                acl, rt.configStore(), RESERVED_ROLES, RESERVED_PRINCIPALS, new MetricsRegistry())) {
            loader.rebuild();
        }

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
