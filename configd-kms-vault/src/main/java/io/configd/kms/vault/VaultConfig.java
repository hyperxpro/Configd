package io.configd.kms.vault;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;
import io.configd.common.kms.KmsBootContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolved Vault Transit KMS config. Fail-closed parsing: missing required settings throw ConfigException at boot.
 */
record VaultConfig(
        String address,
        String transitMount,
        String keyName,
        String namespace,
        Auth auth,
        Path caFile,
        String aadContext,
        int bits,
        java.time.Duration timeout) {

    static final String PREFIX = "configd.kms.vault.";

    record Auth(Method method, String roleId, String secretId, String token) {

        enum Method {
            APPROLE,
            TOKEN
        }
    }

    static VaultConfig parse(ConfigSource cfg, KmsBootContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        String address = require(cfg, "address").replaceAll("/+$", "");
        String transitMount = cfg.getString(PREFIX + "transitMount").map(String::trim).orElse("transit");
        String keyName = require(cfg, "transitKeyName");
        String namespace = cfg.getString(PREFIX + "namespace").map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        Path caFile = cfg.getString(PREFIX + "tls.caFile").map(String::trim).filter(s -> !s.isEmpty())
                .map(Path::of).orElse(null);
        String aadContext = cfg.getString(PREFIX + "aadContext").map(String::trim).filter(s -> !s.isEmpty())
                .orElse(ctx.nodeId());
        int bits = cfg.getInt(PREFIX + "bits", 256);
        if (bits != 128 && bits != 256 && bits != 512) {
            throw new ConfigException(PREFIX + "bits must be 128, 256 or 512 (was " + bits + ")");
        }
        long timeoutMs = cfg.getLong(PREFIX + "timeoutMs", 5000L);
        if (timeoutMs <= 0) {
            throw new ConfigException(PREFIX + "timeoutMs must be positive (was " + timeoutMs + ")");
        }
        return new VaultConfig(address, transitMount, keyName, namespace, parseAuth(cfg), caFile, aadContext,
                bits, java.time.Duration.ofMillis(timeoutMs));
    }

    private static Auth parseAuth(ConfigSource cfg) {
        String method = cfg.getString(PREFIX + "auth.method").map(String::trim).map(s -> s.toLowerCase())
                .orElse("approle");
        return switch (method) {
            case "approle" -> {
                String roleId = require(cfg, "auth.approle.roleId");
                String secretId = resolveSecret(cfg, "auth.approle.secretId", "auth.approle.secretIdFile");
                yield new Auth(Auth.Method.APPROLE, roleId, secretId, null);
            }
            case "token" -> {
                String token = resolveSecret(cfg, "auth.token", "auth.tokenFile");
                yield new Auth(Auth.Method.TOKEN, null, null, token);
            }
            // kubernetes / cert / jwt auth are pluggable extension points (present the platform identity to
            // the matching Vault auth mount) but are not implemented. Fail loud rather than guess a method.
            default -> throw new ConfigException(PREFIX + "auth.method='" + method + "' is not supported;"
                    + " use 'approle' (default) or 'token'. kubernetes/cert/jwt are future extension points.");
        };
    }

    /** Reads a secret from a direct config value, else from a file path, failing loud if neither is set. */
    private static String resolveSecret(ConfigSource cfg, String directKey, String fileKey) {
        Optional<String> direct = cfg.getString(PREFIX + directKey).map(String::trim).filter(s -> !s.isEmpty());
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> file = cfg.getString(PREFIX + fileKey).map(String::trim).filter(s -> !s.isEmpty());
        if (file.isPresent()) {
            try {
                return new String(Files.readAllBytes(Path.of(file.get())), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new ConfigException("cannot read " + PREFIX + fileKey + " at " + file.get() + ": "
                        + e.getMessage());
            }
        }
        throw new ConfigException("Vault auth requires " + PREFIX + directKey + " or " + PREFIX + fileKey);
    }

    private static String require(ConfigSource cfg, String suffix) {
        return cfg.getString(PREFIX + suffix).map(String::trim).filter(s -> !s.isEmpty())
                .orElseThrow(() -> new ConfigException("missing required config " + PREFIX + suffix));
    }
}
