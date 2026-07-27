package io.configd.server;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.WrappedKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;


final class KmsSealedRootStore {

    
    static final String FILE_NAME = "raft-kms-root";

    
    private static final int MAGIC = 0x524B_4D53;
    private static final short FORMAT_VERSION = 1;

    private KmsSealedRootStore() {
    }

    
    static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    
    static void write(Path file, WrappedKey wrapped) {
        byte[] body = encode(wrapped);
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(body));
                ch.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDir(dir);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup of the staging file
            }
            throw new UncheckedIOException("failed to persist the KMS sealed-root carrier at " + file, e);
        }
    }

    
    static WrappedKey read(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the KMS sealed-root carrier at " + file, e);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException("KMS sealed-root file " + file + " has bad magic 0x"
                        + Integer.toHexString(magic) + " (expected RKMS) - refusing to start");
            }
            short fmt = in.readShort();
            if (fmt != FORMAT_VERSION) {
                throw new IllegalStateException("KMS sealed-root file " + file + " has unsupported format "
                        + fmt + " (expected " + FORMAT_VERSION + ") - refusing to start");
            }
            String providerType = in.readUTF();
            String reference = in.readUTF();
            int keyVersion = in.readInt();
            int contextCount = in.readUnsignedShort();
            Map<String, String> context = new LinkedHashMap<>(Math.max(4, contextCount * 2));
            for (int i = 0; i < contextCount; i++) {
                String k = in.readUTF();
                String v = in.readUTF();
                context.put(k, v);
            }
            int cipherLen = in.readInt();
            if (cipherLen < 0 || cipherLen > (1 << 20)) {
                throw new IllegalStateException("KMS sealed-root file " + file + " has implausible ciphertext"
                        + " length " + cipherLen + " - refusing to start");
            }
            byte[] ciphertext = new byte[cipherLen];
            in.readFully(ciphertext);
            if (in.read() != -1) {
                throw new IllegalStateException("KMS sealed-root file " + file + " has trailing bytes"
                        + " - refusing to start");
            }
            return new WrappedKey(new KeyId(providerType, reference, keyVersion), ciphertext, context);
        } catch (EOFException e) {
            throw new IllegalStateException("KMS sealed-root file " + file + " is truncated - refusing to start", e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse the KMS sealed-root carrier at " + file, e);
        }
    }

    private static byte[] encode(WrappedKey wrapped) {
        KeyId keyId = wrapped.keyId();
        byte[] ciphertext = wrapped.ciphertext();
        Map<String, String> context = wrapped.context();
        if (context.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many context entries: " + context.size());
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 + ciphertext.length);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(MAGIC);
            out.writeShort(FORMAT_VERSION);
            out.writeUTF(keyId.providerType());
            out.writeUTF(keyId.reference());
            out.writeInt(keyId.version());
            out.writeShort(context.size());
            for (Map.Entry<String, String> e : context.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue());
            }
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode the KMS sealed-root carrier", e);
        }
        return buf.toByteArray();
    }

    private static void fsyncDir(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Some filesystems reject a directory fsync; the ATOMIC_MOVE itself is the durability primitive,
            // so treat a dir-fsync rejection as non-fatal rather than failing the boot on a benign quirk.
        }
    }
}
