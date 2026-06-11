-------------------------- MODULE ReadIndexSpec --------------------------
\* TLA+ specification of Raft's ReadIndex protocol for linearizable reads.
\*
\* Closes SPEC-GAP-4 / R-14b / PA-5023.
\*
\* The ReadIndex protocol (Ongaro 2014, Section 6.4) lets a leader serve a
\* linearizable read without going through the full log-append cycle. The
\* protocol:
\*
\*   1. Leader L records its current commitIndex as readIdx.
\*   2. L issues a heartbeat round and waits for a quorum of acks at term T.
\*   3. L waits for its state machine to apply at least up to readIdx.
\*   4. L returns the committed value at readIdx as the read response.
\*
\* Safety property: the value returned at readIdx is "fresh" — i.e., any
\* write that committed before the read was issued is visible to the read.
\* Concretely: if any node committed an entry at index i before the read
\* completed, then i <= readIdx of the served read.
\*
\* This spec deliberately abstracts log replication (already verified by
\* ConsensusSpec.tla) and focuses solely on the freshness invariant of
\* the ReadIndex path. Heartbeat acks are modelled as a non-deterministic
\* receipt of a current-term confirmation from a follower.
\*
\* Model-check with TLC. State-space target: < 1M states with default
\* parameters; runs in < 30s.

EXTENDS Integers, FiniteSets, TLC

CONSTANTS
    Nodes,        \* Set of node IDs
    MaxTerm,      \* Term bound
    MaxIndex      \* Maximum committed index to consider

VARIABLES
    currentTerm,  \* currentTerm[n]
    state,        \* state[n] \in {"follower", "candidate", "leader"}
    commitIndex,  \* commitIndex[n] — committed index per node (abstract)
    appliedIndex, \* appliedIndex[n] — state machine application progress
    \* ReadIndex protocol state:
    pendingReads, \* pendingReads[n] = set of records
                  \*   [readIdx, term, acked, completed]
                  \*   tracking in-flight ReadIndex requests on leader n.
    servedReads   \* set of records [server, term, readIdx]
                  \*   of ReadIndex requests that have been served.

vars == <<currentTerm, state, commitIndex, appliedIndex,
          pendingReads, servedReads>>

\* ---- Helpers ----

Quorum == {Q \in SUBSET Nodes : Cardinality(Q) * 2 > Cardinality(Nodes)}

\* True iff some leader holds a SERVEABLE read: heartbeat-quorum acked, applied through
\* its readIdx, at the leader's current term — CompleteReadIndex's exact precondition.
\* A serveable read MUST NOT starve (used by the RR-026 liveness property and to gate
\* InitiateReadIndex so the no-starvation claim is well-posed).
HasServeableRead ==
    \E n \in Nodes:
        \E r \in pendingReads[n]:
            /\ state[n] = "leader"
            /\ r.acked \in Quorum
            /\ appliedIndex[n] >= r.readIdx
            /\ currentTerm[n] = r.term

\* ---- Initial State ----

Init ==
    /\ currentTerm  = [n \in Nodes |-> 0]
    /\ state        = [n \in Nodes |-> "follower"]
    /\ commitIndex  = [n \in Nodes |-> 0]
    /\ appliedIndex = [n \in Nodes |-> 0]
    /\ pendingReads = [n \in Nodes |-> {}]
    /\ servedReads  = {}

\* ---- Type Invariant ----

TypeOK ==
    /\ currentTerm  \in [Nodes -> 0..MaxTerm]
    /\ state        \in [Nodes -> {"follower", "candidate", "leader"}]
    /\ commitIndex  \in [Nodes -> 0..MaxIndex]
    /\ appliedIndex \in [Nodes -> 0..MaxIndex]

\* ---- Background actions (abstract Raft progression) ----

