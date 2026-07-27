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

class BootstrapColdStartTest {

    @TempDir
    Path tempDir;

    @Test
    void zeroStateSingleNodeFormsAndElectsLeaderAndExposesSloSeries() throws Exception {
        Path dataDir = tempDir.resolve("coldstart");
        assertFalse(Files.exists(dataDir), "precondition: TRUE zero state (no data dir yet)");

        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "1",
                "--data-dir", dataDir.toString(),
                "--peers", "",
                "--api-port", "0"
        });

        ConfigdServer server = ConfigdServer.start(config);
        try {
            assertTrue(Files.isDirectory(dataDir), "cold start must materialize the data dir");

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
