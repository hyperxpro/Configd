package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.HealthService;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.store.SigningKeyStore;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ADMIN-gated online keyring term-rotation trigger end-to-end against a REAL on-disk keyring:
 * the endpoint durably rotates the term, and after a simulated restart (a fresh envelope built over the
 * same data dir) (1) the active term advanced, (2) data written under the OLD term still decrypts, and
 * (3) new writes stamp the NEW term. The ADMIN gate is proven fail-closed (non-ADMIN and auth-off denied,
 * the rotation never attempted).
 */
class KeyringRotateEndpointTest {

    private static final String ENABLE = "configd.raft.encryption.enabled";
    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 0;               // gid 0 (N=1)
    private static final String ROTATE = "/v1/admin/keyring/rotate";
    private static final byte[] OLD_PLAINTEXT = "record-written-under-term-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_PLAINTEXT = "record-written-under-term-2".getBytes(StandardCharsets.UTF_8);

    /** The key term is a big-endian int right after the 8-byte header and the 4-byte scope id. */
    private static int keyTermOf(byte[] record) {
        return ByteBuffer.wrap(record, IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE,
                IntegrityEnvelope.KEY_TERM_SIZE).getInt();
    }

    @Test
    void rotateThenRestartAdvancesTermOldDecryptsNewWritesUseNewTerm(@TempDir Path root) throws Exception {
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin"); // outside dataDir (no co-location)
        Path dataDir = root.resolve("data");
        SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);

        System.setProperty(ENABLE, "true");
        try {
            // --- Boot #1: build the encrypting envelope and capture the rotation capability. ---
            KeyringRotator[] rotatorHolder = { null };
            IntegrityEnvelope env1 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    keyStore, keyFile, dataDir, r -> rotatorHolder[0] = r);
            assertTrue(env1.isEncrypting(), "encryption ON must produce an encrypting envelope");
            KeyringRotator rotator = rotatorHolder[0];
            assertTrue(rotator != null, "the boot must hand the rotation capability to the sink");

            byte[] oldRecord = env1.wrap(WAL_MAGIC, SCOPE, OLD_PLAINTEXT);
            assertEquals(1, keyTermOf(oldRecord), "the first boot writes under term 1");

