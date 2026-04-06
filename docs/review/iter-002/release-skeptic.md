# release-skeptic — iter-002

**Date:** 2026-04-19
**HEAD audited:** working tree at `22d2bf30` plus iter-1 modifications.
**Severity floor:** S3.
**Reviewer lens:** release engineering — version-skew, rollback honesty,
canary infrastructure, image-signing chain-of-trust, schema/migration
safety, snapshot-format extensibility.

iter-1 closed the obvious pipeline gaps (rollback section in
`ops/runbooks/release.md`, `verify-published` job, `RELEASE_NOTES_TEMPLATE.md`,
golden-bytes wire-compat, `WIRE_VERSION` byte). What follows is what
remains — most of it harder, all of it would still bite a real GA.

**Findings: 13** (S0: 2, S1: 5, S2: 4, S3: 2)

---

## R-001 — `FrameCodec` does hard-equality wire-version check; mixed-version cluster cannot exist

- **Severity:** S0
- **Location:**
  `configd-transport/src/main/java/io/configd/transport/FrameCodec.java:222-227`,
  `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:243-256`
- **Category:** wire-compat / upgrade-skew
- **Evidence:**
  ```java
  byte version = buf.get();
  if (version != WIRE_VERSION) {
      throw new UnsupportedWireVersionException(version);
  }
  ```
  And the transport closes the connection on `UnsupportedWireVersionException`
  (TcpRaftTransport.java:245-256). There is no negotiation, no
  `accepted-versions` set, no `min/max` window, no
  HelloFrame/handshake. A node with `WIRE_VERSION = 0x02` is *unable
  to join a quorum* containing a node with `WIRE_VERSION = 0x01` —
  every Raft message is rejected at decode and the connection is
  reset.
- **Release-time impact:** §8.10 mandates a 2-release deprecation
  cycle, but the implementation makes a deprecation cycle physically
  impossible. The first wire bump (`0x01 → 0x02`) becomes a hard
  cluster split: rolling-update the StatefulSet pod-by-pod and the
  new pod cannot replicate to the old quorum, the old quorum cannot
  hear heartbeats from the new pod, leadership flaps, the cluster
  loses quorum mid-rollout. The `RELEASE_NOTES_TEMPLATE.md` checkbox
  *"Wire-version byte unchanged OR documented with golden-bytes test
  diff"* is not enough — the runbook needs *"new version is
  decode-accepted by N-1 for at least one release"*, and the code
  needs to match.
- **Fix proposal:** Replace hard-equality with a `SUPPORTED_VERSIONS =
  Set.of((byte) 0x01, (byte) 0x02)` decoder accept-list whose lifetime
  is exactly the §8.10 deprecation window. Add a connection-time
  `Hello` frame so the peer's *highest* supported version is known
  before any AppendEntries is sent (otherwise the encoder still picks
  unilaterally). Document the supported-set in `CHANGELOG.md` and add
  a per-version expiry release tag.
- **Owner:** `configd-transport`, ADR-XXXX (new — wire-version
  negotiation), `CHANGELOG.md`, `RELEASE_NOTES_TEMPLATE.md`
- **Re-flag of:** R-007 (iter-1) — the byte was reserved but the
  decoder semantics defeat its purpose.

## R-002 — D-004 snapshot trailer is non-extensible; second trailer field will silently corrupt

- **Severity:** S0
- **Location:**
  `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:344-368`
  (encoder) and `:454-465` (decoder).
- **Category:** snapshot-format extensibility
- **Evidence:**
  ```java
  // encoder
  buf.putLong(signingEpoch);   // raw 8 bytes appended after entries

  // decoder
  if (buf.remaining() >= Long.BYTES) {
      long restoredEpoch = buf.getLong();
      ...
  }
  ```
  The decoder uses `remaining() >= 8` as the "trailer-present"
  signal. The next time anyone needs to add a second trailer field
  (e.g., a key-rotation-id, a TLS-cert serial, an admission-policy
  hash — pick any one, all of them are coming), they will append
  another `putLong()` and ship it. The N-1 decoder will then
  *silently* read the rotation-id as the signingEpoch (because
  `remaining() >= 8` is still true), and the rotation-id might be
  zero, or might be a giant epoch that prevents subsequent signing
  forever ("epoch went backwards" rejected). There is no
  trailer-magic, no trailer-length-prefix, no
  `trailer-version`-discriminator, no TLV.
