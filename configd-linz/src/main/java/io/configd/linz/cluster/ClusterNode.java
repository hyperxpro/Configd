package io.configd.linz.cluster;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real separate-OS-process node. Raft on 127.0.0.1:raftPort (per-node so iptables --dport cleanly isolates it),
 * HTTP API on 127.0.0.1:apiPort. Postures (auth, encryption, clock skew) are launch-time and persist on restart.
 */
public final class ClusterNode {

    private final int id;
    private final int raftPort;
    private final int apiPort;
    private final Path dataDir;
    private final Path signingKeyFile;
    private final String peersCsv;
    private final String peerAddressesCsv;
    private final Path jar;
    private final Path logFile;
    private final TlsFiles tls;
    private final Posture posture;

    private volatile Process process;

    /** Optional mTLS material (PKCS12 cert/key/trust paths). */
    public record TlsFiles(Path cert, Path key, Path trust) {}

    /**
     * Node posture: authToken (null=auth-off), encryptAtRest (AES-256-GCM via signing key),
     * clockSkewSeconds (libfaketime offset; tick-driven election is clock-insensitive),
     * faketimeLib (required when clockSkew!=0), shardCount (multi-Raft).
     */
    public record Posture(String authToken, boolean encryptAtRest, long clockSkewSeconds, Path faketimeLib,
                          int shardCount) {
        public static Posture none() {
            return new Posture(null, false, 0, null, 1);
        }

        public boolean authEnabled() {
            return authToken != null && !authToken.isBlank();
        }

        public boolean clockSkewed() {
            return clockSkewSeconds != 0 && faketimeLib != null;
        }

        public boolean sharded() {
            return shardCount > 1;
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

    public void launch() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.appendTo(logFile.toFile()));
        applyEnvironment(pb.environment());
        process = pb.start();
    }

    /**
     * Libfaketime LD_PRELOAD for clock skew. Kept separate from buildCommand() because
     * environment is not part of the assertable command line.
     */
    private void applyEnvironment(Map<String, String> env) {
        if (posture.clockSkewed()) {
            env.put("LD_PRELOAD", posture.faketimeLib().toString());
            env.put("FAKETIME", (posture.clockSkewSeconds() >= 0 ? "+" : "")
                    + posture.clockSkewSeconds());
            env.put("FAKETIME_DONT_FAKE_MONOTONIC", "1");
        }
    }

    /**
     * Deterministic, assertable command line. Extracted from launch() so restart() re-invokes launch()
     * with the same command and signing-key path, preserving the at-rest integrity chain across kill/restart.
     */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                javaBin(), "--enable-preview"));
        if (posture.encryptAtRest()) {
            cmd.add("-Dconfigd.raft.encryption.enabled=true");
        }
        if (posture.sharded()) {
            cmd.add("-Dconfigd.raft.shardCount=" + posture.shardCount());
        }
        cmd.addAll(List.of(
                "-jar", jar.toString(),
                "--node-id", Integer.toString(id),
                "--data-dir", dataDir.toString(),
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
     * Hard crash via SIGKILL (untrappable, no shutdown hooks, no flush).
     * Durability fault primitive. Also terminates SIGSTOP-frozen processes.
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
     * Freeze via SIGSTOP (no schedule, sockets stay open). Stale-leader/GC nemesis:
     * frozen leader cannot ACK/heartbeat, majority re-elects; on resume, must not serve stale read.
     */
    public void pause() {
        signal("STOP");
    }

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

    public void restart() throws IOException {
        launch();
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

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
    /**
     * Plaintext HTTP API (loopback). Raft-peer mTLS is linearizability-invariant and already proven;
     * linz exercises only at-rest encryption and API auth (the consensus-path security cells).
     */
    public String apiBase() { return "http://127.0.0.1:" + apiPort; }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
