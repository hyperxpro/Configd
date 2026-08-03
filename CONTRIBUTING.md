# Contributing to Configd

The bar for correctness and operator-honesty is high. Read this in full before opening your first PR.
By participating you agree to the [code of conduct](CODE_OF_CONDUCT.md).

## Prerequisites

- **JDK 25 (Amazon Corretto recommended).** The reactor pins `maven.compiler.release=25` and runs with
  `--enable-preview`. See ADR-0022 for the runtime decision.
- **Maven wrapper:** use the bundled `./mvnw` — do not install Maven globally. The wrapper's
  `distributionSha256Sum` (in `.mvn/wrapper/maven-wrapper.properties`) is the supply-chain pin; never
  override it.
- A clean working tree and a topic branch off `main`.

## Build and test

The single commit-gate command is:

```sh
./mvnw -T 1C verify
```

This runs the full reactor build, unit and property tests, the deterministic simulation tests, the
wire-compat golden-bytes guard, and the supply-chain checks. CI re-runs the same command on push and
pull request (`.github/workflows/ci.yml`).

**Every commit on a PR must pass `./mvnw -T 1C verify`.** Do not push a "WIP" commit that breaks the
build even if a later commit fixes it — bisects across a broken commit are how regressions hide.

Targeted invocations during development:

```sh
./mvnw -pl configd-consensus-core test                 # one module
./mvnw -pl configd-consensus-core test -Dtest=RaftNodeTest
./mvnw test -pl configd-testkit -Dtest='*Simulation*'  # sim suite only
```

### Gates and the local repository

The scripts in `gates/` install their modules with tests skipped and then run targeted tests with
`mvn -o`, pinning the run to the jars just installed. That makes them sensitive to what the local
repository already holds, in a way an ordinary build is not. Two rules follow, and breaking either
one is invisible on a developer box and red on a cold CI cache:

- Surefire downloads its test-framework provider only when the test phase actually runs, so an
  install that skips tests can never fetch it, and an offline run can never recover. Any gate that
  runs Maven offline must first call `gates/prime-offline-repo.sh`, which runs surefire online once.
  Fetching the provider by name does not work — surefire resolves it rooted at the provider, so the
  junit-platform version its own POM declares wins, not this project's.
- `-Dmaven.test.skip=true` skips test *compilation*, so a module that publishes a test-jar never
  attaches one. Use `-DskipTests` anywhere a test-jar is consumed downstream, as `configd-testkit`
  consumes `configd-consensus-core`'s.
- Anything built with `-o` must have had its dependencies resolved online first, and a module
  outside the gate's own `$MODULES` has had nothing resolved at all. A plugin counts: Maven fetches
  one only when a goal needs it, so a gate that never runs `clean` has never fetched the clean
  plugin, however long it has been passing.

A gate that breaks either rule still passes whenever some earlier build happened to leave the
artifact in `~/.m2`. It fails on a cold one — which CI hits on the first run after any `pom.xml`
change, because the Maven cache is keyed on the poms.

## Comments

A comment earns its place by carrying something the code cannot: a non-obvious invariant or ordering
requirement, why looks-wrong-but-deliberate code breaks if you "fix" it, a real gotcha with the bug or
platform named, why a constant has that value, or a public API contract a caller cannot infer.
Restatement, narration, and section banners are deleted rather than rewritten.

Two rules constrain that, and both were learned by getting them wrong:

**Does the identifier resolve?** A coded reference survives if it points at something present.
`W2-3`, `AU4-4` and `D3-5` resolve to `docs/rfc/driver-protocol/*.md`; `INV-SI-2` names an invariant
in `spec/SnapshotInstallSpec.tla`; `INV-ANCHOR-LOWER` names one in
`docs/architecture/frozen-format-v1.md`. Those citations are what makes conformance coverage
auditable, so they stay. Build and process tags that resolve to nothing are decoration and get
stripped. The same test settles intra-file cases: the `(a)`..`(d)` labels in `EdgeInvariants` are
referenced by markers inside `checkAll()` and on the method blocks that implement them, so they
resolve and they stay.

