package io.configd.store;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single mutation to the config store. Sealed to the two permitted variants:
 * {@link Put} (upsert) and {@link Delete} (tombstone).
 * <p>
 * Used both for applying changes from the Raft log and for representing
 * deltas between snapshots.
 */
public sealed interface ConfigMutation {

    /** The config key being mutated. */
    String key();

    /**
     * Upsert a key-value pair.
     *
     * @param key   config key (non-null, non-blank)
     * @param value raw config bytes (non-null)
     */
    record Put(String key, byte[] value) implements ConfigMutation {

        public Put {
            Objects.requireNonNull(key, "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            Objects.requireNonNull(value, "value must not be null");
            value = value.clone();
        }

        /** Returns a defensive copy of the value bytes. */
        @Override
        public byte[] value() {
            return value.clone();
        }

        /** Internal zero-copy access for the write path. */
        public byte[] valueUnsafe() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Put that
                    && this.key.equals(that.key)
                    && Arrays.equals(this.value, that.value);
        }

        @Override
        public int hashCode() {
            return 31 * key.hashCode() + Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Put[key=" + key + ", len=" + value.length + "]";
        }
    }

    /**
     * Delete (tombstone) a key.
     *
     * @param key config key to remove (non-null, non-blank)
     */
    record Delete(String key) implements ConfigMutation {

        public Delete {
            Objects.requireNonNull(key, "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
        }
    }
}