- **Release-time impact:** Any v0.2 snapshot installed onto a v0.1
  follower (perfectly legal during a rolling rollback) corrupts the
  follower's signing state. The snapshot-install metric will not
  fire — the read succeeds, the values are just wrong. This is the
  worst class of release bug: silent state corruption across the
  rollback boundary.
- **Fix proposal:** Re-frame the trailer as length-prefixed TLV
  before any release ships. Concretely:
  `[entries...][TRAILER_MAGIC: 4B 0xC0 0xFD 0x7A 0x11][trailer_len: 4B][trailer_payload: N bytes][trailer_version: 1B]`,
  with trailer_payload being a self-describing TLV. The decoder
  reads MAGIC; if absent, treat as "legacy without trailer" exactly
  as today. If present, it has a length, and unknown TLV codes are
  forward-skipped. Add `LegacySnapshotMissingTrailerTest` and
  `UnknownTrailerCodeIsForwardCompatTest`.
- **Owner:** `configd-config-store`, snapshot-format ADR (new)

## R-003 — R-004 (canary + SLO-burn auto-abort) was deferred from iter-1 and is still missing

- **Severity:** S1
- **Location:** `.github/workflows/release.yml` end-of-file (no
  `canary` job), `docs/battle-ready/release-rehearsal.md:51-58`
  (still-static plan with manual `kubectl rollout undo`).
- **Category:** canary-abort
- **Evidence:** `release.yml` ends at the `verify-published` job
  (line 228). There is no job that deploys the just-published image
  to a canary environment, runs the smoke suite, queries the SLO
  burn-rate, and aborts. The release-rehearsal doc still claims
  *"The deployment automation supports progressive rollout via the
  K8s StatefulSet"* but the StatefulSet has `OrderedReady` and
  `replicas: 3` only — no canary partition, no `partition:` field,
  no per-replica image override, no automated SLO check.
- **Release-time impact:** A signed-and-attested image lands in
  GHCR with full provenance even when its first 60 seconds in a
  staging cluster already burn the 5m error budget. The release is
  "complete" from the pipeline's POV; humans have to notice it's
  bad. iter-1 documented this as deferred; iter-2 must either close
  it or commit (in writing) to deferring through GA.
- **Fix proposal:** Two-step:
  1. Add a `canary` job to `release.yml` gated on a `staging`
     environment, that runs
     `ops/scripts/canary-rollout.sh ${IMAGE}@${DIGEST}` (script to
     be added — should set `partition: 2` on a 3-replica STS, wait
     for ready, scrape `configd_slo_burn_rate_5m` from
     `ops/alerts/configd-slo-alerts.yaml`, exit non-zero if > 1.0).
  2. Add a `:yanked-${SHA}` post-tag step that runs only on canary
     failure and pushes a marker tag plus a cosign attestation of
     `predicate.type = "https://configd.io/release-yanked/v1"`.
- **Owner:** `.github/workflows/release.yml`, `ops/scripts/canary-rollout.sh`,
  `deploy/kubernetes/`

## R-004 — release.yml does not scan the runtime image; CI Trivy scans only the filesystem

- **Severity:** S1
- **Location:** `.github/workflows/release.yml:81-94` (build-push
  step) — no `trivy image` step before publish. Compare with
  `.github/workflows/ci.yml:108-117` which only does
  `scan-type: fs`.
- **Category:** image-vuln-gate
- **Evidence:** The runtime image base is `eclipse-temurin:25-jre-noble`
  — pinned by digest, but every CVE in the noble JRE base layer is
  shipped invisibly. CI's `trivy fs` scans the working tree
  (Maven jars + source), not the assembled OCI manifest. The release
  workflow ships an image that has never been scanned for
  CVEs-in-the-base-OS at HIGH/CRITICAL.
- **Release-time impact:** A v0.1.0 image ships with a known
  Spring4Shell-class CVE in the base layer because nothing checked.
  The cosign signature is honestly applied to a vulnerable image —
  signing does not equal "safe to deploy".
- **Fix proposal:** Add a `trivy image` step in `release.yml`
  *between* `Build and push runtime image` and `Sign image`. Fail
  on HIGH/CRITICAL with the same `ignore-unfixed: true` policy as
  CI. The `Sign image` step must not run if Trivy fails — making
  the unsigned image a sufficient signal that the release was
  rejected at scan.
- **Owner:** `.github/workflows/release.yml`

## R-005 — wire-compat CI fixture-bump enforcement compares fixtures-changed against `WIRE_VERSION` in same PR — but allows fixture *deletion* without bump

