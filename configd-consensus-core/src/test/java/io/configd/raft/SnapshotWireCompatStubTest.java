package io.configd.raft;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Snapshot backwards-compatibility stub.
 *
 * <p>R-005 / §8.10: a wire/format change requires a deprecation cycle of
 * at least two releases. The same rule applies to the snapshot file
 * format because an upgrading node must be able to load the previous
 * release's snapshot.
 *
 * <p>This test is intentionally a stub. There is no v0 snapshot fixture
 * yet (the project has not cut a v0.1.0 release at the time of writing),
 * so we cannot honestly assert backwards-compat. When the first version
 * bump lands, the maintainer must:
 *
 * <ol>
 *   <li>Generate a snapshot under the previous release and check it in
 *       at {@code configd-consensus-core/src/test/resources/snapshot-fixtures/v<N>/snapshot.bin}.</li>
 *   <li>Implement the load path (or assert the existing
 *       {@link DurableRaftState} can read it).</li>
 *   <li>Remove {@link Disabled} and ensure CI runs this test.</li>
 *   <li>If the load path needs a migration, add it to
 *       {@code ops/scripts/migrate-<from>-to-<to>.sh} and reference it
 *       from {@code ops/runbooks/upgrade.md}.</li>
 * </ol>
 *
 * <p>NOT pretending this passes — the {@link Disabled} annotation is
 * load-bearing, not decorative.
 */
class SnapshotWireCompatStubTest {

    /** Path within the test classpath where v0 snapshot fixtures are expected. */
    private static final String FIXTURE_RESOURCE = "snapshot-fixtures/v0/snapshot.bin";

    /** Working-tree fallback path (covers IDE runs from repo root). */
    private static final Path FIXTURE_FALLBACK = Path.of(
            "configd-consensus-core/src/test/resources", FIXTURE_RESOURCE);

    /**
     * TODO(R-005): enable on first version bump. Today this test is a
     * placeholder — disabling it is honest, leaving it in is a
     * commitment to wire it up before {@link DurableRaftState} or the
     * snapshot binary format changes.
     */
    @Test
    @Disabled("Stub: enable on first version bump (R-005). "
            + "No v0 snapshot fixture exists yet.")
    void v0SnapshotLoadsUnderCurrentCode() throws IOException {
        byte[] fixture = readFixture();
        if (fixture == null) {
            // Honest failure: stub mode.
            fail("v0 snapshot fixture missing at " + FIXTURE_RESOURCE
                    + " — generate one from the previous release before enabling.");
        }
        assertNotNull(fixture);
        assertTrue(fixture.length > 0, "v0 snapshot fixture is empty");
        // TODO(R-005): once DurableRaftState exposes a from-bytes loader,
        // call it here and assert the resulting state has the expected
        // term / commit-index / last-applied values from the v0 fixture.
        fail("Snapshot loader not yet wired up — see TODO above.");
    }

    private static byte[] readFixture() throws IOException {
        ClassLoader cl = SnapshotWireCompatStubTest.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in != null) return in.readAllBytes();
        }
        if (Files.exists(FIXTURE_FALLBACK)) {
            return Files.readAllBytes(FIXTURE_FALLBACK);
        }
        return null;
    }
}
