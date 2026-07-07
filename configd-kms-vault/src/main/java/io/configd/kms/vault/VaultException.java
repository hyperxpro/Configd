package io.configd.kms.vault;

/**
 * Any failure talking to Vault (unreachable, timeout, non-2xx status, malformed response, auth/permission
 * denied). {@link VaultTransitKmsProvider} maps this to
 * {@link io.configd.common.kms.KmsUnavailableException} so the boot FAILS CLOSED - the node refuses to start
 * rather than fall back to no encryption or a different provider (KmsProvider R3).
 */
final class VaultException extends RuntimeException {

    VaultException(String message) {
        super(message);
    }

    VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
