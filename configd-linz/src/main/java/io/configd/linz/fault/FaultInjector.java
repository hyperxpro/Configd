package io.configd.linz.fault;

import io.configd.linz.cluster.ClusterNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OS-level fault injection against the real node processes (design §5/§8): iptables
 * partitions and {@code kill -9}. This is the only mechanism that exercises the real
 * blocking-SSLSocket / virtual-thread wire path the sim hides.
 *
 * <p><b>Partition mechanism.</b> Each node's Raft listens on a distinct
 * {@code 127.0.0.1:raftPort}, so a rule on that {@code --dport} cleanly cuts exactly
 * that node's inbound Raft socket without needing source-IP control (impossible on
 * single-host loopback — verified). The rule is <b>inserted at the top</b> of the
 * chain so it fires before any {@code -i lo -j ACCEPT}.
 *
 * <p><b>Why {@code REJECT --reject-with tcp-reset}, not {@code DROP}.</b> The Raft
 * transport opens outbound connections with {@code new Socket(addr, port)} and <b>no
 * connect timeout</b>, on the single tick thread. Under {@code DROP} (black-hole),
 * when the leader must (re)connect to an isolated peer its {@code connect()} blocks
 * for the full TCP SYN timeout — stalling the tick loop so the leader cannot commit
 * to <i>anyone</i> (a real Configd liveness gap, recorded as a finding). {@code REJECT}
 * fails the connect/send <b>fast</b> (RST), so the surviving majority keeps making
 * progress while the isolated node still cannot communicate — what a <i>safety</i>
 * (linearizability) test needs.
 *
 * <p>Every injected rule is tracked and removed by {@link #healAll()} /
 * {@link #close()} (call from a {@code finally} and a shutdown hook): the harness
 * never leaves iptables state behind.
 */
public final class FaultInjector implements AutoCloseable {

    /** A rule we inserted, recorded so we can delete exactly it on heal. */
    private record Rule(String chain, int port) {}

    private final Deque<Rule> active = new ArrayDeque<>();
    private final boolean dryRun; // true => log only (for environments without sudo iptables)

    public FaultInjector() {
        this(false);
    }

    public FaultInjector(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /** Cuts a node's inbound Raft socket (a network partition isolating it). Idempotent-ish. */
    public synchronized void isolate(ClusterNode node) throws IOException, InterruptedException {
        insert("INPUT", node.raftPort());
        insert("OUTPUT", node.raftPort());
    }

    /** Heals the partition for a node (removes the rules added by {@link #isolate}). */
    public synchronized void heal(ClusterNode node) throws IOException, InterruptedException {
        remove(new Rule("OUTPUT", node.raftPort()));
        remove(new Rule("INPUT", node.raftPort()));
    }

    /** Removes every rule still active (teardown). Best-effort; never throws. */
    public synchronized void healAll() {
        List<Rule> snapshot = new ArrayList<>(active);
        for (Rule r : snapshot) {
            try {
                remove(r);
            } catch (Exception e) {
                System.err.println("[fault] heal failed for " + r + ": " + e.getMessage());
            }
        }
    }

    private void insert(String chain, int port) throws IOException, InterruptedException {
        Rule r = new Rule(chain, port);
        if (active.contains(r)) {
            return; // already inserted
        }
        run("sudo", "-n", "iptables", "-I", chain, "1", "-p", "tcp", "--dport",
                Integer.toString(port), "-j", "REJECT", "--reject-with", "tcp-reset");
        active.push(r);
    }

    private void remove(Rule r) throws IOException, InterruptedException {
        if (!active.remove(r)) {
            return; // not active; nothing to do
        }
        run("sudo", "-n", "iptables", "-D", r.chain(), "-p", "tcp", "--dport",
                Integer.toString(r.port()), "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    private void run(String... cmd) throws IOException, InterruptedException {
        if (dryRun) {
            System.out.println("[fault dry-run] " + String.join(" ", cmd));
            return;
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out;
        try (var in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("iptables timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IOException("iptables failed (" + p.exitValue() + "): " + String.join(" ", cmd)
                    + " -> " + new String(out));
        }
    }

    public synchronized int activeRuleCount() {
        return active.size();
    }

    @Override
    public void close() {
        healAll();
    }
}
