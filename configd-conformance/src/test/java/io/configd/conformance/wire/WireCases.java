package io.configd.conformance.wire;

import io.configd.distribution.wire.EdgeFrameCodec.CodecException;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.EdgeFrameGoldenBytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Runner I (WIRE-format conformance) — the case corpus. Two directions, per the protobuf-conformance model:
 *
 * <ul>
 *   <li><b>ACCEPT</b> — the pinned golden vectors ({@link EdgeFrameGoldenBytes}, the single source of truth,
 *       reused via the wire module's test-jar): each must {@code decode → re-encode} <b>byte-for-byte</b>
 *       identically (the cross-language wire oracle, §00 OV5-5).
 *   <li><b>REJECT</b> — a poison-frame corpus enumerating the codec's reject paths (§06/§07 + the decode
 *       taxonomy): each corrupt frame must be rejected with a SPECIFIC {@link io.configd.distribution.wire.ErrorCode}.
 * </ul>
 *
 * Each case yields an {@link #outcome} string (ACCEPT / REJECT:&lt;code&gt; / MISMATCH / REJECT:OTHER); the
 * expected outcome per case lives in the checked-in manifest ({@code wire-manifest.txt}), and
 * {@link WireConformanceRatchetTest} asserts the actual set EQUALS the declared set (an unexpected pass OR an
 * unexpected fail both break CI — the {@code --failure_list} bidirectional ratchet).
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
        return cases;
    }

    // -------- ACCEPT direction: golden round-trips --------

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

    /** decode(golden) → re-encode at {@code version} → byte-identical ⇒ ACCEPT, else MISMATCH / REJECT. */
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

    // -------- REJECT direction: the poison corpus --------

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

        // A flipped payload byte with the ORIGINAL (now-wrong) CRC ⇒ CRC mismatch.
        cases.add(decodeCase("poison.crc-mismatch", flipPayloadByte(anyV1)));

        // An unsupported version byte, CRC recomputed so the reject is the version (not the CRC).
        cases.add(decodeCase("poison.bad-version", reversionRecrc(anyV1, (byte) 0x05)));

        // A structurally-valid 0x02 watch frame fed to a 0x01-pinned decode ⇒ version-pin reject.
        cases.add(new Case("poison.version-pin-mismatch",
                () -> decodeOutcome(() -> EdgeFrameCodec.decode(watch, EdgeFrameCodec.EDGE_WIRE_VERSION))));

        // An unknown type byte, CRC recomputed.
        cases.add(decodeCase("poison.unknown-type", retypeRecrc(anyV1, (byte) 0x7F)));

        // A 0x02 watch frame re-stamped 0x01 (type↔version illegal), CRC recomputed.
        cases.add(decodeCase("poison.watch-type-on-v1", reversionRecrc(watch, EdgeFrameCodec.EDGE_WIRE_VERSION)));

        // A 0x04 auth frame re-stamped 0x01 (auth type illegal off 0x04), CRC recomputed.
        cases.add(decodeCase("poison.auth-type-on-v1", reversionRecrc(auth, EdgeFrameCodec.EDGE_WIRE_VERSION)));

        // A trailing byte inside the declared length that the payload parser does not consume.
        cases.add(decodeCase("poison.trailing-bytes", appendPayloadByte(anyV1)));

        // ---- INNER-PAYLOAD bounds rejects: the reject-before-allocate hardening (§06 F3-2, the
        // amplification/underflow defense). Each mutates ONE inner field of a valid golden to an out-of-bounds
        // value (recomputing the CRC so the outer frame is intact), proving a conforming codec bounds EVERY
        // inner length/count BEFORE allocating — not just the outer frame. ----
        byte[] authBearer = pick(v4, "auth_bearer", auth);
        byte[] subFull = pick(v1, "subscribe_full_store", anyV1);
        byte[] subOk = pick(v1, "subscribe_ok_tail", anyV1);
        byte[] notifyOne = pick(v1, "notify_single_unsigned", pick(v1, "notify_batch", anyV1));

        // AUTH scheme byte ∉ {BEARER=1, BASIC=2} (payload offset 0).
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
            return "ACCEPT"; // decoded without complaint — for a poison case this is a REGRESSION
        } catch (CodecException ce) {
            return "REJECT:" + ce.code();
        } catch (RuntimeException re) {
            return "REJECT:OTHER:" + re.getClass().getSimpleName();
        }
    }

    // -------- byte manipulation helpers (all recompute the CRC unless testing the CRC itself) --------

    private static byte[] intBE(int value, int totalLen) {
        byte[] b = new byte[totalLen];
        b[0] = (byte) (value >>> 24);
        b[1] = (byte) (value >>> 16);
        b[2] = (byte) (value >>> 8);
        b[3] = (byte) value;
        return b;
    }

    /** A copy with the 4-byte BE length field overwritten (CRC untouched — for the length-mismatch case). */
    private static byte[] withLengthField(byte[] frame, int lengthField) {
        byte[] c = frame.clone();
        c[0] = (byte) (lengthField >>> 24);
        c[1] = (byte) (lengthField >>> 16);
        c[2] = (byte) (lengthField >>> 8);
        c[3] = (byte) lengthField;
        return c;
    }

    /** Flip one payload byte, leaving the original CRC ⇒ CRC mismatch. */
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

    /** Insert one extra byte into the payload (bump length, recompute CRC) ⇒ unconsumed trailing byte. */
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

    /** All fixture names present per version — for the coverage/breakdown listing. */
    static Map<Integer, List<String>> goldenFixtureNames() {
        Map<Integer, List<String>> names = new LinkedHashMap<>();
        for (int v = 1; v <= 4; v++) {
            names.put(v, new ArrayList<>(EdgeFrameGoldenBytes.forVersion(v).keySet()));
        }
        return names;
    }
}
