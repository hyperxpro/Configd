package io.configd.raft;

import io.configd.common.Storage;
import io.configd.testkit.CrashStorageHandle;

import java.util.List;

/**
 * Adapts the RR-003 {@link CrashStorage} durability fixture (package-private in
 * {@code io.configd.raft}, consumed here via the consensus-core <b>test-jar</b>) to
 * the {@link AdversarialSim.CrashStorageHandle} seam used by the adversarial
 * simulation. This class lives in {@code io.configd.raft} precisely so it can see
 * the package-private {@code CrashStorage} without that class being made public —
 * the lead's "do not duplicate the class" constraint with zero edits to RR-003's
 * file.
 * <p>
 * {@link #create()} is the single factory the testkit calls; if the test-jar is
 * absent the {@link NoClassDefFoundError} surfaces at construction (a clear wiring
 * error) rather than silently degrading.
 */
public final class CrashStorageAdapter implements CrashStorageHandle {

    private final CrashStorage delegate;

    private CrashStorageAdapter(CrashStorage delegate) {
        this.delegate = delegate;
    }

    /** Builds a fresh crash-capable storage handle backed by {@link CrashStorage}. */
    public static CrashStorageHandle create() {
        return new CrashStorageAdapter(new CrashStorage());
    }

    @Override
    public void armCrashAfterWrites(int afterWrites) {
        delegate.armCrashAfterWrites(afterWrites);
    }

    @Override
    public boolean didCrash() {
        return delegate.didCrash();
    }

    @Override
    public Storage recoveredView() {
        return delegate.recoveredView();
    }

    // ---- Storage delegation ----

    @Override
    public void put(String key, byte[] value) {
        delegate.put(key, value);
    }

    @Override
    public byte[] get(String key) {
        return delegate.get(key);
    }

    @Override
    public void appendToLog(String logName, byte[] data) {
        delegate.appendToLog(logName, data);
    }

    @Override
    public List<byte[]> readLog(String logName) {
        return delegate.readLog(logName);
    }

    @Override
    public void truncateLog(String logName) {
        delegate.truncateLog(logName);
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        delegate.renameLog(fromLogName, toLogName);
    }

    @Override
    public void sync() {
        delegate.sync();
    }
}