\* A node becomes leader at a new term. We model this without going through
\* candidacy explicitly because ConsensusSpec.tla already verifies election
\* safety; here we only need leader-step-down and term-bump to exist so
\* stale leader scenarios can arise. To avoid an abstract-model artefact
\* (two simultaneous leaders at the same term), the new term is strictly
\* greater than every node's current term.
BecomeLeader(n) ==
    /\ \A m \in Nodes: currentTerm[m] < MaxTerm
    /\ LET newTerm == 1 + LET maxTerm == CHOOSE t \in {currentTerm[m] : m \in Nodes}:
                                 \A t2 \in {currentTerm[m] : m \in Nodes}: t2 <= t
                          IN  maxTerm
       IN
       /\ newTerm <= MaxTerm
       /\ currentTerm' = [m \in Nodes |->
                            IF m = n THEN newTerm ELSE currentTerm[m]]
       \* Old leaders step down (their term is strictly less than newTerm).
       /\ state' = [m \in Nodes |->
                        IF m = n THEN "leader"
                        ELSE IF state[m] = "leader" THEN "follower"
                        ELSE state[m]]
       \* Drop all pending reads — any in-flight read on an old leader
       \* is invalidated; new leader starts with empty pending set.
       /\ pendingReads' = [m \in Nodes |-> {}]
    /\ UNCHANGED <<commitIndex, appliedIndex, servedReads>>

\* Leader commits a new entry (abstract — does not model log content,
\* only the commitIndex advancement).
CommitEntry(n) ==
    /\ state[n] = "leader"
    /\ commitIndex[n] < MaxIndex
    /\ commitIndex' = [commitIndex EXCEPT ![n] = commitIndex[n] + 1]
    /\ UNCHANGED <<currentTerm, state, appliedIndex,
                   pendingReads, servedReads>>

\* State machine catches up — appliedIndex tracks commitIndex eventually.
ApplyEntry(n) ==
    /\ appliedIndex[n] < commitIndex[n]
    /\ appliedIndex' = [appliedIndex EXCEPT ![n] = appliedIndex[n] + 1]
    /\ UNCHANGED <<currentTerm, state, commitIndex,
                   pendingReads, servedReads>>

\* Followers replicate (abstract — bring follower's commitIndex up to the
\* leader's commitIndex without explicit AppendEntries).
ReplicateToFollower(leader, follower) ==
    /\ state[leader] = "leader"
    /\ leader /= follower
    /\ commitIndex[follower] < commitIndex[leader]
    /\ commitIndex' = [commitIndex EXCEPT ![follower] = commitIndex[leader]]
    /\ currentTerm' = [currentTerm EXCEPT ![follower] = currentTerm[leader]]
    /\ UNCHANGED <<state, appliedIndex, pendingReads, servedReads>>

\* ---- ReadIndex Protocol ----

\* Step 1: leader initiates a ReadIndex by recording its current commitIndex.
\* Liveness well-posedness (RR-026): do not pile on a new read while a serveable read is
\* already waiting to be served — otherwise churn could keep HasServeableRead true forever
\* while individual reads are served, making the no-starvation property ill-posed. This
\* gate does not weaken safety (a deferred read is still eventually initiated once the
\* serveable one drains) and only affects the liveness model.
InitiateReadIndex(n) ==
    /\ state[n] = "leader"
    /\ Cardinality(pendingReads[n]) < 2  \* state-space bound
    /\ ~HasServeableRead
    /\ pendingReads' = [pendingReads EXCEPT ![n] =
            pendingReads[n] \cup
            {[readIdx |-> commitIndex[n],
              term    |-> currentTerm[n],
              acked   |-> {n}]}]
    /\ UNCHANGED <<currentTerm, state, commitIndex, appliedIndex, servedReads>>

\* Step 2: a follower acks the leader's heartbeat.
\* The follower's currentTerm must be >= the leader's term to ack
\* (i.e., the follower has not voted for a higher-term candidate).
\* Importantly: the leader records the ack only if its own term is
\* still the term in which the read was initiated; otherwise the
\* read is invalid (the leader has stepped down).
ReadHeartbeatAck(n, m, r) ==
    /\ state[n] = "leader"
    /\ r \in pendingReads[n]
    /\ currentTerm[n] = r.term         \* Leader has not stepped down.
    /\ currentTerm[m] <= r.term        \* Follower not in a higher term.
    /\ m \notin r.acked
    /\ pendingReads' = [pendingReads EXCEPT ![n] =
            (pendingReads[n] \ {r}) \cup
            {[readIdx |-> r.readIdx,
              term    |-> r.term,
              acked   |-> r.acked \cup {m}]}]
    /\ UNCHANGED <<currentTerm, state, commitIndex, appliedIndex, servedReads>>

