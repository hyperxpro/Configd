package io.configd.client;

import java.nio.charset.StandardCharsets;

/**
 * The driver-side path grammar and canonicalization, per driver-protocol RFC §01 (Paths and Access). A
 * driver <b>MUST</b> reject, client-side, any path that is not in its single canonical form BEFORE it goes on
 * the wire — otherwise non-canonical spellings such as {@code /a/../b}, {@code /a//b}, a trailing
 * slash, or a byte outside the segment alphabet reach the server as distinct literal keys, silently aliasing
 * and fragmenting the keyspace (two drivers that spell "the same" key differently disagree about it).
 *
 * <p>The grammar:
 * <ul>
 *   <li>a path is a slash-delimited sequence of non-empty segments;</li>
 *   <li>{@code seg-char = [A-Za-z0-9._-]} (ASCII) — any other byte is rejected (control bytes, spaces,
 *       reserved/percent characters, non-ASCII);</li>
 *   <li>the whole-segment forms {@code .} and {@code ..} are reserved (no relative traversal);</li>
 *   <li>an empty interior segment ({@code //}) is <b>invalid</b>, not collapsed;</li>
 *   <li>a single trailing slash MAY be stripped ({@code /a/b/} ≡ {@code /a/b}); the root {@code /} is kept;</li>
 *   <li>≤ 1024 bytes UTF-8 total, ≤ 64 segments, ≤ 256 bytes per segment.</li>
 * </ul>
 *
 * <p>This validator is tolerant of a leading slash so it serves both planes: the binary edge addresses an
 * <b>absolute</b> path ({@code /app/name}); the HTTP control plane takes a <b>flat</b> key ({@code app/name})
 * that is the same path relative to the root. The empty path and the bare root {@code /} denote the
 * whole store (a valid FULL/subtree target) and are accepted; each caller keeps its own absolute-vs-relative
 * policy on top of this shared segment grammar.
 */
public final class PathGrammar {

    /** The deployed key-length limit — a MUST, not just a default. */
    public static final int MAX_PATH_BYTES = 1024;

    private PathGrammar() {
    }

    /**
     * Validate that {@code path} is in canonical form, throwing {@link IllegalArgumentException} on any
     * violation. Accepts both the absolute ({@code /a/b}) and flat/relative ({@code a/b}) spellings, and the
     * whole-store forms (empty or {@code /}). A single trailing slash is tolerated.
     */
    public static void validateCanonical(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        int totalBytes = path.getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes > MAX_PATH_BYTES) {
            throw new IllegalArgumentException(
                    "path exceeds " + MAX_PATH_BYTES + " bytes (" + totalBytes + "): " + path);
        }
        // A single trailing slash may be stripped (but the root "/" is itself the whole store).
        String p = path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || p.equals("/")) {
            return; // the whole store (a FULL / root target) — no segments to check
        }
        String[] segments = p.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (i == 0 && seg.isEmpty()) {
                continue; // the leading '/' of an absolute path — the empty element before the first segment
            }
            if (seg.isEmpty()) {
                throw new IllegalArgumentException(
                        "empty path segment ('//') is invalid — collapse is not allowed, spell it explicitly: " + path);
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException(
                        "the segments '.' and '..' are reserved (no relative traversal): " + path);
            }
            for (int j = 0; j < seg.length(); j++) {
                char c = seg.charAt(j);
                if (!isSegChar(c)) {
                    throw new IllegalArgumentException("illegal character '" + c + "' (0x" + Integer.toHexString(c)
                            + ") in path segment — seg-char is [A-Za-z0-9._-]: " + path);
                }
            }
        }
    }

    private static boolean isSegChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }
}
