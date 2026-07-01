package io.configd.testkit;

import io.configd.common.Storage;

/**
 * Seam over the crash-storage durability fixture that the
 * adversarial simulation ({@link AdversarialSim}) uses to inject crash-restart
 * faults. Implemented in package {@code io.configd.raft} by
 * {@code CrashStorageAdapter}, which wraps the package-private {@code CrashStorage}
 * consumed from the consensus-core test-jar (lead decision: test-jar reuse, no
 * class duplication).
 * <p>
 * Public so the cross-package adapter can implement it; the methods mirror
 * {@code CrashStorage}'s crash-arming and restart-view contract.
 */
public interface CrashStorageHandle extends Storage {

    /**
     * Arms an automatic crash after the {@code afterWrites}-th mutating storage op,
     * so a crash can be placed deterministically at - or mid - a logical operation
     * (e.g. mid-{@code compact()}).
     */
    void armCrashAfterWrites(int afterWrites);

    /** True once an armed crash has fired (its unsynced tail was discarded). */
    boolean didCrash();

    /**
     * A fresh storage over only the durable (fsynced) image - i.e. what a process
     * restart would read after the crash. Pending unsynced writes are dropped.
     */
    Storage recoveredView();
}
