package io.configd.client.http;

import java.util.Optional;
import java.util.function.Function;

/**
 * Maps an HTTP status (plus the disambiguating headers) to the driver reaction -- the machine-readable decision
 * a driver must branch on, <b>never</b> the body (which is plaintext under a misleading {@code application/json},
 * may echo attacker-influenced input, and is unescaped). Pure and side-effect-free so every status reaction is
 * unit-testable in isolation; the {@link LeaderRouter} enacts the decision (backoff, follow, re-stamp, throw).
 */
final class HttpStatusClassifier {

    /** The reaction classes for a unary HTTP response. */
    enum Decision {
        /** 2xx -- the response is the answer (read value / write commit). */
        SUCCESS,
        /** 404 -- a definite "absent", not a routing failure. */
        NOT_FOUND,
        /** 503 with {@code X-Leader-Hint} -- progress toward the hinted leader (follow-once). */
        FOLLOW_HINT,
        /** 503 without a hint, or a read transient -- back off and retry the same endpoint. */
        RETRY_SAME,
        /** 429 -- honor {@code Retry-After}, then retry. */
        RETRY_AFTER,
        /** 504, a mutation timeout, or other mutation 5xx -- indeterminate, retry-to-definite, no read-modify-write. */
        INDETERMINATE,
        /** 409, or a 401 under an enabled replay guard -- retry with a fresh timestamp and nonce. */
        FRESH_STAMP,
        /** 401 without the replay guard -- (re)authenticate; do not hot-loop the same credential. */
        REAUTH,
        /** 403 -- permanently forbidden for this principal. */
        FORBIDDEN,
        /** 400 / 405 -- permanent request error, fix the request. */
        BAD_REQUEST
    }

    static final String LEADER_HINT_HEADER = "X-Leader-Hint";
    static final String FAIL_CLOSED_HEADER = "X-Fail-Closed";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private HttpStatusClassifier() {
    }

    /**
     * @param status         the HTTP status
     * @param header         a case-insensitive single-value header lookup
     * @param isMutation     whether the request was a PUT/DELETE/transfer (governs the "other 5xx" bucket and,
     *                       with {@code replayEnabled}, the {@code 401} branch -- replay headers ride only mutations)
     * @param configMutation whether it was a config PUT/DELETE specifically -- a {@code 409} there is a replayed
     *                       nonce (retry with a fresh stamp); a {@code 409} on the leadership-transfer route is a
     *                       <b>precondition</b> failure (terminal), not a replay -- distinguished here, never by
     *                       the body
     * @param replayEnabled  whether the client is populating replay headers
     */
    static Decision classify(int status, Function<String, Optional<String>> header,
                             boolean isMutation, boolean configMutation, boolean replayEnabled) {
        if (status >= 200 && status < 300) {
            return Decision.SUCCESS;
        }
        return switch (status) {
            case 404 -> Decision.NOT_FOUND;
            case 400, 405 -> Decision.BAD_REQUEST;
            case 401 -> (replayEnabled && isMutation) ? Decision.FRESH_STAMP : Decision.REAUTH;
            case 403 -> Decision.FORBIDDEN;
            case 409 -> (replayEnabled && configMutation) ? Decision.FRESH_STAMP : Decision.BAD_REQUEST;
            case 429 -> Decision.RETRY_AFTER;
            case 503 -> header.apply(LEADER_HINT_HEADER).isPresent() ? Decision.FOLLOW_HINT : Decision.RETRY_SAME;
            case 504 -> Decision.INDETERMINATE;
            default -> {
                if (status >= 500) {
                    // Other 5xx: indeterminate for a mutation (it may have committed), a re-read for a GET.
                    yield isMutation ? Decision.INDETERMINATE : Decision.RETRY_SAME;
                }
                // Any other 4xx: permanent request error.
                yield Decision.BAD_REQUEST;
            }
        };
    }
}