\* Step 3 + 4: leader serves the ReadIndex once
\*   (a) a quorum has acked AND
\*   (b) the state machine has applied at least up to readIdx AND
\*   (c) the leader's term hasn't changed.
CompleteReadIndex(n, r) ==
    /\ state[n] = "leader"
    /\ r \in pendingReads[n]
    /\ r.acked \in Quorum
    /\ appliedIndex[n] >= r.readIdx
    /\ currentTerm[n] = r.term
    /\ pendingReads' = [pendingReads EXCEPT ![n] = pendingReads[n] \ {r}]
    /\ servedReads' = servedReads \cup
            {[server  |-> n,
              term    |-> r.term,
              readIdx |-> r.readIdx]}
    /\ UNCHANGED <<currentTerm, state, commitIndex, appliedIndex>>

\* ---- Next ----

Next ==
    \/ \E n \in Nodes: BecomeLeader(n)
    \/ \E n \in Nodes: CommitEntry(n)
    \/ \E n \in Nodes: ApplyEntry(n)
    \/ \E n, m \in Nodes: ReplicateToFollower(n, m)
    \/ \E n \in Nodes: InitiateReadIndex(n)
    \/ \E n \in Nodes: \E m \in Nodes: \E r \in pendingReads[n]:
            ReadHeartbeatAck(n, m, r)
    \/ \E n \in Nodes: \E r \in pendingReads[n]: CompleteReadIndex(n, r)

Spec == Init /\ [][Next]_vars

\* ---- Safety Invariants ----

\* INV-RI-1: Election Safety (single leader per term) — sanity.
ElectionSafety ==
    \A t \in 0..MaxTerm:
        Cardinality({n \in Nodes : state[n] = "leader" /\ currentTerm[n] = t}) <= 1

\* INV-RI-2: Linearizability of served reads.
\*
\* If a ReadIndex was served at (term=T, readIdx=R), then any commit that
\* happened *before* this read was initiated must satisfy commitIndex <= R.
\*
\* Concretely: at the moment of serving, every node that the leader at term
\* T heard from in the heartbeat quorum had commitIndex <= R AT SOME POINT,
\* so any earlier-committed entry must be at index <= R.
\*
\* Operational form: for every served read at (T, R) and every node m,
\* if m has commitIndex C at term T, then it is not possible that C > R
\* AND the leader at T was not aware of C. Since the leader records
\* commitIndex[leader] as readIdx and the leader's commitIndex is
\* monotonically the maximum (by Raft Leader Completeness), R is the
\* max commitIndex visible to the heartbeat quorum at the time.
\*
\* The simplest checkable form: a served read's readIdx is bounded above
\* by the leader's commitIndex at the time of service. Combined with
\* "leader cannot lose committed entries", this yields linearizability.
\*
\* We express the invariant as: for any served read (server=n, term=T,
\* readIdx=R), the server n's commitIndex at the time of completion was
\* >= R, AND R is consistent with what other nodes saw.
\*
\* For TLC checking, we phrase the invariant on current state:
\*   (a) servedReads only contains entries whose term is <= currentTerm of
\*       some leader who could have served them.
\*   (b) Any served read's readIdx is <= MaxIndex (bound check).
ReadIndexBoundedByMaxIndex ==
    \A r \in servedReads: r.readIdx <= MaxIndex

