# Runbook: Cutting a Release

## Symptoms

This is an operational runbook, not an alert response — it is invoked
on a planned schedule (or to publish a hot-fix). The "trigger" is one
of:

- All Phase-11 GA gates in `docs/ga-review.md` are GREEN and the
  release manager intends to publish a tagged version.
- A previously-published release exhibits a regression (consumers
  observe a bug); the rollback section below is the response.

## Impact

A successful release publishes a Cosign-signed, SLSA-attested
container image to GHCR with a pinned digest. Consumers reference that
digest in their deployments. A failed release leaves GHCR consistent
(nothing partially published — the workflow is atomic) but blocks
downstream operators from upgrading.

A failed rollback leaves the cluster on a known-bad image and forces
an emergency forward fix.

## Operator-Setup

Per ADR-0025 the release operator must, before this runbook applies:

1. Hold a registered GPG signing key associated with the project's
   `git tag -s` policy.
2. Have `cosign` and `gh` CLIs installed locally.
3. Hold push permission on the GHCR repo and write permission on the
   GitHub repo (specifically the `release` workflow scope).
4. Have access to the operator's incident-tracking system to file
   the post-release incident if rollback is needed.

## Diagnosis

### Pre-flight (any release)

1. **All gates green on `main`.** Phase 11 GA review records this in
   `docs/ga-review.md`. If any gate is yellow or red, do not cut.
2. **POM version matches intended tag.** The release workflow re-checks
   this; failing here aborts the release before image push.
3. **CHANGELOG / release notes drafted** from `RELEASE_NOTES_TEMPLATE.md`.

### When something goes wrong post-release

Identify the failure mode:

- `cosign verify` fails for consumers → pipeline-side problem; do NOT
  re-publish, investigate the workflow first.
- Functional regression (consumers observe a bug at the new digest) →
  rollback per the section below, then forward-fix.
- Snapshot-install / wire-format incompatibility on the new digest →
  rollback per the section below; the format change must follow §8.10
  (deprecation ≥ 2 releases) before the bad release becomes the
  current.

## Mitigation

### Cutting the release

```sh
# Update the version (drop -SNAPSHOT)
./mvnw versions:set -DnewVersion=0.1.0
./mvnw versions:commit
git add -A
git commit -m "Release v0.1.0"

# Tag and push
git tag -s v0.1.0 -m "Configd v0.1.0"
git push origin main v0.1.0

# Bump POM to next snapshot
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT
./mvnw versions:commit
git add -A
git commit -m "Open development for v0.2.0"
git push origin main
```

The push of `v0.1.0` triggers `.github/workflows/release.yml`. That
workflow:

1. Re-runs the full reactor `mvn verify`.
2. Generates a CycloneDX SBOM (B5).
3. Builds the runtime image from `docker/Dockerfile.runtime` (B8 —
   pinned base image digest).
4. Pushes to GHCR with tags `:0.1.0` and `:sha-<full-commit>`.
5. **Cosign-signs** the image keylessly with GitHub OIDC (B6 / PA-6011).
6. Attaches a **SLSA build provenance attestation** (PA-6012) to the
   image manifest in the registry.
7. Publishes a GitHub release with the SBOM attached and verification
   instructions in the body.

### Verification (consumers must run)

```sh
DIGEST="sha256:..."  # from the release notes

# 1. Verify Cosign signature against the workflow identity
cosign verify \
  --certificate-identity-regexp \
    'https://github.com/<owner>/<repo>/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/<owner>/<repo>@${DIGEST}

# 2. Verify SLSA build provenance
gh attestation verify oci://ghcr.io/<owner>/<repo>@${DIGEST} \
  --owner <owner>

# 3. Cross-check SBOM
cosign download attestation ghcr.io/<owner>/<repo>@${DIGEST} \
  | jq -r '.payload | @base64d | fromjson | .predicate'
```

If any of (1)–(3) fail, **do not deploy.** Open an incident — a passing
build with failing attestation indicates either pipeline compromise or
a bug in the verification toolchain.

### Deploying the verified image

The reference `deploy/kubernetes/configd-statefulset.yaml` ships with
the placeholder `image: configd:GIT_SHA`. Replace at deploy time with
the digest:

```sh
sed -i "s|configd:GIT_SHA|ghcr.io/<owner>/<repo>@${DIGEST}|" \
  deploy/kubernetes/configd-statefulset.yaml
kubectl apply -f deploy/kubernetes/configd-statefulset.yaml
```

