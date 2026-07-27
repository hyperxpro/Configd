package io.configd.client.edge;

import java.util.Objects;
import java.util.Optional;

/**
 * One applied config change emitted by a {@link Subscription}'s reactive stream — a verified, chain-ordered
 * mutation the client applied to its {@link LocalConfigView}. A {@code PUT} carries the new value; a
 * {@code DELETE} carries none. {@code version} is the {@code toVersion} of the delta the change belongs to
 * (the monotonic applied-mutation sequence), so a consumer can correlate a change with the store's version.
 */
public record ConfigChange(String key, Kind kind, byte[] value, long version) {

    public enum Kind {PUT, DELETE}

    public ConfigChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.PUT) {
            Objects.requireNonNull(value, "a PUT change must carry a value");
            value = value.clone();
        } else if (value != null) {
            throw new IllegalArgumentException("a DELETE change must not carry a value");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
    }

    public static ConfigChange put(String key, byte[] value, long version) {
        return new ConfigChange(key, Kind.PUT, value, version);
    }

    public static ConfigChange delete(String key, long version) {
        return new ConfigChange(key, Kind.DELETE, null, version);
    }

    public boolean isDelete() {
        return kind == Kind.DELETE;
    }

    /** The value for a PUT (a defensive copy), or {@code null} for a DELETE. */
    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    /** The value for a PUT (a defensive copy), or empty for a DELETE. */
    public Optional<byte[]> valueOptional() {
        return Optional.ofNullable(value).map(byte[]::clone);
    }

    @Override
    public String toString() {
        return "ConfigChange[key=" + key + ", kind=" + kind
                + ", valLen=" + (value == null ? -1 : value.length) + ", version=" + version + "]";
    }
}
