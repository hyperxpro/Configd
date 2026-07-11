package io.configd.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap "cold start" proof: how does Configd form a cluster before any cluster exists? The
 * answer (like etcd / Consul) is a STATIC SEED: initial membership is supplied as config (CLI
 * {@code --peers} / the k8s bootstrap ConfigMap), there is no separate "join" vs "first formation"
 * mode, and a node begins consensus immediately. This test drives a TRUE zero-state single-node cold
 * start (empty data dir, empty peer set) and proves the cluster forms and self-elects a leader purely
 * from the live tick loop - i.e. cold start -> serving.
 *
 * <p>It doubles as the live-wiring guard for two defects a unit-level metric test cannot cover: the
 * tick-loop election counter actually advances on a real boot, and the live {@code /metrics} exporter
 * renders the SLO histogram {@code _bucket{le=...}} series the burn-rate alerts query - both asserted
 * against {@link ConfigdServer#scrapeMetrics()} (the production exporter).
 */
class BootstrapColdStartTest {

    @TempDir
    Path tempDir;

    @Test
    void zeroStateSingleNodeFormsAndElectsLeaderAndExposesSloSeries() throws Exception {
        Path dataDir = tempDir.resolve("coldstart");
        assertFalse(Files.exists(dataDir), "precondition: TRUE zero state (no data dir yet)");

        // Static-seed cold start: empty peer set -> single-node cluster; ephemeral API port.
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "1",
                "--data-dir", dataDir.toString(),
                "--peers", "",
                "--api-port", "0"
        });

        ConfigdServer server = ConfigdServer.start(config);
        try {
            assertTrue(Files.isDirectory(dataDir), "cold start must materialize the data dir");

            // Cluster forms purely from the live tick loop: a single-node cluster self-elects, so
            // the tick-thread election counter must advance to >= 1 within a generous bound.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            double elections = 0;
            String scrape = "";
            while (System.nanoTime() < deadline) {
                scrape = server.scrapeMetrics();
                elections = seriesValue(scrape, "configd_raft_elections_total");
                if (elections >= 1) break;
                Thread.sleep(100);
            }
            assertTrue(elections >= 1,
                    "zero-state single node must self-elect a leader (cold start -> serving); "
                            + "raft_elections_total=" + elections);

            // The live production exporter must carry the SLO histogram buckets the alerts query:
            // without histogramSchedules the burn-rate alerts would read an empty series.
            assertTrue(scrape.contains("configd_write_commit_seconds_bucket{le=\"0.150\"}"),
                    "live /metrics must render the SLO histogram buckets the burn-rate alerts query");
            assertTrue(scrape.contains("configd_raft_pending_apply_entries"),
                    "the apply-backlog gauge must be bound on the live server (not hardwired to zero)");
        } finally {
            server.shutdown();
        }
    }

    private static double seriesValue(String scrape, String series) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(series) + "\\s+(\\S+)\\s*$").matcher(scrape);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
