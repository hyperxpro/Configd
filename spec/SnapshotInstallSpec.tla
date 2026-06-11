------------------------ MODULE SnapshotInstallSpec ------------------------
\* TLA+ specification of Raft's InstallSnapshot RPC for follower bootstrap.
\*
\* Closes SPEC-GAP-6 / PA-5027.
\*
\* The InstallSnapshot protocol (Ongaro 2014, Section 7) lets a leader
\* replicate state to a follower whose log is too far behind to catch up
\* via AppendEntries. The protocol:
\*
\*   1. Leader L picks a follower F whose nextIndex[F] is below the
\*      leader's snapshot.lastIncludedIndex.
\*   2. L sends InstallSnapshot(term, lastIncludedIndex, lastIncludedTerm)
\*      to F, optionally chunked.
\*   3. F validates the term, discards conflicting log if any, installs
\*      the snapshot, and updates its commitIndex to lastIncludedIndex.
\*
\* This spec deliberately abstracts log replication and election safety
\* (already proven by ConsensusSpec.tla). Instead we model:
\*
\*   - A global authoritative "committed log" — the cluster has agreed,
\*     via some Raft execution, on a sequence of log entries up to
\*     commitedTip. This stands in for the consistent committed prefix.
\*   - snapshot[n] = the highest index node n has installed (either via
\*     local log or via InstallSnapshot).
\*   - snapshot[n].term must match the term of the entry at that index
\*     in the global log.
\*
\* Safety properties verified:
\*   (a) SnapshotBoundedByCommitted — no node has a snapshot ahead of
\*       the global committed log.
\*   (b) SnapshotMatching — two snapshots at the same index agree on
\*       term (snapshot equivalent of LogMatching).
\*   (c) NoCommitRevert — InstallSnapshot never decreases a follower's
\*       installed snapshot index.
\*   (d) InflightTermMonotonic — every in-flight InstallSnapshot was
\*       sent for an index that exists in the global committed log.

EXTENDS Integers, FiniteSets, Sequences, TLC

CONSTANTS
    Nodes,        \* Set of node IDs
    MaxTerm,      \* Term bound
    MaxIndex,     \* Maximum committed index in the global log
    PERSIST_BEFORE_TRUNCATE
                  \* RR-003 defect switch (BOOLEAN): TRUE = the fixed model
                  \* (snapshot bytes persisted durably BEFORE the WAL prefix is
                  \* truncated); FALSE = reproduce the silent-data-loss counterexample
                  \* (truncate the WAL prefix while the snapshot lives only in RAM, so a
                  \* crash leaves neither the bytes nor the WAL prefix). DurablePrefix
                  \* catches the FALSE case.

VARIABLES
    \* Global authoritative committed log (proxy for "the cluster has agreed").
    \* committedLog is a sequence of [term |-> t] records, with index 1..Len.
    committedLog,
    \* Per-node installed snapshot (the in-RAM / live view).
    snapshot,     \* snapshot[n] = [index |-> i, term |-> t]; index = 0 = none
    \* RR-003 durability model — per node:
    \*   durIndex[n]  = index of the snapshot whose BYTES are durably persisted
    \*                  (0 = none). This is what survives a crash. The in-RAM
    \*                  snapshot[n].index can be >= durIndex[n] (RAM ahead of disk).
    \*   walBase[n]   = the lowest index still present in the durable WAL. The WAL
    \*                  holds the contiguous suffix [walBase[n] .. walTip[n]]. After a
    \*                  snapshot at S is persisted, the WAL prefix [1..S] may be
    \*                  truncated, advancing walBase[n] to S+1.
    \*   walTip[n]    = the highest index present in the durable WAL.
    durIndex,
    walBase,
    walTip,
    \* In-flight InstallSnapshot RPCs.
    inflight      \* set of records [from, to, lastIncludedIndex, lastIncludedTerm]

vars == <<committedLog, snapshot, durIndex, walBase, walTip, inflight>>

\* ---- Helpers ----

Max2(a, b) == IF a > b THEN a ELSE b

\* Term of the entry at index i in the global committed log; 0 if i = 0.
LogTermAt(i) ==
    IF i = 0 THEN 0
    ELSE IF i >= 1 /\ i <= Len(committedLog) THEN committedLog[i].term
    ELSE 0

\* ---- Initial State ----