            // --- Rotate through the REAL ADMIN endpoint. ---
            AdminApiHandler h = handler(acl(), auth(), rotator);
            AdminApiHandler.AdminResponse resp = post(h, "admin");
            assertEquals(200, resp.status(), "an ADMIN principal must be able to rotate the keyring");
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("activeTerm=2"), "the response must report the new active term: " + body);

            // --- Boot #2 (simulated restart): rebuild the envelope over the same data dir. ---
            IntegrityEnvelope env2 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    SigningKeyStore.loadOrCreate(keyFile), keyFile, dataDir);

            // (2) old-term data still decrypts through the rebuilt envelope (non-destructive rotation).
            assertArrayEquals(OLD_PLAINTEXT, env2.unwrap(WAL_MAGIC, SCOPE, oldRecord),
                    "a record written under term 1 must still decrypt after the rotation + restart");

            // (1)+(3) new writes stamp the NEW term.
            byte[] newRecord = env2.wrap(WAL_MAGIC, SCOPE, NEW_PLAINTEXT);
            assertEquals(2, keyTermOf(newRecord), "after rotate + restart, new writes must use term 2");
            assertArrayEquals(NEW_PLAINTEXT, env2.unwrap(WAL_MAGIC, SCOPE, newRecord),
                    "the new-term record must round-trip");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void rotateIsIdempotentlyAdvancingAcrossRepeatedCalls(@TempDir Path root) throws Exception {
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin");
        Path dataDir = root.resolve("data");
        SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
        System.setProperty(ENABLE, "true");
        try {
            KeyringRotator[] holder = { null };
            ConfigdServer.deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir, r -> holder[0] = r);
            AdminApiHandler h = handler(acl(), auth(), holder[0]);
            assertTrue(new String(post(h, "admin").body(), StandardCharsets.UTF_8).contains("activeTerm=2"),
                    "first rotation advances to term 2");
            assertTrue(new String(post(h, "admin").body(), StandardCharsets.UTF_8).contains("activeTerm=3"),
                    "a second rotation advances to term 3 (each rotation is a durable append)");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void nonAdminAndAuthOffAreDeniedAndRotationNotAttempted(@TempDir Path root) throws Exception {
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin");
        Path dataDir = root.resolve("data");
        SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
        System.setProperty(ENABLE, "true");
        try {
            KeyringRotator[] holder = { null };
            ConfigdServer.deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir, r -> holder[0] = r);

            // A guard rotator that records whether rotate() was ever reached, wrapping the real one.
            int[] rotateCalls = { 0 };
            AdminApiHandler.KeyringRotationAdmin guarded = () -> {
                rotateCalls[0]++;
                return holder[0].rotate();
            };

            // Unauthenticated -> 401.
            AdminApiHandler h = handler(acl(), auth(), guarded);
            assertEquals(401, post(h, null).status(), "an unauthenticated rotate must be 401");
            // Authenticated non-ADMIN -> 403.
            assertEquals(403, post(h, "writer").status(), "a non-ADMIN principal must be forbidden from rotate");
            // Auth off + ACL off -> 403 (a key rotation must not be issuable during an insecure bring-up).
            AdminApiHandler authOff = handler(null, null, guarded);
            assertEquals(403, post(authOff, null).status(), "auth-off: a key rotation must be refused, not open");

            assertEquals(0, rotateCalls[0], "no denied request may ever reach the rotation mechanism");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void rotationFailureFailsClosed503(@TempDir Path root) throws Exception {
        // A rotator whose mechanism throws (an IO/crypto failure) must surface fail-closed (503), audited,
        // never a silent 200.
        AdminApiHandler.KeyringRotationAdmin broken = () -> {
            throw new IllegalStateException("keyring write failed");
        };
        AdminApiHandler h = handler(acl(), auth(), broken);
        AdminApiHandler.AdminResponse resp = h.handle(req("POST", ROTATE, null, "admin"));
        assertEquals(503, resp.status(), "a rotation failure must be a fail-closed 503, not a silent success");
        assertTrue(new String(resp.body(), StandardCharsets.UTF_8).contains("failed"),
                "the 503 must surface the failure");
    }

    @Test
    void wrongMethodIs405AndNullRotatorIsAbsent404(@TempDir Path root) throws Exception {
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin");
        Path dataDir = root.resolve("data");
        SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
        System.setProperty(ENABLE, "true");
        try {
            KeyringRotator[] holder = { null };
            ConfigdServer.deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir, r -> holder[0] = r);
            AdminApiHandler h = handler(acl(), auth(), holder[0]);
            assertEquals(405, h.handle(req("GET", ROTATE, null, "admin")).status(),
                    "a non-POST on rotate must be 405");

            // With no rotator wired the route is absent (falls through to 404).
            AdminApiHandler noRotator = handler(acl(), auth(), null);
            assertEquals(404, noRotator.handle(req("POST", ROTATE, null, "admin")).status(),
                    "with no rotator wired, rotate must fall through to 404");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    // ---- helpers ----

    private static AuthInterceptor auth() {
        return new AuthInterceptor(token -> switch (token) {
            case "admin" -> new AuthInterceptor.AuthResult.Authenticated("adminP", Set.of());
            case "writer" -> new AuthInterceptor.AuthResult.Authenticated("writerP", Set.of());
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    private static AclService acl() {
        AclService acl = new AclService();
        acl.grant("_system/", "adminP", Set.of(AclService.Permission.ADMIN));
        acl.grant("", "writerP", EnumSet.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static AdminApiHandler handler(AclService acl, AuthInterceptor auth,
                                           AdminApiHandler.KeyringRotationAdmin rotator) {
        return new AdminApiHandler(new HealthService(), null, new VersionedConfigStore(),
                null, null, auth, acl, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, null,
                /* leadershipAdmin */ null, /* chain */ null, /* raftClusterAdmin */ null, rotator);
    }

    private static AdminApiHandler.AdminRequest req(String method, String path, String query, String token) {
        final URI uri;
        try {
            uri = new URI(null, null, path, query, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad test URI", e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) {
                return ("Authorization".equalsIgnoreCase(name) && token != null) ? "Bearer " + token : null;
            }
            @Override public byte[] body() { return new byte[0]; }
        };
    }

    private static AdminApiHandler.AdminResponse post(AdminApiHandler h, String token) throws Exception {
        return h.handle(req("POST", ROTATE, null, token));
    }
}
