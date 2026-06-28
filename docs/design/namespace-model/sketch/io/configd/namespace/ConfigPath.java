package io.configd.namespace;

import java.nio.charset.StandardCharsets;

/**
 * A canonical, validated configuration path (RFC §3). A path is the LOGICAL hierarchy AND the storage
 * key string — the store/hash/Raft see only this string (path-model.md §6); the hierarchy is an
 * interpretation the API/ACL/watch layers apply. {@code shardFor(scope, value)} hashes the WHOLE
 * {@link #value()} — the {@code '/'} delimiters carry NO routing meaning (INV-PATH, RFC A2-4).
 *
 * <p>Construction normalizes to the single canonical form or throws — there is no "lenient" path.
 */
public record ConfigPath(String value) {

    /** Deployed key-length limit (ConfigWriteService.java:241). */
    public static final int MAX_TOTAL_BYTES = 1024;
    /** Recommended depth bound (RFC A3-5). */
    public static final int MAX_DEPTH = 64;
    /** Recommended per-segment bound (RFC A3-5). */
    public static final int MAX_SEGMENT_BYTES = 256;

    public ConfigPath {
        value = normalize(value);
    }

    /** Convenience factory. */
    public static ConfigPath of(String raw) {
        return new ConfigPath(raw);
    }

    /** True iff {@code c} is a legal {@code seg-char}: {@code [A-Za-z0-9._-]} (RFC A3 grammar). */
    public static boolean isSegChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }

    /**
     * Normalizes {@code raw} to its canonical form (RFC A3-2…A3-5) or throws {@link IllegalArgumentException}.
     * Absolute, {@code seg-char}-only, no empty/{@code .}/{@code ..} segments, single trailing slash
     * stripped (except root), case preserved, within the size/depth limits.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("path must be non-empty");
        }
        if (raw.charAt(0) != '/') {
            throw new IllegalArgumentException("path must be absolute (start with '/'): " + raw);
        }
        if (raw.equals("/")) {
            return "/"; // the root
        }
        // Strip a single trailing slash (A3-4 rule 2); the root case is already handled above.
        String s = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        // split with limit -1 keeps trailing empties so "a//b" -> ["a","","b"] is caught below.
        String[] segs = s.substring(1).split("/", -1);
        if (segs.length > MAX_DEPTH) {
            throw new IllegalArgumentException("path exceeds max depth " + MAX_DEPTH + ": " + raw);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (String seg : segs) {
            if (seg.isEmpty()) {
                throw new IllegalArgumentException("empty segment ('//'): " + raw);
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("relative segment not allowed: " + raw);
            }
            if (seg.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                throw new IllegalArgumentException("segment exceeds " + MAX_SEGMENT_BYTES + " bytes: " + seg);
            }
            for (int i = 0; i < seg.length(); i++) {
                if (!isSegChar(seg.charAt(i))) {
                    throw new IllegalArgumentException("illegal char '" + seg.charAt(i) + "' in path: " + raw);
                }
            }
            sb.append('/').append(seg);
        }
        String out = sb.toString();
        if (out.getBytes(StandardCharsets.UTF_8).length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("path exceeds " + MAX_TOTAL_BYTES + " bytes: " + raw);
        }
        return out;
    }

    @Override
    public String toString() {
        return value;
    }
}