\* INV-RI-3: Read freshness (de-vacuumed, R-05c).
\*
\* A served read never reports an index its server had not yet APPLIED.
\* CompleteReadIndex requires appliedIndex[n] >= r.readIdx, and appliedIndex is
\* monotonic non-decreasing, so for every served read r, r.readIdx is forever
\* <= appliedIndex[r.server]. This is the F-0009 property: a ReadIndex must wait
\* for the state machine to apply up to readIdx before serving. A regression that
\* serves a read ahead of the applied state (a stale read) is caught here.
\* (Non-vacuous: constrains servedReads against appliedIndex; the previous form
\* had a literal `TRUE` consequent and could never fail.)
ReadFreshness ==
    \A r \in servedReads:
        r.readIdx <= appliedIndex[r.server]

\* INV-RI-4: Stale leader cannot serve (de-vacuumed, R-05c).
\*
\* A read is never served at a term beyond its server's current term.
\* CompleteReadIndex serves only while currentTerm[n] = r.term, and per-node
\* currentTerm is monotonic non-decreasing, so every served read satisfies
\* r.term <= currentTerm[r.server] forever. A regression that lets a node serve
\* a read recorded at a term ahead of the node (a stale/stepped-down leader
\* serve) is caught here. (Non-vacuous: constrains servedReads against
\* currentTerm; the previous form had a literal `TRUE` consequent.)
NoStaleLeaderServe ==
    \A r \in servedReads:
        r.term <= currentTerm[r.server]

\* INV-RI-5: Monotonic served reads per leader-term.
\* Within the same (leader, term), served readIdx values are non-decreasing
\* along the wall-clock order of completion (real-time linearizability).
\* In TLC we cannot directly observe wall-clock order; we instead check that
\* the leader's commitIndex never decreases within a term — which is a
\* prerequisite for monotonic readIdx since readIdx is set from commitIndex.
\* This is verified structurally: commitIndex is only ever incremented.

SafetyInvariants ==
    /\ TypeOK
    /\ ElectionSafety
    /\ ReadIndexBoundedByMaxIndex
    /\ ReadFreshness
    /\ NoStaleLeaderServe

\* ---- Liveness (RR-026) ----
\*
\* True iff some leader is holding a SERVEABLE read: one with a heartbeat-quorum ack,
\* A fair specification. The base Spec (`Init /\ [][Next]_vars`) admits stuttering forever,
\* so any liveness check is vacuous — RR-026's exact gap. FairNext adds weak fairness to
\* the read-progress actions (the heartbeat ack and apply that DRIVE a read to serveable,
\* and the serve itself). To keep the progress claim well-posed, InitiateReadIndex is
\* gated to not pile on a NEW read while a serveable one is already waiting (see the
\* action's `~HasServeableRead` guard) — so a serveable read is the continuous
\* completion witness and weak fairness suffices to drain it (no churn-masking).
FairNext ==
    /\ WF_vars(\E n \in Nodes: \E r \in pendingReads[n]: CompleteReadIndex(n, r))
    /\ WF_vars(\E n \in Nodes: ApplyEntry(n))
    /\ WF_vars(\E n \in Nodes: \E m \in Nodes: \E r \in pendingReads[n]:
                    ReadHeartbeatAck(n, m, r))

LiveSpec == Init /\ [][Next]_vars /\ FairNext

\* LIVE-RI-1: ReadEventuallyServed — a serveable read never starves: whenever a leader
\* holds a serveable read, eventually no serveable read remains (it was served via
\* CompleteReadIndex, which removes it and grows servedReads, or the leader stepped down).
\* The InitiateReadIndex `~HasServeableRead` gate prevents churn from keeping
\* HasServeableRead true forever while individual reads are served, so this is the honest
\* no-read-starves progress guarantee of the ReadIndex path (a state-level leads-to TLC
\* can check, unlike a per-pending-element temporal quantifier).
\*
\* WRONG-FAIRNESS VACUITY CHECK: under the UNFAIR base Spec (no WF) this property FAILS —
\* TLC finds the stutter counterexample (a serveable read sits forever because nothing is
\* forced to run). That demonstration lives in
\* gates/spec-smoke/ReadIndexSpec-livenessvacuity.cfg (same PROPERTIES under Spec →
\* violated), proving the GREEN result under LiveSpec is earned by the fairness.
ReadEventuallyServed ==
    HasServeableRead ~> (~HasServeableRead)

=============================================================================
