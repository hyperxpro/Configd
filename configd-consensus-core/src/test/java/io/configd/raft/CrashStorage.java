package io.configd.raft;

import io.configd.common.Storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A crash-capable {@link Storage} for durability testing (RR-003 / RR-086).
 * <p>
 * <b>Durability model (mirrors {@code FileStorage}).</b> A power loss reverts
 * any change to durable storage that was not fsynced before the crash. The two
 * write kinds differ in <em>where</em> their fsync lives, and this harness models
 * that faithfully:
 * <ul>
 *   <li><b>{@code put} / {@code appendToLog} — self-durable.</b>
 *       {@code FileStorage.put} writes a temp file, {@code force(true)}s it,
 *       atomic-renames, and fsyncs the directory before returning;
 *       {@code FileStorage.appendToLog} {@code force(true)}s the data. So once
 *       either returns, the bytes are on the platter. These are promoted to the
 *       durable image immediately.</li>
 *   <li><b>{@code truncateLog} / {@code renameLog} — rename-style, pending
 *       until {@link #sync()}.</b> {@code FileStorage} performs the
 *       delete/rename but does NOT fsync the directory; the rename only becomes
 *       durable when a following {@code sync()} (the directory fsync) runs. A
 *       crash before that {@code sync()} reverts the rename. This is exactly the
 *       RR-086 hazard: {@code RaftLog.truncateFrom} and {@code RaftLog.compact}
 *       call {@code storage.sync()} after {@code rewriteWal()} precisely to make
 *       the rename durable — delete that {@code sync()} (the surviving RR-086
 *       mutant) and the WAL rewrite is lost on crash, which this harness makes
 *       observable. A wrapper over {@code FileStorage}, or a plain in-memory
 *       map, cannot see this: an atomic rename is visible to a same-directory
 *       reopen whether or not the directory was fsynced, so fsync removal is
 *       silent there (RR-086's exact gap).</li>
 * </ul>
 * A {@link #crash()} discards every rename-style mutation still awaiting a
 * {@code sync()}; self-durable writes already in the durable image survive.
 * <p>
 * <b>Restart semantics.</b> A "restart" models a fresh process booting against
 * the bytes that actually reached the platter: build a new {@code RaftLog} /
 * {@code RaftNode} over {@link #recoveredView()} (a deep copy of the durable
 * image), so the recovered view is independent of the crashed instance.
 * <p>
 * <b>Arming a crash at a point.</b> Crashes are armed semantically against the
 * specific durable step a test cares about — {@link #crashBeforeKeyPut(String)},
 * {@link #crashAfterKeyDurable(String)}, {@link #crashBeforeWalDelete(String)} —
 * or after a fixed number of writes ({@link #armCrashAfterWrites(int)}), so a
 * crash can be placed deterministically at, or in the middle of, a multi-step
 * logical operation such as {@code compact()}.
 * <p>
 * Not thread-safe; the Raft consensus path is single-threaded (R-01).
 */
final class CrashStorage implements Storage {

    /** A buffered, not-yet-durable mutation. */
    private interface Pending {
        void applyTo(Image image);
    }

    /** The logical durable state: kv map + named logs (each a list of frames). */
    static final class Image {
        final Map<String, byte[]> kv = new LinkedHashMap<>();
        final Map<String, List<byte[]>> logs = new LinkedHashMap<>();

        Image copy() {
            Image c = new Image();
            for (var e : kv.entrySet()) {
                c.kv.put(e.getKey(), e.getValue().clone());
            }
            for (var e : logs.entrySet()) {
                List<byte[]> copy = new ArrayList<>(e.getValue().size());
                for (byte[] frame : e.getValue()) {
                    copy.add(frame.clone());
                }
                c.logs.put(e.getKey(), copy);
            }
            return c;
        }
    }

    /** Bytes that have reached the platter — survive a crash. */
    private Image durable = new Image();
    /** durable + pending rename-style ops applied — what the live instance reads. */
    private Image working = new Image();
    /** Rename-style mutations awaiting the directory fsync (sync()). */
    private final List<Pending> pendingRenames = new ArrayList<>();

    /** Count of mutating operations issued (for crash-point arming/inspection). */
    private int operationCount;
    /** -1 = disarmed; otherwise auto-crash once operationCount reaches this. */
    private int crashAfter = -1;
    /** Set once any armed auto-crash fires, so callers can detect it. */
    private boolean crashed;
    /**
     * Set once an ARMED crash has fired: the modelled process is dead, so any
     * further storage call from the (now-zombie) caller is a no-op. A real crash
     * stops the process mid-operation; the in-process test keeps executing the
     * Java that follows, and this flag prevents those post-crash calls from
     * mutating the durable image. The test must {@link #recoveredView()} to model
     * the restart. (An explicit {@link #crash()} does NOT set this — callers that
     * crash explicitly then keep using the instance are responsible for their own
     * sequencing.)
     */
    private boolean dead;

    /** When set, crash just BEFORE the first {@code put} to this key. */
    private String crashBeforeKeyPut;
    /** When set, crash just BEFORE the first mutation that deletes/renames this log. */
    private String crashBeforeLogDelete;
    /** When set, crash just AFTER the named key's value is made durable. */
    private String crashAfterKeySynced;

    CrashStorage() {
        // Empty durable image — models a brand-new node with no prior state.
    }

    private CrashStorage(Image durableSeed) {
        this.durable = durableSeed.copy();
        this.working = durableSeed.copy();
    }

    /**
     * Returns a fresh {@code CrashStorage} seeded with this storage's current
     * <em>durable</em> image — i.e. what a restart would see. Rename-style
     * mutations not yet fsynced are intentionally NOT carried over.
     */
    CrashStorage recoveredView() {
        return new CrashStorage(durable);
    }

    /**
     * Arms an automatic {@link #crash()} immediately after the
     * {@code afterWrites}-th mutating operation completes (1-based). A value
     * {@code <= 0} disarms.
     */
    void armCrashAfterWrites(int afterWrites) {
        this.crashAfter = afterWrites;
    }

    /**
     * Arms a crash to fire <em>just before</em> the first {@code put} to the
     * named key. Models a power loss before the snapshot blob is even written —
     * so neither the blob nor any WAL truncation is durable and the full WAL is
     * intact.
     */
    void crashBeforeKeyPut(String key) {
        this.crashBeforeKeyPut = key;
    }

    /**
     * Arms a crash to fire <em>just before</em> the first mutation that would
     * delete or rename-away the named WAL log (i.e. before the WAL prefix
     * deletion that compaction performs becomes durable). Models a power loss
     * after a snapshot was persisted but before the WAL truncation lands.
     */
    void crashBeforeWalDelete(String logName) {
        this.crashBeforeLogDelete = logName;
    }

    /**
     * Arms a crash to fire <em>just after</em> the named key's value is made
     * durable by a {@code put}. Models a power loss after the snapshot blob is
     * fsynced but before any later step (the WAL truncation) completes.
     */
    void crashAfterKeyDurable(String key) {
        this.crashAfterKeySynced = key;
    }

    /** Number of mutating operations issued so far. */
    int operationCount() {
        return operationCount;
    }

    /** True if an armed auto-crash has fired. */
    boolean didCrash() {
        return crashed;
    }

    /**
     * Crashes: discards every rename-style mutation that has not been fsynced via
     * {@link #sync()}. Self-durable writes ({@code put}/{@code appendToLog})
     * already in the durable image survive. Any {@code RaftLog}/{@code RaftNode}
     * still holding this instance must be discarded — use {@link #recoveredView()}
     * to model the restart.
     */
    void crash() {
        pendingRenames.clear();
        working = durable.copy();
    }

    // ---- Self-durable writes: reach the durable image immediately ----

    @Override
    public void put(String key, byte[] value) {
        if (dead) {
            return;
        }
        if (crashBeforeKeyPut != null && crashBeforeKeyPut.equals(key) && !crashed) {
            fireArmedCrash();
            return; // crashed before this put landed
        }
        byte[] v = value.clone();
        durable.kv.put(key, v.clone());
        working.kv.put(key, v.clone());
        operationCount++;
        if (crashAfterKeySynced != null && crashAfterKeySynced.equals(key) && !crashed) {
            fireArmedCrash();
            return;
        }
        maybeAutoCrash();
    }

    @Override
    public void appendToLog(String logName, byte[] data) {
        if (dead) {
            return;
        }
        byte[] d = data.clone();
        durable.logs.computeIfAbsent(logName, k -> new ArrayList<>()).add(d.clone());
        working.logs.computeIfAbsent(logName, k -> new ArrayList<>()).add(d.clone());
        operationCount++;
        maybeAutoCrash();
    }

    // ---- Rename-style ops: durable only after the next sync() (dir fsync) ----

    @Override
    public void truncateLog(String logName) {
        if (dead) {
            return;
        }
        if (crashBeforeDelete(logName)) {
            return;
        }
        recordRename(image -> image.logs.remove(logName));
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        if (dead) {
            return;
        }
        // A rename deletes `fromLogName` and replaces `toLogName`; either being
        // the guarded WAL means the prefix deletion is about to land.
        if (crashBeforeDelete(toLogName) || crashBeforeDelete(fromLogName)) {
            return;
        }
        recordRename(image -> {
            List<byte[]> log = image.logs.remove(fromLogName);
            if (log != null) {
                image.logs.put(toLogName, log);
            }
        });
    }

    private void recordRename(Pending p) {
        // Reflect immediately in the working image (the running process sees its
        // own rename); durability waits for sync() (the directory fsync).
        p.applyTo(working);
        pendingRenames.add(p);
        operationCount++;
        maybeAutoCrash();
    }

    /** Crashes (and returns true) iff a before-delete crash is armed for {@code logName}. */
    private boolean crashBeforeDelete(String logName) {
        if (crashBeforeLogDelete != null && crashBeforeLogDelete.equals(logName) && !crashed) {
            fireArmedCrash();
            return true;
        }
        return false;
    }

    private void maybeAutoCrash() {
        if (crashAfter > 0 && operationCount >= crashAfter && !crashed) {
            fireArmedCrash();
        }
    }

    /** Fires an armed crash and marks the modelled process dead. */
    private void fireArmedCrash() {
        crashed = true;
        dead = true;
        crash();
    }

    @Override
    public void sync() {
        if (dead) {
            return;
        }
        // Directory fsync: promote pending rename-style mutations to durable.
        for (Pending p : pendingRenames) {
            p.applyTo(durable);
        }
        pendingRenames.clear();
    }

    // ---- Reads: served from the working image (durable + pending renames) ----

    @Override
    public byte[] get(String key) {
        byte[] v = working.kv.get(key);
        return v != null ? v.clone() : null;
    }

    @Override
    public List<byte[]> readLog(String logName) {
        List<byte[]> log = working.logs.get(logName);
        if (log == null) {
            return List.of();
        }
        List<byte[]> copy = new ArrayList<>(log.size());
        for (byte[] frame : log) {
            copy.add(frame.clone());
        }
        return copy;
    }
}
