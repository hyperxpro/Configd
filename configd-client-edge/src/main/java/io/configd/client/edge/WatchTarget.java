package io.configd.client.edge;

import io.configd.client.PathGrammar;
import io.configd.distribution.wire.EdgeFrame;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;

/** Watch target: (scope, kind, path) address plus flags. KEY addresses one shard; PREFIX/FULL scatter all. */
public record WatchTarget(int scope, Kind kind, String path, EnumSet<Flag> flags) {

    public static final int MAX_PATH_BYTES = 1024;

    public enum Kind {KEY, PREFIX, FULL}

    public enum Flag {
        FULL_CHAIN_VERIFY(EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY),
        PREV_VALUE(EdgeFrame.WATCH_FLAG_PREV_VALUE),
        WITH_INITIAL_SNAPSHOT(EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT);

        final int bit;

        Flag(int bit) {
            this.bit = bit;
        }
    }

    public WatchTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(flags, "flags");
        if (scope < 0 || scope > 0xFF) {
            throw new IllegalArgumentException("scope must fit a u8 (0..255): " + scope);
        }
        flags = EnumSet.copyOf(flags.isEmpty() ? EnumSet.noneOf(Flag.class) : flags);
        if (kind == Kind.FULL) {
            if (!path.isEmpty()) {
                throw new IllegalArgumentException("a FULL watch must carry an empty path, got: " + path);
            }
        } else {
            validatePath(path);
        }
    }

    public static WatchTarget key(String path) {
        return new WatchTarget(0, Kind.KEY, path, EnumSet.noneOf(Flag.class));
    }

    public static WatchTarget prefix(String prefix) {
        return new WatchTarget(0, Kind.PREFIX, prefix, EnumSet.noneOf(Flag.class));
    }

    public static WatchTarget full() {
        return new WatchTarget(0, Kind.FULL, "", EnumSet.noneOf(Flag.class));
    }

    public WatchTarget with(Flag... extra) {
        EnumSet<Flag> next = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        for (Flag f : extra) {
            next.add(f);
        }
        return new WatchTarget(scope, kind, path, next);
    }

    public boolean fullChainVerify() {
        return flags.contains(Flag.FULL_CHAIN_VERIFY);
    }

    public boolean withInitialSnapshot() {
        return flags.contains(Flag.WITH_INITIAL_SNAPSHOT);
    }

    public int targetKindByte() {
        return switch (kind) {
            case KEY -> EdgeFrame.WATCH_TARGET_KEY;
            case PREFIX -> EdgeFrame.WATCH_TARGET_PREFIX;
            case FULL -> EdgeFrame.WATCH_TARGET_FULL;
        };
    }

    public int flagBits() {
        int bits = 0;
        for (Flag f : flags) {
            bits |= f.bit;
        }
        return bits;
    }

    public byte[] pathBytes() {
        return path.getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(String key) {
        return switch (kind) {
            case FULL -> true;
            case PREFIX -> key.startsWith(path);
            case KEY -> key.equals(path);
        };
    }

    private static void validatePath(String path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("a KEY/PREFIX watch requires a non-empty path");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a watch path must be absolute (start with '/'): " + path);
        }
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException(
                    "watch path exceeds " + MAX_PATH_BYTES + " bytes: " + bytes.length);
        }
        PathGrammar.validateCanonical(path);
    }
}
