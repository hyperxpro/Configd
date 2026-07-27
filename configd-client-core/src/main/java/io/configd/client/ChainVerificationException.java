package io.configd.client;

/**
 * Cryptographic verification failure of the signed config chain. Security control, fail-closed: never
 * silently dropped; always tears the connection down.
 */
public final class ChainVerificationException extends ConfigdException {

    public ChainVerificationException(String message) {
        super(message);
    }

    public ChainVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
