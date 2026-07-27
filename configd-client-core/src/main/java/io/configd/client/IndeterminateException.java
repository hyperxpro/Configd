package io.configd.client;

/**
 * Mutation outcome unknown: write MAY have committed. Reaction: outcome is indeterminate. Idempotent mutations
 * may retry to definite result; negative re-read is not proof of non-commit; must not do read-modify-write.
 */
public final class IndeterminateException extends ConfigdException {

    public IndeterminateException(String message) {
        super(message);
    }

    public IndeterminateException(String message, Throwable cause) {
        super(message, cause);
    }
}
