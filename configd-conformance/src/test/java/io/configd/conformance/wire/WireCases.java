package io.configd.conformance.wire;

import io.configd.distribution.wire.EdgeFrameCodec.CodecException;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.EdgeFrameGoldenBytes;
import io.configd.distribution.wire.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Runner I (wire-format conformance) -- the case corpus. Two directions, per the protobuf-conformance model:
 *
 * <ul>
 *   <li><b>ACCEPT</b> -- the pinned golden vectors ({@link EdgeFrameGoldenBytes}, the single source of truth,
 *       reused via the wire module's test-jar): each must decode then re-encode byte-for-byte identically
 *       (the cross-language wire oracle, §00 OV5-5).
 *   <li><b>REJECT</b> -- a poison-frame corpus enumerating the codec's reject paths (§06/§07 and the decode
 *       taxonomy): each corrupt frame must be rejected with a specific {@link io.configd.distribution.wire.ErrorCode}.
 * </ul>
 *
 * Each case yields an {@link #outcome} string (ACCEPT / REJECT:&lt;code&gt; / MISMATCH / REJECT:OTHER); the
 * expected outcome per case lives in the checked-in manifest ({@code wire-manifest.txt}), and
 * {@link WireConformanceRatchetTest} asserts the actual set equals the declared set (an unexpected pass or an
 * unexpected fail both break CI -- the {@code --failure_list} bidirectional ratchet).
 */
final class WireCases {

    /** One wire case: a stable id + a producer of its actual outcome string. */
    record Case(String id, java.util.function.Supplier<String> actualOutcome) {
        String outcome() {
            try {
                return actualOutcome.get();
            } catch (Throwable t) {
                return "ERROR:" + t.getClass().getSimpleName();
            }
        }
    }

    private WireCases() {
    }

    static List<Case> all() {
        List<Case> cases = new ArrayList<>();
        cases.addAll(goldenRoundTrips());
        cases.addAll(poison());
        cases.addAll(boundsAndSanitizeCases());
        return cases;
    }

    // ACCEPT direction: golden round-trips.

    private static List<Case> goldenRoundTrips() {
        List<Case> cases = new ArrayList<>();
        for (int version = 1; version <= 4; version++) {
            byte ver = (byte) version;
            for (Map.Entry<String, byte[]> e : EdgeFrameGoldenBytes.forVersion(version).entrySet()) {
                byte[] golden = e.getValue();
                cases.add(new Case("golden.v" + version + "." + e.getKey(),
                        () -> roundTrip(golden, ver)));
            }
        }
        return cases;
    }

    /** Decodes {@code golden}, re-encodes at {@code version}: byte-identical is ACCEPT, otherwise MISMATCH or REJECT. */
    private static String roundTrip(byte[] golden, byte version) {
        EdgeFrame frame;
        try {
            frame = EdgeFrameCodec.decode(golden);
        } catch (CodecException ce) {
            return "REJECT:" + ce.code();
        }
        byte[] reencoded = EdgeFrameCodec.encode(frame, version);
        return java.util.Arrays.equals(golden, reencoded) ? "ACCEPT" : "MISMATCH";
    }

    // REJECT direction: the poison corpus.

    private static List<Case> poison() {
        Map<String, byte[]> v1 = EdgeFrameGoldenBytes.forVersion(1);
        Map<String, byte[]> v2 = EdgeFrameGoldenBytes.forVersion(2);
        Map<String, byte[]> v4 = EdgeFrameGoldenBytes.forVersion(4);
        byte[] anyV1 = firstValue(v1);
        byte[] watch = pick(v2, "watch_create", firstValue(v2));
        byte[] auth = firstValue(v4);

        List<Case> cases = new ArrayList<>();

        // Too short to hold header+trailer.
        cases.add(decodeCase("poison.too-short", new byte[]{0, 0, 0, 4}));

        // Declared length exceeds the 2 MiB cap. The buffer is >= the 10-byte minimum so the length-field
        // check (FRAME_TOO_LARGE) is reached, not the too-short guard.
        cases.add(decodeCase("poison.length-over-cap", intBE(3 * 1024 * 1024, 12)));

        // Declared length disagrees with the actual byte count (not over cap, not under min).
        cases.add(decodeCase("poison.length-mismatch", withLengthField(anyV1, anyV1.length + 16)));

        // A flipped payload byte with the original (now-stale) CRC: CRC mismatch.
        cases.add(decodeCase("poison.crc-mismatch", flipPayloadByte(anyV1)));

        // An unsupported version byte, CRC recomputed so the reject is the version (not the CRC).
        cases.add(decodeCase("poison.bad-version", reversionRecrc(anyV1, (byte) 0x05)));

        // A structurally valid 0x02 watch frame fed to a 0x01-pinned decode: version-pin reject.
        cases.add(new Case("poison.version-pin-mismatch",
                () -> decodeOutcome(() -> EdgeFrameCodec.decode(watch, EdgeFrameCodec.EDGE_WIRE_VERSION))));

        // An unknown type byte, CRC recomputed.
        cases.add(decodeCase("poison.unknown-type", retypeRecrc(anyV1, (byte) 0x7F)));

        // A 0x02 watch frame re-stamped 0x01 (type/version combination illegal), CRC recomputed.
        cases.add(decodeCase("poison.watch-type-on-v1", reversionRecrc(watch, EdgeFrameCodec.EDGE_WIRE_VERSION)));

        // A 0x04 auth frame re-stamped 0x01 (auth type illegal off 0x04), CRC recomputed.
        cases.add(decodeCase("poison.auth-type-on-v1", reversionRecrc(auth, EdgeFrameCodec.EDGE_WIRE_VERSION)));

        // A trailing byte inside the declared length that the payload parser does not consume.
        cases.add(decodeCase("poison.trailing-bytes", appendPayloadByte(anyV1)));

        // Inner-payload bounds rejects: the reject-before-allocate hardening (§06 F3-2, the amplification and
        // underflow defense). Each mutates one inner field of a valid golden to an out-of-bounds value
        // (recomputing the CRC so the outer frame is intact), proving a conforming codec bounds every inner
        // length/count before allocating -- not just the outer frame.
        byte[] authBearer = pick(v4, "auth_bearer", auth);
        byte[] subFull = pick(v1, "subscribe_full_store", anyV1);
        byte[] subOk = pick(v1, "subscribe_ok_tail", anyV1);
        byte[] notifyOne = pick(v1, "notify_single_unsigned", pick(v1, "notify_batch", anyV1));

        // AUTH scheme byte not in {BEARER=1, BASIC=2} (payload offset 0).
        cases.add(decodeCase("poison.inner.auth-unknown-scheme", subU8(authBearer, 0, (byte) 0x09)));
        // NOTIFY count > MAX_NOTIFY_BATCH (payload offset 0, the count u32).
        cases.add(decodeCase("poison.inner.notify-count-over-cap", subU32(notifyOne, 0, 100)));
        // SUBSCRIBE prefixCount amplifier: prefixCount * 4 > remaining (payload offset 1, after the fullStore byte).
        cases.add(decodeCase("poison.inner.subscribe-prefix-count", subU32(subFull, 1, 0x7FFFFFFF)));
        // SUBSCRIBE topologyEpoch == 0 (reserved-illegal): payload offset 5 (after fullStore u8 + prefixCount=0 u32).
        cases.add(decodeCase("poison.inner.subscribe-topology-epoch-zero", subU64Zero(subFull, 5)));
        // NOTIFY inner mutations-blob length > remaining: payload offset 4 (count) + 8*4 (seq/ts/from/to) = 36.
        cases.add(decodeCase("poison.inner.notify-blob-length", subU32(notifyOne, 36, 0x7FFFFFFF)));
        // SUBSCRIBE_OK mode ordinal out of range: payload offset 8 (after latestSeq u64).
        cases.add(decodeCase("poison.inner.subscribe-ok-mode-ordinal", subU8(subOk, 8, (byte) 0x7F)));

        return cases;
    }

    /**
     * The §06 F5 (u64 field ranges) and F6-9 (ERROR_CLOSE message) reject/passthrough paths, on top of the
     * base poison corpus. Each field-range case mutates one inner u64 of a valid golden to a high-bit or zero
     * value (recomputing the outer CRC) and asserts the specific reject the record constructor / cursor codec
     * yields; the ERROR_CLOSE passthrough case proves the codec preserves an untrusted control-byte message
     * byte-for-byte (sanitization is the driver's job, not the codec's -- F6-9).
     */
    private static List<Case> boundsAndSanitizeCases() {
        Map<String, byte[]> v1 = EdgeFrameGoldenBytes.forVersion(1);
        Map<String, byte[]> v2 = EdgeFrameGoldenBytes.forVersion(2);
        byte[] cursorAck = pick(v1, "cursor_ack", firstValue(v1));
        byte[] subFull = pick(v1, "subscribe_full_store", firstValue(v1));
        byte[] errClose = pick(v1, "error_frame_corrupt", firstValue(v1));
        byte[] watchCreate = pick(v2, "watch_create", firstValue(v2));

        List<Case> cases = new ArrayList<>();

        // F6-9 (passthrough): the codec preserves an ERROR_CLOSE message carrying control bytes (newline, ANSI
        // ESC, NUL) byte-for-byte through a decode/re-encode round trip -- it does not sanitize or reject it.
        // That round trip is the wire-observable fact that makes sanitize-before-display the driver's job.
        String controlBytes = "boom\n" + (char) 0x1B + "[31mY" + (char) 0x00 + "Z"; // newline + ANSI ESC + NUL
        byte[] hostileMsg = EdgeFrameCodec.encode(
                new EdgeFrame.ErrorClose(ErrorCode.PROTOCOL_VIOLATION, controlBytes));
        cases.add(new Case("error-close.control-bytes-in-message", () -> roundTrip(hostileMsg, (byte) 1)));

        // F6-9 (reject): an ERROR_CLOSE code byte outside the 1..13 taxonomy decodes as FRAME_CORRUPT
        // (ErrorCode.fromCode rejects it; the code is payload offset 0).
        cases.add(decodeCase("poison.inner.error-close-unknown-code", subU8(errClose, 0, (byte) 0xFF)));

        // F5-1 (client-emitted seq): a CURSOR_ACK.seq with the high bit set (>= 2^63) decodes as FRAME_CORRUPT
        // (the CursorAck ctor validates non-negative; seq is payload offset 0).
        cases.add(decodeCase("poison.inner.cursor-ack-seq-high-bit", subU8(cursorAck, 0, (byte) 0x80)));

        // F5-1 (client-emitted resumeCursor): a SUBSCRIBE.resumeCursor with the high bit set decodes as
        // FRAME_CORRUPT (payload offset 13 = fullStore u8 + prefixCount u32(0) + topologyEpoch u64).
        cases.add(decodeCase("poison.inner.subscribe-resume-cursor-high-bit", subU8(subFull, 13, (byte) 0x80)));

        // F5-2 (failover sentinel): SUBSCRIBE.failoverResumeCursor's ONLY legal high-bit value is
        // 0xFFFF...FF ("none"); any other high-bit pattern decodes as FRAME_CORRUPT. Overwrite the golden's
        // sentinel with 0x8000...0 (payload offset 21 = after resumeCursor u64).
        cases.add(decodeCase("poison.inner.subscribe-failover-cursor-nonsentinel",
                subU64(subFull, 21, 0x8000000000000000L)));

        // F5-3 / F8-2 / W3-5 (cursor epoch): the cursor vector's topologyEpoch 0 is reserved-illegal, decoding
        // as FRAME_CORRUPT via decodeCursor -- a distinct code path from the SUBSCRIBE-inline epoch check.
        // Zero the WATCH_CREATE cursor epoch (payload offset 21 = watchId u64 + scope u8 + targetKind u8 +
        // path[len u32 = 7 + 7 bytes]).
        cases.add(decodeCase("poison.inner.watch-cursor-topology-epoch-zero", subU64Zero(watchCreate, 21)));

        return cases;
    }

    /** Overwrite a big-endian u64 at {@code payloadOffset} with {@code value} + recompute CRC. */
    private static byte[] subU64(byte[] frame, int payloadOffset, long value) {
        byte[] c = frame.clone();
        int i = EdgeFrameCodec.HEADER_SIZE + payloadOffset;
        for (int k = 0; k < 8; k++) {
            c[i + k] = (byte) (value >>> (56 - 8 * k));
        }
        recomputeCrc(c);
        return c;
    }

    /** Overwrite a u8 at {@code payloadOffset} (i.e. frame offset HEADER_SIZE+payloadOffset) + recompute CRC. */
    private static byte[] subU8(byte[] frame, int payloadOffset, byte value) {
        byte[] c = frame.clone();
        c[EdgeFrameCodec.HEADER_SIZE + payloadOffset] = value;
        recomputeCrc(c);
        return c;
    }

    /** Overwrite a big-endian u32 at {@code payloadOffset} + recompute CRC. */
    private static byte[] subU32(byte[] frame, int payloadOffset, int value) {
        byte[] c = frame.clone();
        int i = EdgeFrameCodec.HEADER_SIZE + payloadOffset;
        c[i] = (byte) (value >>> 24);
        c[i + 1] = (byte) (value >>> 16);
        c[i + 2] = (byte) (value >>> 8);
        c[i + 3] = (byte) value;
        recomputeCrc(c);
        return c;
    }

    /** Zero the 8 bytes of a u64 at {@code payloadOffset} + recompute CRC. */
    private static byte[] subU64Zero(byte[] frame, int payloadOffset) {
        byte[] c = frame.clone();
        int i = EdgeFrameCodec.HEADER_SIZE + payloadOffset;
        for (int k = 0; k < 8; k++) {
            c[i + k] = 0;
        }
        recomputeCrc(c);
        return c;
    }

    private static Case decodeCase(String id, byte[] frame) {
        return new Case(id, () -> decodeOutcome(() -> EdgeFrameCodec.decode(frame)));
    }

    private static String decodeOutcome(java.util.function.Supplier<EdgeFrame> decode) {
        try {
            decode.get();
            return "ACCEPT"; // decoded without complaint -- for a poison case this is a REGRESSION
        } catch (CodecException ce) {
            return "REJECT:" + ce.code();
        } catch (RuntimeException re) {
            return "REJECT:OTHER:" + re.getClass().getSimpleName();
        }
    }

    // Byte manipulation helpers (all recompute the CRC unless testing the CRC itself).

    private static byte[] intBE(int value, int totalLen) {
        byte[] b = new byte[totalLen];
        b[0] = (byte) (value >>> 24);
        b[1] = (byte) (value >>> 16);
        b[2] = (byte) (value >>> 8);
        b[3] = (byte) value;
        return b;
    }

    /** A copy with the 4-byte BE length field overwritten (CRC untouched -- for the length-mismatch case). */
    private static byte[] withLengthField(byte[] frame, int lengthField) {
        byte[] c = frame.clone();
        c[0] = (byte) (lengthField >>> 24);
        c[1] = (byte) (lengthField >>> 16);
        c[2] = (byte) (lengthField >>> 8);
        c[3] = (byte) lengthField;
        return c;
    }

    /** Flip one payload byte, leaving the original CRC: CRC mismatch. */
    private static byte[] flipPayloadByte(byte[] frame) {
        byte[] c = frame.clone();
        int i = EdgeFrameCodec.HEADER_SIZE; // first payload byte
        c[i] ^= 0x01;
        return c;
    }

    /** Overwrite the version byte and recompute the CRC (so the reject is the version, not the CRC). */
    private static byte[] reversionRecrc(byte[] frame, byte version) {
        byte[] c = frame.clone();
        c[4] = version; // version byte follows the 4-byte length
        recomputeCrc(c);
        return c;
    }

    /** Overwrite the type byte and recompute the CRC. */
    private static byte[] retypeRecrc(byte[] frame, byte type) {
        byte[] c = frame.clone();
        c[5] = type; // type byte follows length(4)+version(1)
        recomputeCrc(c);
        return c;
    }

    /** Insert one extra byte into the payload (bump length, recompute CRC): an unconsumed trailing byte. */
    private static byte[] appendPayloadByte(byte[] frame) {
        byte[] c = new byte[frame.length + 1];
        int crcOffset = frame.length - EdgeFrameCodec.TRAILER_SIZE;
        System.arraycopy(frame, 0, c, 0, crcOffset);      // header + payload
        c[crcOffset] = 0x00;                               // the extra payload byte
        // trailer slot is the last 4 bytes of c (recomputed below)
        int newLength = c.length;
        c[0] = (byte) (newLength >>> 24);
        c[1] = (byte) (newLength >>> 16);
        c[2] = (byte) (newLength >>> 8);
        c[3] = (byte) newLength;
        recomputeCrc(c);
        return c;
    }

    /** Recompute the CRC32C trailer over [0, len-4) and write it into the last 4 bytes. */
    private static void recomputeCrc(byte[] frame) {
        int crcOffset = frame.length - EdgeFrameCodec.TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, crcOffset);
        int v = (int) crc.getValue();
        frame[crcOffset] = (byte) (v >>> 24);
        frame[crcOffset + 1] = (byte) (v >>> 16);
        frame[crcOffset + 2] = (byte) (v >>> 8);
        frame[crcOffset + 3] = (byte) v;
    }

    private static byte[] firstValue(Map<String, byte[]> m) {
        return m.values().iterator().next();
    }

    private static byte[] pick(Map<String, byte[]> m, String keySubstr, byte[] fallback) {
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            if (e.getKey().contains(keySubstr)) {
                return e.getValue();
            }
        }
        return fallback;
    }

    /** All fixture names present per version -- for the coverage/breakdown listing. */
    static Map<Integer, List<String>> goldenFixtureNames() {
        Map<Integer, List<String>> names = new LinkedHashMap<>();
        for (int v = 1; v <= 4; v++) {
            names.put(v, new ArrayList<>(EdgeFrameGoldenBytes.forVersion(v).keySet()));
        }
        return names;
    }
}