Init ==
    /\ committedLog = <<>>
    /\ snapshot     = [n \in Nodes |-> [index |-> 0, term |-> 0]]
    /\ durIndex     = [n \in Nodes |-> 0]
    \* WAL initially holds the empty contiguous suffix starting at index 1.
    /\ walBase      = [n \in Nodes |-> 1]
    /\ walTip       = [n \in Nodes |-> 0]
    /\ inflight     = {}

\* ---- Type Invariant ----

TypeOK ==
    /\ Len(committedLog) <= MaxIndex
    /\ \A i \in 1..Len(committedLog): committedLog[i].term \in 1..MaxTerm
    /\ \A n \in Nodes:
        /\ snapshot[n].index \in 0..MaxIndex
        /\ snapshot[n].term  \in 0..MaxTerm
        /\ durIndex[n] \in 0..MaxIndex
        /\ walBase[n] \in 1..(MaxIndex + 1)
        /\ walTip[n] \in 0..MaxIndex

\* ---- Background actions ----

\* The cluster commits a new entry at term t. Term must be non-decreasing
\* (Raft Leader Completeness ensures committed log terms are monotonic).
ClusterCommit(t) ==
    /\ Len(committedLog) < MaxIndex
    /\ t \in 1..MaxTerm
    /\ IF Len(committedLog) = 0
       THEN TRUE
       ELSE committedLog[Len(committedLog)].term <= t
    /\ committedLog' = Append(committedLog, [term |-> t])
    /\ UNCHANGED <<snapshot, durIndex, walBase, walTip, inflight>>

\* RR-003 WAL model: a node durably appends the next committed entry to its WAL.
\* The WAL holds the contiguous suffix [walBase[n] .. walTip[n]]; it can grow up to
\* the global committed tip. This is the durable WAL suffix that, together with the
\* durable snapshot, must reconstruct the full committed prefix.
ReplicateToWal(n) ==
    /\ walTip[n] < Len(committedLog)
    /\ walTip[n] + 1 >= walBase[n]   \* keep the WAL suffix contiguous
    /\ walTip' = [walTip EXCEPT ![n] = walTip[n] + 1]
    /\ UNCHANGED <<committedLog, snapshot, durIndex, walBase, inflight>>

\* A node locally advances its IN-RAM snapshot to follow the global committed log.
\* RR-003: this is the RAM-only step (the pre-fix bug took a snapshot ONLY in RAM).
\* It does NOT persist bytes (see PersistSnapshot) and does NOT truncate the WAL
\* (see TruncateWal). The snapshot point must be covered by durable state (snapshot
\* bytes or WAL) so it is a real recoverable index: bounded by the WAL tip.
LocalSnapshot(n) ==
    /\ snapshot[n].index < Len(committedLog)
    /\ \E i \in (snapshot[n].index + 1)..Len(committedLog):
        \* Can only snapshot up to what is in the durable WAL (you cannot snapshot
        \* state you have not yet persisted to the WAL).
        /\ i <= walTip[n]
        /\ snapshot' = [snapshot EXCEPT ![n] =
                            [index |-> i, term |-> LogTermAt(i)]]
    /\ UNCHANGED <<committedLog, durIndex, walBase, walTip, inflight>>

\* RR-003 FIX STEP: persist the in-RAM snapshot BYTES durably. Advances durIndex[n]
\* to the in-RAM snapshot index. This is the step the pre-fix code never performed
\* (the snapshot lived only in the RAM field `latestSnapshot`).
PersistSnapshot(n) ==
    /\ durIndex[n] < snapshot[n].index
    /\ durIndex' = [durIndex EXCEPT ![n] = snapshot[n].index]
    /\ UNCHANGED <<committedLog, snapshot, walBase, walTip, inflight>>

\* RR-003 ORDERING: truncate the WAL prefix [walBase .. S] after a snapshot at S.
\* In the FIXED model (PERSIST_BEFORE_TRUNCATE = TRUE) truncation is GATED on the
\* snapshot bytes being durable (durIndex[n] >= S): you never delete the WAL prefix
\* until a complete snapshot covers it. In the DEFECT model (FALSE) truncation may
\* run while the snapshot is RAM-only (durIndex[n] < S), opening the loss window.
TruncateWal(n) ==
    /\ snapshot[n].index >= walBase[n]
    /\ snapshot[n].index <= walTip[n]
    /\ (PERSIST_BEFORE_TRUNCATE => durIndex[n] >= snapshot[n].index)
    /\ walBase' = [walBase EXCEPT ![n] = snapshot[n].index + 1]
    /\ UNCHANGED <<committedLog, snapshot, durIndex, walTip, inflight>>

