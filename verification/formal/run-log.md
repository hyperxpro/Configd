# TLA+ Model Checking Run Log

## Re-verification Run (2026-04-13)

### Tool Version
- **TLC**: tla2tools.jar (bundled in spec/)
- **Java**: OpenJDK 25.0.2 (Corretto)

### Parameters
- Nodes: {n1, n2, n3}
- MaxTerm: 3
- MaxLogLen: 3
- Values: {v1, v2}
- CHECK_DEADLOCK: FALSE
- Workers: auto

### Invariants Checked
- TypeOK
- ElectionSafety
- StateMachineSafety
- NoStaleOverwrite
- LogMatching
- ReconfigSafety
- SingleServerInvariant
- NoOpBeforeReconfig

### Results
| Metric | Original (2026-04-10) | Re-run (2026-04-13) | Match? |
|--------|----------------------|---------------------|--------|
| Status | PASS | PASS | ✅ |
| States generated | 13,775,323 | 13,775,323 | ✅ Exact |
| Distinct states | 3,299,086 | 3,299,086 | ✅ Exact |
| Depth | 25 | 25 | ✅ |
| Time | 04min 10s | 04min 32s | ✅ Within variance |
| Fingerprint collision prob | N/A | 1.9E-6 | ✅ Acceptable |

### Findings

#### FINDING F-TLA-01: Liveness Property NOT Checked (Severity: High)
The `EdgePropagationLiveness` temporal property (line 253-258) is defined in the spec but is NOT listed in the `.cfg` file's INVARIANTS. TLC only checks state invariants by default — temporal properties require explicit PROPERTY declarations. This means the liveness guarantee has NEVER been model-checked.

#### FINDING F-TLA-02: Small Model Bounds (Severity: Medium)
MaxTerm=3, MaxLogLen=3, 3 Nodes is a minimal configuration. Bugs that manifest only with:
- 4+ terms (cascading elections)
- 4+ log entries (longer replication sequences)
- 5 nodes (the production configuration)
...would not be found. Industry standard for Raft model checking is at minimum 5 nodes, MaxTerm=4, MaxLogLen=4 (e.g., etcd's Raft TLA+ spec).

#### FINDING F-TLA-03: VersionMonotonicity is Trivial (Severity: High)
The VersionMonotonicity invariant at line 184 is:
```
VersionMonotonicity == \A e \in Nodes: edgeVersion[e] >= 0
```
This only checks non-negativity. A real monotonicity check would require a temporal property or history variable:
```
VersionMonotonicity == [][\A e \in Nodes: edgeVersion'[e] >= edgeVersion[e]]_vars
```
The current invariant is vacuously true and provides zero verification value.

#### FINDING F-TLA-04: LeaderCompleteness is an Approximation (Severity: Medium)
The spec's NOTE at lines 155-160 acknowledges that INV-2 is a "state-level approximation" of the true Leader Completeness property. The real property is temporal: "any FUTURE leader must contain all committed entries." The spec only checks leaders visible in the current state.

#### FINDING F-TLA-05: Spec Does Not Model Code-Only Features (Severity: High)
The following features exist in code but NOT in the TLA+ spec:
- PreVote protocol (RaftNode lines 822-848, 879-893, 932-971)
- CheckQuorum mechanism (RaftNode lines 649-663, 1384-1401)
- ReadIndex protocol (RaftNode lines 330-361)
- Leadership transfer (RaftNode lines 271-289, 899-908, 1419-1428)
- Pipelining / inflightCount (RaftNode lines 1081-1083)
- Backpressure / maxPendingProposals (RaftNode lines 251-254)

These are not mere optimizations — PreVote and CheckQuorum affect safety under partition. Their absence from the spec means the formal verification provides no guarantee that their implementation is correct.
