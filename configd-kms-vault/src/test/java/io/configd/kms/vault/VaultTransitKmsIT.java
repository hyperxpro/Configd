package io.configd.kms.vault;

import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsUnavailableException;
import io.configd.common.kms.RootKey;
import io.configd.common.kms.WrappedKey;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Vault proof against a {@code hashicorp/vault} container: the Transit seal-custodian works end to end -
 * AppRole login, boot-unseal round-trip, a Transit KEK ROTATE after which the OLD carrier still decrypts, an
 * AAD-mismatch rejection, and (the fail-closed law) an unreachable Vault surfacing as
 * {@link KmsUnavailableException}.
 *
 * <p>Flag-guarded on {@code -Dconfigd.it.containers=true} so it is skipped in the normal reactor (it needs a
 * Docker daemon and pulls the Vault image). Vault runs in dev mode (auto-unsealed, known root token); the
 * transit engine + a named key + AppRole are provisioned at startup, then the provider drives the real API.
 */
@EnabledIfSystemProperty(named = "configd.it.containers", matches = "true")
final class VaultTransitKmsIT {

    private static final String ROOT_TOKEN = "root-dev-token";
    private static final String MOUNT = "transit";
    private static final String KEY = "configd-root-kek";
    private static final String ROLE = "configd";

    private static VaultContainer<?> vault;
    private static String roleId;
    private static String secretId;

    @BeforeAll
    static void startVault() throws Exception {
        vault = new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.13").asCompatibleSubstituteFor("vault"))
                .withVaultToken(ROOT_TOKEN)
                .withStartupTimeout(Duration.ofMinutes(3))
                .withInitCommand(
                        "secrets enable transit",
                        "write -f " + MOUNT + "/keys/" + KEY + " type=aes256-gcm96",
                        "auth enable approle");
        vault.start();

        exec("sh", "-c", "echo 'path \"" + MOUNT + "/*\" { capabilities = [\"create\",\"read\",\"update\"] }' "
                + "| VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=" + ROOT_TOKEN + " vault policy write configd -");
        exec("vault", "write", "auth/approle/role/" + ROLE,
                "token_policies=configd", "secret_id_ttl=60m", "token_ttl=60m", "token_max_ttl=60m");
        roleId = exec("vault", "read", "-field=role_id", "auth/approle/role/" + ROLE + "/role-id").trim();
        secretId = exec("vault", "write", "-f", "-field=secret_id", "auth/approle/role/" + ROLE + "/secret-id").trim();
        assertTrue(!roleId.isEmpty() && !secretId.isEmpty(), "minted AppRole credentials");
    }

    @AfterAll
    static void stopVault() {
        if (vault != null) {
            vault.stop();
        }
    }

    private static String exec(String... cmd) throws Exception {
        Container.ExecResult r = vault.execInContainer(cmd);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("vault exec failed: " + String.join(" ", cmd)
                    + " -> " + r.getStderr() + r.getStdout());
        }
        return r.getStdout();
    }

    private static VaultConfig config(String address, String aad) {
        Map<String, String> m = new HashMap<>();
        m.put("configd.kms.vault.address", address);
        m.put("configd.kms.vault.transitMount", MOUNT);
        m.put("configd.kms.vault.transitKeyName", KEY);
        m.put("configd.kms.vault.auth.approle.roleId", roleId);
        m.put("configd.kms.vault.auth.approle.secretId", secretId);
        m.put("configd.kms.vault.timeoutMs", "5000");
        return VaultConfig.parse(new MapConfig(m), new KmsBootContext(aad));
    }

    private static VaultTransitKmsProvider provider(String aad) {
        return new VaultTransitKmsProvider(config(vault.getHttpHostAddress(), aad));
    }

    @Test
    void bootUnsealRoundTripsAgainstRealVault() throws Exception {
        VaultTransitKmsProvider provider = provider("node-alpha");
        provider.healthCheck();
        KmsProvider.Provisioned prov = provider.generateRootKey();
        byte[] original = prov.rootKey().withMaterial(byte[]::clone);
        assertEquals(32, original.length);
        WrappedKey wrapped = prov.wrapped();
        assertTrue(new String(wrapped.ciphertext(), StandardCharsets.UTF_8).startsWith("vault:v"),
                "real Vault returns a self-describing vault:vN: carrier");

        // A brand-new provider instance (fresh login) unseals the persisted carrier to the identical bytes.
        RootKey unsealed = provider("node-alpha").unwrap(wrapped);
        assertArrayEquals(original, unsealed.withMaterial(byte[]::clone));
    }

    @Test
    void rotateKekThenOldCarrierStillDecrypts() throws Exception {
        VaultTransitKmsProvider provider = provider("node-beta");
        KmsProvider.Provisioned prov = provider.generateRootKey();
        byte[] original = prov.rootKey().withMaterial(byte[]::clone);

        provider.rotateKek();

        RootKey afterRotate = provider("node-beta").unwrap(prov.wrapped());
        assertArrayEquals(original, afterRotate.withMaterial(byte[]::clone),
                "old data still decrypts after a KEK rotation");
    }

    @Test
    void aadMismatchIsRejectedByRealVault() throws Exception {
        WrappedKey wrapped = provider("node-gamma").generateRootKey().wrapped();
        assertThrows(KmsUnavailableException.class, () -> provider("node-DELTA").unwrap(wrapped));
    }

    @Test
    void unreachableVaultFailsClosed() {
        VaultTransitKmsProvider dead = new VaultTransitKmsProvider(config("http://127.0.0.1:1", "node-alpha"));
        assertThrows(KmsUnavailableException.class, dead::healthCheck);
        WrappedKey bogus = new WrappedKey(dead.currentKeyId(),
                "vault:v1:AAAA".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThrows(KmsUnavailableException.class, () -> dead.unwrap(bogus));
    }
}
