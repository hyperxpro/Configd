package io.configd.common;

/**
 * Thrown when an {@link IntegrityEnvelope} fails to verify a structurally-present
 * artifact: wrong magic on a long-enough buffer, unknown/rolled-back format
 * version, CRC32C mismatch, MAC mismatch, or, under a keyed (fail-closed)
 * envelope, a missing MAC or {@code algId=NONE} downgrade.
 * <p>
 * This is unchecked so the durability call sites ({@code RaftLog.readSnapshotBlob},
 * the WAL replay path, the anchor open path) propagate it as a loud
 * recovery refusal rather than silently swallowing it to {@code null}. A silent
 * downgrade would allow a tampered artifact to be accepted.
 * <p>
 * It is deliberately distinct from "structurally absent / too short to be an
 * envelope", which the absent-tolerant {@link IntegrityEnvelope#unwrapOrNull}
 * reports by returning {@code null} (torn tail, first boot, or legacy
 * non-enveloped bytes in keyless mode).
 */
public final class IntegrityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
        super(message);
    }

    /**
     * @param message the failure description
     * @param cause   the underlying crypto failure (e.g. an AES-GCM bad-tag / decrypt error)
     */
    public IntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
