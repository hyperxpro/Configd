# INV-MSW-ATOMIC — the multi-shard watch authorization contract

This is the authorization specification for a watch whose target scatters across more than one Raft
group (N&gt;1). It is a load-bearing security invariant: a watch is authorized as a single indivisible
whole-target decision and served as all N shard legs or none. The shard-complete `_acl/` policy plane
this gate delivers is what that decision is evaluated against; the enforcement across N legs is the
next gate's work (see [Scope](#scope-what-this-gate-delivers-vs-what-gate-3-enforces)).

The authority of record is the code the properties below name plus the tests that pin them — not this
prose. Method and test names are navigational; the classification (what holds, what enforces it) is the
durable content. Cross-referenced normative clauses live in
[`../rfc/driver-protocol/02-watches.md`](../rfc/driver-protocol/02-watches.md) (§2, W7-2a / W7-3 / W7-7).

## The invariant

> A multi-shard watch is authorized as a **single indivisible whole-target decision** and served as
> **all N legs or none**. If the authorizer is unavailable, the policy snapshot is unloadable **or
> incomplete**, or any required shard leg cannot be established, the server MUST reject/close the WHOLE
> watch (`WATCH_CANCELED(NOT_AUTHORIZED)` at create; whole-watch cancel mid-stream). It MUST NEVER
> silently degrade to the subset of shards that happen to be reachable/authorized.

A served subset is two violations at once: a silent-partial / false-completeness (indistinguishable from
"no changes" for the missing shards — what W7-2 forbids), and an authorization hole (the W7-2a universal
quantifier over every key in the target was not actually discharged). Degrade-to-fewer-shards is a
security downgrade, not graceful degradation.

## Why the policy plane must be shard-complete first

`_acl/roles/*` and `_acl/bindings/*` are ordinary keys routed by `shardFor(scope, key)`, so at N&gt;1 they
hash-scatter across all N groups. A policy loader that reads only the primary group's store observes ~1/N
of the policy: a role, binding, or **DENY** on a non-primary shard is silently absent. Missing ALLOW is an
under-grant (a fail-closed availability effect); **missing DENY is an under-deny — a genuine authorization
bypass**, because a watch that an interior DENY should reject is authorized. The same incomplete snapshot
also fails to advance `configPolicyVersion()` on a non-primary `_acl/` apply, so the W7-7 revocation
trigger never fires for a non-primary-shard revocation (unbounded revocation latency).

This gate makes the loader shard-complete: it scatter-gathers `_acl/` across every group's store,
registers on every group's state machine, serializes rebuilds on one node-local worker, and publishes
under a node-local monotonic version that is exactly the `configPolicyVersion()` the per-connection W7-7
re-authorization consumes on the same node. No wire change, no new consensus. The invariant above then has
a complete policy source to evaluate against.

## The pinned properties (what a shard-complete decision rests on)

| Property | Why it holds | Pinned by |
|---|---|---|
| **Shard-independent predicate.** `coversTarget` / `authorizesWatch` are pure, static functions of `(rules, target, cap)` — no gid, no shard count, no instance state. The whole-target decision is invariant to the order per-shard rules are merged. | A scattered PREFIX/FULL target is one whole-subtree cover over the logical prefix; a per-shard authz loop is neither needed nor wanted (it would reintroduce divergence). | `WatchAuthzGateContractTest.{coversTargetIsInvariantToRuleMergeOrder, authorizesWatchIsInvariantToRuleMergeOrder}`; `AclService.coversTarget`/`authorizesWatch` |
| **One gate, before any leg streams a byte.** Validate → snapshot policy version → authorize whole target → register → only then drive the drain(s); zero data frames precede a reject. | Single node, single `AclService` snapshot, one decision — per-leg divergence is impossible. | `WatchVeneerDriverTest.{denyingAuthorizerRejectsWithNotAuthorizedAndZeroDataFrames, fullChainVerifyDenyEmitsZeroNotifyBeforeReject}`; `FanOutConnectionDriver.handleWatchCreate` |
| **Version read BEFORE the gate** (seed-before-authorize) so a reload racing the create is caught on the first re-auth. | `versionAtCreate` is read before `authorize`; a later revoking reload advances past the seed and triggers re-auth. | `WatchRevocationTest.revocationRacingTheCreateIsCaughtOnFirstReauth`; `FanOutConnectionDriver` (version read before the gate) |
| **FULL / full_chain_verify → root effective target `""`.** The verbatim whole-scope stream is authorized only by a root-scope grant; a subtree-only principal is denied (the cross-tenant-leak guard). | Mapping the effective target to `""` before the cover makes `"".startsWith(A.prefix)` hold only for a root grant. | `WatchAuthzGateContractTest.fullAndFullChainVerifyMapToRootAndDependOnAWholeStoreGrant`; `AclServiceWatchAuthorizerTest` (W7 matrix, tests 5-6); `AclServiceWatchAuthorizer` |
| **Fail-closed absolutes.** Null authorizer / `"plaintext"` identity / any throwable ⇒ deny. | The driver gate denies on every doubt. | `WatchVeneerDriverTest.{nullAuthorizerFailsClosed, unauthenticatedPlaintextIdentityFailsClosedEvenWhenAuthorizerAllows, throwingAuthorizerFailsClosed}`; `FanOutConnectionDriver.authorize` |

