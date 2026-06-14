package io.configd.common;

/**
 * Thrown when an {@link IntegrityEnvelope} fails to verify a structurally-present
 * artifact: wrong magic on a long-enough buffer, unknown/rolled-back format
 * version, CRC32C mismatch, MAC mismatch, or — under a keyed (fail-closed)
 * envelope — a missing MAC / {@code algId=NONE} downgrade.
 * <p>
 * This is unchecked so the durability call sites ({@code RaftLog.readSnapshotBlob},
 * the WAL replay path, {@code DurableRaftState.load}) propagate it as a loud
 * recovery refusal rather than silently swallowing it to {@code null} — a silent
 * downgrade would reintroduce the PA-2021 vulnerability (ADR-0042 D-1 condition 4).
 * <p>
 * It is deliberately distinct from "structurally absent / too short to be an
 * envelope", which the absent-tolerant {@link IntegrityEnvelope#unwrapOrNull}
 * reports by returning {@code null} (legit torn tail / first boot / legacy
 * non-enveloped bytes in keyless mode).
 */
public final class IntegrityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
        super(message);
    }
}
