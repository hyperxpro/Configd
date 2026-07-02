package io.configd.common.kms;

/**
 * Thrown when a {@link KmsProvider} cannot seal or unseal the root key because the
 * key-management backend is unavailable (KMS unreachable, CMK disabled/denied,
 * throttled, ...).
 * <p>
 * <b>Checked on purpose.</b> The at-rest availability contract requires a
 * <em>conscious</em> decision at the one boot seam that calls the provider: if
 * unseal fails, the node must FAIL CLOSED (refuse to start), never fall back to no
 * encryption or to a different provider. Making this checked forces the boot caller
 * to handle it rather than let it slip past - the same fail-closed posture the
 * codebase already applies to a forced-but-unavailable transport tier.
 */
public class KmsUnavailableException extends Exception {

    public KmsUnavailableException(String message) {
        super(message);
    }

    public KmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