**A comment can be a build fixture.** `gates/gate-5.sh` greps `RaftConfig.java` for `default 1024`;
`gates/gate-phase1.sh` greps `ShardMap.java` for `Opaque, stable shard IDs`. Deleting either as
ceremony turns the gate red, and nothing in the Java file reveals the dependency. Before deleting a
comment that states a specific constant, default, or named invariant, check whether a gate, script, or
workflow greps for it:

```sh
grep -rn "<the phrase>" gates/ .github/ ops/
```

If you add such a dependency, say so in the comment itself, the way `RaftConfig.java` does.

A phrase becomes a fixture two ways: a script greps the source file itself, or it greps a run log for
something the source prints, which pins the string literal. `gates/list-source-fixtures.sh` derives
both sets by scanning what the gate, workflow and ops scripts consume, so the list below is generated
rather than remembered — run it after adding or removing a grep and fold the output back in. Hand
maintenance is what left this list short of `EDGE-GATE-SUMMARY` and `PROBE-HISTOGRAM: scope=global`,
which is exactly the gate-red the rule exists to prevent.

Greps against the source file:

| Consumer | File | Phrase |
| --- | --- | --- |
| `gates/gate-5.sh` | `raft/RaftConfig.java` | `maxPendingProposals.*default 1024` |
| `gates/gate-phase1.sh` | `replication/ShardMap.java` | `Opaque, stable shard IDs` |
| `gates/gate-phase1.sh` | `transport/FrameCodec.java` | `WIRE_VERSION = (byte) 0x02`, `HEADER_SIZE = 26` |
| `gates/gate-phase1.sh` | `transport/MessageType.java` | `RAFT_COALESCED_HEARTBEAT(0x11)` |
| `gates/gate-mswatch.sh` | `fanout/FanOutConnectionDriver.java` | `class FanOutConnectionDriver implements WatchMultiplexSink.Coordinator`, `Map<Integer, FanOutSessionCore> cores`, `Map<Integer, WatchMultiplexSink> sinks`, `allGids.length > 1 && !config.allowPartialShardView` |
| `gates/gate-mswatch.sh` | `server/AclConfigPolicyLoaderMultiShardTest.java` | `tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected` |
| `gates/gate-B.sh` | `docs/architecture/raft-threading-contract.md` | `owner thread` |
| `gates/gate-B.sh` | `gates/gate-phase0.sh` | `M3 cost-flat-in-N` |
| `gates/gate-3.sh` | `gates/contract-test-map.md`, `gates/gate3-map-expectation.txt` | `^CONTRACT-MAP-SUMMARY:` |
| `.github/workflows/ci.yml` | `transport/FrameCodec.java` | `public static final byte WIRE_VERSION` |

Greps against a run log, which pin a printed string literal:

| Consumer | Emitting source | Phrase |
| --- | --- | --- |
| `gates/gate-3.sh` | `EdgeAdversarialGateSeedSweepTest`, `AdversarialSimTest`, `EdgeIntegratedNightlySweepTest` | `safetyViolations=0` |
| `gates/gate-3.sh` | `EdgeAdversarialGateSeedSweepTest` | `EDGE-GATE-SUMMARY` |
| `gates/gate-3.sh` | `probe/PropagationProbe.java`, `ProbeMechanismTest` | `PROBE-HISTOGRAM: scope=global` |
| `gates/gate-1.sh` | `gates/smoke-multinode.sh` | `SMOKE PASS` |
| `gates/gate-1.sh` | `linz/CheckerSelfTest` | `Tests run: 8` exactly |
| `gates/gate-2.sh` | `configd-linz` checker output | `LINEARIZABLE`, `RESULT: PASS`/`INDETERMINATE` |
| `gates/e2e-compose-scenario.sh` | `fanout/FanOutSessionCore.java` | field `pendingDemotionNotice` (read via `javap`, so the field name is the fixture) |

### Bulk comment sweeps

