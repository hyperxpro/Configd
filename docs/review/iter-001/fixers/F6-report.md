# F6 — Tier-1-RELEASE — fix report

**Date:** 2026-04-19
**Scope:** S0 R-002 + R-001, R-003, R-005, R-007, R-008 (§8.10 wire-compat hard rule).

## Summary of changes

### Task 1 — Rollback section in `ops/runbooks/release.md` (closes R-002 / S0)
Added a 7-step `## Rollback` section between `## Deploying the verified
image` and `## Do not`:

- Step 0: identify previous good digest (do not read from floating tag)
- Step 1: re-verify previous image with cosign + gh attestation (same
  protocol as forward release)
- Step 2: revert manifest commit
- Step 3: drain ONE canary node first via cordon + StatefulSet patch,
  using the digest pin form (`@${PREV_DIGEST}`) — references the
  `image: configd:GIT_SHA` placeholder visible in
  `deploy/kubernetes/configd-statefulset.yaml`
- Step 4: watch `configd_snapshot_install_failed_total` via Prometheus
  port-forward; pause rollout if non-zero
- Step 5: roll remaining replicas only after canary clean for 10 min
- Step 6: 4-criterion success declaration:
  1. all 3 pods READY 1/1
  2. commit-index parity across all replicas via
     `/admin/raft/status`
  3. zero error rate on `configd_write_failure_total` /
     `configd_snapshot_install_failed_total`,
     `configd_slo_burn_rate_1h < 1.0`
  4. image actually rolled (per `kubectl get pod ... -o jsonpath`)
- Step 7: cut a forward fix release (no tag amend per §8.10 / existing
  `## Do not` block)

Also extended `## Do not` with three new bullets covering rollback
hazards.

### Task 2 — `RELEASE_NOTES_TEMPLATE.md`
File already existed (untracked). Added the four required sections:

- `## Wire-Compatibility` — five-item checklist including the literal
  required line `- [ ] Wire-version byte unchanged OR documented with
  golden-bytes test diff`, plus links to the §8.10 fixture path.
- `## Snapshot/WAL Compatibility` — three-item checklist referencing the
  new stub tests (Task 5).
- `## Rollback Procedure` — short pointer to
  `ops/runbooks/release.md ## Rollback` plus the rule that the previous
  digest must be re-verified before applying.
- `## Verified-On Environments` — six-row table for K8s, JDK,
  containerd, cosign, gh CLI, and the reference workload.

### Task 3 — Wire-version byte in FrameCodec (closes R-005 / R-007 / R-008)
Header layout went from `[Length:4][Type:1][GroupId:4][Term:8] = 17B`
to `[Length:4][WireVersion:1][Type:1][GroupId:4][Term:8] = 18B` with a
trailing 4-byte CRC32C (CRC trailer was being added in parallel by F2
for C-001; I integrated it consistently across `byte[] encode`,
`ByteBuffer encode`, `decode`, `frameSize`, `TcpRaftTransport`).

Added:

- Constant `public static final byte WIRE_VERSION = (byte) 0x01;` with
  the required `// Wire-format change requires a deprecation cycle of
  >= 2 releases per §8.10.` comment immediately above.
- `public static final int WIRE_VERSION_OFFSET = 4;` and
  `public static byte peekWireVersion(byte[] data)` for streaming
  decoders.
- `UnsupportedWireVersionException extends IllegalArgumentException`
  carrying `observedVersion()`.
- `FrameCodec.decode` rejects mismatched wire-version with
  `UnsupportedWireVersionException` *before* type lookup or payload
  allocation.
- `TcpRaftTransport.handleInboundConnection` catches
  `UnsupportedWireVersionException` (and `FrameChecksumException`
  added by F2), emits a structured single-line JSON log including
  `peer_id`, and returns to close the connection (per §8.10
  requirement that the peer is dropped, not silently downgraded).
