package io.configd.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/**
 * Persistent key enables signature continuity across leader restarts + deliberate rotation.
 * File format (v1): [magic][version][uuid][priv-len][priv-DER][pub-len][pub-DER].
 * Mode 0600 on generation (POSIX only; best-effort non-POSIX).
 */
public final class SigningKeyStore {

    private static final int MAGIC = 0xC0DF_51C5; // "configd sig" sigil
    private static final short VERSION = 1;
    private static final String ALGORITHM = "Ed25519";

    private final KeyPair keyPair;
    private final UUID keyId;

    private SigningKeyStore(KeyPair keyPair, UUID keyId) {
        this.keyPair = keyPair;
        this.keyId = keyId;
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public UUID keyId() {
        return keyId;
    }

    public static SigningKeyStore loadOrCreate(Path path) throws GeneralSecurityException, IOException {
        if (Files.exists(path)) {
            return load(path);
        }
        return generateAndWrite(path);
    }

    static SigningKeyStore load(Path path) throws GeneralSecurityException, IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IOException("Unrecognized signing key file (bad magic): " + path);
        }
        short version = buf.getShort();
        if (version != VERSION) {
            throw new IOException("Unsupported signing key file version: " + version);
        }
        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        UUID keyId = new UUID(mostSig, leastSig);

        int privLen = buf.getInt();
        if (privLen < 0 || privLen > 8192) {
            throw new IOException("Invalid private key length: " + privLen);
        }
        byte[] privBytes = new byte[privLen];
        buf.get(privBytes);

        int pubLen = buf.getInt();
        if (pubLen < 0 || pubLen > 8192) {
            throw new IOException("Invalid public key length: " + pubLen);
        }
        byte[] pubBytes = new byte[pubLen];
        buf.get(pubBytes);

        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        var priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        var pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
        return new SigningKeyStore(new KeyPair(pub, priv), keyId);
    }

    static SigningKeyStore generateAndWrite(Path path) throws GeneralSecurityException, IOException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair keyPair = gen.generateKeyPair();
        UUID keyId = UUID.randomUUID();

        byte[] encoded = encode(keyPair, keyId);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Durable crash-safe: temp file + fsync + chmod 0600 + atomic rename + fsync dir.
        // Bare Files.write loses on crash mid-write (torn key). No REPLACE_EXISTING (never overwrite).
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(encoded);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true); // fsync data + metadata
        }
        restrictToOwner(tmp);
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
        fsyncDirectory(parent);

        return new SigningKeyStore(keyPair, keyId);
    }

    private static byte[] encode(KeyPair keyPair, UUID keyId) {
        byte[] privBytes = keyPair.getPrivate().getEncoded();
        byte[] pubBytes = keyPair.getPublic().getEncoded();
        int size = 4 + 2 + 16 + 4 + privBytes.length + 4 + pubBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC);
        buf.putShort(VERSION);
        buf.putLong(keyId.getMostSignificantBits());
        buf.putLong(keyId.getLeastSignificantBits());
        buf.putInt(privBytes.length);
        buf.put(privBytes);
        buf.putInt(pubBytes.length);
        buf.put(pubBytes);
        return buf.array();
    }

    /**
     * Best-effort POSIX 0600; non-POSIX UnsupportedOperationException ignored.
     */
    private static void restrictToOwner(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
    }

    private static void fsyncDirectory(Path dir) throws IOException {
        if (dir == null) {
            return; // no parent to fsync (e.g. a bare relative filename); best-effort
        }
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        }
    }

    /**
     * Test helper: matches production 0600 restriction.
     */
    static void writeForTest(Path path, KeyPair keyPair, UUID keyId) throws IOException {
        Files.write(path, encode(keyPair, keyId),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        restrictToOwner(path);
    }

    public static String format(UUID id) {
        return id.toString().replace("-", "");
    }
}
