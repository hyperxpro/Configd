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


final class NodeAnchorService {

    private static final Logger LOG = Logger.getLogger(NodeAnchorService.class.getName());

    
    private static final long OWNER_READ_TIMEOUT_MS = 500L;

    private NodeAnchorService() {
    }

    
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
                    + ", audit=" + auditCount + ", shardDigest bound)");
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
                + ", N=" + shardCount + ")");
        return nodeAnchor;
    }

    
    static Runnable newRefresher(NodeAnchorFile nodeAnchor, AuditLog auditLog,
            java.util.function.Supplier<Map<Integer, Long>> durableIndexSource,
            long intervalMs, int kRecords) {
        return new Refresher(nodeAnchor, auditLog, durableIndexSource, intervalMs, kRecords);
    }

    
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

    
    private static boolean isGenesis(byte[] hash) {
        return MessageDigest.isEqual(hash, NodeAnchorRecord.ZERO_HASH);
    }

    
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
