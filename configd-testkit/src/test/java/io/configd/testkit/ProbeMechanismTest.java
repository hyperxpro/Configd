package io.configd.testkit;

import io.configd.distribution.CommitNotification;
import io.configd.probe.PropagationProbe;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mechanism test (charter section 3 V2) for the synthetic propagation probe
 * ({@link PropagationProbe}) and its OBSERVER-ONLY {@link EdgeFanOutSim} seam.
 *
 * <p>Three properties, all under <b>logical</b> time so assertions are EXACT (no
 * tolerance bands):
 * <ol>
 *   <li><b>Constructed distribution.</b> Two edges apply notifications whose
 *       leader commit timestamps are set so the logical staleness is exactly
 *       {@code +10/+50/+499/+501 ms}. The probe's per-edge and global percentiles,
 *       counts, min and max must equal a reference {@link Histogram} fed the same
 *       values - i.e. the probe reproduces the constructed distribution exactly.</li>
 *   <li><b>Unmatched counting.</b> A {@code recordVisible} for a seq that was never
 *       published is counted as unmatched (per observer and globally) and never folds
 *       into the latency distribution.</li>
 *   <li><b>Observer-only determinism.</b> Running the {@link EdgeSimDeterminismTest}
 *       scenario WITH a probe attached produces a byte-identical digest to running it
 *       WITHOUT one - proof that the probe seam perturbs nothing (charter "observer-only").</li>
 * </ol>
 *
 * <p>Staleness sample = {@code visibleTs - publishTs} where {@code publishTs} is the
 * leader-assigned commit timestamp (commit-timestamp spec section 2) and {@code visibleTs} is the edge's
 * logical apply time - contract section 2 staleness invariant.
 */
class ProbeMechanismTest {

    // Mirror EdgeSimDeterminismTest's scenario exactly so the digest comparison is valid.
    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;

    // The constructed staleness distribution (ms) - the exact samples the probe must record.
    private static final long D_A1 = 10;
    private static final long D_A2 = 50;
    private static final long D_B1 = 499;
    private static final long D_B2 = 501;

    // -----------------------------------------------------------------------
    // (1) constructed distribution - EXACT percentiles/counts across two edges
    // -----------------------------------------------------------------------

