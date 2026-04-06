# release-skeptic — iter-001
**Findings:** 14

## R-001 — release.yml has no rollback / auto-abort path
- **Severity:** S1
- **Location:** .github/workflows/release.yml:31-145
- **Category:** rollback
- **Evidence:** The workflow only contains `build-and-publish`. No `on_failure:` job, no `rollback`/`revert-tag`/`yank-image` step, and no canary stage with SLO gate.
- **Impact:** A signed-and-attested image lands in GHCR even if downstream verification or the smoke test fails. Operators have no automated path to mark the tag bad — the image is consumable by `kubectl apply` immediately.
- **Fix direction:** Add a `verify-published` job that re-runs cosign+SLSA+`gh attestation verify` against the just-pushed digest; on failure, push a `:yanked-<sha>` tag, post-attest the image as failing, and open a GH issue. Optionally publish an OCI artifact at `ghcr.io/<repo>:<ver>.yank-marker`.
- **Proposed owner:** release engineering / `.github/workflows/release.yml`

## R-002 — no documented rollback procedure for a bad release
- **Severity:** S0
- **Location:** ops/runbooks/release.md:86-92
- **Category:** rollback
- **Evidence:** "Do not amend the release tag after push. If something is wrong, cut `vX.Y.Z+1` — never reuse a version." There is no "Rolling back" section anywhere in the file.
- **Impact:** If `v0.1.0` is broken on rollout, on-call must invent procedure under pressure. There is no written guidance on `kubectl rollout undo`, on un-publishing the GitHub release, on revoking the cosign signature, on pinning consumers back to the prior digest, or on what to do for an in-flight half-rolled-out StatefulSet (which was created fresh, not rolling-updated).
- **Fix direction:** Add a "Rollback" section that covers (1) `kubectl rollout undo statefulset/configd -n configd` with explicit prereq that the prior digest must already be applied (it isn't — manifest is `image: configd:GIT_SHA`), (2) marking the GitHub release as a draft or pre-release, (3) pushing a follow-up commit that re-pins the manifest to N-1, (4) cosign-signing a "yanked" attestation. Also reference `kubectl rollout history`.
- **Proposed owner:** `ops/runbooks/release.md`

## R-003 — StatefulSet placeholder defeats `kubectl rollout undo`
- **Severity:** S1
- **Location:** deploy/kubernetes/configd-statefulset.yaml:66-68
- **Category:** rollback
- **Evidence:** `image: configd:GIT_SHA` — placeholder. Runbook patches it via `sed -i` and a manual `kubectl apply`. No GitOps/Argo manifest history, no recorded prior digest in the cluster.
- **Impact:** `kubectl rollout undo statefulset/configd` (which the release-rehearsal doc claims is the rollback in `docs/battle-ready/release-rehearsal.md:58`) only works if the controller has a previous `ControllerRevision` to roll to. After a one-shot `sed`+`apply`, that revision history exists, but the runbook never instructs operators to verify it nor specifies the rollback command.
- **Fix direction:** Either (a) commit a Kustomize/Helm overlay so each release is a Git commit with the digest embedded, or (b) add a "Pre-deploy: capture current digest with `kubectl get sts configd -n configd -o jsonpath='{.spec.template.spec.containers[0].image}'` and stash it" step to the runbook.
- **Proposed owner:** `deploy/kubernetes/`, `ops/runbooks/release.md`

## R-004 — release pipeline does no canary, no SLO-burn auto-abort
- **Severity:** S1
- **Location:** .github/workflows/release.yml:81-119
- **Category:** canary-abort
- **Evidence:** `Build and push runtime image` pushes to GHCR with a final tag. Pipeline ends. The only canary mention is a static plan in `docs/battle-ready/release-rehearsal.md:51-58` (steps 1–4 with no automation, "Rollback: `kubectl rollout undo`" as a manual step).
- **Impact:** No automated SLO-burn check after deploy. Pipeline cannot abort a bad rollout; SLO breach detection lives in Prometheus alert rules with no closed feedback loop into the release.
- **Fix direction:** Add a `canary` job gated on a downstream environment that runs the smoke suite, checks the burn-rate alerts (`ops/alerts/`), and exits non-zero on breach. Combine with R-001's yank-marker.
- **Proposed owner:** `.github/workflows/release.yml`, `ops/alerts/`

## R-005 — no wire-compat matrix in CI (N vs N-1 / N+1)
- **Severity:** S1
- **Location:** .github/workflows/ci.yml:30-49
- **Category:** wire-compat
- **Evidence:** CI runs `mvn verify` and the property/simulation tests on a single revision. No job pulls a prior released artifact and exercises `RaftMessageCodec` / `CommandCodec` / `FrameCodec` against it.
- **Impact:** A breaking wire change can land without a single test failing because every test compiles against HEAD. Mixed-version clusters during rolling upgrade will fail in production. There is no enforcement of the §8.10 "deprecation ≥ 2 releases" policy referenced in the prompt — neither `FrameCodec.java` nor `CommandCodec.java` carries any version field.
- **Fix direction:** Add a `wire-compat` job that downloads the most-recently-tagged JAR (or a golden encoded-bytes fixture set under `configd-transport/src/test/resources/golden/v0.0.x/`) and round-trips it through current decoders, asserting bytes-in/bytes-out. Fixture files become the contract.
- **Proposed owner:** `configd-transport`, `configd-config-store`

## R-006 — no migration framework, no undo migrations
- **Severity:** S2
- **Location:** repo-wide — no `*Migration*` directory or class exists (verified by file search). `docs/runbooks/version-gap.md` discusses replication lag, not schema/storage migration.
- **Category:** migration-undo
- **Evidence:** No matches for `Migration` Java types. `CommandCodec.java:1-38` has fixed type bytes (`0x01`, `0x02`, `0x03`) with no version prefix; `RaftMessageCodec.java:39-296` has no envelope version.
- **Impact:** When the storage/log/snapshot format must change (it will), there is no scaffold to (a) detect prior format on disk, (b) migrate forward, (c) write an undo. Rolling back N→N-1 after a write under N will ENOENT the prior decoder.
- **Fix direction:** Add `configd-config-store/src/main/java/io/configd/store/migrations/` with a `Migration` SPI (apply/undo, source-version, target-version), wire into `ConfigStateMachine` startup, and require a paired `*UndoMigrationTest` for each. Add a snapshot file header `[magic][format-version][...]`.
- **Proposed owner:** `configd-config-store`, `configd-consensus-core`

## R-007 — no schema/protocol version field on the wire
- **Severity:** S1
- **Location:** configd-transport/src/main/java/io/configd/transport/FrameCodec.java:9-29 ; configd-config-store/src/main/java/io/configd/store/CommandCodec.java:14-23
- **Category:** deprecation
- **Evidence:** Frame layout is `[Length][Type][GroupId][Term][Payload]` — no protocol-version byte. Command layout is `[type byte][...]` — type IDs are 0x01/0x02/0x03 with no envelope version.
- **Impact:** §8.10's "deprecation ≥ 2 releases" cannot be enforced because the receiver cannot distinguish v1 from v2 of the same `MessageType`. Any future field addition is either (a) a silent corruption or (b) a new `MessageType` that explodes the enum.
- **Fix direction:** Reserve a 1-byte `version` slot in the frame header now (consume from `groupId`'s upper byte or bump `HEADER_SIZE`). Define a wire-compat ADR. Backfill golden fixtures.
- **Proposed owner:** `configd-transport`

## R-008 — DB / WAL / snapshot backwards-compat tests absent
- **Severity:** S1
- **Location:** repo-wide — no test class named `*BackwardsCompat*`, `*WireCompat*`, `*Upgrade*`, `*FormatV[0-9]*`. The only "version" test material is `CommandCodecPropertyTest` (round-trip of current version, not cross-version).
- **Category:** wire-compat
- **Evidence:** Verified by repository search. `docs/battle-ready/release-rehearsal.md:42-49` table claims "CommandCodec versioning … Forward-compatible via unknown-type rejection" but unknown-type rejection ≠ versioning, and no test exercises it cross-version.
- **Impact:** A change to the snapshot file or WAL record format that compiles cleanly will silently break upgrades. Restore-from-snapshot during rollback to N-1 is not validated.
- **Fix direction:** Add `configd-config-store/src/test/resources/golden-snapshots/v0.1.0/snapshot.bin` and a test `SnapshotBackwardsCompatTest` that asserts current code can read it. Same for WAL.
- **Proposed owner:** `configd-config-store`, `configd-consensus-core`

## R-009 — reproducible-build claim not verified in CI
- **Severity:** S2
- **Location:** .github/workflows/release.yml:90-94 ("Reproducible build — pin SOURCE_DATE_EPOCH to the commit timestamp.")
- **Category:** reproducibility
- **Evidence:** Only `SOURCE_DATE_EPOCH` is set. No second build is run, no digest comparison is asserted. `docs/battle-ready/release-rehearsal.md:70` lists "Reproducible build verification: Two independent builds from same commit, compare hashes" as a pre-launch requirement that is not implemented anywhere in the workflow.
- **Impact:** The release advertises reproducibility (and SLSA expects it) but it is unverified. A non-deterministic input (timestamp leakage in a JAR's MANIFEST.MF, file ordering in a fat-jar) would never be caught.
- **Fix direction:** Add a CI job that builds the runtime image twice on different runners with identical inputs (`SOURCE_DATE_EPOCH`, base image digest is pinned — good) and asserts the manifest digests match. Or use `diffoci`. Fail loud if not byte-identical.
- **Proposed owner:** `.github/workflows/`

## R-010 — runtime image lacks an OCI digest pin in the K8s manifest's default
- **Severity:** S2
- **Location:** deploy/kubernetes/configd-statefulset.yaml:66-68
- **Category:** tag-mutability
- **Evidence:** `image: configd:GIT_SHA` is a tag-form placeholder. Runbook describes a `sed` substitution to a digest. There is no admission webhook (e.g., Kyverno `disallow-image-tags`) to enforce digest-only.
- **Impact:** Operator who copies the manifest verbatim and does the substitution wrong (uses `:0.1.0` instead of `@sha256:...`) defeats Cosign verification — the image ref no longer pins an attested digest. Runbook says "NEVER deploy a floating tag" (line 11) but does not enforce it.
- **Fix direction:** Add a `ValidatingAdmissionPolicy` (k8s 1.30+) or Kyverno cluster policy under `deploy/kubernetes/` that rejects any pod in the `configd` namespace whose image lacks `@sha256:`. Reference it from the runbook.
- **Proposed owner:** `deploy/kubernetes/`

## R-011 — release-notes template missing rollback / version-compat sections
- **Severity:** S2
- **Location:** RELEASE_NOTES_TEMPLATE.md:1-72
- **Category:** notes-template
- **Evidence:** The template has `Breaking changes`, `SLO contract changes`, `Operational changes`, `Security`, `Container image`, `Known issues`, `Acknowledgements`. It does NOT have `Rollback` (how to go back to the previous version), `Wire-format compatibility` (does N talk to N-1?), `Migration / undo migration` (was a schema bump applied? is it reversible?), `Feature flags` (what flipped, what is the removal-by date?).
- **Impact:** Notes published from this template ship without the operator-critical "if this goes badly, do X" line. R-002 documents the runbook gap; this is the same gap manifesting at the user-facing layer.
- **Fix direction:** Add four sections: `## Rollback`, `## Wire-format compatibility (N ↔ N-1)`, `## Schema/format migrations`, `## Feature flags introduced/removed`.
- **Proposed owner:** `RELEASE_NOTES_TEMPLATE.md`

## R-012 — cosign verify command in release.yml body uses an unescaped GitHub-Actions expression
- **Severity:** S3
- **Location:** .github/workflows/release.yml:135-141
- **Category:** verification
- **Evidence:** The release-body HEREDOC interpolates `${{ github.repository }}`, `${{ env.IMAGE_NAME }}`, etc. directly into the cosign command. `IMAGE_NAME` is `${{ github.repository }}` which contains a `/` (e.g., `aayush/configd`). The `--certificate-identity-regexp 'https://github.com/aayush/configd/.github/...'` will be valid as a regex but the unescaped `.` matches any char. Operators copy-pasting onto a shell where any of those values contain a regex meta-char (e.g., a fork named `configd-v2.0`) will get a regex that matches more than they expect — silent over-trust.
- **Impact:** Verifier-too-permissive: a sibling fork's release.yml could satisfy the regex. Low likelihood, but the SLSA/cosign chain is the trust boundary so any laxness is worth fixing.
- **Fix direction:** Use `--certificate-identity` (literal) instead of `--certificate-identity-regexp`, OR escape the dots: `https://github\.com/...`.
- **Proposed owner:** `.github/workflows/release.yml`, `ops/runbooks/release.md`, `RELEASE_NOTES_TEMPLATE.md`

## R-013 — release notes "Container image" tag in body not deduped against `:sha-<sha>` mutable risk
- **Severity:** S3
- **Location:** .github/workflows/release.yml:74-79
- **Category:** tag-mutability
- **Evidence:** `tags: type=semver,pattern={{version}}` and `type=sha,format=long` produce `:0.1.0` and `:sha-<full>`. Both are pushed and signed. The `:0.1.0` semver tag is theoretically immutable by policy but not by GHCR enforcement — a re-pushed `v0.1.0` tag would overwrite the manifest reference (the digest itself is content-addressed, but `:0.1.0` is not).
- **Impact:** A future operator who re-runs the workflow on a hot-fixed tag silently mutates `:0.1.0`'s manifest pointer. The runbook says "never reuse a version" but the tag is not GHCR-immutable.
- **Fix direction:** Enable GHCR's "Immutable tags" / "retention" policy, document it in the release runbook, or add a workflow guard that fails if the version tag already exists in the registry.
- **Proposed owner:** `.github/workflows/release.yml`, GHCR settings (out-of-repo)

## R-014 — `docs/runbooks/reconfiguration-rollback.md` is membership rollback, not release rollback — and uses metrics that don't exist
- **Severity:** S2
- **Location:** docs/runbooks/reconfiguration-rollback.md:1-52 (referenced in `docs/ga-review.md:138-153` as known doc-drift)
- **Category:** rollback
- **Evidence:** The only file in the repo with "rollback" in its name is about Raft joint-consensus rollback (`configd_raft_config_change_pending`, `configd_raft_role`) — it has nothing to do with rolling back a software release. `docs/ga-review.md:144-147` already flags those metrics as non-existent.
- **Impact:** Anyone searching the repo for "rollback" lands on this file and either (a) believes there is a release-rollback procedure (there isn't), or (b) tries to use the membership-rollback metrics (which don't exist). Either way it delays incident response.
- **Fix direction:** Rename to `raft-membership-rollback.md` and add a release-level `release-rollback.md` that pairs with R-002.
- **Proposed owner:** `docs/runbooks/`, `ops/runbooks/`
