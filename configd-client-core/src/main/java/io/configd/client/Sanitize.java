package io.configd.client;

/**
 * Sanitizes untrusted, server-controlled diagnostic text before it is logged or attached to an exception.
 * The {@code ERROR_CLOSE} / {@code WATCH_CANCELED} {@code message} and HTTP error bodies are arbitrary
 * bytes under a possibly-malicious server: control characters, newlines, ANSI/CSI escapes, or NULs that
 * would forge log lines or inject terminal sequences. A driver <b>MUST NOT</b> machine-parse them and
 * <b>MUST</b> sanitize them before display.
 *
 * <p>The transform is deliberately conservative and lossless-to-print: C0/C1 control characters (including
 * {@code \n}, {@code \r}, {@code \t}, ESC, NUL) and the DEL character are replaced with a visible
 * {@code \}{@code uXXXX} escape; all other code points pass through. The result is bounded to
 * {@link #MAX_LEN} code units so a hostile server cannot make a log line unbounded.
 */
public final class Sanitize {

    public static final int MAX_LEN = 512;

    private Sanitize() {
    }

    public static String message(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        boolean truncated = raw.length() > MAX_LEN;
        int limit = truncated ? MAX_LEN : raw.length();
        StringBuilder sb = new StringBuilder(limit + 8);
        for (int i = 0; i < limit; i++) {
            char c = raw.charAt(i);
            if (isControl(c)) {
                sb.append("\\u").append(hex4(c));
            } else {
                sb.append(c);
            }
        }
        if (truncated) {
            sb.append("…"); // a single, non-control ellipsis marks that content was dropped
        }
        return sb.toString();
    }

    /** C0 controls (0x00–0x1F), DEL (0x7F), and the C1 range (0x80–0x9F) — the log-forging / ANSI bytes. */
    private static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }

    private static String hex4(char c) {
        String h = Integer.toHexString(c);
        return "0000".substring(h.length()) + h;
    }
}
