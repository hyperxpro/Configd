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

    String key();

    record Put(String key, byte[] value) implements ConfigMutation {

        public Put {
            Objects.requireNonNull(key, "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            Objects.requireNonNull(value, "value must not be null");
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        /** Unsafe zero-copy access for write path; callers MUST NOT mutate. */
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

    record Delete(String key) implements ConfigMutation {

        public Delete {
            Objects.requireNonNull(key, "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
        }
    }
}
