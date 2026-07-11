# Contributing to Configd

The bar for correctness and operator-honesty is high. Read this in full before opening your first PR.

## Prerequisites

- **JDK 25 (Amazon Corretto recommended).** The reactor pins `maven.compiler.release=25` and runs with
  `--enable-preview`. See ADR-0022 for the runtime decision (supersedes ADR-0009).
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

Performance numbers in the docs must be backed by an artifact under `perf/results/` or a measurement run
under `docs/measurement/`, or carry an explicit "modeled, not measured" note pointing at the model. Do
not promote a modeled number to "measured" without committing the benchmark or load-test artifact. Any
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

For bugs that affect data integrity, consensus safety, or authentication, report privately first: see
the security contact in `SECURITY.md` if present, otherwise email the maintainers before opening a
public issue.
