package io.configd.linz.fault;

import io.configd.linz.cluster.ClusterNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * OS-level network fault injection against the real node processes. Two primitives,
 * both applied to a node's distinct {@code 127.0.0.1:raftPort} so they cut exactly
 * that node's inbound Raft socket without needing source-IP control (impossible on
 * single-host loopback):
 *
 * <ul>
 *   <li><b>isolate</b> - a full {@code REJECT --reject-with tcp-reset} on the raft
 *       {@code --dport}: every peer's send to this node (and therefore every reply
 *       riding back on that peer's channel) fails fast with an RST. Because this
 *       transport sends ALL outgoing frames - requests <i>and</i> responses - on the
 *       sender's own per-peer outbound channel to the target's listener port, a
 *       {@code --dport} cut makes the node unable to be reached by anyone: it can push
 *       frames out but receives nothing back, so it cannot maintain quorum. Isolating a
 *       leader fails CheckQuorum -> it steps down; isolating a minority of nodes leaves
 *       the majority linearizable while the isolated nodes must never serve a stale read.</li>
 *   <li><b>lossy</b> - a probabilistic {@code DROP} (iptables {@code statistic} module)
 *       on the raft {@code --dport}: a fraction of inbound frames vanish, forcing TCP
 *       retransmits, heartbeat jitter and election churn on an otherwise-connected node.
 *       This is the packet-loss/dribble nemesis. {@code DROP} (not {@code REJECT}) is
 *       correct here because we WANT silent loss; a bounded connect timeout keeps a
 *       dropped SYN from stalling the leader's tick loop.</li>
 * </ul>
 *
 * <p><b>Why {@code REJECT --reject-with tcp-reset} for a full cut, not {@code DROP}.</b>
 * The transport's dead-peer detector fails a send-only outbound after an ACK deadline,
 * but a fast RST steps the surviving majority forward immediately, which is what a
 * <i>safety</i> (linearizability) test needs - it keeps the cluster making progress while
 * the isolated node genuinely cannot communicate.
 *
 * <p><b>Teardown.</b> Every injected rule is recorded with the exact argument vector used
 * to insert it, so {@link #heal}/{@link #healAll} delete precisely those rules and nothing
 * else. Call {@link #close()} from a {@code finally} and a shutdown hook: the harness never
 * leaves iptables state behind. Rule bookkeeping is thread-safe so overlapping (combination)
 * faults can be applied and healed from independent fault timers.
 */
public final class FaultInjector implements AutoCloseable {

    /**
     * A rule we inserted, recorded as the exact {@code -A}-style spec (chain + match
     * predicate + target) so we can delete precisely it. Two rules are equal iff their
     * specs are equal, which lets {@link #heal} target one node's rules without touching
     * another overlapping fault's rules.
     */
    private record Rule(List<String> spec) {}

    private final List<Rule> active = new CopyOnWriteArrayList<>();
    private final boolean dryRun; // true => log only (for environments without sudo iptables)

    public FaultInjector() {
        this(false);
    }

    public FaultInjector(boolean dryRun) {
        this.dryRun = dryRun;
    }

    // Full isolation (symmetric listener-port cut).

    /** Cuts a node's inbound Raft socket (a network partition isolating it). Idempotent. */
    public void isolate(ClusterNode node) throws IOException, InterruptedException {
        insert(rejectSpec("INPUT", node.raftPort()));
        insert(rejectSpec("OUTPUT", node.raftPort()));
    }

    /** Heals the isolation for a node (removes the rules added by {@link #isolate}). */
    public void heal(ClusterNode node) throws IOException, InterruptedException {
        remove(rejectSpec("OUTPUT", node.raftPort()));
        remove(rejectSpec("INPUT", node.raftPort()));
    }

    // Probabilistic packet loss (dribble).

    /**
     * Randomly drops {@code lossPercent}% of inbound frames to a node's Raft port using
     * the iptables {@code statistic} module. This degrades but does not sever the link,
     * driving retransmits and election churn.
     */
    public void lossy(ClusterNode node, int lossPercent) throws IOException, InterruptedException {
        insert(lossSpec("INPUT", node.raftPort(), lossPercent));
    }

    /** Removes the packet-loss rule added by {@link #lossy}. */
    public void healLossy(ClusterNode node, int lossPercent) throws IOException, InterruptedException {
        remove(lossSpec("INPUT", node.raftPort(), lossPercent));
    }

    // Teardown.

    /** Removes every rule still active (teardown). Best-effort; never throws. */
    public void healAll() {
        for (Rule r : new ArrayList<>(active)) {
            try {
                remove(r.spec());
            } catch (Exception e) {
                System.err.println("[fault] heal failed for " + r.spec() + ": " + e.getMessage());
            }
        }
    }

    public int activeRuleCount() {
        return active.size();
    }

    @Override
    public void close() {
        healAll();
    }

    // Rule specs: the match predicate + target, without the -I/-D verb.

    private static List<String> rejectSpec(String chain, int port) {
        return List.of(chain, "-p", "tcp", "--dport", Integer.toString(port),
                "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    private static List<String> lossSpec(String chain, int port, int lossPercent) {
        // statistic --mode random --probability P drops each matching packet independently
        // with probability P; formatted to a stable 6dp string so insert/remove specs match.
        String prob = String.format(java.util.Locale.ROOT, "%.6f", lossPercent / 100.0);
        return List.of(chain, "-p", "tcp", "--dport", Integer.toString(port),
                "-m", "statistic", "--mode", "random", "--probability", prob, "-j", "DROP");
    }

    // iptables plumbing.

    private synchronized void insert(List<String> spec) throws IOException, InterruptedException {
        Rule r = new Rule(spec);
        if (active.contains(r)) {
            return; // already inserted
        }
        // Insert at the top of the chain so it fires before any `-i lo -j ACCEPT`.
        List<String> cmd = new ArrayList<>(List.of("sudo", "-n", "iptables", "-I", spec.get(0), "1"));
        cmd.addAll(spec.subList(1, spec.size()));
        run(cmd);
        active.add(r);
    }

    private synchronized void remove(List<String> spec) throws IOException, InterruptedException {
        Rule r = new Rule(spec);
        if (!active.remove(r)) {
            return; // not active; nothing to do
        }
        List<String> cmd = new ArrayList<>(List.of("sudo", "-n", "iptables", "-D", spec.get(0)));
        cmd.addAll(spec.subList(1, spec.size()));
        run(cmd);
    }

    private void run(List<String> cmd) throws IOException, InterruptedException {
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
}