- **Severity:** S1
- **Location:** `.github/workflows/ci.yml:160-190` (the
  `Enforce wire-version bump on fixture drift` step).
- **Category:** wire-compat / golden-bytes
- **Evidence:**
  ```
  fixtures_changed=$(git diff --name-only "${BASE}...HEAD" \
      -- 'configd-transport/src/test/resources/wire-fixtures/**' || true)
  ```
  This catches additions and modifications. It does **not** catch
  the case where a developer deletes the entire `v1/` directory and
  adds a `v2/` directory simultaneously *without* bumping
  `WIRE_VERSION` — because the grep against `WIRE_VERSION_OFFSET`
  uses `(byte)\\s*0x` which any cosmetic javadoc edit that mentions
  `(byte) 0x` will satisfy. Worse, deleting `v0/` (when it exists)
  to drop legacy support is *exactly* the §8.10-violating action
  this guard is supposed to catch, and it doesn't.
- **Release-time impact:** False-positive PASS on a removal of an
  N-1 fixture set — the developer can drop wire-format support for
  the previous release in one PR and never trip the §8.10
  deprecation gate.
- **Fix proposal:** Extend the guard to:
  (a) Detect deletions: `git diff --diff-filter=D --name-only`
      against `wire-fixtures/v*` must accompany a deletion ADR linked
      from the PR body and a CHANGELOG `### Removed` entry naming
      the previous wire-version.
  (b) Tighten the WIRE_VERSION grep to require an actual numeric
      change: parse the old vs new value and assert
      `new == old + 1`.
  (c) Forbid removing `wire-fixtures/v<N-1>/` until at least two
      release tags later than the bump that introduced `v<N>`.
- **Owner:** `.github/workflows/ci.yml`

## R-006 — no end-to-end upgrade test (N-1 cluster → N rolling restart with live traffic)

- **Severity:** S1
- **Location:** repo-wide; no test class matches `*UpgradeIT*`,
  `*RollingRestart*`, `*MixedVersion*`. The wire-compat goldens
  prove byte-equality at the codec layer, but never exercise an
  actual two-version cluster. `SnapshotWireCompatStubTest` /
  `WalWireCompatStubTest` are still `@Disabled` (rightly so — no
  v0 fixture exists yet).
- **Category:** upgrade-skew
- **Evidence:** Grepping the test tree returns only round-trip
  tests at HEAD. The simulated network in
  `configd-testkit/SimulatedNetwork.java` could host a mixed-version
  cluster but no test does so. No CI job pulls the previous
  released image and runs a smoke against it.
- **Release-time impact:** The first wire-bump (or the first
  AppendEntries field add) ships under "all green CI" but pipelines
  in production fail at the StatefulSet's middle replica. R-001
  identifies the structural blocker; R-006 identifies the missing
  *test* that would have caught R-001 itself.
- **Fix proposal:** Add an integration suite under
  `configd-testkit/src/test/java/io/configd/testkit/upgrade/` that:
  (a) Spins a 3-node cluster on the prior release artifact
      (downloaded by GAV from GHCR or by `mvn dependency:get` of the
      prior tag), (b) issues 100 PUTs, (c) rolling-restarts one node
      to HEAD, (d) issues 100 more PUTs, (e) asserts every node
      catches up to the same commit-index, (f) rolls the second
      node, (g) repeats. Wire it as a CI job named `upgrade-skew`.
- **Owner:** `configd-testkit`, `.github/workflows/ci.yml`

## R-007 — no schema-migration framework; D-004 trailer was added without a Migration SPI

- **Severity:** S1
- **Location:** repo-wide — no class named `*Migration*` exists.
  D-004 was applied directly inside
  `ConfigStateMachine.snapshot/restoreSnapshot` with an ad-hoc
  "if remaining bytes" probe. R-006 of iter-1 raised this; iter-1
  did not address it.
- **Category:** migration-undo
- **Evidence:** `ls
  configd-config-store/src/main/java/io/configd/store/ | grep -i
  migration` → empty. The snapshot trailer fix is in-line; there's
  no record of "this format went from version N to version N+1",
  no `applyMigration(from, to)` API, no inverse `undoMigration`.
