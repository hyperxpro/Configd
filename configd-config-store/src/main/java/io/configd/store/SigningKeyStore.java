package io.configd.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Persistent Ed25519 signing key store (F-0052).
 * <p>
 * Previously, {@code ConfigdServer} generated a fresh Ed25519 keypair on every
 * boot, which meant the "signing chain" claim was falsified the moment the
 * leader restarted: edges had no way to associate signatures across restarts,
 * and there was no key rotation mechanism. This class provides a persistent,
 * stable keypair loadable from a file supplied by the operator.
 * <p>
 * <b>File format</b> (v1):
 * <pre>
 *   [4 bytes: magic 0xC0DF51G5]
 *   [2 bytes: version = 1]
 *   [16 bytes: key-id (random UUID big-endian most / least)]
 *   [4 bytes: private key DER length] [private key DER (PKCS#8)]
 *   [4 bytes: public  key DER length] [public  key DER (X.509)]
 * </pre>
 * POSIX file mode is forced to 0600 on generation so the file is readable only
 * by the owning process account.
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

    /**
     * Loads a keypair from {@code path} if the file exists; otherwise generates
     * a fresh keypair, assigns a random {@code keyId}, and writes the file with
     * mode {@code 0600} (best-effort on POSIX, silently skipped elsewhere).
     *
     * @param path persistent key file path (non-null)
     * @return a loaded or freshly generated {@link SigningKeyStore}
     */
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

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(path, buf.array(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        // Best-effort POSIX 0600. On non-POSIX platforms this will throw
        // UnsupportedOperationException, which we silently ignore — caller
        // can audit permissions out-of-band.
        try {
            Files.setPosixFilePermissions(path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }

        return new SigningKeyStore(keyPair, keyId);
    }

    /** Writes an arbitrary keypair and id — test-only helper. */
    static void writeForTest(Path path, KeyPair keyPair, UUID keyId) throws IOException {
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
        Files.write(path, buf.array(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        PosixFilePermissions.fromString("rw-------");
    }

    /** Utility: format a UUID without dashes — unused currently but handy. */
    public static String format(UUID id) {
        return id.toString().replace("-", "");
    }
}
