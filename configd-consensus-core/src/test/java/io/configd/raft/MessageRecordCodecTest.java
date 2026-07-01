package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating tests for the hand-written {@code equals}/{@code hashCode}/
 * {@code toString}/validation of the consensus message records that carry real
 * logic: {@link InstallSnapshotRequest}, {@link SnapshotState}, {@link LogEntry}.
 * <p>
 * No same-module test previously exercised their members. They are
 * NOT pure boilerplate: each has a HAND-WRITTEN {@code equals}/{@code hashCode}
 * that uses {@code Arrays.equals}/{@code Arrays.hashCode} on its {@code byte[]}
 * fields (a default record equals would compare arrays by identity and be
 * wrong), plus compact-constructor validation. This focused test asserts
 * per-field {@code equals} discrimination, hashCode distinctness across distinct
 * instances, non-empty {@code toString}, and the validation guards. Deterministic.
 */
class MessageRecordCodecTest {

    private static final NodeId L1 = NodeId.of(1);
    private static final NodeId L2 = NodeId.of(2);

    // InstallSnapshotRequest

    @Nested
    class InstallSnapshot {

        private InstallSnapshotRequest base() {
            return new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9});
        }

        @Test
        void equalsAndHashCodeAgreeForIdenticalContent() {
            InstallSnapshotRequest a = base();
            InstallSnapshotRequest b = new InstallSnapshotRequest(3, L1, 7, 2, 0,
                    new byte[]{1, 2, 3}, true, new byte[]{9});
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertEquals(a, a); // reflexive
            assertNotEquals(a, "not a request");
            assertNotEquals(a, null);
        }

        @Test
        void everyFieldParticipatesInEquals() {
            InstallSnapshotRequest base = base();
            // Each variant differs in exactly one field; each must be NOT equal,
            // which kills the corresponding equals RemoveConditional mutant.
            assertNotEquals(base, new InstallSnapshotRequest(9, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9}));   // term
            assertNotEquals(base, new InstallSnapshotRequest(3, L2, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9}));   // leaderId
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 99, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9}));  // lastIncludedIndex
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 99, 0, new byte[]{1, 2, 3}, true, new byte[]{9}));  // lastIncludedTerm
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 2, 5, new byte[]{1, 2, 3}, true, new byte[]{9}));   // offset
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{9, 9, 9}, true, new byte[]{9}));   // data
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, false, new byte[]{9}));  // done
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{8}));   // clusterConfigData
            assertNotEquals(base, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, null));            // config null vs present
        }

        @Test
        void hashCodeDistinguishesEveryField() {
            InstallSnapshotRequest base = base();
            int h = base.hashCode();
            // Distinct content must (overwhelmingly) yield distinct hashCodes; this
            // kills the hashCode Math/Primitive mutants (a collapsed combiner would
            // map these to the same value).
            assertNotEquals(h, new InstallSnapshotRequest(9, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 8, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 7, 3, 0, new byte[]{1, 2, 3}, true, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 7, 2, 1, new byte[]{1, 2, 3}, true, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{4, 5, 6}, true, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, false, new byte[]{9}).hashCode());
            assertNotEquals(h, new InstallSnapshotRequest(3, L1, 7, 2, 0, new byte[]{1, 2, 3}, true, new byte[]{8}).hashCode());
        }

        @Test
        void toStringIsNonEmptyAndReflectsFields() {
            String s = base().toString();
            assertFalse(s.isEmpty());
            assertTrue(s.contains("term=3"));
            assertTrue(s.contains("dataLen=3"));
            assertTrue(s.contains("done=true"));
            assertTrue(s.contains("hasConfig=true"));
        }

        @Test
        void nullDataDefaultsToEmptyAndNullLeaderThrows() {
            // compact ctor: null data -> empty; null leaderId -> NPE.
            InstallSnapshotRequest r = new InstallSnapshotRequest(1, L1, 0, 0, 0, null, true);
            assertEquals(0, r.data().length);
            assertThrows(NullPointerException.class,
                    () -> new InstallSnapshotRequest(1, null, 0, 0, 0, new byte[0], true));
        }
    }

    // SnapshotState

    @Nested
    class Snapshot {

        private SnapshotState base() {
            return new SnapshotState(new byte[]{1, 2, 3}, 5, 2, new byte[]{7});
        }

        @Test
        void equalsAndHashCodeAgree() {
            SnapshotState a = base();
            SnapshotState b = new SnapshotState(new byte[]{1, 2, 3}, 5, 2, new byte[]{7});
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, "x");
            assertNotEquals(a, null);
        }

        @Test
        void everyFieldParticipatesInEqualsAndHashCode() {
            SnapshotState base = base();
            assertNotEquals(base, new SnapshotState(new byte[]{9}, 5, 2, new byte[]{7}));        // data
            assertNotEquals(base, new SnapshotState(new byte[]{1, 2, 3}, 9, 2, new byte[]{7}));  // index
            assertNotEquals(base, new SnapshotState(new byte[]{1, 2, 3}, 5, 9, new byte[]{7}));  // term
            assertNotEquals(base, new SnapshotState(new byte[]{1, 2, 3}, 5, 2, new byte[]{8}));  // config
            assertNotEquals(base, new SnapshotState(new byte[]{1, 2, 3}, 5, 2, null));           // config null
            int h = base.hashCode();
            assertNotEquals(h, new SnapshotState(new byte[]{9}, 5, 2, new byte[]{7}).hashCode());
            assertNotEquals(h, new SnapshotState(new byte[]{1, 2, 3}, 6, 2, new byte[]{7}).hashCode());
            assertNotEquals(h, new SnapshotState(new byte[]{1, 2, 3}, 5, 3, new byte[]{7}).hashCode());
            assertNotEquals(h, new SnapshotState(new byte[]{1, 2, 3}, 5, 2, new byte[]{8}).hashCode());
        }

        @Test
        void sizeReturnsDataLengthAndToStringNonEmpty() {
            assertEquals(3, base().size());
            assertEquals(0, new SnapshotState(new byte[0], 0, 0).size());
            String s = base().toString();
            assertFalse(s.isEmpty());
            assertTrue(s.contains("lastIncludedIndex=5"));
            assertTrue(s.contains("dataLen=3"));
        }

        @Test
        void validationRejectsNegativeIndexTermAndNullData() {
            assertThrows(NullPointerException.class, () -> new SnapshotState(null, 0, 0, null));
            assertThrows(IllegalArgumentException.class, () -> new SnapshotState(new byte[0], -1, 0, null));
            assertThrows(IllegalArgumentException.class, () -> new SnapshotState(new byte[0], 0, -1, null));
        }
    }

    // LogEntry

    @Nested
    class Entry {

        @Test
        void equalsAndHashCodeUseByteAwareComparison() {
            LogEntry a = new LogEntry(4, 2, new byte[]{1, 2});
            LogEntry b = new LogEntry(4, 2, new byte[]{1, 2});
            // A default record equals would compare the command arrays by identity
            // (different instances) and report NOT equal. The hand-written equals must
            // report EQUAL.
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, "x");
            assertNotEquals(a, null);
        }

        @Test
        void everyFieldParticipatesInEqualsAndHashCode() {
            LogEntry base = new LogEntry(4, 2, new byte[]{1, 2});
            assertNotEquals(base, new LogEntry(9, 2, new byte[]{1, 2}));   // index
            assertNotEquals(base, new LogEntry(4, 9, new byte[]{1, 2}));   // term
            assertNotEquals(base, new LogEntry(4, 2, new byte[]{3, 4}));   // command
            int h = base.hashCode();
            assertNotEquals(h, new LogEntry(5, 2, new byte[]{1, 2}).hashCode());
            assertNotEquals(h, new LogEntry(4, 3, new byte[]{1, 2}).hashCode());
            assertNotEquals(h, new LogEntry(4, 2, new byte[]{5, 6}).hashCode());
        }

        @Test
        void toStringNonEmptyAndNoopFactoryAndValidation() {
            assertFalse(new LogEntry(1, 1, new byte[]{7}).toString().isEmpty());
            LogEntry noop = LogEntry.noop(3, 5);
            assertEquals(3, noop.index());
            assertEquals(5, noop.term());
            assertEquals(0, noop.command().length);
            // Validation: index must be >= 1, term >= 0; null command -> empty.
            assertThrows(IllegalArgumentException.class, () -> new LogEntry(0, 1, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new LogEntry(1, -1, new byte[0]));
            assertEquals(0, new LogEntry(1, 1, null).command().length);
        }
    }
}
