package io.configd.client;

/**
 * A <b>mutation whose outcome is unknown</b>: HTTP {@code 504} (write deadline expired), or a transport
 * timeout / dropped connection on a mutation (Gate 4). The write <b>MAY</b> have committed and MAY still
 * commit later.
 *
 * <p><b>§07 reaction:</b> the outcome is <b>indeterminate</b> — an idempotent last-writer-wins mutation may
 * be retried to a definite result; a negative re-read is <b>not</b> proof of non-commit; a driver <b>MUST
 * NOT</b> perform a read-modify-write across it (§04 D4-8 / §07 E7). Defined here in Gate 1 for a complete
 * hierarchy; the control plane that raises it is Gate 4.
 */
public final class IndeterminateException extends ConfigdException {

    public IndeterminateException(String message) {
        super(message);
    }

    public IndeterminateException(String message, Throwable cause) {
        super(message, cause);
    }
}
