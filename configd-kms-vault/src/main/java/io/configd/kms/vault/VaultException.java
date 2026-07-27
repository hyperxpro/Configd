package io.configd.kms.vault;

/**
 * Vault communication failure. Mapped to KmsUnavailableException so boot fails closed.
 */
final class VaultException extends RuntimeException {

    VaultException(String message) {
        super(message);
    }

    VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