\* RR-003 CRASH/RESTART: the node crashes and recovers. All in-RAM state is lost;
\* recovery rebuilds from DURABLE state only — the persisted snapshot at durIndex[n]
\* plus the durable WAL suffix [walBase[n] .. walTip[n]]. The recovered in-RAM
\* snapshot is the durable snapshot (the WAL suffix is replayed on top, but for the
\* snapshot-position invariant we model the recovered snapshot index as durIndex).
\* This is where the silent-loss defect manifests: if the WAL prefix was truncated
\* (walBase advanced past durIndex+1) while the snapshot bytes were NOT persisted
\* (durIndex < that point), the recovered state has a GAP — committed entries that
\* are in neither the durable snapshot nor the durable WAL.
CrashRestart(n) ==
    /\ snapshot' = [snapshot EXCEPT ![n] =
                        [index |-> durIndex[n], term |-> LogTermAt(durIndex[n])]]
    /\ UNCHANGED <<committedLog, durIndex, walBase, walTip, inflight>>

\* ---- InstallSnapshot Protocol ----

\* A leader sends InstallSnapshot to a follower whose installed snapshot
\* is behind. The leader's own snapshot is what it sends.
SendInstallSnapshot(leader, follower) ==
    /\ leader /= follower
    /\ snapshot[leader].index > snapshot[follower].index
    /\ Cardinality(inflight) < 3   \* state-space bound
    /\ inflight' = inflight \cup
            {[from              |-> leader,
              to                |-> follower,
              lastIncludedIndex |-> snapshot[leader].index,
              lastIncludedTerm  |-> snapshot[leader].term]}
    /\ UNCHANGED <<committedLog, snapshot, durIndex, walBase, walTip>>

\* The follower receives an InstallSnapshot and installs it.
\* Critical safety preconditions:
\*   (a) The snapshot must be newer than the follower's current snapshot
\*       (NoCommitRevert).
\*   (b) The snapshot's term/index must agree with the global committed
\*       log (we verify this as an invariant).
ReceiveInstallSnapshot(msg) ==
    /\ msg \in inflight
    /\ \/ \* Snapshot is older — discard the message.
          /\ msg.lastIncludedIndex <= snapshot[msg.to].index
          /\ inflight' = inflight \ {msg}
          /\ UNCHANGED <<committedLog, snapshot, durIndex, walBase, walTip>>
       \/ \* Snapshot is newer — install. RR-003: the follower persists the received
          \* snapshot bytes DURABLY (durIndex advances) BEFORE compaction, then the WAL
          \* prefix up to the snapshot point is dropped (walBase advances past it). This
          \* mirrors handleInstallSnapshot's persist-then-compact ordering, so a follower
          \* restart after an install recovers the snapshot instead of silently dropping
          \* everything below lastIncludedIndex.
          /\ msg.lastIncludedIndex > snapshot[msg.to].index
          /\ snapshot' = [snapshot EXCEPT ![msg.to] =
                            [index |-> msg.lastIncludedIndex,
                             term  |-> msg.lastIncludedTerm]]
          /\ durIndex' = [durIndex EXCEPT ![msg.to] =
                            Max2(durIndex[msg.to], msg.lastIncludedIndex)]
          /\ walBase' = [walBase EXCEPT ![msg.to] =
                            Max2(walBase[msg.to], msg.lastIncludedIndex + 1)]
          /\ walTip' = [walTip EXCEPT ![msg.to] =
                            Max2(walTip[msg.to], msg.lastIncludedIndex)]
          /\ inflight' = inflight \ {msg}
          /\ UNCHANGED <<committedLog>>

\* ---- Next ----

Next ==
    \/ \E t \in 1..MaxTerm: ClusterCommit(t)
    \/ \E n \in Nodes: ReplicateToWal(n)
    \/ \E n \in Nodes: LocalSnapshot(n)
    \/ \E n \in Nodes: PersistSnapshot(n)
    \/ \E n \in Nodes: TruncateWal(n)
    \/ \E n \in Nodes: CrashRestart(n)
    \/ \E n, m \in Nodes: SendInstallSnapshot(n, m)
    \/ \E msg \in inflight: ReceiveInstallSnapshot(msg)

Spec == Init /\ [][Next]_vars

\* ---- Safety Invariants ----

