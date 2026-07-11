package io.configd.server;

import io.configd.api.AuditLog;
import io.configd.common.IntegrityEnvelope;
import io.configd.raft.NodeAnchorFile;
import io.configd.raft.NodeAnchorRecord;
import io.configd.raft.RaftNode;
import io.configd.replication.MultiRaftDriver;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The node-anchor boot cross-check and its off-ack-path periodic refresh. The node-anchor binds
 * three node-wide facts under {@code K_integrity}:
 *
 * <ol>
 *   <li><b>Topology.</b> {@code (topologyEpoch, shardCount)} == the {@code TopologyDescriptor}; a
 *       mismatch is a topology-descriptor rollback ⇒ REFUSE.</li>
 *   <li><b>Audit head.</b> {@code (auditRecordCount, auditHeadHash)} anchored on a periodic cadence;
 *       an on-disk audit chain truncated BELOW the anchored head ⇒ REFUSE. The tail written since the
 *       last anchor (at most K records / T ms old) is not covered by this check - a known, bounded
 *       gap.</li>
 *   <li><b>Shard liveness.</b> {@code shardAnchorDigest} = SHA-256 over the sorted
 *       {@code (gid, lastDurableIndex)} pairs. A shard wiped to FRESH (its {@code raft-anchor}
 *       deleted, WAL truncated to 0, snapshot deleted) resets its head to index 0 and boots FRESH;
 *       the node-anchor detects it.</li>
 * </ol>
 *
 * <p><b>Digest boot semantics.</b> A strict "any digest change ⇒ REFUSE" would brick the node on
 * every legal crash, because a shard's {@code lastDurableIndex} legitimately advances between the
 * periodic node-anchor ticks - a forward move in the un-anchored window, and a design that bricks a
 * node on every legal crash fails just as surely as one that misses a real attack. The rule actually
 * enforced is narrower: a shard RESET TO INDEX 0 changes the digest ⇒ REFUSE. So the check is the
 * node-level parallel of the per-shard {@code W<A}/{@code W>A} recovery asymmetry:
 *
 * <ul>
 *   <li>digest matches ⇒ PROCEED;</li>
 *   <li>digest differs AND some shard booted FRESH (its {@code raft-anchor} was absent - the
 *       signature of a wipe, since a legal node never deletes a per-shard anchor) ⇒ REFUSE;</li>
 *   <li>digest differs AND no shard is FRESH ⇒ a legitimate forward advance (per-shard recovery already
 *       refused any {@code W<A} on a present anchor) ⇒ accept-forward: re-anchor + PROCEED.</li>
 * </ul>
 *
 * This detects a shard that was wiped and reset to FRESH; a shard rolled back to an older-but-still-
 * valid anchor slot is a harder attack that only the external {@code AnchorWitness} peer-quorum check
 * can catch, and is out of scope here. To hide a wipe this way an attacker would also have to roll the
 * node-anchor itself back to a digest that never existed - i.e. forge or replay the node-anchor, which
 * needs the signing key or defeats the witness.
 *
 * <p>Off the ack path: neither the boot cross-check nor the refresh touches consensus commit/ack. A
 * failed node-anchor refresh is logged and retried - it is NOT the fail-closed halt the per-shard
 * {@code raft-anchor} fsync is.
 */
final class NodeAnchorService {

    private static final Logger LOG = Logger.getLogger(NodeAnchorService.class.getName());

    /** Per-shard owner-thread read budget for a periodic digest refresh (best-effort, off the ack path). */
    private static final long OWNER_READ_TIMEOUT_MS = 500L;

    private NodeAnchorService() {
    }