Deleting comments across many files is a mechanical edit, so it needs mechanical proof: compare the
two revisions as **token sequences with comments dropped**, not as bytes. Byte comparison needs
whitespace normalisation first, and that normalisation can hide a lost token. Check XML and YAML
parse separately — byte-identity does not imply well-formedness, since a comment containing `--` is
invalid XML — and treat `#` inside a heredoc as data, not a comment.

That standard exists because a sweep tool here did delete code. Its block grouping merged an inline
trailing comment with the whole-line comment that followed it, so removing the pair took the code line
between them; it destroyed `HASH_LEN` and two `RootKey` fields before token comparison caught it. The
damage was a tool defect, not bad judgment by whoever selected the comments — the selections were
correct and the applier mis-executed them. Suspect the applier first.

## ADR process

Any decision that changes a wire/on-disk/persisted format, a consensus or replication invariant, a
public API or operator-visible behavior, or a build-system or runtime-version pin requires an
Architectural Decision Record under `docs/adr/adr-NNNN-<short-name>.md`. Numbering is sequential; pick
the next number after the highest existing ADR. Follow the structure of a well-formed existing ADR
(`adr-0022-java-25-runtime.md` is a clean example):

1. **Status** — Proposed / Accepted / Superseded (and what supersedes it).
2. **Context** — the problem and the constraints.
3. **Decision** — the actual choice, in one short paragraph.
4. **Influenced by** — specific prior art, papers, or production incidents, with versions and dates.
5. **Reasoning** — why this choice over the alternatives.
6. **Rejected alternatives** — concrete alternatives considered and the one reason each was rejected.
7. **Consequences** — positive, negative, and risks with their mitigations.
8. **Reviewers** — names and sign-off.
9. **Verification** — how an outsider would test that the decision holds in the deployed system, and
   what would invalidate it.

Open the ADR and the matching code change in the same PR. An operator-visible decision also needs a
runbook update under `ops/runbooks/`.

## Runbook conformance

Every operational runbook in `ops/runbooks/` follows the skeleton in
`ops/runbooks/runbook-conformance-template.md`: Symptoms, Impact, Operator-Setup, Diagnosis,
Mitigation, Resolution, Rollback, Postmortem, Related, Do-not. If your change adds an alert, a probe,
or a recovery step, update the runbook to match. Drills (real or tabletop) write a result file under
`ops/dr-drills/results/` — see the template for the format.

## Performance and target honesty

The system's latency, throughput, and availability targets (see `docs/adr/adr-throughput-target.md` and
the architecture docs) are a commitment. Any change to a target number requires sign-off from a reviewer
who is not the author, and at least one reviewer from the on-call rotation
(`docs/adr/adr-0025-on-call-rotation-required.md`). The same rule covers the `configd_*_seconds`
histogram bucket schedules in `configd-observability/ConfigdMetrics.java` — the buckets are part of the
SLO surface.

Performance numbers in the docs must be backed by a real measurement run — attach the raw artifact to
the PR or pin it in a commit referenced from the doc (raw dumps are not kept in the working tree) — or
carry an explicit "modeled, not measured" note pointing at the model. Do
not promote a modeled number to "measured" without a benchmark or load-test artifact. Any
wire-format change requires a deprecation cycle of at least two releases; the CI `wire-compat` job
enforces that fixture changes come with a `FrameCodec.WIRE_VERSION` bump.

## Pull request checklist

- [ ] `./mvnw -T 1C verify` passes locally.
- [ ] If a public API, wire, or on-disk contract changed: an ADR is opened and a supported-version note
      is added.
- [ ] If an operator-visible behavior changed: the runbook is updated and still satisfies the
      conformance template.
- [ ] If a target number changed: an independent sign-off is recorded in the PR description.
- [ ] No metric is referenced from prose without being registered in `ConfigdMetrics`.
- [ ] Release notes are drafted at release time per `ops/runbooks/release.md` (no standing changelog
      file is maintained between releases).

## Reporting

For bugs that affect data integrity, consensus safety, or authentication, report privately first via
GitHub's private vulnerability reporting — see [SECURITY.md](SECURITY.md) — before opening a public
issue.
