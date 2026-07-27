package io.configd.api;

import java.util.Objects;
import java.util.Set;

/**
 * Authentication interceptor for API requests.
 * Validates caller identity before allowing write/admin operations.
 * <p>
 * Thread safety: safe for concurrent use provided the underlying
 * {@link TokenValidator} is thread-safe.
 */
public final class AuthInterceptor {

    public sealed interface AuthResult {
        /**
         * The token was valid and the caller is authenticated. {@code roles} is defensively copied to a
         * non-null, immutable snapshot (null elements rejected), so a pluggable {@link TokenValidator}
         * cannot hand the authorization path a null, mutable, or aliased role set.
         */
        record Authenticated(String principal, Set<String> roles) implements AuthResult {
            // Compact ctor must be public: a record nested in an interface is implicitly public, and the
            // canonical constructor cannot reduce that access.
            public Authenticated {
                Objects.requireNonNull(roles, "roles must not be null");
                roles = Set.copyOf(roles);
            }
        }
        record Denied(String reason) implements AuthResult {}
    }

    @FunctionalInterface
    public interface TokenValidator {
        AuthResult validate(String token);
    }

    private final TokenValidator validator;

    public AuthInterceptor(TokenValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * @param token the bearer token (may be null or blank)
     * @return the authentication result
     */
    public AuthResult authenticate(String token) {
        if (token == null || token.isBlank()) {
            return new AuthResult.Denied("missing auth token");
        }
        return validator.validate(token);
    }
}