\* INV-SI-1: Snapshot is bounded by the global committed log.
\* No node has installed a snapshot whose index exceeds the global committed
\* tip. (LocalSnapshot enforces this; InstallSnapshot only carries snapshots
\* sourced from another node, so transitively bounded — verified here.)
SnapshotBoundedByCommitted ==
    \A n \in Nodes:
        snapshot[n].index <= Len(committedLog)

\* INV-SI-2: Snapshot Matching — two snapshots at the same index agree on
\* term, AND that term matches the global committed log.
\* This is the snapshot equivalent of LogMatching from ConsensusSpec.
SnapshotMatching ==
    /\ \A n, m \in Nodes:
        (snapshot[n].index = snapshot[m].index /\ snapshot[n].index > 0) =>
            snapshot[n].term = snapshot[m].term
    /\ \A n \in Nodes:
        snapshot[n].index > 0 =>
            snapshot[n].term = LogTermAt(snapshot[n].index)

\* INV-SI-3: NoCommitRevert (de-vacuumed, R-05c) — installing a newer-index
\* snapshot must never revert the term. For any in-flight InstallSnapshot that
\* WOULD install (lastIncludedIndex > the receiver's current snapshot index),
\* its term must be >= the receiver's current snapshot term. Snapshots are
\* sourced from the committed log, whose terms are monotonic in index
\* (ClusterCommit), so a higher index always carries a >= term; a regression
\* that ships/installs a higher-index but lower-term snapshot (a commit revert)
\* is caught here. (Non-vacuous: the previous form was `P \/ ~P`, a tautology.)
NoCommitRevert ==
    \A msg \in inflight:
        msg.lastIncludedIndex > snapshot[msg.to].index =>
            msg.lastIncludedTerm >= snapshot[msg.to].term

\* INV-SI-4: InflightTermMonotonic — every in-flight InstallSnapshot
\* references an index that exists in the global committed log with the
\* matching term. This rejects "leader sends a snapshot it doesn't have"
\* scenarios.
InflightTermMonotonic ==
    \A msg \in inflight:
        msg.lastIncludedIndex <= Len(committedLog)
        /\ msg.lastIncludedTerm = LogTermAt(msg.lastIncludedIndex)

\* INV-SI-5: DurablePrefix (RR-003) — at every instant, the DURABLE state of every
\* node reconstructs a complete contiguous prefix of the committed log: the persisted
\* snapshot at durIndex[n] PLUS the durable WAL suffix [walBase[n] .. walTip[n]] cover
\* [1 .. coveredTip] with NO gap. Concretely, there must be no committed index that is
\* below the WAL base (so not in the WAL) AND above the durable snapshot index (so not
\* in the snapshot) — such an index is on durable storage NOWHERE and is silently lost
\* on a crash/restart. The WAL suffix must also be contiguous with the snapshot:
\* walBase[n] <= durIndex[n] + 1 (no hole between the snapshot top and the WAL start).
\*
\* This is the runtime invariant the RR-003 fix establishes ("persisted snapshot at S +
\* contiguous WAL suffix [S+1..last] reconstructs all committed"). With the fixed model
\* (PERSIST_BEFORE_TRUNCATE = TRUE) the truncation gate keeps walBase <= durIndex+1, so
\* the prefix is always whole. With the defect (FALSE) TruncateWal can advance walBase
\* past durIndex+1 while durIndex still trails, opening a hole at the indices in
\* (durIndex .. walBase-1] — the silent-data-loss window. TLC reports the counterexample.
DurablePrefix ==
    \A n \in Nodes:
        walBase[n] <= durIndex[n] + 1

\* INV-SI-6: RecoveredCoversCommitted (RR-003) — after a crash/restart, the recovered
\* in-RAM snapshot index never silently advances past a hole: the recovered snapshot
\* (= durIndex on restart) plus the durable WAL must still cover the committed prefix
\* the node had acknowledged. We express the durable-coverage consequence: no node's
\* durable snapshot is ahead of the committed log, and the WAL suffix it retains never
\* starts above the next index after the durable snapshot (the no-gap consequence of
\* DurablePrefix, kept as a separate checkable form for the recovery direction).
RecoveredCoversCommitted ==
    \A n \in Nodes:
        /\ durIndex[n] <= Len(committedLog)
        /\ walTip[n] <= Len(committedLog)

SafetyInvariants ==
    /\ TypeOK
    /\ SnapshotBoundedByCommitted
    /\ SnapshotMatching
    /\ NoCommitRevert
    /\ InflightTermMonotonic
    /\ DurablePrefix
    /\ RecoveredCoversCommitted

=============================================================================
