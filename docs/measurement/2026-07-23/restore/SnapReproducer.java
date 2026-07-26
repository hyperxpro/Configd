import io.configd.common.Clock;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigSigner;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Reproduces, byte-for-byte, the raw snapshot() output of a live N=1 Configd node
 * that has applied the 20-key reference keyset (rst/key0..19 = restore-val-0..19).
 *
 * The ConfigStateMachine is wired EXACTLY as ConfigdServer wires it in the
 * signing-key deployment: (store, clock, invariantChecker=null, signer). The
 * signer only advances signingEpoch (folded into the snapshot TLV trailer that the
 * state-machine hash covers); the Ed25519 signature bytes themselves never enter
 * the snapshot, so any valid keypair yields the identical snapshot.
 */
public final class SnapReproducer {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "/tmp/restore.snap");

        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signer = new ConfigSigner(kp);
        var store = new VersionedConfigStore();
        var sm = new ConfigStateMachine(store, Clock.system(), null, signer);

        // Apply the same 20 PUTs. index/term are Raft-log positions; snapshot() keys
        // off the state machine's own sequenceCounter, so only the count of mutating
        // applies matters here (matches the server's 20 committed PUTs).
        long index = 1;
        for (int k = 0; k < 20; k++) {
            byte[] cmd = CommandCodec.encodePut(
                    "rst/key" + k, ("restore-val-" + k).getBytes(StandardCharsets.UTF_8));
            sm.apply(index++, 1, cmd);
        }

        byte[] snap = sm.snapshot();
        Files.write(out, snap);

        // Compute sha256 over the payload region [12:] the same way the conformance
        // script's `tail -c +13 | sha256sum` does.
        byte[] payload = Arrays.copyOfRange(snap, 12, snap.length);
        String payloadHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));

        System.out.println("wrote        : " + out + " (" + snap.length + " bytes)");
        System.out.println("seq(header)  : " + sm.sequenceCounter());
        System.out.println("signingEpoch : " + sm.signingEpoch());
        System.out.println("sha256(snap[12:]) : " + payloadHash);
        System.out.println("stateMachineHashHex(): " + sm.stateMachineHashHex());
        System.out.println("equal        : " + payloadHash.equals(sm.stateMachineHashHex()));
    }
}