- **Release-time impact:** Each future format bump replays the
  D-004 ad-hoc pattern; each one accretes special-case code in
  `restoreSnapshotInternal`; neither the developer nor the operator
  has a record of which formats are loadable without migration.
  Rollback from v0.3 → v0.1 (skipping v0.2's migration) is
  undefined.
- **Fix proposal:** Add
  `configd-config-store/src/main/java/io/configd/store/migrations/Migration.java`
  with `int sourceVersion()`, `int targetVersion()`,
  `byte[] applyForward(byte[])`, `byte[] applyReverse(byte[])`,
  plus a `MigrationRegistry` discovered via `ServiceLoader`. Wire
  into `ConfigStateMachine.restoreSnapshot` as the *only* upgrade
  path — the ad-hoc D-004 logic gets restated as `Migration v1→v2`.
  Add `*UndoMigrationTest` per migration.
- **Owner:** `configd-config-store`, `configd-consensus-core`
- **Re-flag of:** R-006 (iter-1) — explicitly deferred but the
  D-004 fix made the absence more acute.

## R-008 — runtime image entrypoint and K8s StatefulSet command disagree; manifest as-shipped will fail to start

- **Severity:** S1
- **Location:** `docker/Dockerfile.runtime:62-75` vs
  `deploy/kubernetes/configd-statefulset.yaml:79-104`.
- **Category:** release-deploy-skew
- **Evidence:** Dockerfile sets:
  ```
  WORKDIR /app
  COPY --from=builder /staging/libs /app/libs
  ENTRYPOINT ["java", ..., "-cp", "libs/*", "io.configd.server.ConfigdServer"]
  ```
  i.e., the image has `/app/libs/*.jar` and is class-path launched.
  The K8s manifest *overrides* this with:
  ```
  command: ["java", ..., "-jar", "configd-server.jar", "--node-id", ...]
  ```
  There is no `/app/configd-server.jar` and no symlink — the COPY
  step writes to `/app/libs/`. `java -jar configd-server.jar` from
  `WORKDIR /app` will exit with `Unable to access jarfile`. The
  image as-shipped boots; the manifest as-shipped does not.
- **Release-time impact:** First operator who follows the runbook
  literally (`kubectl apply -f deploy/kubernetes/configd-statefulset.yaml`)
  gets a `CrashLoopBackOff`. The Dockerfile passes its tests
  because nobody runs the runtime image with the manifest's
  `command`; the manifest passes lint because nobody starts a real
  pod from it. The release-rehearsal doc claims "Docker build
  CONFIGURED" without ever booting the manifest against the image.
- **Fix proposal:** Pick one. Either (a) ship a fat jar at
  `/app/configd-server.jar` (use the existing shade plugin output)
  and align the Dockerfile ENTRYPOINT to it, OR (b) drop the
  `command:` override from the StatefulSet and rely on the image's
  ENTRYPOINT, passing args via `args:` only. Add a CI job that
  starts a `kind` cluster, applies the manifest, and asserts the
  pod becomes Ready within 60s. This is the test that catches it.
- **Owner:** `docker/Dockerfile.runtime`, `deploy/kubernetes/configd-statefulset.yaml`

## R-009 — `verify-published` checks SLSA via `cosign verify-attestation --type slsaprovenance`, but `actions/attest-build-provenance@v2` writes the predicate-type as `https://slsa.dev/provenance/v1`

- **Severity:** S2
- **Location:** `.github/workflows/release.yml:114-119` (signing
  step) vs `:198-207` (verify step).
- **Category:** verification chain-of-trust
- **Evidence:** The signing step uses
  `actions/attest-build-provenance@v2` whose attestation predicate
  is the SLSA v1 in-toto type
  `https://slsa.dev/provenance/v1`. The verify step uses
  `cosign verify-attestation --type slsaprovenance`. Cosign's
  `--type slsaprovenance` shorthand resolves to
  `https://slsa.dev/provenance/v0.2`. The version mismatch means
  cosign will not find a matching attestation and the verification
  step will fail — *or worse*, succeed against a different,
  unrelated attestation if one happens to be present.
- **Release-time impact:** Either the `verify-published` job
  always fails (visible — manageable), or it succeeds for the wrong
  reason (invisible — release ships with no real SLSA verification
  even though the green check claims otherwise). The
  `gh attestation verify` cross-check on line 209-216 will catch
  the absence of a v1 attestation, but operators copying the
  `cosign verify-attestation` command from the runbook will hit
  the same trap.
- **Fix proposal:** Use `--type slsaprovenance1` or pass the
  literal `--type=https://slsa.dev/provenance/v1` to
  `cosign verify-attestation`. Update the runbook example
  (`ops/runbooks/release.md:113-115` — it currently uses
  `cosign download attestation` which has the same predicate-type
  pitfall).
- **Owner:** `.github/workflows/release.yml`, `ops/runbooks/release.md`

## R-010 — no `ops/runbooks/upgrade.md`; `RELEASE_NOTES_TEMPLATE.md` references a runbook that does not exist

- **Severity:** S2
- **Location:** `RELEASE_NOTES_TEMPLATE.md:50-51` references
  `ops/runbooks/upgrade.md`. `find ops docs -name "upgrade*"`
  returns nothing.
- **Category:** docs-drift
- **Evidence:**
  ```
  - [ ] If either format changed: migration tool committed under
        `ops/scripts/migrate-<from>-to-<to>.sh` and referenced in
        `ops/runbooks/upgrade.md`
  ```
  Grepping the repo: no `upgrade.md` file in `ops/runbooks/` or
  `docs/runbooks/`. `docs/runbooks/version-gap.md` exists but
  describes Raft replication-lag remediation, not version upgrades.
- **Release-time impact:** The release-notes template tells the
  release manager to link a runbook that does not exist. The
  unchecked checkbox blocks the release; the checked-but-broken
  checkbox ships a release whose upgrade procedure is "see
  ops/runbooks/upgrade.md → 404". Either way, an actual format
  bump has no operator playbook.
- **Fix proposal:** Author `ops/runbooks/upgrade.md` to the §8.14
  8-section skeleton with sections for: pre-flight version-skew
  check, snapshot/WAL forward-compat check, rolling restart order
  (highest-ordinal first to mirror the rollback section), failure
  modes (stuck install-snapshot, mismatched WIRE_VERSION), abort
  criteria. Reference from `RELEASE_NOTES_TEMPLATE.md`,
  `ops/runbooks/release.md`, and (if R-007 is done) the new
  `Migration` SPI Javadoc.
- **Owner:** `ops/runbooks/upgrade.md` (new), `RELEASE_NOTES_TEMPLATE.md`,
  `ops/runbooks/release.md`

## R-011 — no release-engineering ADR; `release.md` has a TODO admitting the gap

- **Severity:** S2
- **Location:** `ops/runbooks/release.md:138-143`:
  ```
  <!-- TODO: confirm correct ADR — there is no dedicated release-
  engineering ADR yet; ... A dedicated
  `adr-XXXX-release-engineering.md` should be authored when the release
  pipeline is exercised end-to-end (Phase 9 demotion in iter-1). -->
  ```
- **Category:** governance
- **Evidence:** The ADR sequence is 0001-0027. None of them name
  the release pipeline. The runbook itself flags the gap. iter-1's
  inventory mentions ADRs 21/22/25 cover *parts* of the contract
  (build, runtime, on-call) but no ADR captures: *which immutable
  registry, which signing identity, which attestation predicate
  type, which retention policy, which deprecation cadence, which
  pre-tag pre-flight, which post-tag verify*. Without that, the
  pipeline drift is invisible: any single change to `release.yml`
  is locally justified but nothing requires the system to remain
  coherent.
- **Release-time impact:** Future engineers will incrementally
  re-discover (or accidentally violate) the contract — e.g., by
  swapping `--certificate-identity-regexp` for a
  `--certificate-identity` literal, which is correct (see iter-1
  R-012) but invalidates every previously-published verification
  command unless documented.
- **Fix proposal:** Author `docs/decisions/adr-0028-release-engineering.md`
  capturing: (a) registry = GHCR, (b) signing identity =
  `https://github.com/<owner>/<repo>/.github/workflows/release.yml@refs/tags/v*`,
  (c) attestation predicate types pinned to SLSA v1 in-toto,
  (d) image tag immutability requirement, (e) §8.10 deprecation
  cadence, (f) the §8.15 `## Verification` section must reference
  `verify-published` job evidence.
- **Owner:** `docs/decisions/adr-0028-release-engineering.md` (new)

## R-012 — `cosign sign --yes` is invoked per-tag, signing the same digest multiple times; verifier semantics under multiple signatures are surprising

- **Severity:** S3
- **Location:** `.github/workflows/release.yml:103-110`
  ```
  for tag in $TAGS; do
    cosign sign --yes "${tag}@${DIGEST}"
  done
  ```
- **Category:** signing
- **Evidence:** `metadata-action` produces both `:0.1.0` and
  `:sha-<full>`. The loop signs `${IMAGE}:0.1.0@${DIGEST}` and
  `${IMAGE}:sha-<full>@${DIGEST}` — same digest, two
  invocations. Cosign attaches the signature to the *digest* in
  the registry's signature-tag namespace; the second invocation
  appends a second signature for the same identity to the same
  digest's signature-tag. `cosign verify` with
  `--certificate-identity-regexp` checks "at least one signature
  from a matching identity" — so the duplicate is benign for
  verification but consumes registry quota and confuses
  `cosign tree` output. More worrying: if the workflow is ever
  re-run (e.g., manually re-tagging on a hot-fix), the new
  signatures are *additive*, never replacing — including any
  signature accidentally produced by a misconfigured run.
- **Release-time impact:** Operationally inert today, but if
  a future incident requires "revoke this signature," the
  per-digest signature accretion makes the verify command's
  "matches at least one identity" semantics over-permissive.
- **Fix proposal:** Sign once, by digest, with
  `cosign sign --yes "${IMAGE}@${DIGEST}"`. The signature
  attaches to the digest, not the tag, so all tags inherit it.
  Reduces the `for` loop to a single command and avoids signature
  accretion.
- **Owner:** `.github/workflows/release.yml`

## R-013 — `CHANGELOG.md` `[0.1.0]` entry is a placeholder paragraph, not Keep-a-Changelog-formatted; first GA tag will fail the format CI (which doesn't yet exist)

- **Severity:** S3
- **Location:** `CHANGELOG.md:29-35`
- **Category:** notes-template
- **Evidence:** Per Keep-a-Changelog 1.1.0, every released
  version section requires `### Added/Changed/Deprecated/Removed/Fixed/Security`
  subsections (any combination of which may be omitted). The
  current `[0.1.0] - TBD` entry is a free-form paragraph
  beginning *"GA target. Tracked in `docs/progress.md`..."*. The
  release.yml's `Verify version tag matches POM` step does not
  also verify that `CHANGELOG.md` has a non-placeholder section
  for the tag being pushed. So the first `git tag v0.1.0` push
  will publish a release whose CHANGELOG is the literal
  placeholder.
- **Release-time impact:** Cosmetic on its own, but it's the
  release manager's last sanity-check that the notes are real.
  Pair with R-010 (missing upgrade runbook) and the user-facing
  artifact is meaningfully broken.
- **Fix proposal:** Add a `Verify CHANGELOG entry exists` step
  to `release.yml` that greps for `^## \[${tag_version}\]` and
  asserts the section contains at least one of the six K-a-C
  subsection headers and is not the literal "TBD" string. Update
  the existing `[0.1.0]` placeholder to follow K-a-C format
  pre-emptively (it can still say "TBD" inside `### Added`).
- **Owner:** `.github/workflows/release.yml`, `CHANGELOG.md`

---

## Aging-out from iter-1 (still open in iter-2)

| iter-1 ID | Status | This-iter ID |
|---|---|---|
| R-003 (StatefulSet placeholder defeats `kubectl rollout undo`) | Partially closed by ops/runbooks/release.md rollback section but the manifest still ships `image: configd:GIT_SHA`; the rollback now uses `kubectl patch` instead of `rollout undo`. Acceptable. | (closed via runbook) |
| R-004 (canary + SLO-burn auto-abort) | OPEN | R-003 (this iter) |
| R-006 (migration framework) | OPEN | R-007 (this iter) |
| R-007 (wire-version field) | Byte reserved; decoder semantics broken | R-001 (this iter) — escalated to S0 |
| R-008 (snapshot/WAL backwards-compat tests) | Stub files added, both `@Disabled` until first version bump. Acceptable for iter-2 but enable-on-bump is a hard prerequisite. | (no new finding) |
| R-009 (reproducible-build verification) | OPEN — `SOURCE_DATE_EPOCH` set but no second-build comparison | (carried — re-flag in iter-3 if no movement) |
| R-010 (digest-only admission policy) | OPEN | (carried) |
| R-013 (immutable tags) | OPEN — depends on GHCR registry settings | (carried) |
| R-014 (`reconfiguration-rollback.md` rename) | OPEN | (carried) |

---

## Stability signal contribution from this review

This review introduces **0** new S0 *via fixes* — every S0 finding
(R-001, R-002) names a pre-existing latent defect that ships under
green CI. The findings are evidence the §4.6 stability_signal must
remain non-zero until R-001 and R-002 are closed; iter-2 cannot
terminate.
