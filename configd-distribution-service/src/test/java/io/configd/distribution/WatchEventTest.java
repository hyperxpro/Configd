package io.configd.distribution;

import io.configd.store.ConfigMutation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchEventTest {

    private static final byte[] VALUE = "val".getBytes();

    @Nested
    class Construction {

        @Test
        void singleMutationEvent() {
            var put = new ConfigMutation.Put("key", VALUE);
            WatchEvent event = WatchEvent.of(put, 1);

            assertEquals(1, event.size());
            assertTrue(event.isSingle());
            assertEquals(1, event.version());
            assertEquals(put, event.mutations().getFirst());
        }

        @Test
        void multiMutationEvent() {
            var put = new ConfigMutation.Put("a", VALUE);
            var delete = new ConfigMutation.Delete("b");
            WatchEvent event = new WatchEvent(List.of(put, delete), 5);

            assertEquals(2, event.size());
            assertFalse(event.isSingle());
            assertEquals(5, event.version());
        }

        @Test
        void nullMutationsThrows() {
            assertThrows(NullPointerException.class, () -> new WatchEvent(null, 1));
        }

        @Test
        void emptyMutationsThrows() {
            assertThrows(IllegalArgumentException.class, () -> new WatchEvent(List.of(), 1));
        }

        @Test
        void zeroVersionThrows() {
            var put = new ConfigMutation.Put("key", VALUE);
            assertThrows(IllegalArgumentException.class, () -> new WatchEvent(List.of(put), 0));
        }

        @Test
        void negativeVersionThrows() {
            var put = new ConfigMutation.Put("key", VALUE);
            assertThrows(IllegalArgumentException.class, () -> new WatchEvent(List.of(put), -1));
        }

        @Test
        void nullSingleMutationThrows() {
            assertThrows(NullPointerException.class, () -> WatchEvent.of(null, 1));
        }

        @Test
        void mutationsListIsImmutable() {
            ConfigMutation put = new ConfigMutation.Put("key", VALUE);
            var mutable = new java.util.ArrayList<ConfigMutation>(List.of(put));
            WatchEvent event = new WatchEvent(mutable, 1);

            assertThrows(UnsupportedOperationException.class,
                    () -> event.mutations().add(new ConfigMutation.Delete("x")));
        }
    }

    @Nested
    class AffectedKeys {

        @Test
        void singlePutAffectsOneKey() {
            WatchEvent event = WatchEvent.of(new ConfigMutation.Put("key", VALUE), 1);
            assertEquals(java.util.Set.of("key"), event.affectedKeys());
        }

        @Test
        void singleDeleteAffectsOneKey() {
            WatchEvent event = WatchEvent.of(new ConfigMutation.Delete("key"), 1);
            assertEquals(java.util.Set.of("key"), event.affectedKeys());
        }

        @Test
        void mixedMutationsDeduplicateKeys() {
            var mutations = List.<ConfigMutation>of(
                    new ConfigMutation.Put("a", VALUE),
                    new ConfigMutation.Delete("a"),
                    new ConfigMutation.Put("b", VALUE)
            );
            WatchEvent event = new WatchEvent(mutations, 3);

            assertEquals(java.util.Set.of("a", "b"), event.affectedKeys());
        }

        @Test
        void emptySetForSameKeyMultipleTimes() {
            var mutations = List.<ConfigMutation>of(
                    new ConfigMutation.Put("same", VALUE),
                    new ConfigMutation.Put("same", "new".getBytes())
            );
            WatchEvent event = new WatchEvent(mutations, 2);

            assertEquals(java.util.Set.of("same"), event.affectedKeys());
        }
    }

    @Nested
    class RecordSemantics {

        @Test
        void equalityBasedOnContent() {
            var put = new ConfigMutation.Put("key", VALUE);
            WatchEvent e1 = new WatchEvent(List.of(put), 1);
            WatchEvent e2 = new WatchEvent(List.of(put), 1);
            assertEquals(e1, e2);
        }

        @Test
        void differentVersionNotEqual() {
            var put = new ConfigMutation.Put("key", VALUE);
            WatchEvent e1 = new WatchEvent(List.of(put), 1);
            WatchEvent e2 = new WatchEvent(List.of(put), 2);
            assertNotEquals(e1, e2);
        }
    }
}