Pinning by digest (not tag) is mandatory for production — see
PodSecurity / NetworkPolicy comments in the manifest, the supply-chain
contract in ADR-0021 (Maven build / reproducibility) and the Java-25
runtime pin in ADR-0022.
<!-- TODO: confirm correct ADR — there is no dedicated release-
engineering ADR yet; the original reference was to ADR-0026 which is
the OpenTelemetry stub (wrong topic). The closest extant decisions are
ADR-0021 (build) and ADR-0022 (runtime pin). A dedicated
`adr-XXXX-release-engineering.md` should be authored when the release
pipeline is exercised end-to-end (Phase 9 demotion in iter-1). -->

## Resolution

A release is **resolved** when:

- The GitHub release is published with SBOM attached.
- `cosign verify` succeeds against the published digest.
- `gh attestation verify` succeeds against the same digest.
- The next-snapshot bump commit is on `main`.
- The release notes link from CHANGELOG.md is filed.

A rollback is **resolved** when the criteria in the **Rollback —
declare rollback successful** section are met.

## Rollback

A release rollback is **re-pinning the StatefulSet to the previous
verified digest** and reverting the manifest commit that introduced the
new one. We never amend or delete a published tag — every prior digest
remains verifiable, which is the whole point of pinning.

### 0. Identify the previous good digest

```sh
PREV_DIGEST="sha256:..."   # the digest that was running before the bad release
PREV_TAG="0.1.0"           # human-readable tag matching ${PREV_DIGEST}
BAD_TAG="0.2.0"            # the tag being rolled back
```

`PREV_DIGEST` lives in the previous release notes / git history of
`deploy/kubernetes/configd-statefulset.yaml`; never read it from the
GHCR `:latest` or `:stable` floating tag.

### 1. Re-verify the previous image (do not skip — same protocol as forward release)

```sh
cosign verify \
  --certificate-identity-regexp \
    'https://github.com/<owner>/<repo>/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/<owner>/<repo>@${PREV_DIGEST}

gh attestation verify oci://ghcr.io/<owner>/<repo>@${PREV_DIGEST} \
  --owner <owner>
```

If either fails, **stop and escalate** — rolling back to an
unverifiable image is the same supply-chain hazard as deploying an
unverifiable forward release.

### 2. Revert the manifest commit

```sh
# Find the commit that bumped the StatefulSet image to the bad release.
git log --oneline -- deploy/kubernetes/configd-statefulset.yaml | head -5

git revert <bad-bump-sha>
git push origin main
```

If GitOps (Argo / Flux) is in use, the revert push is sufficient — the
controller reconciles. Otherwise apply the manifest manually.

### 3. Drain ONE canary node first

```sh
# Cordon and drain the canary (configd-2 by convention; see
# deploy/kubernetes/configd-statefulset.yaml — replicas: 3, names
# configd-0/1/2). Always rollback the highest-ordinal pod first so
# the leader (typically configd-0 right after promotion) is the LAST
# to roll, not the first.
kubectl -n configd cordon $(kubectl -n configd get pod -l app=configd \
    -o jsonpath='{.items[-1:].spec.nodeName}')

# Patch only the canary pod's image. Use the full digest, never a tag.
kubectl -n configd patch statefulset configd --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/image",
       "value":"ghcr.io/<owner>/<repo>@'"${PREV_DIGEST}"'"}]'

# Wait for the canary pod to come up on the previous image:
kubectl -n configd rollout status statefulset/configd --timeout=5m \
    --watch
```

`deploy/kubernetes/configd-statefulset.yaml` ships with the placeholder
`image: configd:GIT_SHA`; the `kubectl patch` above is the production
equivalent of the `sed` rewrite documented in the deploy section.

### 4. Watch for snapshot-install failure

While the canary catches up, an incompatible snapshot format is the
single most likely rollback failure mode. Watch:

```sh
# Prometheus alerting query — fail rollback if non-zero in the canary
# observation window.
kubectl -n configd port-forward svc/prometheus 9090:9090 &
curl -sG 'http://localhost:9090/api/v1/query' \
  --data-urlencode 'query=increase(configd_snapshot_install_failed_total[10m])' \
  | jq '.data.result'
```

If the metric is non-zero, **stop the rollout** (`kubectl -n configd
rollout pause statefulset/configd`), uncordon the canary, capture the
canary's `/data` snapshot for incident review, and escalate. Continuing
through a snapshot-install failure can corrupt the follower's log.

### 5. Roll the remaining replicas

Only after the canary has been on `${PREV_DIGEST}` for at least 10
minutes with `configd_snapshot_install_failed_total` flat at zero:

