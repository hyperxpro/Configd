package io.configd.edge;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VersionCursor}.
 */
class VersionCursorTest {

    // -----------------------------------------------------------------------
    // INITIAL sentinel
    // -----------------------------------------------------------------------

    @Nested
    class InitialCursor {

        @Test
        void initialHasVersionZero() {
            assertEquals(0, VersionCursor.INITIAL.version());
        }

        @Test
        void initialHasTimestampZero() {
            assertEquals(0, VersionCursor.INITIAL.timestamp());
        }
    }

    // -----------------------------------------------------------------------
    // isNewerThan
    // -----------------------------------------------------------------------

    @Nested
    class IsNewerThan {

        @Test
        void higherVersionIsNewer() {
            VersionCursor c5 = new VersionCursor(5, 5000);
            VersionCursor c3 = new VersionCursor(3, 3000);

            assertTrue(c5.isNewerThan(c3));
        }

        @Test
        void lowerVersionIsNotNewer() {
            VersionCursor c3 = new VersionCursor(3, 3000);
            VersionCursor c5 = new VersionCursor(5, 5000);

            assertFalse(c3.isNewerThan(c5));
        }

        @Test
        void equalVersionIsNotNewer() {
            VersionCursor c1 = new VersionCursor(5, 100);
            VersionCursor c2 = new VersionCursor(5, 200);

            assertFalse(c1.isNewerThan(c2));
            assertFalse(c2.isNewerThan(c1));
        }

        @Test
        void initialIsNotNewerThanAnything() {
            VersionCursor other = new VersionCursor(1, 100);

            assertFalse(VersionCursor.INITIAL.isNewerThan(other));
        }

        @Test
        void anyVersionIsNewerThanInitial() {
            VersionCursor c1 = new VersionCursor(1, 100);

            assertTrue(c1.isNewerThan(VersionCursor.INITIAL));
        }
    }

    // -----------------------------------------------------------------------
    // Record accessors
    // -----------------------------------------------------------------------

    @Nested
    class RecordAccessors {

        @Test
        void versionAccessor() {
            VersionCursor cursor = new VersionCursor(42, 99999);
            assertEquals(42, cursor.version());
        }

        @Test
        void timestampAccessor() {
            VersionCursor cursor = new VersionCursor(42, 99999);
            assertEquals(99999, cursor.timestamp());
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void negativeVersionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new VersionCursor(-1, 0));
        }

        @Test
        void negativeTimestampThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new VersionCursor(0, -1));
        }

        @Test
        void zeroVersionAndTimestampIsValid() {
            VersionCursor cursor = new VersionCursor(0, 0);
            assertEquals(0, cursor.version());
            assertEquals(0, cursor.timestamp());
        }
    }

    // -----------------------------------------------------------------------
    // Equality (record semantics)
    // -----------------------------------------------------------------------

    @Nested
    class Equality {

        @Test
        void sameFieldsAreEqual() {
            VersionCursor c1 = new VersionCursor(5, 1000);
            VersionCursor c2 = new VersionCursor(5, 1000);
            assertEquals(c1, c2);
            assertEquals(c1.hashCode(), c2.hashCode());
        }

        @Test
        void differentVersionsAreNotEqual() {
            VersionCursor c1 = new VersionCursor(5, 1000);
            VersionCursor c2 = new VersionCursor(6, 1000);
            assertNotEquals(c1, c2);
        }

        @Test
        void differentTimestampsAreNotEqual() {
            VersionCursor c1 = new VersionCursor(5, 1000);
            VersionCursor c2 = new VersionCursor(5, 2000);
            assertNotEquals(c1, c2);
        }
    }
}