    @Test
    void probeReproducesConstructedStalenessDistributionExactly() {
        // Zero CP workload + zero faults: the ONLY publications/visibilities are the
        // ones this test injects, so the probe's distribution is exactly constructed.
        EdgeFanOutSim sim = new EdgeFanOutSim(99L, /*cpNodes*/ 2, /*edges*/ 2, /*ticks*/ 1,
                /*edgeFaults*/ false, StreamDriver.NONE,
                new AdversarialSchedule.Intensity(0, 0, 0.0), EdgeInvariants.BOUND_MS);
        PropagationProbe probe = new PropagationProbe();
        sim.attachProbe(probe);

        List<EdgeActor> edges = sim.edges();
        EdgeActor edgeA = edges.get(0); // id 100
        EdgeActor edgeB = edges.get(1); // id 101

        // Edge A applies two contiguous deltas with logical staleness +10 and +50.
        applyWithStaleness(probe, edgeA, /*seq*/ 1, /*from*/ 0, /*to*/ 1, "a/k1", "v1", D_A1);
        applyWithStaleness(probe, edgeA, /*seq*/ 2, /*from*/ 1, /*to*/ 2, "a/k2", "v2", D_A2);
        // Edge B applies two contiguous deltas (its own version line) with +499 and +501.
        applyWithStaleness(probe, edgeB, /*seq*/ 3, /*from*/ 0, /*to*/ 1, "b/k1", "v1", D_B1);
        applyWithStaleness(probe, edgeB, /*seq*/ 4, /*from*/ 1, /*to*/ 2, "b/k2", "v2", D_B2);

        // Reference histograms fed the identical values - the probe must match them exactly.
        Histogram refGlobal = newHistogram();
        Histogram refA = newHistogram();
        Histogram refB = newHistogram();
        for (long v : new long[]{D_A1, D_A2, D_B1, D_B2}) refGlobal.recordValue(v);
        refA.recordValue(D_A1); refA.recordValue(D_A2);
        refB.recordValue(D_B1); refB.recordValue(D_B2);

        // Per-edge exactness.
        assertHistogramMatches(refA, probe, edgeA.edgeId());
        assertHistogramMatches(refB, probe, edgeB.edgeId());

        // Global exactness.
        assertEquals(4, probe.globalCount(), "global count");
        assertEquals(0, probe.globalUnmatched(), "no unmatched in this scenario");
        for (double p : new double[]{50.0, 90.0, 99.0, 99.9, 99.99}) {
            assertEquals(refGlobal.getValueAtPercentile(p), probe.globalPercentile(p),
                    "global p" + p + " must match the constructed distribution");
        }
        assertEquals(refGlobal.getMinValue(), probe.min(EdgeActor.EDGE_ID_BASE),
                "edge A min is the global min (10)");
        assertEquals(refGlobal.getMaxValue(), probe.globalMax(), "global max == 501");
        assertEquals(10, probe.min(EdgeActor.EDGE_ID_BASE), "edge A min staleness == 10");
        assertEquals(50, probe.max(EdgeActor.EDGE_ID_BASE), "edge A max staleness == 50");
        assertEquals(501, probe.max(EdgeActor.EDGE_ID_BASE + 1), "edge B max staleness == 501");

        // The greppable summary line must carry the exact stats for the global scope.
        String summary = probe.summaryLines();
        assertTrue(summary.contains(
                        "PROBE-HISTOGRAM: scope=global count=4 p50=" + refGlobal.getValueAtPercentile(50.0)
                                + " p99=" + refGlobal.getValueAtPercentile(99.0)
                                + " p999=" + refGlobal.getValueAtPercentile(99.9)
                                + " max=501 unit=ms"),
                "global summary line must report the exact constructed stats:\n" + summary);

        // Evidence record: the sim-mode (logical-time) report (charter section 3 V2 deliverable 5).
        System.out.println("=== Configd propagation probe — SIM MODE (logical time) ===");
        System.out.println("constructed staleness distribution: edge-100={+10ms,+50ms} "
                + "edge-101={+499ms,+501ms}; logical time → exact, no tolerance bands.");
        System.out.println("staleness sample = visibleTs(logical apply) - publishTs(leader "
                + "commit ts, ADR-0035 §2 / contract §2 INV-S1)");
        System.out.println();
        System.out.print(probe.report());
    }

    // -----------------------------------------------------------------------
    // (2) unmatched counting - visible-without-published
    // -----------------------------------------------------------------------

    @Test
    void unmatchedVisibleSeqIsCountedAndKeptOutOfTheDistribution() {
        PropagationProbe probe = new PropagationProbe();
        // seq 7 published at t=1000; a matched visible at t=1100 -> 100ms sample.
        probe.recordPublished(7, 1_000);
        probe.recordVisible(0, 7, 1_100);
        // seq 999 NEVER published -> unmatched, must not affect the distribution.
        probe.recordVisible(0, 999, 5_000);
        probe.recordVisible(1, 999, 5_000); // a different observer, also unmatched

        assertEquals(1, probe.count(0), "only the matched sample counts for observer 0");
        assertEquals(100, probe.percentile(0, 50.0), "the one matched sample is 100ms");
        assertEquals(1, probe.unmatched(0), "observer 0 has one unmatched seq");
        assertEquals(1, probe.unmatched(1), "observer 1 has one unmatched seq");
        assertEquals(2, probe.globalUnmatched(), "two unmatched globally");
        assertEquals(1, probe.globalCount(), "unmatched never enters the global distribution");
        assertTrue(probe.report().contains("unmatched = 1"),
                "the report must surface unmatched counts");
    }

