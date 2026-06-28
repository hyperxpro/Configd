# Namespace-model design sketch (compile-checked artifact)

**Design artifact, not wiring.** These types make the [`../access-control.md`](../access-control.md) ACL
model and the [`../../../rfc/driver-protocol/01-paths-and-access.md`](../../../rfc/driver-protocol/01-paths-and-access.md)
path/authz contract **concrete and compile-checked**. Nothing here is wired into the build; no production
code is changed this session. It exists to catch type-level mistakes and to pin the load-bearing logic
(path normalization, the union+deny evaluation, the watch-authz contract) as runnable code rather than
prose.

Self-contained (a local `Scope` enum stands in for `io.configd.common.ConfigScope`), so it compiles
standalone on JDK 25.

## Files

| File | Role | Spec ref |
|---|---|---|
| `Scope.java` | the orthogonal replication-domain axis (stand-in) | RFC §2 |
| `Capability.java` | `{READ, LIST, WRITE, WATCH, ADMIN}` | access-control §2 / RFC A5-1 |
| `ConfigPath.java` | path **normalization + validation** (canonical form or throw) | RFC §3 |
| `PathPattern.java` | `Exact` / `Subtree` / `SingleSegment`; `matches` / `contains` / `intersects` | RFC §3.4, §6 |
| `PolicyRule.java` | `(scopes, pattern, effect, caps)`; `ALLOW`/`DENY` | access-control §1, §4 |
| `PolicySet.java` | **union-of-ALLOW minus DENY, deny-precedence, default-deny**; `coversTarget` | access-control §4 / RFC A5-4 |
| `WatchAuthz.java` | the **watch-authz contract**: authorize-at-subscription, reject-over-broad, `full_chain_verify`⇒root | access-control §6 / RFC §6 |
| `SketchSmokeTest.java` | a `main()` of asserts mirroring the worked examples | — |

## Build + run

```sh
cd docs/design/namespace-model/sketch
javac -d /tmp/sk-out io/configd/namespace/*.java
java -ea -cp /tmp/sk-out io.configd.namespace.SketchSmokeTest
# -> SKETCH OK   (verified on Corretto JDK 25)
```

The smoke test asserts: path normalization (canonical + rejected forms); the access-control.md §4.3 worked
example (union grants `READ+WRITE` on flags; `READ` on a known secret via two rules; `LIST` on secrets
denied by deny-precedence; cross-tenant default-deny); and the §6 watch contract (subtree watch within
grant allowed; over-broad watch rejected; `full_chain_verify`/`FULL` rejected without root scope and
allowed with it; `WATCH`-without-`READ` rejected — INV-WATCH-READ).

## What the sketch deliberately omits

Wire encoding, the actual edge subscribe path, the cursor-vector list pagination, policy-as-config storage,
and any integration with the real `AclService`/`ConfigScope`/`StaticShardMap`. Those are wiring, out of
scope for a design session. The sketch fixes *semantics*, not plumbing.
