package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;

import java.nio.charset.StandardCharsets;

/**
 * Validates a {@code WATCH_CREATE} target against the path grammar and range rules,
 * producing the {@code BAD_SUBSCRIBE} (400-class) diagnostic the veneer emits on a malformed
 * target. Structural framing (CRC, frame size, the FULL=>empty-path invariant) is the codec's
 * job; this is the <b>semantic</b> target validation the codec deliberately leaves to the
 * session layer (see {@link EdgeFrame.WatchCreate}).
 *
 * <p><b>Self-contained on purpose.</b> The binary write-path validator lives in
 * {@code configd-control-plane-api} ({@code ConfigWriteService}), which the fan-out plane
 * cannot depend on (reactor order). This is a focused re-implementation of the same absolute
 * path grammar: {@code seg-char}-only segments, no empty/{@code .}/{@code ..} segment,
 * {@code <=} 1024 UTF-8 bytes - with the section 3.4 subtree allowance that a PREFIX target
 * MAY carry a single trailing {@code /} (the {@code /a/} == {@code /a/**} subtree form). The
 * literal match model (subtree {@code startsWith}) means the trailing slash is significant
 * and is preserved on {@link WatchTarget#path()}.
 */
final class WatchTargetValidator {

    /** A path MUST NOT exceed 1024 UTF-8 bytes (the deployed key-length limit). */
    static final int MAX_PATH_BYTES = 1024;

    /** The known scope ordinals (GLOBAL=0, REGIONAL=1, LOCAL=2). */
    static final int MAX_SCOPE = 2;

    /**
     * The recognized {@code WATCH_CREATE} flag bits: {@code full_chain_verify} (bit0),
     * {@code prev_value} (bit1), {@code with_initial_snapshot} (bit2). A driver MUST NOT set a flag
     * it has not negotiated, and the server fails closed on an unrecognized bit - so a flags
     * byte with any bit outside this mask is rejected {@code BAD_SUBSCRIBE}.
     */
    static final int KNOWN_FLAGS_MASK = EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY
            | EdgeFrame.WATCH_FLAG_PREV_VALUE
            | EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT;

    private WatchTargetValidator() {
    }

    /**
     * @return {@code null} if the target is well-formed; otherwise a human-readable reason
     *         the caller surfaces as {@code WATCH_CANCELED(BAD_SUBSCRIBE)}.
     */
    static String validate(int scope, int targetKind, byte[] pathBytes, int flags) {
        // Fail closed on an unrecognized flag bit: a driver must not set a flag it
        // has not negotiated, so a bit outside the known mask is a malformed subscription.
        if ((flags & ~KNOWN_FLAGS_MASK) != 0) {
            return "unknown WATCH_CREATE flag bit(s) set (fail-closed on unrecognized, W5-4a): 0x"
                    + Integer.toHexString(flags & 0xFF);
        }
        if (scope < 0 || scope > MAX_SCOPE) {
            return "scope out of range (0..2): " + scope;
        }
        switch (targetKind) {
            case EdgeFrame.WATCH_TARGET_FULL -> {
                // Defensive: the WatchCreate record already guarantees FULL=>empty path, so a
                // decoded FULL frame cannot reach here non-empty. Kept for direct-call safety.
                return pathBytes.length == 0 ? null : "FULL target must carry an empty path";
            }
            case EdgeFrame.WATCH_TARGET_KEY, EdgeFrame.WATCH_TARGET_PREFIX -> {
                if (pathBytes.length == 0) {
                    return "KEY/PREFIX target requires a non-empty path (W2-4)";
                }
                if (pathBytes.length > MAX_PATH_BYTES) {
                    return "path exceeds " + MAX_PATH_BYTES + " bytes: " + pathBytes.length;
                }
                String path = new String(pathBytes, StandardCharsets.UTF_8);
                return validateGrammar(path, targetKind == EdgeFrame.WATCH_TARGET_PREFIX);
            }
            default -> {
                return "target_kind out of range (0=KEY,1=PREFIX,2=FULL): " + targetKind;
            }
        }
    }

    /**
     * Validates the absolute path grammar. A PREFIX subtree target MAY carry one trailing
     * {@code /} (the {@code /a/} subtree form); a KEY target MUST be a concrete canonical path.
     */
    private static String validateGrammar(String path, boolean prefix) {
        if (path.isEmpty() || path.charAt(0) != '/') {
            return "path must be absolute (begin with '/'): " + path;
        }
        // Subtree form: strip a single trailing slash for a PREFIX before segment checks
        // (the root "/" is left intact). The slash stays on WatchTarget.path() for the filter.
        String body = path;
        if (prefix && body.length() > 1 && body.charAt(body.length() - 1) == '/') {
            body = body.substring(0, body.length() - 1);
        }
        if (body.equals("/")) {
            return null; // the root (whole-scope subtree, or a degenerate root key)
        }
        // body now is "/seg(/seg)*"; split on '/' keeping trailing empties so "//" and a
        // stray trailing '/' surface as an empty segment.
        String[] segments = body.substring(1).split("/", -1);
        for (String seg : segments) {
            if (seg.isEmpty()) {
                return "empty path segment ('//' or a trailing '/') not allowed: " + path;
            }
            if (seg.equals(".") || seg.equals("..")) {
                return "relative path segment ('.'/'..') not allowed: " + path;
            }
            for (int i = 0; i < seg.length(); i++) {
                if (!isSegChar(seg.charAt(i))) {
                    return "invalid character '" + seg.charAt(i) + "' in path segment (seg-char is "
                            + "[A-Za-z0-9._-]): " + path;
                }
            }
        }
        return null;
    }

    /** {@code seg-char = ALPHA / DIGIT / "." / "_" / "-"} (the allowed characters in a path segment). */
    private static boolean isSegChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }
}