    /**
     * Boot cross-check + first-boot mint. Opens (or mints) the node-anchor and, when it already exists,
     * cross-checks topology, audit head, and shard-liveness digest fail-closed (see the class javadoc).
     *
     * @param dataDir          the data directory (holds {@code node-anchor})
     * @param integrity        the Raft integrity envelope (same {@code K_integrity} as the WAL)
     * @param topologyEpoch    the deploy-time topology epoch (from the TopologyDescriptor)
     * @param shardCount       the deploy-time shard count N
     * @param bootDurableIndex gid → this shard's recovered {@code raft-anchor.lastDurableIndex}
     *                         (captured on the boot thread, before owner binding - race-free)
     * @param freshShards      the gids whose {@code raft-anchor} was ABSENT at open (booted FRESH)
     * @param auditLog         the security audit log, or {@code null} when auth is disabled
     * @return the opened, verified (or minted) node-anchor - the caller owns it and must {@code close()}
     * @throws IllegalStateException on any cross-check failure (refuse to start, fail-closed)
     */
    static NodeAnchorFile enforceNodeAnchor(Path dataDir, IntegrityEnvelope integrity,
            long topologyEpoch, int shardCount, Map<Integer, Long> bootDurableIndex,
            Set<Integer> freshShards, AuditLog auditLog) {
        NodeAnchorFile nodeAnchor = NodeAnchorFile.openInDirectory(dataDir, integrity);

        List<AuditLog.Record> persisted = (auditLog != null) ? auditLog.persistedRecords() : List.of();
        long auditCount = persisted.size();
        byte[] auditHead = persisted.isEmpty()
                ? NodeAnchorRecord.ZERO_HASH
                : persisted.get(persisted.size() - 1).recordHash();
        byte[] digestNow = NodeAnchorRecord.computeShardAnchorDigest(bootDurableIndex);

        if (!nodeAnchor.existedAtOpen()) {
            // First boot for this data dir: mint the node-anchor binding the current topology, audit
            // head, and shard-liveness digest. No cross-check (there is nothing to roll back to yet).
            NodeAnchorRecord mint =
                    new NodeAnchorRecord(1L, topologyEpoch, shardCount, auditCount, auditHead, digestNow);
            nodeAnchor.bootstrap(mint);
            System.out.println("  Node anchor  : minted (epoch=" + topologyEpoch + ", N=" + shardCount
                    + ", audit=" + auditCount + ", shardDigest bound) [frozen-format §2.5]");
            return nodeAnchor;
        }
        if (!nodeAnchor.hasValidRecord()) {
            nodeAnchor.close();
            throw new IllegalStateException("node-anchor " + dataDir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME)
                    + " is present with both slots invalid - refusing to start (tamper; distinct from a"
                    + " first boot, which has no node-anchor file at all). Restore from a trusted replica"
                    + " or redeploy on a clean data directory.");
        }

        NodeAnchorRecord na = nodeAnchor.current();

        // 1. Topology cross-check (rollback guard). A mismatch means the standalone TopologyDescriptor
        //    was swapped for an older legitimately-signed one (or the shard count was tampered with) -
        //    REFUSE. There is exactly one legitimate topology per deployment today; a legitimate reshard
        //    would need to advance this file and the topology descriptor together.
        if (na.topologyEpoch() != topologyEpoch || na.shardCount() != shardCount) {
            nodeAnchor.close();
            throw new IllegalStateException("node-anchor topology cross-check FAILED: node-anchor binds"
                    + " (epoch=" + na.topologyEpoch() + ", N=" + na.shardCount() + ") but the topology"
                    + " descriptor is (epoch=" + topologyEpoch + ", N=" + shardCount + ") - a topology"
                    + " rollback / tamper. Refusing to start (fail closed).");
        }

        // 2. Audit-head cross-check. The replayed on-disk chain must still REACH the anchored head; a
        //    chain truncated below it dropped anchored records - REFUSE. Only enforced when auth is on
        //    (auditLog != null); a node-anchor that binds an audit head but boots with auth OFF cannot
        //    verify it, so the check is skipped with a loud warning (a known, logged gap).
        if (!isGenesis(na.auditHeadHash())) {
            if (auditLog == null) {
                System.err.println("WARNING: node-anchor binds an audit head but auth is disabled -"
                        + " the audit-head cross-check is SKIPPED (cannot verify without the audit log).");
            } else if (!auditChainReachesHead(persisted, na.auditHeadHash())) {
                nodeAnchor.close();
                throw new IllegalStateException("node-anchor audit-head cross-check FAILED: the persisted"
                        + " security-audit chain no longer reaches the anchored head (auditRecordCount="
                        + na.auditRecordCount() + ") - the audit log was truncated below the last anchored"
                        + " record. Refusing to start (fail closed).");
            }
        }

