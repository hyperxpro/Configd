package io.configd.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanitizeTest {

    private static final char ESC = (char) 0x1B;
    private static final char NUL = (char) 0x00;

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertEquals("", Sanitize.message(null));
        assertEquals("", Sanitize.message(""));
    }

    @Test
    void printableTextIsUnchanged() {
        assertEquals("quarantined for 30s; retry later", Sanitize.message("quarantined for 30s; retry later"));
    }

    @Test
    void controlAnsiAndNulAreEscaped() {
        // newline, ESC (0x1B), and NUL (0x00) — the log-forging / terminal-injection bytes.
        String raw = "a\nb" + ESC + "[31mX" + NUL;
        String out = Sanitize.message(raw);
        assertFalse(out.indexOf('\n') >= 0, "raw newline is gone");
        assertFalse(out.indexOf(ESC) >= 0, "raw ESC is gone");
        assertFalse(out.indexOf(NUL) >= 0, "raw NUL is gone");
        assertTrue(out.contains("\\u000a"), "newline rendered as \\u000a");
        assertTrue(out.contains("\\u001b"), "ESC rendered as \\u001b");
        assertTrue(out.contains("\\u0000"), "NUL rendered as \\u0000");
        assertTrue(out.startsWith("a") && out.contains("[31mX"), "printable content preserved");
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            assertTrue(c >= 0x20 && !(c >= 0x7F && c <= 0x9F),
                    "output carries no control byte, found 0x" + Integer.toHexString(c));
        }
    }

    @Test
    void overlongInputIsTruncated() {
        String raw = "x".repeat(Sanitize.MAX_LEN + 100);
        String out = Sanitize.message(raw);
        assertTrue(out.length() <= Sanitize.MAX_LEN + 1, "bounded to MAX_LEN plus the ellipsis");
        assertTrue(out.endsWith("…"), "truncation marked");
    }
}
