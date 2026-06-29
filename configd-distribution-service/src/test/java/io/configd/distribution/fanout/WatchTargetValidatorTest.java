package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WatchTargetValidator} — the semantic {@code WATCH_CREATE} target +
 * flag validation that produces the {@code BAD_SUBSCRIBE} diagnostic (RFC §2 W2-4, W5-4a). A
 * {@code null} return means well-formed; a non-null string is the reject reason.
 */
@DisplayName("WatchTargetValidator — WATCH_CREATE target + flag validation (BAD_SUBSCRIBE)")
class WatchTargetValidatorTest {

    private static byte[] p(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String key(String path, int flags) {
        return WatchTargetValidator.validate(0, EdgeFrame.WATCH_TARGET_KEY, p(path), flags);
    }

    private static String prefix(String path, int flags) {
        return WatchTargetValidator.validate(0, EdgeFrame.WATCH_TARGET_PREFIX, p(path), flags);
    }

    @Test
    @DisplayName("known flag bits accepted; any unknown bit rejected (W5-4a fail-closed)")
    void flagValidation() {
        assertNull(key("/a", 0), "no flags");
        assertNull(key("/a", EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY), "bit0 full_chain_verify");
        assertNull(key("/a", EdgeFrame.WATCH_FLAG_PREV_VALUE), "bit1 prev_value");
        assertNull(key("/a", EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT), "bit2 with_initial_snapshot");
        assertNull(key("/a", 0x07), "all three known bits");
        assertNotNull(key("/a", 0x08), "bit3 (unknown) ⇒ reject");
        assertNotNull(key("/a", 0x80), "bit7 (unknown) ⇒ reject");
        assertNotNull(key("/a", 0xFF), "any unknown bit set ⇒ reject");
        assertNotNull(key("/a", EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY | 0x08),
                "a known bit mixed with an unknown bit ⇒ reject");
    }

    @Test
    @DisplayName("scope and target_kind ranges")
    void rangeChecks() {
        assertNotNull(WatchTargetValidator.validate(3, EdgeFrame.WATCH_TARGET_KEY, p("/a"), 0), "scope > 2");
        assertNotNull(WatchTargetValidator.validate(-1, EdgeFrame.WATCH_TARGET_KEY, p("/a"), 0), "scope < 0");
        assertNotNull(WatchTargetValidator.validate(0, 9, p("/a"), 0), "target_kind out of range");
    }

    @Test
    @DisplayName("path grammar: absolute seg-char, no empty/./.., ≤1024B; FULL empty; PREFIX trailing slash")
    void grammar() {
        assertNull(key("/a/b", 0), "a canonical absolute KEY path");
        assertNull(prefix("/a/", 0), "PREFIX subtree may carry one trailing slash");
        assertNull(WatchTargetValidator.validate(0, EdgeFrame.WATCH_TARGET_FULL, new byte[0], 0), "FULL empty path");
        assertNotNull(key("a/b", 0), "not absolute");
        assertNotNull(key("/a//b", 0), "empty segment ('//')");
        assertNotNull(key("/a/../b", 0), "relative segment ('..')");
        assertNotNull(key("/a/b!", 0), "invalid seg-char");
        assertNotNull(key("", 0), "empty path for a KEY target");
        assertNotNull(WatchTargetValidator.validate(0, EdgeFrame.WATCH_TARGET_FULL, p("/x"), 0),
                "FULL with a non-empty path");
        assertNotNull(key("/" + "a".repeat(1025), 0), "path exceeds 1024 bytes");
    }
}
