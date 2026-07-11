package io.configd.api;

/**
 * Thrown by {@link PolicySerializer#parse} (and by the server-layer reserved-name validation that runs
 * alongside it) when an {@code _acl/} policy subtree is malformed, has an unrecognized key shape, or
 * collides with a reserved role/principal name - i.e. any reason the whole load must be REJECTED.
 * <p>
 * Extends {@link IllegalArgumentException} (so it is a {@link RuntimeException} the loader's
 * fail-closed-to-last-good {@code catch (RuntimeException)} handles, and so callers that expect the
 * documented {@code IllegalArgumentException} contract still match) while remaining a distinct,
 * assertable type for tests and structured logging.
 */
public final class PolicyParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message a human-readable reason that NAMES the offending key/line/name (no secret material)
     */
    public PolicyParseException(String message) {
        super(message);
    }
}
