package io.configd.linz.cluster;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One Configd node as a <b>separate OS process</b> - a real JVM launched from the
 * shaded {@code configd-server} jar, talking to peers over the real
 * {@code TcpRaftTransport}. The in-process simulated network is never used.
 *
 * <p>Raft listens on {@code 127.0.0.1:raftPort} (a distinct port per node, so an
 * iptables {@code --dport} rule cleanly isolates exactly one node's inbound Raft
 * socket without source-IP control, which is impossible on single-host loopback).
 * The HTTP API listens on the wildcard at {@code apiPort}; clients reach it at
 * {@code 127.0.0.1:apiPort}.
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

    private volatile Process process;

    /** Optional mTLS material (PKCS12 cert/key/trust paths). */
    public record TlsFiles(Path cert, Path key, Path trust) {}

    public ClusterNode(int id, int raftPort, int apiPort, Path dataDir, Path signingKeyFile,
                       String peersCsv, String peerAddressesCsv, Path jar, Path logFile,
                       TlsFiles tls) {
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
    }

    /** Launches (or relaunches) the node process against the same {@code --data-dir}. */
    public void launch() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.appendTo(logFile.toFile())); // append so restart keeps history
        process = pb.start();
    }

    /**
     * Builds the exact server command line. Extracted from {@link #launch()} so it is
     * deterministic and assertable without spawning a JVM. {@link #restart()} re-invokes
     * {@code launch()}, so the same command - including the stable per-node signing-key path - is
     * reused across a kill/relaunch cycle.
     */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                javaBin(), "--enable-preview", "-jar", jar.toString(),
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
     * faults require.
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
    public String apiBase() { return "http://127.0.0.1:" + apiPort; }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
