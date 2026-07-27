package io.configd.client;

import java.nio.charset.StandardCharsets;

/**
 * Driver-side path grammar per RFC §01. Client must reject client-side any non-canonical path BEFORE wire
 * (canonical form enforces agreement on what "the same" key is). Grammar: slash-delimited segments, seg-char
 * [A-Za-z0-9._-], dot-forms reserved, empty interior invalid, trailing slash stripped, ≤1024 bytes UTF-8.
 * Tolerant of leading slash for both planes. Empty path and root / denote whole store.
 */
public final class PathGrammar {

    public static final int MAX_PATH_BYTES = 1024;

    private PathGrammar() {
    }

    public static void validateCanonical(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        int totalBytes = path.getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes > MAX_PATH_BYTES) {
            throw new IllegalArgumentException(
                    "path exceeds " + MAX_PATH_BYTES + " bytes (" + totalBytes + "): " + path);
        }
        String p = path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || p.equals("/")) {
            return;
        }
        String[] segments = p.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (i == 0 && seg.isEmpty()) {
                continue;
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
