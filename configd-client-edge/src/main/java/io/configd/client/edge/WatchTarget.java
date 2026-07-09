package io.configd.client.edge;

import io.configd.client.PathGrammar;
import io.configd.distribution.wire.EdgeFrame;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;

/**
 * What a watch observes: a {@code (scope, kind, path)} address plus the request {@link Flag}s (§02 W2-2 /
 * W5-2). A KEY watch addresses exactly one shard; a PREFIX (subtree) or FULL watch scatters across all shards
 * (W2-3). The path is validated <b>client-side</b> to the §01 grammar (W2-4): absolute, UTF-8, ≤ 1024 bytes,
 * empty iff FULL.
 */
public record WatchTarget(int scope, Kind kind, String path, EnumSet<Flag> flags) {

    /** Per §01 A2-1: the whole store is {@code /} for FULL; KEY/PREFIX paths are absolute. Max 1024 bytes (W2-4). */
    public static final int MAX_PATH_BYTES = 1024;

    /** The three target forms (W2-2). */
    public enum Kind {KEY, PREFIX, FULL}

    /** The {@code WATCH_CREATE} request flag bits (W5-4a). */
    public enum Flag {
        /** Stream the verbatim signed chain; the client verifies + filters locally. Requires root scope (W7-3). */
        FULL_CHAIN_VERIFY(EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY),
        /** Request the pre-image of each change (etcd {@code prev_kv}); MAY be unsupported in v1 (W5-4a). */
        PREV_VALUE(EdgeFrame.WATCH_FLAG_PREV_VALUE),
        /** Request the existing state before tailing — the only way to get current state (W3-4 / W5-4a). */
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

    /** A KEY watch on {@code path} in GLOBAL scope, from-now, no flags. */
    public static WatchTarget key(String path) {
        return new WatchTarget(0, Kind.KEY, path, EnumSet.noneOf(Flag.class));
    }

    /** A PREFIX (subtree) watch on {@code prefix} in GLOBAL scope. */
    public static WatchTarget prefix(String prefix) {
        return new WatchTarget(0, Kind.PREFIX, prefix, EnumSet.noneOf(Flag.class));
    }

    /** A FULL (whole-scope) watch in GLOBAL scope. Requires root scope (W7-3). */
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

    /** The {@code target_kind} byte (0=KEY, 1=PREFIX, 2=FULL). */
    public int targetKindByte() {
        return switch (kind) {
            case KEY -> EdgeFrame.WATCH_TARGET_KEY;
            case PREFIX -> EdgeFrame.WATCH_TARGET_PREFIX;
            case FULL -> EdgeFrame.WATCH_TARGET_FULL;
        };
    }

    /** The OR of the flag bits for the {@code WATCH_CREATE} frame. */
    public int flagBits() {
        int bits = 0;
        for (Flag f : flags) {
            bits |= f.bit;
        }
        return bits;
    }

    /** UTF-8 path bytes (empty for FULL). */
    public byte[] pathBytes() {
        return path.getBytes(StandardCharsets.UTF_8);
    }

    /** True iff {@code key} is under this target (client-side filter for full_chain_verify mode). */
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
        // §01 A3: reject a non-canonical / non-seg-char path client-side. A PREFIX subtree target's trailing
        // slash is tolerated (kept for startsWith matching); '.'/'..'/'//' and illegal bytes are rejected so the
        // driver never puts an aliasing key on the wire.
        PathGrammar.validateCanonical(path);
    }
}
