package io.configd.client;

/**
 * A <b>permanent request error</b> on the HTTP control plane: the request itself is malformed or invalid and
 * will fail identically if retried unchanged. It maps HTTP <b>400</b> (invalid key/scope/value, an empty
 * {@code PUT} body, an invalid {@code _acl/} policy, a validation failure) and <b>405</b> (wrong method on an
 * endpoint) — §04 D2-3/D2-4/D4-1/D4-5, §07 E2-1.
 *
 * <p><b>§07 reaction:</b> <b>do not retry unchanged</b> — fix the request. This is distinct from the
 * retryable/indeterminate outcomes ({@link UnavailableException}, {@link IndeterminateException}) and from an
 * authorization failure ({@link ForbiddenException}); a {@code 400} that is specifically an ACL policy-shape
 * rejection is still a {@code BadRequestException} (the caller distinguishes it, if needed, from the sanitized
 * server message — diagnostic only, never machine-branched, §07 E6).
 */
public final class BadRequestException extends ConfigdException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, String sanitizedServerMessage) {
        super(message, null, null, sanitizedServerMessage);
    }
}
