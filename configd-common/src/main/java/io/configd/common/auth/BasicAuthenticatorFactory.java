package io.configd.common.auth;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in factory for the {@code basic} (HTTP Basic, RFC 7617) mode. Reads the user store from
 * {@code configd.auth.basic.users} - a comma-separated list of {@code username:passwordHash:role1|role2}
 * entries, where the password hash is a {@link BasicAuthPasswords} PBKDF2 string. Fails closed at boot on an
 * empty store or a malformed entry (a config store that misreads its own auth config must not start).
 */
public final class BasicAuthenticatorFactory implements AuthenticatorFactory {

    @Override
    public String type() {
        return "basic";
    }

    @Override
    public Authenticator create(ConfigSource cfg) {
        List<String> entries = cfg.getList("configd.auth.basic.users");
        if (entries.isEmpty()) {
            throw new ConfigException(
                    "configd.auth.mode=basic requires configd.auth.basic.users (username:passwordHash:roles entries)");
        }
        Map<String, BasicAuthenticator.User> users = new HashMap<>();
        for (String entry : entries) {
            // username : passwordHash : roles(pipe-separated, optional). The hash uses '$' internally and
            // base64 (no ':' or ','), so ':' cleanly separates the three fields.
            String[] parts = entry.split(":", 3);
            if (parts.length < 2) {
                throw new ConfigException("malformed configd.auth.basic.users entry (need username:passwordHash[:roles])");
            }
            String username = parts[0];
            String hash = parts[1];
            if (username.isBlank()) {
                throw new ConfigException("configd.auth.basic.users entry has a blank username");
            }
            if (!BasicAuthPasswords.isValidHash(hash)) {
                throw new ConfigException("configd.auth.basic.users entry for '" + username
                        + "' has an invalid password hash (expected a pbkdf2-sha256$... value)");
            }
            Set<String> roles = parts.length == 3 && !parts[2].isBlank()
                    ? java.util.Arrays.stream(parts[2].split("\\|")).map(String::trim).filter(s -> !s.isEmpty())
                            .collect(Collectors.toUnmodifiableSet())
                    : Set.of();
            if (users.putIfAbsent(username, new BasicAuthenticator.User(hash, roles)) != null) {
                throw new ConfigException("configd.auth.basic.users has a duplicate username: " + username);
            }
        }
        return new BasicAuthenticator(users);
    }
}