    // -----------------------------------------------------------------------
    // (2b) the PROBE-HISTOGRAM report-format contract (checklist item
    //      "propagation probe histograms"). The staleness DISTRIBUTION is
    //      deliberately not a registry series; this line format IS the contract
    //      charter step (d) and CI grep for in both live modes. Asserted here - 
    //      not in EdgeMetricsContractTest (configd-edge-node) - because
    //      configd-testkit depends on configd-edge-node, so the probe cannot be
    //      referenced from that module without a dependency cycle.
    // -----------------------------------------------------------------------

    @Test
    void reportEmitsOneGreppableProbeHistogramLinePerScope() {
        PropagationProbe probe = new PropagationProbe();
        probe.recordPublished(1, 1_000);
        probe.recordVisible(7, 1, 1_010);  // observer 7: exactly one 10ms sample
        probe.recordVisible(9, 1, 1_250);  // observer 9: exactly one 250ms sample

        String report = probe.report();
        // Single-sample scopes make every percentile exact regardless of histogram impl.
        assertTrue(report.contains(
                        "PROBE-HISTOGRAM: scope=observer-7 count=1 p50=10 p99=10 p999=10 max=10 unit=ms"),
                "per-observer summary line malformed or missing:\n" + report);
        assertTrue(report.contains(
                        "PROBE-HISTOGRAM: scope=observer-9 count=1 p50=250 p99=250 p999=250 max=250 unit=ms"),
                "per-observer summary line malformed or missing:\n" + report);
        // The global aggregate line - the exact form step (d)'s
        // `grep "PROBE-HISTOGRAM: scope=global"` keys on.
        assertTrue(report.contains("PROBE-HISTOGRAM: scope=global count=2 "),
                "global summary line malformed or missing:\n" + report);
        assertTrue(report.contains(" unit=ms"), "unit suffix missing:\n" + report);
    }

    // -----------------------------------------------------------------------
    // (3) observer-only: identical determinism digest with and without a probe
    // -----------------------------------------------------------------------