```sh
kubectl -n configd uncordon $(kubectl -n configd get pod -l app=configd \
    -o jsonpath='{.items[-1:].spec.nodeName}')

# StatefulSet `OrderedReady` rollout will now propagate the previous
# digest to the remaining pods one-by-one.
kubectl -n configd rollout status statefulset/configd --timeout=15m
```

### 6. Declare rollback successful

The rollback is **successful** when *all* of the following hold for at
least 10 consecutive minutes:

- All 3 pods report ready: `kubectl -n configd get pods -l app=configd`
  shows `READY 1/1` for every replica.
- Commit-index parity: every pod's served `X-Config-Version` header on
  a probe-key GET is identical (no follower more than one heartbeat
  behind). The header is set by `HttpApiServer.handleGet` from
  `ReadResult.version()` — see `configd-server/.../HttpApiServer.java`.
  <!-- TODO PA-7002: admin endpoint missing — `/admin/raft/status` is
  not exposed on HttpApiServer; once it ships, swap the probe-key
  approach for a direct `commit_index` read. -->
  ```sh
  # Probe a stable key — operators typically maintain a small
  # /v1/config/__release_probe__ value updated by CI on every release.
  for pod in configd-0 configd-1 configd-2; do
    kubectl -n configd exec ${pod} -- \
      curl -sf -D - "http://localhost:8080/v1/config/__release_probe__" \
      | grep -i '^X-Config-Version:'
  done
  ```
- No error spike: `rate(configd_write_commit_failed_total[5m])` and
  `rate(configd_snapshot_install_failed_total[5m])` are both zero, and
  no SLO burn-rate alerts are currently firing:
  `count(ALERTS{alertname=~"ConfigdWriteCommitFastBurn|ConfigdControlPlaneAvailability",alertstate="firing"}) == 0`.
  <!-- TODO PA-XXXX: a derived `configd_slo_burn_rate_1h` series is not
  emitted; the canonical signal is the burn-rate alert state from
  `ops/alerts/configd-slo-alerts.yaml`. -->
  The metric `configd_write_failure_total` does NOT exist; the actual
  registered counter is `configd_write_commit_failed_total` (see
  `ConfigdMetrics.NAME_WRITE_COMMIT_FAILED`).
- Image actually rolled: `kubectl -n configd get pod -l app=configd
  -o jsonpath='{range .items[*]}{.spec.containers[0].image}{"\n"}{end}'`
  shows `ghcr.io/<owner>/<repo>@${PREV_DIGEST}` for every replica.

If any of the four checks fails after 10 minutes, the rollback itself
has failed; do not declare success — open an incident.

### 7. Cut a forward fix release

Per `## Do not`, you do not amend the bad tag. After the cluster is
stable on `${PREV_DIGEST}`:

```sh
./mvnw versions:set -DnewVersion=0.2.1
git commit -am "Release v0.2.1 — fix for v${BAD_TAG} rollback"
git tag -s v0.2.1 -m "Configd v0.2.1"
git push origin main v0.2.1
```

Then re-deploy via the standard forward path.

## Postmortem

- For a successful release: short retrospective of what changed and any
  dashboard / alert noise observed during the rollout.
- For a rollback: full incident review within one business day. Required
  fields: which check first failed, who paged, time-to-mitigate (canary
  patched), time-to-resolve (all replicas back on prior digest), root
  cause of the bad release, and an action item for what test would have
  caught the regression in CI.

## Related

- `.github/workflows/release.yml` — pipeline source
- `RELEASE_NOTES_TEMPLATE.md` — release notes scaffold
- `CHANGELOG.md` — Keep-a-Changelog log per release
- `docs/decisions/adr-0021-maven-build-system.md` — build system /
  reproducibility contract that the release workflow re-runs as
  `mvn verify`.
- `docs/decisions/adr-0022-java-25-runtime.md` — JDK pin that the
  Cosign signature attests to (any change requires a CHANGELOG entry).
- `docs/decisions/adr-0025-on-call-rotation-required.md` — operator-side
  paging hand-off.

## Do not

- Do not push a release tag without a successful pre-flight `mvn verify`.
- Do not amend the release tag after push. If something is wrong, cut
  `vX.Y.Z+1` — never reuse a version. Re-using a tag invalidates every
  consumer's pinned digest and breaks Cosign verification.
- Do not deploy an image whose attestation does not verify.
- Do not roll all replicas at once during rollback — always drain one
  canary first and watch `configd_snapshot_install_failed_total`.
- Do not roll back to an image whose `cosign verify` /
  `gh attestation verify` does not pass; the previous-known-good digest
  is still subject to the same verification protocol as a forward release.
