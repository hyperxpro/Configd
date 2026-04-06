# Contributing to Configd

Thanks for considering a contribution. Configd is pre-GA; the bar for
correctness and operator-honesty is high. Read this in full before
opening your first PR.

## Prerequisites

- **JDK 25 (Amazon Corretto recommended).** The reactor pins
  `maven.compiler.release=25` and runs with `--enable-preview`. See
  ADR-0022 for the runtime decision (supersedes ADR-0009).
- **Maven wrapper:** use the bundled `./mvnw` — do not install Maven
  globally. The wrapper's `distributionSha256Sum` (in
  `.mvn/wrapper/maven-wrapper.properties`) is the supply-chain pin
  (B4 / PA-6006). Never override it.
- A clean working tree and a topic branch off `main`.

## Build / test rules

The single commit-gate command is:

```sh
./mvnw -T 1C verify
```

This runs the full reactor build, unit + property tests, deterministic
simulation tests, the wire-compat golden-bytes guard, and the supply-chain
checks. CI re-runs the same command on push and pull-request
(`.github/workflows/ci.yml`).

**Every commit on a PR must pass `./mvnw -T 1C verify`.** Do not push
a "WIP" commit that breaks the build, even if a later commit fixes it.
Bisects across a broken commit are how regressions hide.

Targeted invocations during development:

```sh
./mvnw -pl configd-consensus-core test                # one module
./mvnw -pl configd-consensus-core test -Dtest=RaftNodeTest
./mvnw test -pl configd-testkit -Dtest='*Simulation*'  # sim suite only
```

## ADR process

Any decision that changes:

- A wire format, on-disk format, or persisted contract,
- A consensus / replication invariant,
- A public API or operator-visible behavior,
- A build-system or runtime-version pin,

requires an Architectural Decision Record under
`docs/decisions/adr-NNNN-<short-name>.md`. The numbering is sequential;
look at the highest existing ADR (e.g. `adr-0028-...`) and pick the
next number.

If a `docs/decisions/adr-NNNN-template.md` exists, copy it. Otherwise
follow the structure of an existing well-formed ADR
(`adr-0022-java-25-runtime.md` is a clean example):

1. **Status** — Proposed / Accepted / Superseded (and what supersedes).
2. **Context** — what problem, what constraints.
3. **Decision** — the actual choice, in one short paragraph.
4. **Influenced by** — cite specific prior art / papers / production
   incidents (with version numbers / dates).
5. **Reasoning** — *why* this choice over alternatives.
6. **Rejected Alternatives** — concrete alternatives considered and the
   single reason each was rejected.
7. **Consequences** — Positive / Negative / Risks-and-mitigations.
8. **Reviewers** — names + sign-off.
9. **Verification** — how an outsider would test that the decision
   actually holds in the deployed system, and what would invalidate it.

Open the ADR and the matching code change in the **same PR**.
Operator-visible decisions also require a runbook update under
`ops/runbooks/`.

## Runbook conformance

Every operational runbook in `ops/runbooks/` is bound by
`ops/runbooks/runbook-conformance-template.md`. If your change adds an
alert, a probe, or a recovery step, the runbook update must follow the
9-section skeleton: Symptoms / Impact / Operator-Setup / Diagnosis /
Mitigation / Resolution / Rollback / Postmortem / Related / Do not.

Drills (real or tabletop) write a result file under
`ops/dr-drills/results/` — see the template for the exact format.

## §0.1 target sign-off

`PROMPT.md §0.1` enumerates the system targets (latency, availability,
throughput) the project commits to. **Any change to a number in §0.1
requires a sign-off from a reviewer who is not the author of the
change.** Open the PR with `[§0.1]` in the title and tag at least one
reviewer from the on-call rotation defined in
`docs/decisions/adr-0025-on-call-rotation-required.md`.

The same rule applies to changes to the `configd_*_seconds` histogram
bucket schedules in `configd-observability/ConfigdMetrics.java` — the
buckets are part of the SLO surface.

## Honesty rules (loop directive §8)

- **§8.1:** Performance numbers in `docs/performance.md` and
  `docs/architecture.md` must be backed by an artefact under
  `perf/results/` or carry a `MODELED, NOT MEASURED` banner with a
  pointer to the model. Do not promote a modeled number to "measured"
  without committing the JMH / load-test artefact.
- **§8.10:** Any wire-format change requires a deprecation cycle of
  ≥ 2 releases. The CI `wire-compat` job enforces that fixture changes
  come with a `FrameCodec.WIRE_VERSION` bump.
- **§8.14 / §8.15:** Every runbook follows the 9-section skeleton;
  every ADR has a Verification section.

## Pull request checklist

- [ ] `./mvnw -T 1C verify` passes locally.
- [ ] CHANGELOG.md updated under the next-release heading.
- [ ] If a public API / wire / on-disk contract changed: ADR opened,
      `docs/handoff.md` updated, supported-version note added.
- [ ] If an operator-visible behavior changed: runbook updated,
      conformance template still satisfied.
- [ ] If a §0.1 target changed: independent sign-off recorded in the PR
      description.
- [ ] No new metric is referenced from prose without being registered in
      `ConfigdMetrics` (per DOC-033 / Tier-1-METRIC-DRIFT).

## Reporting

For bugs that affect data integrity, consensus safety, or
authentication, file a security-sensitive issue (see the project's
security contact in `SECURITY.md` if present; otherwise email the
maintainers privately before opening a public issue).
