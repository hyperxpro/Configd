package io.configd.kms;

/**
 * Thrown by {@link KmsProvider} when the key-custody service is configured but
 * cannot satisfy a {@code wrap}/{@code unwrap}/{@code healthCheck} — KMS unreachable,
 * CMK disabled/deleted/denied, auth failure, or timeout.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p><b>Checked on purpose.</b> The §4 availability contract requires a
 * <em>conscious</em> decision at the boot seam, so this is a checked exception: the
 * caller cannot ignore it. The only contract-conformant responses are:
 * <ul>
 *   <li><b>at boot, {@code unwrap} fails →</b> the node REFUSES TO START (fail-closed,
 *       mirroring the ADR-0042 / D-1 sticky fail-closed posture). It must NOT fall back
 *       to no-encryption, and it must NOT silently fall back to a different provider —
 *       a silent downgrade is how a "data is KMS-protected" claim becomes fiction
 *       (the same rule {@code NettyTransport.select()} applies to a forced transport).</li>
 *   <li><b>an already-running node never calls {@code unwrap} again,</b> so a KMS blip
 *       cannot reach this path — the node continues on its cached {@link RootKey}.</li>
 * </ul>
 */
public class KmsUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public KmsUnavailableException(String message) {
        super(message);
    }

    public KmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
