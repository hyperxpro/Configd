package io.configd.client.edge;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test builders for the Gate-2 read/hydrate stream: Ed25519 leader key pairs, signed {@link ConfigDelta}s
 * (byte-identical to the server's signing over {@link ConfigDelta#signingPayload()}), {@code NOTIFY} frames,
 * and a {@code SNAPSHOT_BEGIN/CHUNK/END} sequence for a hydration snapshot.
 */
final class StreamFixtures {

    private static final SecureRandom RNG = new SecureRandom();

    private StreamFixtures() {
    }

    static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** A signed PUT delta {@code from -> to} at {@code epoch}, signed by {@code signer}. */
    static ConfigDelta signedPut(KeyPair signer, long from, long to, long epoch, String key, String value)
            throws Exception {
        return signed(signer, from, to, epoch,
                List.of(new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    /** A signed delta over {@code mutations} at {@code epoch}, signed by {@code signer}. */
    static ConfigDelta signed(KeyPair signer, long from, long to, long epoch, List<ConfigMutation> mutations)
            throws Exception {
        byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
        RNG.nextBytes(nonce);
        // Build an unsigned delta to compute the exact signing payload, then re-emit signed.
        ConfigDelta unsigned = new ConfigDelta(from, to, mutations, null, epoch, nonce);
        byte[] signature = sign(signer.getPrivate(), unsigned.signingPayload());
        return new ConfigDelta(from, to, mutations, signature, epoch, nonce);
    }

    /** An UNSIGNED delta (for the fail-closed test). */
    static ConfigDelta unsignedPut(long from, long to, String key, String value) {
        return new ConfigDelta(from, to,
                List.of(new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    static EdgeFrame.Notify notify(long seq, long commitTs, ConfigDelta delta) {
        return new EdgeFrame.Notify(List.of(new CommitNotification(seq, commitTs, delta)));
    }

    /** Builds the {@code SNAPSHOT_BEGIN → CHUNK* → SNAPSHOT_END} frames for a store at {@code seq}. */
    static List<EdgeFrame> snapshotFrames(long seq, Map<String, String> entries, int chunkBytes) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            data = data.put(e.getKey(), new VersionedValue(e.getValue().getBytes(StandardCharsets.UTF_8), seq, 0L));
        }
        ConfigSnapshot snapshot = new ConfigSnapshot(data, seq, 0L);
        byte[] body = EdgeSnapshotCodec.serialize(snapshot);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, chunkBytes);
        List<EdgeFrame> frames = new ArrayList<>();
        frames.add(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        frames.addAll(chunks);
        frames.add(new EdgeFrame.SnapshotEnd(seq));
        return frames;
    }

    /** Builds the per-(watch_id,gid) {@code WATCH_SNAPSHOT_BEGIN → CHUNK* → END} catch-up substream. */
    static List<EdgeFrame> watchSnapshotFrames(long watchId, int gid, long seq, Map<String, String> entries,
                                               int chunkBytes) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            data = data.put(e.getKey(), new VersionedValue(e.getValue().getBytes(StandardCharsets.UTF_8), seq, 0L));
        }
        byte[] body = EdgeSnapshotCodec.serialize(new ConfigSnapshot(data, seq, 0L));
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, chunkBytes);
        List<EdgeFrame> frames = new ArrayList<>();
        frames.add(new EdgeFrame.WatchSnapshotBegin(watchId, gid, seq, chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk c : chunks) {
            frames.add(new EdgeFrame.WatchSnapshotChunk(watchId, gid, c.index(), c.bytes()));
        }
        frames.add(new EdgeFrame.WatchSnapshotEnd(watchId, gid, seq));
        return frames;
    }

    static Map<String, String> entries(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }
}
