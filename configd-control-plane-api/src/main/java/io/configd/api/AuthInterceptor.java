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

    /**
     * Result of an authentication attempt.
     */
    public sealed interface AuthResult {
        /** The token was valid and the caller is authenticated. */
        record Authenticated(String principal, Set<String> roles) implements AuthResult {}
        /** Authentication was denied. */
        record Denied(String reason) implements AuthResult {}
    }

    /**
     * Validates an authentication token and returns the result.
     */
    @FunctionalInterface
    public interface TokenValidator {
        AuthResult validate(String token);
    }

    private final TokenValidator validator;

    /**
     * Creates an auth interceptor with the given token validator.
     *
     * @param validator the token validation strategy (non-null)
     */
    public AuthInterceptor(TokenValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * Authenticates a request using the provided token.
     *
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