- `FrameCodecPropertyTest.unknownWireVersionIsRejected` — new jqwik
  property test exercising 200 random non-1 version bytes; asserts
  exception type and `observedVersion()` value. The pre-existing
  `unknownTypeCodeIsRejected` test was updated to compute a valid CRC
  trailer so it rejects on type, not on checksum (drive-by fix
  consistent with F2's CRC integration).

Verified: `mvn -pl configd-transport test
-Dtest=FrameCodecTest,FrameCodecPropertyTest,WireCompatGoldenBytesTest`
→ 38 tests, 0 failures.

### Task 4 — Wire-compat CI job + golden-bytes fixtures
New job `wire-compat` in `.github/workflows/ci.yml` runs after the
existing build-and-test, tlc-model-check, supply-chain-scan jobs:

1. Compiles + runs `WireCompatGoldenBytesTest` (which encodes one of
   every `MessageType` and asserts byte-equality against the v1
   fixtures).
2. On `pull_request` events, diffs
   `configd-transport/src/test/resources/wire-fixtures/**` against
   the PR base. If any fixture file changed AND the same diff does
   *not* contain a `WIRE_VERSION = (byte) 0x...` change in
   `FrameCodec.java`, the job fails with the §8.10 rationale message.
3. On failure uploads the fixture directory as `wire-fixtures-debug`
   for inspection.

Fixture set committed: 16 files under
`configd-transport/src/test/resources/wire-fixtures/v1/` (one per
`MessageType` enum value, lowercase enum name + `.bin`):

- `append_entries.bin` (26B) … `timeout_now.bin` (26B)
- `heartbeat.bin` (22B — empty payload)

Generator: `WireFixtureGenerator.main()` with `--update-fixtures
<dir>` mode for maintainers regenerating after a legitimate bump.
Stable canonical inputs (group id `0x01020304`, term
`0x0A0B0C0D0E0F1011`, payload `0xDEADBEEF`) so the same map is built
on every invocation.

### Task 5 — Snapshot/WAL backwards-compat stubs (R-005)
Two new files:

- `configd-consensus-core/src/test/java/io/configd/raft/SnapshotWireCompatStubTest.java`
- `configd-consensus-core/src/test/java/io/configd/raft/WalWireCompatStubTest.java`

Both are JUnit5 tests annotated
`@Disabled("Stub: enable on first version bump (R-005). No v0
{snapshot,WAL} fixture exists yet.")` with a TODO referencing R-005.
Both attempt to load a v0 fixture (classpath-first, working-tree
fallback) and would `fail()` honestly if enabled today — they do NOT
assert success. Maintainer instructions for enabling are in the class
javadoc.

Compilation verified in isolation against the consensus-core test
classpath. (Note: a separate pre-existing test compile failure in
`InstallSnapshotTest` from F2's `InstallSnapshotResponse` 4-arg
constructor change blocks the full module test compile — that is in
F2's scope, not mine.)

### Task 6 — `verify-published` job in `.github/workflows/release.yml` (R-001)
New job `verify-published` runs `needs: build-and-publish`. It:

1. Pulls the published image by digest (`docker pull
   ghcr.io/<repo>@<digest>`).
2. `cosign verify` against the workflow identity regex and OIDC
   issuer (same command consumers run).
3. `cosign verify-attestation --type slsaprovenance` cross-check.
4. `gh attestation verify oci://<image>@<digest>` independent
   check.
5. Writes a structured evidence block to
   `${GITHUB_STEP_SUMMARY}` so the workflow run page itself becomes
   the audit artifact for Phase 9 / B6 / PA-6012 re-promotion.

The job fails the workflow if any verify step fails. Per the task
constraints, this verify is not pretended to have run successfully —
it can only produce evidence when a real `vX.Y.Z` tag is pushed and
the publish chain executes end-to-end. The Phase 9 / B6 / PA-6012
demotions stay YELLOW until that evidence exists.

## Files touched

Modified:

- `/home/ubuntu/Programming/Configd/ops/runbooks/release.md`
- `/home/ubuntu/Programming/Configd/RELEASE_NOTES_TEMPLATE.md`
- `/home/ubuntu/Programming/Configd/configd-transport/src/main/java/io/configd/transport/FrameCodec.java`
  (wire-version + integrated F2's CRC32C trailer consistently across
  byte[] encode, ByteBuffer encode, decode, frameSize, error types)
- `/home/ubuntu/Programming/Configd/configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java`
  (catch + structured-log UnsupportedWireVersionException and
  FrameChecksumException; bumped minimum frame size)
- `/home/ubuntu/Programming/Configd/configd-transport/src/test/java/io/configd/transport/FrameCodecPropertyTest.java`
  (new `unknownWireVersionIsRejected` property test; updated
  `unknownTypeCodeIsRejected` for CRC trailer)
- `/home/ubuntu/Programming/Configd/.github/workflows/ci.yml`
  (new `wire-compat` job)
- `/home/ubuntu/Programming/Configd/.github/workflows/release.yml`
  (new `verify-published` job)

Created:

- `/home/ubuntu/Programming/Configd/configd-transport/src/test/java/io/configd/transport/wirecompat/WireFixtureGenerator.java`
- `/home/ubuntu/Programming/Configd/configd-transport/src/test/java/io/configd/transport/wirecompat/WireCompatGoldenBytesTest.java`
- `/home/ubuntu/Programming/Configd/configd-transport/src/test/resources/wire-fixtures/v1/*.bin` (16 fixtures)
- `/home/ubuntu/Programming/Configd/configd-consensus-core/src/test/java/io/configd/raft/SnapshotWireCompatStubTest.java`
- `/home/ubuntu/Programming/Configd/configd-consensus-core/src/test/java/io/configd/raft/WalWireCompatStubTest.java`

## Fixture set committed

Yes — full v1 fixture set (16 files) generated from
`WireFixtureGenerator` and verified by `WireCompatGoldenBytesTest`
(38 transport tests pass under JDK 25).

No snapshot/WAL fixtures committed — the stubs are honest
`@Disabled` placeholders awaiting the first version bump (per task
constraint: "Do NOT pretend they pass").

## CI job names added

- `wire-compat` (in `.github/workflows/ci.yml`) — gates every PR /
  push to `main` on §8.10 fixture parity + version-bump rule.
- `verify-published` (in `.github/workflows/release.yml`) — runs
  `needs: build-and-publish` on every `v*.*.*` tag push and gates
  release success on third-party-replayable cosign +
  cosign-verify-attestation + `gh attestation verify` of the pushed
  digest.

## Known follow-ups for iter-2+

- `verify-published` cannot be confirmed working until a real release
  tag is pushed; until then Phase 9 / B6 / PA-6012 stay YELLOW per
  honesty-auditor demotion list.
- Snapshot/WAL stubs need real v0 fixtures (and the matching
  `from-bytes` loader on `DurableRaftState` / `RaftLog`) before the
  first wire bump.
- Existing `InstallSnapshotTest` compile failure is in F2's scope
  (4-arg constructor change for D-002 echo); my new files compile
  cleanly in isolation.