## Bypass classes → killing property → test

| # | Attack | Killed by | Test / posture |
|---|---|---|---|
| B1 | Coordinator authorizes/serves only reachable shards → partial view / false-empty | INV-MSW-ATOMIC (all-or-none) | Gate 3: force one leg unavailable → whole watch `NOT_AUTHORIZED`, zero data frames |
| B2 | Leg opened after the re-auth sweep rides stale authorization (failover / lazy open) | Seed-before-authorize; every late leg goes through the same create gate | `WatchRevocationTest.revocationRacingTheCreateIsCaughtOnFirstReauth` (single-leg today); Gate 3 for late legs |
| B3 | Resume-with-vector hoping to skip the gate | Resume = create for authz; the cursor is data, never an authz token | `WatchRevocationTest` (revoke, resume with old cursor → `NOT_AUTHORIZED`) |
| B4 | gid-spoofed cursor components to widen scope / touch foreign shards | The authorized **target** determines the materialized shard set, never the cursor; gid ∉ [0,N) ⇒ `BAD_SUBSCRIBE` | Gate 3 coverage (`shardIds()`-driven) |
| B5 | Cross-scope confusion (`shardFor` folds scope but the authz path is scope-blind, DL-O6-02) | v1 is single-scope (GLOBAL-only) ⇒ inert; **pinned known limitation** — multi-scope needs scope-aware ACLs first | Known limitation; guard if multi-scope ever ships |
| B6 | Write a tightening DENY to a non-primary-shard `_acl/` key (the P0 as an attack) | Shard-complete loader + all-shards node-local monotonic version | `AclConfigPolicyLoaderMultiShardTest.tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected` (GREEN) + `...tB6_redProof_singleStorePrimaryOnly_missesNonPrimaryShardDeny_watchStillAuthorized` (the bug, pinned) |
| B7 | Revocation race: a leg keeps delivering after the logical decision flipped | One logical re-check → all N legs cut atomically; bound off the slowest shard | `AclConfigPolicyLoaderMultiShardTest.tW7Ver_nonPrimaryDenyApply_advancesConfigPolicyVersion` (trigger); Gate 3 for the all-legs cut |

B6 is the P0 this gate closes; its regression pair is the durable proof (the single-store construction misses
a non-primary DENY; the multi-shard construction applies it). B1 / B4 / B7's cross-leg enforcement is Gate 3.

## Scope — what this gate delivers vs what Gate 3 enforces

**This gate delivers** the shard-complete `_acl/` policy source (`AclConfigPolicyLoader` multi-shard mode +
the all-shards listener wiring) that the invariant is authorized against, plus the pinned predicate /
one-gate / seed-before-authorize / FULL→`""` / fail-closed contract above.

**Gate 3 enforces the invariant across N legs:** one whole-target decision fans out to N seeds + N tails,
all-or-none establishment, and whole-watch revocation (W7-7 generalized) cutting all N legs atomically from
the client's view when the decision flips. The revocation bound (with this gate's fix): max over the
required shards of (commit+apply on the serving node's replica) + loader rebuild + one session poll tick; a
multi-key `_acl/` revocation is effective only when its last required key applies, since cross-shard `_acl/`
edits are non-atomic (`CrossShardWriteGuard.requireSingleShard` rejects a cross-shard batch). Without this
gate's fix that bound is unbounded for any non-primary-shard revocation — the gating defect it removes.
