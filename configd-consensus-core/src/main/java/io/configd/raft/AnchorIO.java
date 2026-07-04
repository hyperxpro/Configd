package io.configd.raft;

import java.io.Closeable;

/**
 * The low-level byte transport under {@link AnchorFile}. It exposes exactly the four
 * primitives the dual-slot writer needs - existence, whole-image read, one-time
 * preallocated create, in-place slot overwrite, and a data-sync barrier - so the slot
 * logic (pick-stale-slot, highest-valid-seq, torn-slot fallback) lives in one place and
 * is exercised identically over a real file and over the crash model.
 *
 * <p>Two backends:
 * <ul>
 *   <li>{@link FileAnchorIO} - the production path: a real {@code raft-anchor} file in the
 *       WAL's directory, fixed-offset {@code pwrite} + {@code fdatasync} (the frozen §2.4
 *       write protocol). Steady-state writes never allocate (the file is preallocated at
 *       creation), so anchor-ENOSPC is impossible after boot.</li>
 *   <li>{@link StorageAnchorIO} - the durability-test path: the same 1032-byte image carried
 *       as a single self-durable {@code Storage} value, so the existing {@code CrashStorage}
 *       crash model captures the anchor's durability faithfully (an un-synced in-place write
 *       is lost on crash exactly as an un-{@code fdatasync}'d slot would be).</li>
 * </ul>
 *
 * <p>The {@code sync()} here is a plain data-sync barrier; the fail-closed policy (a throwing
 * sync must abort the durable advance and panic) is enforced one level up in {@link AnchorFile}
 * / {@code RaftNode}, so this seam only reports I/O failure by propagating an unchecked exception.
 */
interface AnchorIO extends Closeable {

    /** Whether the anchor artifact is present at open time (a real file / a stored value). */
    boolean exists();

    /** The full anchor image (container header + both slots), or {@code null} if absent. */
    byte[] readImage();

    /**
     * One-time creation: lays down the fully preallocated {@code image} (header + both 512-B
     * slots) and makes it durable (data + the file's existence). After this the anchor never
     * allocates again - every later {@link #writeAt} overwrites a slot in place.
     */
    void createPreallocated(byte[] image);

    /** In-place overwrite of {@code [offset, offset+bytes.length)} - NOT durable until {@link #sync()}. */
    void writeAt(long offset, byte[] bytes);

    /** Data-sync barrier ({@code fdatasync} / a self-durable put) for the preceding {@link #writeAt}. */
    void sync();

    @Override
    void close();
}