    @Test
    void attachingAProbeDoesNotChangeTheDeterminismDigest() {
        long seed = 123_456_789L; // same seed as EdgeSimDeterminismTest
        String withoutProbe = digest(seed, false);
        String withProbe = digest(seed, true);
        assertEquals(withoutProbe, withProbe,
                "attaching an observer-only probe must not change the EdgeFanOutSim digest "
                        + "(observer-only proof): " + withoutProbe + " vs " + withProbe);

        // Non-vacuity: the probe actually observed publishes while attached (the seam
        // fired), so the equality above is meaningful - not "both ran a no-op probe".
        // The CP workload commits mutations that fire the FanOutBuffer listener ->
        // recordPublished, even though StreamDriver.NONE delivers nothing to the edges.
        PropagationProbe probe = new PropagationProbe();
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                true, StreamDriver.NONE,
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
        sim.attachProbe(probe);
        sim.run();
        assertTrue(probe.publishedSeqCount() > 0,
                "the recordPublished seam must fire from the FanOutBuffer listener under workload");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** The AdversarialSim/EdgeFanOutSim epoch: the sim clock before any tick advances it. */
    private static final long EPOCH_MS = 1_700_000_000_000L;

    /**
     * Delivers a single contiguous {@link CommitNotification} directly to {@code edge}
     * (bypassing the random edge network so the apply tick - and thus the logical
     * visibility time - is exact) with its commit timestamp set so the recorded
     * staleness is exactly {@code stalenessMs}. Also feeds {@code recordPublished} with
     * the same publish time (the publish side {@code DirectInjectionDriver} cannot drive,
     * since it bypasses the FanOutBuffer). The edge's attached apply-observer drives
     * {@code recordVisible} at the apply moment.
     *
     * <p>Exactness: the sim was built with 1 tick and never run, so the edge's logical
     * clock sits at {@link #EPOCH_MS}; the apply-observer therefore stamps
     * {@code visibleTs = EPOCH_MS}. Setting {@code publishTs = EPOCH_MS - stalenessMs}
     * makes the recorded staleness exactly {@code stalenessMs}.
     */
    private static void applyWithStaleness(PropagationProbe probe, EdgeActor edge,
            long seq, long fromVersion, long toVersion, String key, String value, long stalenessMs) {
        long visibleTs = EPOCH_MS;
        long publishTs = visibleTs - stalenessMs;
        probe.recordPublished(seq, publishTs);
        CommitNotification n = notification(seq, fromVersion, toVersion, publishTs, key, value);
        edge.deliver(new EdgeStream.Notify(n));
        edge.tick(); // applies now -> apply-observer fires recordVisible(edgeId, seq, visibleTs)
        assertEquals(toVersion, edge.currentVersion(),
                "delta " + fromVersion + "->" + toVersion + " must apply on edge " + edge.edgeId());
    }

    private static CommitNotification notification(long seq, long fromVersion, long toVersion,
            long commitTsMillis, String key, String value) {
        List<ConfigMutation> mutations = List.of(
                new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8)));
        ConfigDelta delta = new ConfigDelta(fromVersion, toVersion, mutations);
        return new CommitNotification(seq, commitTsMillis, delta);
    }

    private static Histogram newHistogram() {
        return new Histogram(1, PropagationProbe.HIGHEST_TRACKABLE_MILLIS,
                PropagationProbe.SIGNIFICANT_DIGITS);
    }

    private static void assertHistogramMatches(Histogram ref, PropagationProbe probe, int observerId) {
        assertEquals(ref.getTotalCount(), probe.count(observerId),
                "observer " + observerId + " count");
        assertEquals(ref.getMinValue(), probe.min(observerId), "observer " + observerId + " min");
        assertEquals(ref.getMaxValue(), probe.max(observerId), "observer " + observerId + " max");
        for (double p : new double[]{50.0, 90.0, 99.0, 99.9, 99.99}) {
            assertEquals(ref.getValueAtPercentile(p), probe.percentile(observerId, p),
                    "observer " + observerId + " p" + p);
        }
    }

    /**
     * Reuses {@link EdgeSimDeterminismTest}'s digest shape exactly. When {@code withProbe}
     * is true, an observer-only probe is attached before the run; the digest must be
     * identical either way.
     */
    private static String digest(long seed, boolean withProbe) {
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ true, StreamDriver.NONE,
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
        if (withProbe) {
            sim.attachProbe(new PropagationProbe());
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        ByteArrayOutputStream scratch = new ByteArrayOutputStream(128);
        DataOutputStream dos = new DataOutputStream(scratch);

        AdversarialSim cp = sim.cpSim();
        for (int t = 0; t < TICKS; t++) {
            sim.tick();
            try {
                dos.writeInt(t);
                for (int i = 0; i < CP_NODES; i++) {
                    dos.writeInt(cp.node(i).role().ordinal());
                    dos.writeLong(cp.node(i).currentTerm());
                    var leader = cp.node(i).leaderId();
                    dos.writeInt(leader == null ? -1 : leader.id());
                    var log = cp.log(i);
                    dos.writeLong(log.lastIndex());
                    dos.writeLong(log.commitIndex());
                    dos.writeLong(log.lastApplied());
                    dos.writeLong(cp.store(i).currentVersion());
                }
                for (EdgeActor edge : sim.edges()) {
                    dos.writeInt(edge.incarnation());
                    dos.writeLong(edge.cursor());
                    dos.writeLong(edge.currentVersion());
                    dos.writeInt(edge.staleness().ordinal());
                    dos.writeInt(edge.inboxSize());
                }
                dos.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            md.update(scratch.toByteArray());
            scratch.reset();
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
