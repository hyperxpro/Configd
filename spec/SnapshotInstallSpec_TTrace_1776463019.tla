---- MODULE SnapshotInstallSpec_TTrace_1776463019 ----
EXTENDS Sequences, TLCExt, SnapshotInstallSpec, Toolbox, SnapshotInstallSpec_TEConstants, Naturals, TLC

_expression ==
    LET SnapshotInstallSpec_TEExpression == INSTANCE SnapshotInstallSpec_TEExpression
    IN SnapshotInstallSpec_TEExpression!expression
----

_trace ==
    LET SnapshotInstallSpec_TETrace == INSTANCE SnapshotInstallSpec_TETrace
    IN SnapshotInstallSpec_TETrace!trace
----

_inv ==
    ~(
        TLCGet("level") = Len(_TETrace)
        /\
        currentTerm = ((n1 :> 1 @@ n2 :> 2 @@ n3 :> 0))
        /\
        log = ((n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>))
        /\
        state = ((n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"))
        /\
        snapshot = ((n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 1, term |-> 2] @@ n3 :> [index |-> 0, term |-> 0]))
        /\
        commitIndex = ((n1 :> 1 @@ n2 :> 1 @@ n3 :> 0))
        /\
        inflight = ({})
    )
----

_init ==
    /\ state = _TETrace[1].state
    /\ inflight = _TETrace[1].inflight
    /\ currentTerm = _TETrace[1].currentTerm
    /\ snapshot = _TETrace[1].snapshot
    /\ commitIndex = _TETrace[1].commitIndex
    /\ log = _TETrace[1].log
----

_next ==
    /\ \E i,j \in DOMAIN _TETrace:
        /\ \/ /\ j = i + 1
              /\ i = TLCGet("level")
        /\ state  = _TETrace[i].state
        /\ state' = _TETrace[j].state
        /\ inflight  = _TETrace[i].inflight
        /\ inflight' = _TETrace[j].inflight
        /\ currentTerm  = _TETrace[i].currentTerm
        /\ currentTerm' = _TETrace[j].currentTerm
        /\ snapshot  = _TETrace[i].snapshot
        /\ snapshot' = _TETrace[j].snapshot
        /\ commitIndex  = _TETrace[i].commitIndex
        /\ commitIndex' = _TETrace[j].commitIndex
        /\ log  = _TETrace[i].log
        /\ log' = _TETrace[j].log

\* Uncomment the ASSUME below to write the states of the error trace
\* to the given file in Json format. Note that you can pass any tuple
\* to `JsonSerialize`. For example, a sub-sequence of _TETrace.
    \* ASSUME
    \*     LET J == INSTANCE Json
    \*         IN J!JsonSerialize("SnapshotInstallSpec_TTrace_1776463019.json", _TETrace)

=============================================================================

 Note that you can extract this module `SnapshotInstallSpec_TEExpression`
  to a dedicated file to reuse `expression` (the module in the 
  dedicated `SnapshotInstallSpec_TEExpression.tla` file takes precedence 
  over the module `SnapshotInstallSpec_TEExpression` below).

---- MODULE SnapshotInstallSpec_TEExpression ----
EXTENDS Sequences, TLCExt, SnapshotInstallSpec, Toolbox, SnapshotInstallSpec_TEConstants, Naturals, TLC

expression == 
    [
        \* To hide variables of the `SnapshotInstallSpec` spec from the error trace,
        \* remove the variables below.  The trace will be written in the order
        \* of the fields of this record.
        state |-> state
        ,inflight |-> inflight
        ,currentTerm |-> currentTerm
        ,snapshot |-> snapshot
        ,commitIndex |-> commitIndex
        ,log |-> log
        
        \* Put additional constant-, state-, and action-level expressions here:
        \* ,_stateNumber |-> _TEPosition
        \* ,_stateUnchanged |-> state = state'
        
        \* Format the `state` variable as Json value.
        \* ,_stateJson |->
        \*     LET J == INSTANCE Json
        \*     IN J!ToJson(state)
        
        \* Lastly, you may build expressions over arbitrary sets of states by
        \* leveraging the _TETrace operator.  For example, this is how to
        \* count the number of times a spec variable changed up to the current
        \* state in the trace.
        \* ,_stateModCount |->
        \*     LET F[s \in DOMAIN _TETrace] ==
        \*         IF s = 1 THEN 0
        \*         ELSE IF _TETrace[s].state # _TETrace[s-1].state
        \*             THEN 1 + F[s-1] ELSE F[s-1]
        \*     IN F[_TEPosition - 1]
    ]

=============================================================================



Parsing and semantic processing can take forever if the trace below is long.
 In this case, it is advised to uncomment the module below to deserialize the
 trace from a generated binary file.

\*
\*---- MODULE SnapshotInstallSpec_TETrace ----
\*EXTENDS IOUtils, SnapshotInstallSpec, SnapshotInstallSpec_TEConstants, TLC
\*
\*trace == IODeserialize("SnapshotInstallSpec_TTrace_1776463019.bin", TRUE)
\*
\*=============================================================================
\*

---- MODULE SnapshotInstallSpec_TETrace ----
EXTENDS SnapshotInstallSpec, SnapshotInstallSpec_TEConstants, TLC

trace == 
    <<
    ([currentTerm |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "follower" @@ n2 :> "follower" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 0, term |-> 0] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "leader" @@ n2 :> "follower" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 0, term |-> 0] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),log |-> (n1 :> <<[term |-> 1]>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "leader" @@ n2 :> "follower" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 0, term |-> 0] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),log |-> (n1 :> <<[term |-> 1]>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "leader" @@ n2 :> "follower" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 0, term |-> 0] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "leader" @@ n2 :> "follower" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 2 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 2 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<[term |-> 2]>> @@ n3 :> <<>>),state |-> (n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 0 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 2 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<[term |-> 2]>> @@ n3 :> <<>>),state |-> (n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 0, term |-> 0] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 1 @@ n3 :> 0),inflight |-> {}]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 2 @@ n3 :> 0),log |-> (n1 :> <<>> @@ n2 :> <<>> @@ n3 :> <<>>),state |-> (n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"),snapshot |-> (n1 :> [index |-> 1, term |-> 1] @@ n2 :> [index |-> 1, term |-> 2] @@ n3 :> [index |-> 0, term |-> 0]),commitIndex |-> (n1 :> 1 @@ n2 :> 1 @@ n3 :> 0),inflight |-> {}])
    >>
----


=============================================================================

---- MODULE SnapshotInstallSpec_TEConstants ----
EXTENDS SnapshotInstallSpec

CONSTANTS n1, n2, n3

=============================================================================

---- CONFIG SnapshotInstallSpec_TTrace_1776463019 ----
CONSTANTS
    Nodes = { n1 , n2 , n3 }
    MaxTerm = 3
    MaxIndex = 4
    n1 = n1
    n2 = n2
    n3 = n3

INVARIANT
    _inv

CHECK_DEADLOCK
    \* CHECK_DEADLOCK off because of PROPERTY or INVARIANT above.
    FALSE

INIT
    _init

NEXT
    _next

CONSTANT
    _TETrace <- _trace

ALIAS
    _expression
=============================================================================
\* Generated on Fri Apr 17 21:57:01 UTC 2026