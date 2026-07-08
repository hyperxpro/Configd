package io.configd.kms.vault;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A hand-rolled Vault HTTP client over the JDK {@link HttpClient}, scoped to the Transit seal-custodian
 * surface Configd uses. It is deliberately tiny: login, and the four Transit calls that seal / unseal / rotate
 * a single per-node secret. The security-critical crypto happens INSIDE Vault; this client only shuttles
 * base64 blobs, so no vendor SDK is needed and the core stays Vault-free.
 *
 * <h2>Seal model (why encrypt, not datakey)</h2>
 * The per-node custody secret is generated with the JDK {@link java.security.SecureRandom} and sealed with
 * {@code transit/encrypt} carrying {@code associated_data} (the node AAD). Both {@code encrypt} and
 * {@code decrypt} honour {@code associated_data} on every Vault version, so a relocated/copied blob whose AAD
 * does not match FAILS to unseal - the node-binding defence. ({@code transit/datakey} would have Vault mint the
 * entropy but does not bind AEAD associated-data the same way.) Vault still holds the KEK and only ever returns
 * a small {@code vault:vN:} wrapped blob; Configd derives all keyring keys locally (KmsProvider R1).
 *
 * <p>Not thread-safe by contract need: the provider calls it once at boot, then drops it.
 */
final class VaultTransitClient implements AutoCloseable {

    private final VaultConfig cfg;
    private final HttpClient http;

    VaultTransitClient(VaultConfig cfg) {
        this.cfg = cfg;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(cfg.timeout());
        if (cfg.caFile() != null) {
            b.sslContext(buildSslContext(cfg.caFile()));
        }
        this.http = b.build();
    }

    /** Authenticates to Vault and returns the client token (AppRole login, or a raw token for dev). */
    String login() {
        VaultConfig.Auth auth = cfg.auth();
        return switch (auth.method()) {
            case TOKEN -> auth.token();
            case APPROLE -> {
                String body = Json.object("role_id", auth.roleId(), "secret_id", auth.secretId());
                Object resp = post("/v1/auth/approle/login", null, body);
                String token = Json.string(resp, "auth.client_token");
                if (token == null) {
                    throw new VaultException("AppRole login returned no auth.client_token");
                }
                yield token;
            }
        };
    }

    /**
     * Seals {@code plaintext} under the Transit key with the node AAD, returning the {@code vault:vN:}
     * ciphertext. Used to provision the per-node secret and to re-seal it on KEK rotation.
     */
    String encrypt(String token, byte[] plaintext) {
        String body = Json.object(
                "plaintext", b64(plaintext),
                "associated_data", b64(cfg.aadContext().getBytes(StandardCharsets.UTF_8)));
        Object resp = post(transitPath("encrypt"), token, body);
        String ciphertext = Json.string(resp, "data.ciphertext");
        if (ciphertext == null) {
            throw new VaultException("transit/encrypt returned no data.ciphertext");
        }
        return ciphertext;
    }

    /** Unseals a {@code vault:vN:} ciphertext back to plaintext, requiring the same node AAD. */
    byte[] decrypt(String token, String ciphertext) {
        String body = Json.object(
                "ciphertext", ciphertext,
                "associated_data", b64(cfg.aadContext().getBytes(StandardCharsets.UTF_8)));
        Object resp = post(transitPath("decrypt"), token, body);
        String plaintextB64 = Json.string(resp, "data.plaintext");
        if (plaintextB64 == null) {
            throw new VaultException("transit/decrypt returned no data.plaintext");
        }
        return Base64.getDecoder().decode(plaintextB64);
    }

    /** Rewraps a stored ciphertext under the latest key version (after a rotate); AAD unchanged. */
    String rewrap(String token, String ciphertext) {
        String body = Json.object(
                "ciphertext", ciphertext,
                "associated_data", b64(cfg.aadContext().getBytes(StandardCharsets.UTF_8)));
        Object resp = post(transitPath("rewrap"), token, body);
        String rewrapped = Json.string(resp, "data.ciphertext");
        if (rewrapped == null) {
            throw new VaultException("transit/rewrap returned no data.ciphertext");
        }
        return rewrapped;
    }

    /** Rotates the Transit key: a new version is added; old ciphertext still decrypts (self-describing prefix). */
    void rotateKey(String token) {
        post("/v1/" + cfg.transitMount() + "/keys/" + cfg.keyName() + "/rotate", token, "{}");
    }

    /** Pre-flight reachability: any HTTP response = reachable; a connect/timeout failure = unavailable. */
    void health() {
        HttpRequest req = baseRequest("/v1/sys/health", null).GET().build();
        try {
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new VaultException("Vault health probe failed (unreachable): " + cfg.address(), e);
        }
    }

    private Object post(String path, String token, String body) {
        HttpRequest req = baseRequest(path, token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new VaultException("Vault request to " + path + " failed (unreachable): " + e.getMessage(), e);
        }
        int status = resp.statusCode();
        if (status / 100 != 2) {
            throw new VaultException("Vault " + path + " returned HTTP " + status + ": " + errorText(resp.body()));
        }
        String responseBody = resp.body();
        if (responseBody == null || responseBody.isBlank()) {
            // Vault write ops (e.g. transit/keys/.../rotate) answer 204 No Content with an empty body; the
            // caller reads no field from these, so an empty object is the correct parse.
            return Map.of();
        }
        try {
            return Json.parse(responseBody);
        } catch (RuntimeException e) {
            throw new VaultException("Vault " + path + " returned a malformed body: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder baseRequest(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(cfg.address() + path))
                .timeout(cfg.timeout());
        if (token != null) {
            b.header("X-Vault-Token", token);
        }
        if (cfg.namespace() != null) {
            b.header("X-Vault-Namespace", cfg.namespace());
        }
        return b;
    }

    /** Extracts Vault's {@code errors:[...]} array for a readable message, else the raw body (truncated). */
    private static String errorText(String body) {
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof java.util.Map<?, ?> m && m.get("errors") instanceof List<?> errs) {
                return errs.toString();
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw body
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private String transitPath(String op) {
        return "/v1/" + cfg.transitMount() + "/" + op + "/" + cfg.keyName();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static SSLContext buildSslContext(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (var cert : cf.generateCertificates(in)) {
                trust.setCertificateEntry("vault-ca-" + i++, (X509Certificate) cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new VaultException("failed to build TLS context from CA file " + caFile + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // JDK HttpClient has no explicit close before JDK 21's AutoCloseable; on JDK 25 it is AutoCloseable.
        // Close it so pooled connections/selector threads are released promptly after the one boot use.
        if (http instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort release; the provider is being dropped anyway (R2)
            }
        }
    }
}