        // 3. Shard-liveness digest. See the class javadoc for the reasoning.
        if (MessageDigest.isEqual(digestNow, na.shardAnchorDigest())) {
            // Clean: the per-shard liveness fingerprint is unchanged - no shard lost its durable head.
            return nodeAnchor;
        }
        if (!freshShards.isEmpty()) {
            nodeAnchor.close();
            throw new IllegalStateException("node-anchor shard-liveness cross-check FAILED (R-f): the"
                    + " shard-anchor digest changed AND shard(s) " + sorted(freshShards) + " booted FRESH"
                    + " (their raft-anchor was absent) - a wiped shard reset to index 0. A legal node never"
                    + " deletes a per-shard anchor. Refusing to start (fail closed); restore the shard from"
                    + " a trusted replica.");
        }
        // Forward advance in the un-anchored window: every shard's anchor was present and recovered
        // (per-shard recovery already refused any W<A), so no shard went backward. Accept-forward: bind
        // the current liveness digest + audit head into a new node-anchor slot. When auth is OFF we
        // cannot observe the audit chain (auditCount is 0 and auditHead is genesis in this branch), so
        // PRESERVE the previously anchored audit head instead of regressing it to genesis - regressing
        // it would let a later auth-ON boot find a genesis head and skip the audit-truncation cross-check
        // for a truncation that predated this auth-off boot. Auth ON advances the head normally.
        long fwdAuditCount = (auditLog != null) ? auditCount : na.auditRecordCount();
        byte[] fwdAuditHead = (auditLog != null) ? auditHead : na.auditHeadHash();
        nodeAnchor.write(na.withAuditAndDigest(fwdAuditCount, fwdAuditHead, digestNow));
        System.out.println("  Node anchor  : verified + re-anchored forward (epoch=" + topologyEpoch
                + ", N=" + shardCount + ") [frozen-format §2.5]");
        return nodeAnchor;
    }

    /**
     * A stateful periodic refresh task: on the K-records-or-T-ms cadence it binds the current audit head
     * and shard-liveness digest into a new node-anchor slot (one 512-B slot write + {@code fdatasync}).
     * Runs on its own single scheduler thread, off the ack path.
     *
     * @param nodeAnchor          the opened node-anchor (written only by this refresher after boot)
     * @param auditLog            the security audit log, or {@code null} when auth is disabled
     * @param durableIndexSource  supplies gid → {@code lastDurableIndex} for every shard, or
     *                            {@code null} when a shard could not be read this poll (retry next tick);
     *                            in production {@link #readDurableIndexOnOwners} (owner-thread dispatch)
     * @param intervalMs          T: refresh at least this often (bounds the un-anchored tail window)
     * @param kRecords            K: also refresh once this many audit records accrue since the last write
     * @return the {@link Runnable} to schedule at a sub-T poll period
     */
    static Runnable newRefresher(NodeAnchorFile nodeAnchor, AuditLog auditLog,
            java.util.function.Supplier<Map<Integer, Long>> durableIndexSource,
            long intervalMs, int kRecords) {
        return new Refresher(nodeAnchor, auditLog, durableIndexSource, intervalMs, kRecords);
    }

    /**
     * Reads every shard's {@code lastDurableIndex} ON its owner thread (single-owner-confined) via the
     * driver's owner-executor dispatch, returning the {@code gid → lastDurableIndex} map, or {@code null}
     * if any shard could not be read within the budget (busy owner / shutting down) - in which case the
     * refresh is skipped and retried next tick. This is the production {@code durableIndexSource}.
     */
    static Map<Integer, Long> readDurableIndexOnOwners(MultiRaftDriver driver, int[] gids) {
        try {
            Map<Integer, Future<Long>> futures = new LinkedHashMap<>(gids.length * 2);
            for (int gid : gids) {
                RaftNode node = driver.getGroup(gid);
                if (node == null) {
                    return null;
                }
                // node.log() asserts the owner thread; the task runs ON ownerExecutor(gid), so the
                // per-shard anchor read stays single-owner-confined (no cross-thread race).
                futures.put(gid, driver.ownerExecutor(gid).submit(() -> node.log().lastDurableIndex()));
            }
            Map<Integer, Long> perShard = new HashMap<>(gids.length * 2);
            long deadline = System.nanoTime() + OWNER_READ_TIMEOUT_MS * 1_000_000L;
            for (Map.Entry<Integer, Future<Long>> e : futures.entrySet()) {
                long remainingMs = Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
                perShard.put(e.getKey(), e.getValue().get(remainingMs, TimeUnit.MILLISECONDS));
            }
            return perShard;
        } catch (Exception e) {
            // Timeout / rejected (owner shutting down) / interrupted: skip this refresh, retry later.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Whether the anchored head is genesis (all-zero) - nothing to cross-check. */
    private static boolean isGenesis(byte[] hash) {
        return MessageDigest.isEqual(hash, NodeAnchorRecord.ZERO_HASH);
    }

    /**
     * Whether the anchored head {@code recordHash} is still present in the persisted chain. Present ⇒
     * the chain reached at least that far (records after it are the un-anchored tail); absent ⇒
     * the log was truncated below the anchored head. The anchored head is always a recent record, so a
     * legitimate rotation (which drops only the OLDEST records) never removes it.
     */
    private static boolean auditChainReachesHead(List<AuditLog.Record> persisted, byte[] anchoredHead) {
        for (AuditLog.Record r : persisted) {
            if (MessageDigest.isEqual(r.recordHash(), anchoredHead)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<Integer> sorted(Set<Integer> gids) {
        java.util.List<Integer> list = new java.util.ArrayList<>(gids);
        java.util.Collections.sort(list);
        return list;
    }

    /** The periodic refresh state machine (single-thread confined to the node-anchor scheduler). */
    private static final class Refresher implements Runnable {
        private final NodeAnchorFile nodeAnchor;
        private final AuditLog auditLog;
        private final java.util.function.Supplier<Map<Integer, Long>> durableIndexSource;
        private final long intervalMs;
        private final int kRecords;

        // Seeded once from the on-disk audit head so a process that appends no audit records still
        // carries the boot head forward. Advanced by this-process appends (auditLog.records()).
        private final long baselineAuditCount;
        private final byte[] seedHead;

        private long lastWriteMs;              // 0 => force the first write
        private long lastWrittenAuditCount;

        Refresher(NodeAnchorFile nodeAnchor, AuditLog auditLog,
                java.util.function.Supplier<Map<Integer, Long>> durableIndexSource,
                long intervalMs, int kRecords) {
            this.nodeAnchor = nodeAnchor;
            this.auditLog = auditLog;
            this.durableIndexSource = durableIndexSource;
            this.intervalMs = intervalMs;
            this.kRecords = kRecords;
            List<AuditLog.Record> persisted = (auditLog != null) ? auditLog.persistedRecords() : List.of();
            this.baselineAuditCount = persisted.size();
            this.seedHead = persisted.isEmpty()
                    ? NodeAnchorRecord.ZERO_HASH
                    : persisted.get(persisted.size() - 1).recordHash();
            this.lastWriteMs = 0L;
            this.lastWrittenAuditCount = baselineAuditCount;
        }

        @Override
        public void run() {
            try {
                long now = System.currentTimeMillis();
                long count = baselineAuditCount;
                byte[] head = seedHead;
                if (auditLog != null) {
                    List<AuditLog.Record> recs = auditLog.records();
                    if (!recs.isEmpty()) {
                        count = baselineAuditCount + recs.size();
                        head = recs.get(recs.size() - 1).recordHash();
                    }
                }
                boolean due = lastWriteMs == 0L
                        || (count - lastWrittenAuditCount) >= kRecords
                        || (now - lastWriteMs) >= intervalMs;
                if (!due) {
                    return;
                }
                Map<Integer, Long> perShard = durableIndexSource.get();
                if (perShard == null) {
                    return; // a shard owner was busy/unreachable this poll - retry next tick (best-effort)
                }
                byte[] digest = NodeAnchorRecord.computeShardAnchorDigest(perShard);
                nodeAnchor.write(nodeAnchor.current().withAuditAndDigest(count, head, digest));
                lastWriteMs = now;
                lastWrittenAuditCount = count;
            } catch (Throwable t) {
                // Off the ack path: a node-anchor refresh failure is NOT the fail-closed halt the
                // per-shard anchor fsync is. Log and keep serving; the next tick retries.
                LOG.log(Level.SEVERE, "node-anchor periodic refresh failed (continuing; will retry)", t);
            }
        }
    }
}
