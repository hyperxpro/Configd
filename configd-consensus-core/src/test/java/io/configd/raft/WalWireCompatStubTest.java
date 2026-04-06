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
 * WAL backwards-compatibility stub.
 *
 * <p>R-005 / §8.10: a wire/format change requires a deprecation cycle of
 * at least two releases. The same rule applies to the WAL segment
 * format because an upgrading node must replay segments written by the
 * previous release before producing its first new entry.
 *
 * <p>This test is intentionally a stub. There is no v0 WAL fixture yet
 * (the project has not cut a v0.1.0 release at the time of writing), so
 * we cannot honestly assert backwards-compat. When the first version
 * bump lands, the maintainer must:
 *
 * <ol>
 *   <li>Generate a WAL segment under the previous release and check it
 *       in at
 *       {@code configd-consensus-core/src/test/resources/wal-fixtures/v<N>/wal-000001.log}.</li>
 *   <li>Implement the replay path (or assert the existing
 *       {@link io.configd.raft.RaftLog} can replay it).</li>
 *   <li>Remove {@link Disabled} and ensure CI runs this test.</li>
 *   <li>If the replay path needs a migration, add it to
 *       {@code ops/scripts/migrate-<from>-to-<to>.sh} and reference it
 *       from {@code ops/runbooks/upgrade.md}.</li>
 * </ol>
 *
 * <p>NOT pretending this passes — the {@link Disabled} annotation is
 * load-bearing, not decorative.
 */
class WalWireCompatStubTest {

    /** Path within the test classpath where v0 WAL fixtures are expected. */
    private static final String FIXTURE_RESOURCE = "wal-fixtures/v0/wal-000001.log";

    /** Working-tree fallback path (covers IDE runs from repo root). */
    private static final Path FIXTURE_FALLBACK = Path.of(
            "configd-consensus-core/src/test/resources", FIXTURE_RESOURCE);

    /**
     * TODO(R-005): enable on first version bump. Today this test is a
     * placeholder — disabling it is honest, leaving it in is a
     * commitment to wire it up before the WAL segment format changes.
     */
    @Test
    @Disabled("Stub: enable on first version bump (R-005). "
            + "No v0 WAL fixture exists yet.")
    void v0WalSegmentReplaysUnderCurrentCode() throws IOException {
        byte[] fixture = readFixture();
        if (fixture == null) {
            // Honest failure: stub mode.
            fail("v0 WAL fixture missing at " + FIXTURE_RESOURCE
                    + " — generate one from the previous release before enabling.");
        }
        assertNotNull(fixture);
        assertTrue(fixture.length > 0, "v0 WAL fixture is empty");
        // TODO(R-005): once RaftLog exposes a replay-from-bytes API,
        // call it here and assert the resulting log has the expected
        // entries from the v0 fixture (term, index, command bytes).
        fail("WAL replay loader not yet wired up — see TODO above.");
    }

    private static byte[] readFixture() throws IOException {
        ClassLoader cl = WalWireCompatStubTest.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in != null) return in.readAllBytes();
        }
        if (Files.exists(FIXTURE_FALLBACK)) {
            return Files.readAllBytes(FIXTURE_FALLBACK);
        }
        return null;
    }
}
