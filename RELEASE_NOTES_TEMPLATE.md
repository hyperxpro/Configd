# Configd vX.Y.Z

<!--
Fill out every section below. Empty sections are deleted before publish,
not left blank — an empty "Breaking changes" section is meaningfully
different from no section at all (the former says "we checked", the
latter says "we forgot to check").

Source of truth for what's in the release: git log vPREVIOUS..vX.Y.Z.
-->

## Summary

One paragraph. Who needs this release, why, what's the headline.

## Highlights

- Three to five bullets. User-facing changes only.

## Breaking changes

- List every breaking change with migration steps. If none: write
  "None — drop-in replacement for vX.Y.(Z-1)".

## Wire-Compatibility

§8.10 hard rule: a wire-format change requires a deprecation cycle of
at least two releases. Mark every checkbox that applies. Unchecked
boxes block the release.

- [ ] Wire-version byte unchanged OR documented with golden-bytes test diff
- [ ] No new mandatory frame field; any new field is opt-in via reserved bytes
- [ ] All `MessageType` enum values preserve their existing on-wire byte codes
- [ ] `configd-transport/src/test/resources/wire-fixtures/v<N>/*.bin`
      fixtures regenerated and committed (see `wire-compat` CI job)
- [ ] If wire-version was bumped, deprecation note added to `CHANGELOG.md`
      naming the release in which the previous version is removed (≥ N+2)

## Snapshot/WAL Compatibility

Stateful upgrades — a new node should be able to read snapshots and WAL
segments written by the previous release.

- [ ] Snapshot binary format unchanged OR backward-compat test added under
      `configd-consensus-core/src/test/java/io/configd/raft/SnapshotWireCompatStubTest.java`
      and enabled
- [ ] WAL segment format unchanged OR backward-compat test added under
      `WalWireCompatStubTest.java` and enabled
- [ ] If either format changed: migration tool committed under
      `ops/scripts/migrate-<from>-to-<to>.sh` and referenced in
      `ops/runbooks/upgrade.md`

## SLO contract changes

- Did any SLO threshold tighten or loosen? (See
  `ProductionSloDefinitions.java`.)
- If yes: describe the customer-visible effect.

## Bug fixes

- Bullet per fix, link to issue / PR.

## Performance

- Reference any updated `docs/perf-baseline.md` rows.
- Note any throughput / latency regression-or-improvement.

## Operational changes

- New runbook? Updated alert? Changed dashboard panel?
- New required configuration? Removed deprecated flag?

## Security

- CVEs addressed (with CVE IDs).
- Supply-chain provenance: confirm Cosign signature and SLSA
  attestation are published (see Container image section below).

## Container image

`ghcr.io/<owner>/<repo>@sha256:<digest>`

Verification (full protocol in `ops/runbooks/release.md`):

```sh
cosign verify \
  --certificate-identity-regexp 'https://github.com/<owner>/<repo>/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/<owner>/<repo>@sha256:<digest>
```

## Rollback Procedure

Operators rolling forward to this release must know how to roll back
to the previous digest before they apply the manifest.

- Previous good digest: `ghcr.io/<owner>/<repo>@sha256:<previous-digest>`
- Detailed rollback steps: `ops/runbooks/release.md` — section
  `## Rollback`. Includes canary-first drain ordering, the
  `configd_snapshot_install_failed_total` watch, and the four
  success criteria (commit-index parity, no error spike for 10m).
- Rollback verification: re-run the `cosign verify` and
  `gh attestation verify` commands above against the previous digest
  before applying. Never roll back to an unverifiable image.

## Verified-On Environments

The release engineer has personally verified each cell below; an
empty cell is a release blocker, not a "we didn't bother".

| Surface | Version / SHA | Verified-by | UTC timestamp |
|---|---|---|---|
| Kubernetes | 1.29.x — `kind v0.23.0` | | |
| JDK runtime | OpenJDK / Corretto 25 | | |
| Container runtime | containerd 1.7+ | | |
| Cosign client | v2.4.x | | |
| `gh` CLI | 2.50+ | | |
| Reference workload | `deploy/kubernetes/configd-statefulset.yaml` | | |

## Known issues

- Anything that didn't make this release but is in flight. Link the
  tracking issue.

## Acknowledgements

- Contributors, reporters, security researchers.
