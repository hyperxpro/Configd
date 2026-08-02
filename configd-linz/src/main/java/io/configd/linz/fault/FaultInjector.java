package io.configd.linz.fault;

import io.configd.linz.cluster.ClusterNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * OS-level network fault injection via iptables on node's 127.0.0.1:raftPort (per-node --dport cut).
 * isolate: REJECT --tcp-reset; lossy: statistic DROP. REJECT used for full cut because RST speeds
 * majority re-election (safety); DROP for dribble to preserve silent loss / bounded timeouts.
 * All rules recorded and torn down cleanly via heal/healAll/close; thread-safe for overlapping faults.
 */
public final class FaultInjector implements AutoCloseable {

    /**
     * Inserted rule as exact spec (chain + predicate + target) so heal() deletes precisely it,
     * without touching overlapping faults on other nodes.
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

    public void isolate(ClusterNode node) throws IOException, InterruptedException {
        insert(rejectSpec("INPUT", node.raftPort()));
        insert(rejectSpec("OUTPUT", node.raftPort()));
    }

    public void heal(ClusterNode node) throws IOException, InterruptedException {
        remove(rejectSpec("OUTPUT", node.raftPort()));
        remove(rejectSpec("INPUT", node.raftPort()));
    }

    public void lossy(ClusterNode node, int lossPercent) throws IOException, InterruptedException {
        insert(lossSpec("INPUT", node.raftPort(), lossPercent));
    }

    public void healLossy(ClusterNode node, int lossPercent) throws IOException, InterruptedException {
        remove(lossSpec("INPUT", node.raftPort(), lossPercent));
    }
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

    private static List<String> rejectSpec(String chain, int port) {
        return List.of(chain, "-p", "tcp", "--dport", Integer.toString(port),
                "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    private static List<String> lossSpec(String chain, int port, int lossPercent) {
        // Formatted to stable 6dp so insert/remove specs match
        String prob = String.format(java.util.Locale.ROOT, "%.6f", lossPercent / 100.0);
        return List.of(chain, "-p", "tcp", "--dport", Integer.toString(port),
                "-m", "statistic", "--mode", "random", "--probability", prob, "-j", "DROP");
    }


    private synchronized void insert(List<String> spec) throws IOException, InterruptedException {
        Rule r = new Rule(spec);
        if (active.contains(r)) {
            return;
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
            return;
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
