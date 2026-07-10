package io.configd.linz.cluster;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One Configd node as a <b>separate OS process</b> - a real JVM launched from the
 * shaded {@code configd-server} jar, talking to peers over the real Netty consensus
 * transport. The in-process simulated network is never used.
 *
 * <p>Raft listens on {@code 127.0.0.1:raftPort} (a distinct port per node, so an
 * iptables {@code --dport} rule cleanly isolates exactly one node's inbound Raft
 * socket without source-IP control, which is impossible on single-host loopback).
 * The HTTP API listens on {@code apiPort}; clients reach it at {@code 127.0.0.1:apiPort}.
 *
 * <p><b>Postures.</b> A {@link Posture} carries the optional security/durability
 * configuration exercised by the faulted-linz matrix: bearer-token API auth, at-rest
 * encryption of the Raft durability artifacts, and a wall-clock skew (via libfaketime).
 * These are launch-time settings, reused verbatim on {@link #restart()} so a crash and
 * WAL recovery run under the same posture.
 */
public final class ClusterNode {

    private final int id;
    private final int raftPort;
    private final int apiPort;
    private final Path dataDir;
    private final Path signingKeyFile;      // the node's Ed25519 signing key, OUTSIDE dataDir (D-1)
    private final String peersCsv;          // other node ids, comma-separated
    private final String peerAddressesCsv;  // id=host:port,... for ALL nodes
    private final Path jar;
    private final Path logFile;
    private final TlsFiles tls;             // null for plaintext
    private final Posture posture;

    private volatile Process process;

    /** Optional mTLS material (PKCS12 cert/key/trust paths). */
    public record TlsFiles(Path cert, Path key, Path trust) {}

    /**
     * Security/durability posture for a node.
     *
     * @param authToken        bearer token required by the HTTP API, or null for auth-off
     * @param encryptAtRest    when true, sets {@code configd.raft.encryption.enabled=true} so the
     *                         WAL/snapshot durability artifacts are AES-256-GCM encrypted at rest
     *                         (the default {@code local} KMS provider derives the key from the
     *                         node's signing key - no external KMS needed)
     * @param clockSkewSeconds a wall-clock offset (positive or negative) applied to this node's JVM
     *                         via libfaketime; 0 means no skew. Election timing in this system is
     *                         tick-count driven, not wall-clock driven, so a skew is a timestamp/
     *                         staleness perturbation, not an election fault - the matrix runs it to
     *                         prove that claim empirically rather than by analysis.
     * @param faketimeLib      absolute path to {@code libfaketime.so.1}, required only when
     *                         {@code clockSkewSeconds != 0}; null otherwise
     */
    public record Posture(String authToken, boolean encryptAtRest, long clockSkewSeconds, Path faketimeLib) {
        public static Posture none() {
            return new Posture(null, false, 0, null);
        }

        public boolean authEnabled() {
            return authToken != null && !authToken.isBlank();
        }

        public boolean clockSkewed() {
            return clockSkewSeconds != 0 && faketimeLib != null;
        }
    }

    public ClusterNode(int id, int raftPort, int apiPort, Path dataDir, Path signingKeyFile,
                       String peersCsv, String peerAddressesCsv, Path jar, Path logFile,
                       TlsFiles tls) {
        this(id, raftPort, apiPort, dataDir, signingKeyFile, peersCsv, peerAddressesCsv, jar, logFile,
                tls, Posture.none());
    }

    public ClusterNode(int id, int raftPort, int apiPort, Path dataDir, Path signingKeyFile,
                       String peersCsv, String peerAddressesCsv, Path jar, Path logFile,
                       TlsFiles tls, Posture posture) {
        this.id = id;
        this.raftPort = raftPort;
        this.apiPort = apiPort;
        this.dataDir = dataDir;
        this.signingKeyFile = signingKeyFile;
        this.peersCsv = peersCsv;
        this.peerAddressesCsv = peerAddressesCsv;
        this.jar = jar;
        this.logFile = logFile;
        this.tls = tls;
        this.posture = posture;
    }

    /** Launches (or relaunches) the node process against the same {@code --data-dir}. */
    public void launch() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.appendTo(logFile.toFile())); // append so restart keeps history
        applyEnvironment(pb.environment());
        process = pb.start();
    }

    /**
     * Applies posture environment variables (libfaketime clock skew) to the child process
     * environment. Kept separate from {@link #buildCommand()} because environment is not part
     * of the assertable command line.
     */
    private void applyEnvironment(Map<String, String> env) {
        if (posture.clockSkewed()) {
            // libfaketime intercepts clock_gettime/gettimeofday via LD_PRELOAD; FAKETIME accepts a
            // signed offset like "+2" / "-3" (seconds). The skew persists across restart so WAL
            // recovery replays under the same skewed timestamp domain.
            env.put("LD_PRELOAD", posture.faketimeLib().toString());
            env.put("FAKETIME", (posture.clockSkewSeconds() >= 0 ? "+" : "")
                    + posture.clockSkewSeconds());
            env.put("FAKETIME_DONT_FAKE_MONOTONIC", "1"); // never skew the monotonic clock the JVM/tick loop uses
        }
    }

    /**
     * Builds the exact server command line. Extracted from {@link #launch()} so it is
     * deterministic and assertable without spawning a JVM. {@link #restart()} re-invokes
     * {@code launch()}, so the same command - including the stable per-node signing-key path
     * and posture - is reused across a kill/relaunch cycle.
     */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                javaBin(), "--enable-preview"));
        if (posture.encryptAtRest()) {
            // System property read by ConfigdServer.encryptionAtRestEnabled -> the durability
            // envelope switches from term-versioned HMAC (algId=1) to AES-256-GCM (algId=2).
            cmd.add("-Dconfigd.raft.encryption.enabled=true");
        }
        cmd.addAll(List.of(
                "-jar", jar.toString(),
                "--node-id", Integer.toString(id),
                "--data-dir", dataDir.toString(),
                // Mount the signing key OUTSIDE the data dir so the server's D-1 co-location guard
                // (PA-2021) is SATISFIED, not disabled. The same stable path is reused on restart()
                // after kill -9 so WAL recovery keeps a valid at-rest integrity chain.
                "--signing-key-file", signingKeyFile.toString(),
                "--peers", peersCsv,
                "--bind-address", "127.0.0.1",
                "--bind-port", Integer.toString(raftPort),
                "--api-port", Integer.toString(apiPort),
                "--peer-addresses", peerAddressesCsv));
        if (posture.authEnabled()) {
            cmd.add("--auth-token");
            cmd.add(posture.authToken());
        }
        if (tls != null) {
            cmd.add("--tls-cert");
            cmd.add(tls.cert().toString());
            cmd.add("--tls-key");
            cmd.add(tls.key().toString());
            cmd.add("--tls-trust-store");
            cmd.add(tls.trust().toString());
        }
        return cmd;
    }

    /**
     * Hard crash: {@code destroyForcibly()} is {@code SIGKILL} on Unix - untrappable,
     * no shutdown hooks, no graceful flush. This is the "kill -9" the durability
     * faults require. SIGKILL also terminates a SIGSTOP-frozen process, so a paused
     * node is still torn down cleanly.
     */
    public void kill9() {
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroyForcibly();
        try {
            p.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process = null;
    }

    /**
     * Freezes the process with {@code SIGSTOP}: it stops being scheduled but keeps its
     * sockets and TCP connections OPEN, so peers see it as present-but-silent. This is the
     * stale-leader / stop-the-world-GC nemesis - a leader that has not stepped down yet
     * cannot ACK or heartbeat, the majority may elect a new leader, and on {@link #resume()}
     * the frozen node must NOT serve a stale linearizable read or commit an old proposal.
     */
    public void pause() {
        signal("STOP");
    }

    /** Resumes a {@link #pause()}d process with {@code SIGCONT}. */
    public void resume() {
        signal("CONT");
    }

    private void signal(String sig) {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        try {
            Process k = new ProcessBuilder("kill", "-" + sig, Long.toString(p.pid()))
                    .redirectErrorStream(true).start();
            k.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            System.err.println("[node " + id + "] kill -" + sig + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Relaunch after a crash, recovering from the same data-dir (WAL). */
    public void restart() throws IOException {
        launch();
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** OS pid of the live process, or -1 if not running. */
    public long pid() {
        Process p = process;
        return p == null ? -1 : p.pid();
    }

    public int id() { return id; }
    public int raftPort() { return raftPort; }
    public int apiPort() { return apiPort; }
    public Path dataDir() { return dataDir; }
    public Path signingKeyFile() { return signingKeyFile; }
    public Path logFile() { return logFile; }
    public Posture posture() { return posture; }
    // The linz workload is driven over the plaintext HTTP API on loopback. Raft-peer mTLS
    // (the tls field) is a transport wrapper that is linearizability-invariant and already
    // proven functional by the horizontal-scale run, so the matrix does not re-drive it as a
    // linz cell; the security-relevant cells that touch the consensus path are at-rest
    // encryption and API auth, both exercised over this plaintext client channel.
    public String apiBase() { return "http://127.0.0.1:" + apiPort; }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
