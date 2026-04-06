---- MODULE ReadIndexSpec_TTrace_1776462705 ----
EXTENDS ReadIndexSpec, Sequences, TLCExt, Toolbox, ReadIndexSpec_TEConstants, Naturals, TLC

_expression ==
    LET ReadIndexSpec_TEExpression == INSTANCE ReadIndexSpec_TEExpression
    IN ReadIndexSpec_TEExpression!expression
----

_trace ==
    LET ReadIndexSpec_TETrace == INSTANCE ReadIndexSpec_TETrace
    IN ReadIndexSpec_TETrace!trace
----

_inv ==
    ~(
        TLCGet("level") = Len(_TETrace)
        /\
        currentTerm = ((n1 :> 1 @@ n2 :> 1 @@ n3 :> 0))
        /\
        state = ((n1 :> "leader" @@ n2 :> "leader" @@ n3 :> "follower"))
        /\
        pendingReads = ((n1 :> {} @@ n2 :> {} @@ n3 :> {}))
        /\
        appliedIndex = ((n1 :> 0 @@ n2 :> 0 @@ n3 :> 0))
        /\
        servedReads = ({})
        /\
        commitIndex = ((n1 :> 0 @@ n2 :> 0 @@ n3 :> 0))
    )
----

_init ==
    /\ state = _TETrace[1].state
    /\ currentTerm = _TETrace[1].currentTerm
    /\ appliedIndex = _TETrace[1].appliedIndex
    /\ pendingReads = _TETrace[1].pendingReads
    /\ servedReads = _TETrace[1].servedReads
    /\ commitIndex = _TETrace[1].commitIndex
----

_next ==
    /\ \E i,j \in DOMAIN _TETrace:
        /\ \/ /\ j = i + 1
              /\ i = TLCGet("level")
        /\ state  = _TETrace[i].state
        /\ state' = _TETrace[j].state
        /\ currentTerm  = _TETrace[i].currentTerm
        /\ currentTerm' = _TETrace[j].currentTerm
        /\ appliedIndex  = _TETrace[i].appliedIndex
        /\ appliedIndex' = _TETrace[j].appliedIndex
        /\ pendingReads  = _TETrace[i].pendingReads
        /\ pendingReads' = _TETrace[j].pendingReads
        /\ servedReads  = _TETrace[i].servedReads
        /\ servedReads' = _TETrace[j].servedReads
        /\ commitIndex  = _TETrace[i].commitIndex
        /\ commitIndex' = _TETrace[j].commitIndex

\* Uncomment the ASSUME below to write the states of the error trace
\* to the given file in Json format. Note that you can pass any tuple
\* to `JsonSerialize`. For example, a sub-sequence of _TETrace.
    \* ASSUME
    \*     LET J == INSTANCE Json
    \*         IN J!JsonSerialize("ReadIndexSpec_TTrace_1776462705.json", _TETrace)

=============================================================================

 Note that you can extract this module `ReadIndexSpec_TEExpression`
  to a dedicated file to reuse `expression` (the module in the 
  dedicated `ReadIndexSpec_TEExpression.tla` file takes precedence 
  over the module `ReadIndexSpec_TEExpression` below).

---- MODULE ReadIndexSpec_TEExpression ----
EXTENDS ReadIndexSpec, Sequences, TLCExt, Toolbox, ReadIndexSpec_TEConstants, Naturals, TLC

expression == 
    [
        \* To hide variables of the `ReadIndexSpec` spec from the error trace,
        \* remove the variables below.  The trace will be written in the order
        \* of the fields of this record.
        state |-> state
        ,currentTerm |-> currentTerm
        ,appliedIndex |-> appliedIndex
        ,pendingReads |-> pendingReads
        ,servedReads |-> servedReads
        ,commitIndex |-> commitIndex
        
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
\*---- MODULE ReadIndexSpec_TETrace ----
\*EXTENDS ReadIndexSpec, IOUtils, ReadIndexSpec_TEConstants, TLC
\*
\*trace == IODeserialize("ReadIndexSpec_TTrace_1776462705.bin", TRUE)
\*
\*=============================================================================
\*

---- MODULE ReadIndexSpec_TETrace ----
EXTENDS ReadIndexSpec, ReadIndexSpec_TEConstants, TLC

trace == 
    <<
    ([currentTerm |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),state |-> (n1 :> "follower" @@ n2 :> "follower" @@ n3 :> "follower"),pendingReads |-> (n1 :> {} @@ n2 :> {} @@ n3 :> {}),appliedIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),servedReads |-> {},commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0)]),
    ([currentTerm |-> (n1 :> 0 @@ n2 :> 1 @@ n3 :> 0),state |-> (n1 :> "follower" @@ n2 :> "leader" @@ n3 :> "follower"),pendingReads |-> (n1 :> {} @@ n2 :> {} @@ n3 :> {}),appliedIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),servedReads |-> {},commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0)]),
    ([currentTerm |-> (n1 :> 1 @@ n2 :> 1 @@ n3 :> 0),state |-> (n1 :> "leader" @@ n2 :> "leader" @@ n3 :> "follower"),pendingReads |-> (n1 :> {} @@ n2 :> {} @@ n3 :> {}),appliedIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0),servedReads |-> {},commitIndex |-> (n1 :> 0 @@ n2 :> 0 @@ n3 :> 0)])
    >>
----


=============================================================================

---- MODULE ReadIndexSpec_TEConstants ----
EXTENDS ReadIndexSpec

CONSTANTS n1, n2, n3

=============================================================================

---- CONFIG ReadIndexSpec_TTrace_1776462705 ----
CONSTANTS
    Nodes = { n1 , n2 , n3 }
    MaxTerm = 3
    MaxIndex = 3
    n2 = n2
    n1 = n1
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
\* Generated on Fri Apr 17 21:51:45 UTC 2026