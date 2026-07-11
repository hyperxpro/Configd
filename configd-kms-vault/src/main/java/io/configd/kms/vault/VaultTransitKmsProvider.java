package io.configd.kms.vault;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsUnavailableException;
import io.configd.common.kms.RootKey;
import io.configd.common.kms.WrappedKey;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/**
 * A {@link KmsProvider} that uses HashiCorp Vault's Transit engine as a SEAL CUSTODIAN for one per-node
 * keyring-custody secret. Vault holds the KEK; this provider seals ({@code wrap}) and unseals
 * ({@code unwrap}) a single 32-byte secret and never asks Vault to touch a config record - the missing
 * per-record method is the whole point. At boot the core calls {@link #unwrap} once, caches the secret,
 * and drops this provider; if Vault is unreachable it throws {@link KmsUnavailableException} and the node
 * FAILS CLOSED - never a fallback.
 *
 * <h2>Provision / unseal / rotate</h2>
 * <ul>
 *   <li>{@link #generateRootKey()} (once, first boot): mint {@code S} with {@link SecureRandom}, seal it via
 *       {@code transit/encrypt} with the node AAD, return {@code S} plus the {@code vault:vN:} carrier to
 *       persist. Needs the one-time {@code transit/encrypt} permission.</li>
 *   <li>{@link #unwrap(WrappedKey)} (every boot): {@code transit/decrypt} the carrier with the same AAD.</li>
 *   <li>{@link #wrap(RootKey)} (KEK rotation, rare): re-seal {@code S} under the current key version. Old
 *       carriers still decrypt because Vault selects the version from the {@code vault:vN:} prefix, so a
 *       rotate needs NO action for existing data.</li>
 * </ul>
 *
 * <h2>Auth</h2>
 * AppRole is the portable default (RoleID + SecretID); a raw token is dev-only. Kubernetes / TLS-cert / JWT
 * auth are extension points (present the platform identity to the matching Vault auth mount). Login happens
 * once per backend call and the token is dropped with the provider, so no renewal daemon exists.
 */
public final class VaultTransitKmsProvider implements KmsProvider {

    /** Discovery discriminator; also the {@code providerType} stamped into every {@link KeyId}. */
    static final String TYPE = "vault-transit";

    /**
     * The custody secret is a SINGLE generation (Configd's keyring holds the term-versioned data roots, not
     * this provider), so its {@link KeyId} term is fixed at 1; the Vault key version lives, decoupled, in the
     * {@code vault:vN:} ciphertext prefix.
     */
    private static final int CUSTODY_TERM = 1;

    private final VaultConfig cfg;
    private final SecureRandom rng;

    public VaultTransitKmsProvider(VaultConfig cfg) {
        this(cfg, new SecureRandom());
    }

    VaultTransitKmsProvider(VaultConfig cfg, SecureRandom rng) {
        this.cfg = cfg;
        this.rng = rng;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public KeyId currentKeyId() {
        return keyId();
    }

    private KeyId keyId() {
        return new KeyId(TYPE, cfg.transitMount() + "/" + cfg.keyName(), CUSTODY_TERM);
    }

    @Override
    public Provisioned generateRootKey() throws KmsUnavailableException {
        byte[] secret = new byte[cfg.bits() / 8];
        rng.nextBytes(secret);
        try (VaultTransitClient client = new VaultTransitClient(cfg)) {
            String token = client.login();
            String ciphertext = client.encrypt(token, secret);
            RootKey root = new RootKey(secret, keyId());
            WrappedKey wrapped = new WrappedKey(keyId(),
                    ciphertext.getBytes(StandardCharsets.UTF_8), sealContext());
            return new Provisioned(root, wrapped);
        } catch (VaultException e) {
            throw unavailable("provision (transit/encrypt)", e);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public WrappedKey wrap(RootKey rootKey) throws KmsUnavailableException {
        try (VaultTransitClient client = new VaultTransitClient(cfg)) {
            String token = client.login();
            String ciphertext = rootKey.withMaterial(m -> client.encrypt(token, m));
            return new WrappedKey(keyId(), ciphertext.getBytes(StandardCharsets.UTF_8), sealContext());
        } catch (VaultException e) {
            throw unavailable("re-seal (transit/encrypt)", e);
        }
    }

    @Override
    public RootKey unwrap(WrappedKey wrapped) throws KmsUnavailableException {
        String ciphertext = new String(wrapped.ciphertext(), StandardCharsets.UTF_8);
        try (VaultTransitClient client = new VaultTransitClient(cfg)) {
            String token = client.login();
            byte[] secret = client.decrypt(token, ciphertext);
            try {
                return new RootKey(secret, wrapped.keyId());
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        } catch (VaultException e) {
            throw unavailable("unseal (transit/decrypt)", e);
        }
    }

    @Override
    public void healthCheck() throws KmsUnavailableException {
        try (VaultTransitClient client = new VaultTransitClient(cfg)) {
            client.health();
        } catch (VaultException e) {
            throw unavailable("health probe (sys/health)", e);
        }
    }

    /** Rotates the Vault Transit KEK - a new version is added and old carriers still decrypt. (admin/tests) */
    void rotateKek() throws KmsUnavailableException {
        try (VaultTransitClient client = new VaultTransitClient(cfg)) {
            client.rotateKey(client.login());
        } catch (VaultException e) {
            throw unavailable("rotate (transit/keys/rotate)", e);
        }
    }

    private Map<String, String> sealContext() {
        // Non-secret, self-describing metadata persisted beside the carrier. The AAD actually enforced at
        // unseal is the CONFIGURED aadContext (so a relocated node with a different identity fails), not this.
        return Map.of("vaultMount", cfg.transitMount(), "vaultKey", cfg.keyName(), "aad", cfg.aadContext());
    }

    private KmsUnavailableException unavailable(String op, VaultException cause) {
        return new KmsUnavailableException(
                "Vault Transit provider could not " + op + " at " + cfg.address() + " (mount="
                        + cfg.transitMount() + ", key=" + cfg.keyName() + "): " + cause.getMessage(), cause);
    }
}
