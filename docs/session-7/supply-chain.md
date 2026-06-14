# Session 7 — Supply-Chain Security Findings

Owner: supply-chain-engineer. Branch: `session-7-security`. Date: 2026-06-14.

Charter §8 + §10.8 (ENV-BLOCKED honesty rule): anything that needs
network/tooling unavailable in this sandbox is recorded ENV-BLOCKED with the
EXACT need and the EXACT CI command — never assumed-passing.

This doc covers E-1 (CVE scan), E-2 (SBOM), E-3 (reproducible build),
E-4 (secret scan), E-5 (runbook/script credential audit).

---

## E-1. Dependency CVE scan — OWASP dependency-check

### What was added (pom.xml)
- New `-Pcve-scan` profile in the root `pom.xml` running
  `org.owasp:dependency-check-maven:12.1.0` (version pinned via the
  `dependency-check.version` property).
- Configured to **fail the build on CVSS >= 7.0** (`<failBuildOnCVSS>7</failBuildOnCVSS>`)
  i.e. HIGH/CRITICAL.
- Emits `HTML`, `JSON`, and `SARIF` to
  `target/dependency-check/`.
- References a committed, empty suppression scaffold
  `dependency-check-suppressions.xml` (any future suppression MUST cite a
  triage ADR / Decision-Log id — no green-washing).
- .NET / Node / Python analyzers disabled (pure-Java reactor).

The profile lives in a PROFILE so the default reactor build / gate-1 never
pays the NVD-feed cost.

### Run attempt — ENV-BLOCKED (HONEST)
`./mvnw -N -Pcve-scan org.owasp:dependency-check-maven:update-only` was run
with a bounded 180s timeout. Result:

- The plugin **reached the NVD network** and began the feed update:
  `NVD API has 357,796 records in this update` then
  `Downloaded 10,000 / 20,000 / ... / 357,796`.
- It emitted the plugin's own warning:
  `An NVD API Key was not provided - it is highly recommended to use an NVD
  API key as the update can take a VERY long time without an API Key`.
- After ~2 minutes it had reached only ~6% of records and was being
  rate-limited (no API key). It was killed at the bounded timeout.

**Verdict: ENV-BLOCKED.** The first-run NVD feed download (357,796 CVE
records) without an API key is impractically slow and rate-limited in this
sandbox; finishing it would blow any reasonable local budget. I did NOT
let it hang, and I am NOT claiming a clean CVE scan — no scan completed,
so no findings could be triaged here.

**Exact need for S7.5 manifest:**
1. An **NVD API key** (`NVD_API_KEY`, free from https://nvd.nist.gov/developers/request-an-api-key)
   passed as `-DnvdApiKey=$NVD_API_KEY` — drops the update from "VERY long"
   to a few minutes, and avoids rate-limiting.
2. A **persistent dependency-check data dir** cached between CI runs
   (the H2 NVD DB under `~/.m2/repository/org/owasp/dependency-check-data/`,
   or `-DdataDirectory=...`) so only the first run pays the full download;
   subsequent runs do an incremental delta.
3. Outbound HTTPS to `services.nvd.nist.gov` (and the GitHub Security
   Advisory / OSS Index endpoints if those analyzers stay enabled).

### Triage of the dependency surface (from the SBOM, see E-2)
Even though the scan did not complete, here is the third-party surface the
gate-7 scan will evaluate, with usage context so triage is fast when it runs:

Runtime (shipped in the image — highest priority if a CVE lands):
- `org.agrona:agrona:1.23.1`
- `org.jctools:jctools-core:4.0.5`
- `org.hdrhistogram:HdrHistogram:2.2.2`
- `io.micrometer:micrometer-core/commons/observation:1.14.4`
- `org.latencyutils:LatencyUtils:2.0.3` (transitive via micrometer)

Test / benchmark / tooling scope (NOT shipped in the runtime image; a CVE
here is build-time risk only, not a deployed-artifact risk — but NOT
auto-suppressed):
- `org.junit.*:5.11.4 / 1.11.4`, `org.opentest4j:1.3.0`, `org.apiguardian:1.1.2`
- `org.openjdk.jmh:jmh-core / jmh-generator-annprocess:1.37` (optional scope)
- `net.sf.jopt-simple:5.0.4` and `4.6` (two versions — jmh vs jcstress)
- `org.apache.commons:commons-math3:3.6.1` (optional, via jmh)
- `org.openjdk.jcstress:jcstress-core:0.16`
- `net.java.dev.jna:jna / jna-platform:5.8.0` (via jcstress)

Note for the triager: `jna 5.8.0` is the oldest/most likely to carry a
disclosed advisory; it is jcstress/test-tooling scope only.

### EXACT gate-7 CVE command (for Seam 6 — runnable in CI)
```
./mvnw -Pcve-scan org.owasp:dependency-check-maven:aggregate \
  -DfailBuildOnCVSS=7 \
  -Dformats=HTML,JSON,SARIF \
  -DnvdApiKey=$NVD_API_KEY \
  --no-transfer-progress
```
- `aggregate` walks the full reactor (the right goal for a multi-module
  build); `-DfailBuildOnCVSS=7` is also set in the profile so it fails on
  HIGH/CRITICAL even if the flag is dropped.
- Provide `NVD_API_KEY` as a CI secret and cache the dependency-check data
  dir between runs (see ENV-BLOCK need above).
- This is a REAL command (the plugin and profile resolve and start; only
  the NVD download is what's blocked locally).

---

## E-2. SBOM — CycloneDX, committed + regenerated in CI

### Committed path
`docs/session-7/sbom/bom.json` — CycloneDX 1.6, 37 components, 38
dependency nodes (the full reactor dependency graph).

NOTE / finding: the SBOM was generated with the FULL reactor, NOT with the
`-N` (non-recursive) flag that `release.yml` currently uses. `-N
makeAggregateBom` on this aggregator root produces a bom with **0
components** (the root pom has no real runtime deps and `-N` skips the
modules). The committed SBOM here is the useful one. See "SBOM finding"
below — `release.yml`'s `-N` should be reconsidered (separate from my
no-edit-ci constraint; flagged for the lead).

### EXACT regeneration command (for gate-7 / CI)
```
./mvnw -DskipTests --no-transfer-progress \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.0:makeAggregateBom
# output: target/bom.json
```
(Version 2.9.0 matches `release.yml`. `-DskipTests` avoids the test run;
the goal only needs the resolved reactor model.)

### Gate behaviour recommendation: DIFF, don't just regenerate
gate-7 should **regenerate and DIFF against the committed SBOM** to catch
dependency drift (a new transitive dep added without updating the committed
SBOM). Suggested gate step:
```
./mvnw -DskipTests --no-transfer-progress \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.0:makeAggregateBom
# normalize the volatile serialNumber + timestamp, then diff
jq 'del(.serialNumber, .metadata.timestamp)' target/bom.json > /tmp/fresh.bom
jq 'del(.serialNumber, .metadata.timestamp)' docs/session-7/sbom/bom.json > /tmp/committed.bom
diff /tmp/fresh.bom /tmp/committed.bom
```
The `jq del` strips the per-run UUID `serialNumber` and the build
`timestamp`, which change every run and would otherwise make the diff
always fail. Fail the gate if the component/dependency sets differ.

Committed bom.json sha256 (for reference; not stable across runs because of
serialNumber/timestamp — use the normalized diff above for the gate):
`e6848a08e6271c04552de0ec647daf704c4819e5d55bcbca02eefebd4f483a64`

---

## E-3. Build reproducibility — PROVEN byte-identical

### What was added (pom.xml)
`<project.build.outputTimestamp>2026-06-14T00:00:00Z</project.build.outputTimestamp>`
in root `<properties>` — a FIXED value (not a date function). This makes
maven-jar-plugin (pinned 3.4.2, F-0056) stamp every JAR entry mtime
deterministically.

### Proof
Two `./mvnw -q -DskipTests clean package` builds from clean, all 31 produced
jars captured and `sha256sum`-compared:

```
diff <(sha256sum run-A jars) <(sha256sum run-B jars)  →  EMPTY
→ ALL 31 JARS BYTE-IDENTICAL across the two clean builds
```

Confirmed the timestamp is actually baked in: jar entries show
`2026-06-14 00:00`, and `META-INF/MANIFEST.MF` carries stable
`Created-By: Maven JAR Plugin 3.4.2` / `Build-Jdk-Spec: 25` (no volatile
build-host fields).

Main-module jar sha256s (run A == run B):
```
5381ff45e0762b6c809e870ab3446f4d87cb1e6403c8043fba4aca6f543edae8  configd-common-0.1.0-SNAPSHOT.jar
1f71b3d3d149e34f3411b2484945e1c1101ad8a4c86adce0a3ba7d56355465ae  configd-consensus-core-0.1.0-SNAPSHOT.jar
9ac995c55406cec1b3385133879f4c3d1085a533652a565726079582dba49b20  configd-config-store-0.1.0-SNAPSHOT.jar
f1a21c81adf18c27dfab41321d20d8247a2e758c202fb9589f74a790242a3c3e  configd-control-plane-api-0.1.0-SNAPSHOT.jar
4c3ee957276a533d01773cb9a65f7e943a0f43987981e7b24ea0ecf0962cabbd  configd-server-0.1.0-SNAPSHOT.jar (shaded)
91bb57902f21be28e7e207726ce05d75a4f64e80378d1e315008f1fbd8edc46b  configd-edge-node-0.1.0-SNAPSHOT.jar (shaded)
```

**Verdict: REPRODUCIBLE.** No residual non-determinism observed in the
jar artifacts on this box.

Caveat (honesty): this proves jar-level reproducibility on a SINGLE host
(same JDK 25 Corretto, same Maven 3.9.9). It does not by itself prove
cross-host / cross-JDK-build reproducibility, nor does it cover the
container image (the Docker base images use floating
`eclipse-temurin:25-*-noble` tags — a separate documented gap, not fixed
here). `SOURCE_DATE_EPOCH` is already passed in `release.yml`'s image
build.

### EXACT gate-7 reproducibility command (for Seam 6)
```
./mvnw -q -DskipTests clean package --no-transfer-progress
find . -path '*/target/*.jar' -not -name '*-sources.jar' | sort \
  | xargs sha256sum > /tmp/run1.sha
./mvnw -q -DskipTests clean package --no-transfer-progress
find . -path '*/target/*.jar' -not -name '*-sources.jar' | sort \
  | xargs sha256sum > /tmp/run2.sha
diff /tmp/run1.sha /tmp/run2.sha   # must be empty
```
(Maven's own `artifact:check-buildplan` / `artifact:compare` is an
alternative but the double-build + sha256 diff is dependency-free.)

---

## E-4. Secret scan (repo + history) — ENV-BLOCKED (gitleaks not installed)

`gitleaks version` → `command not found`. gitleaks is **NOT installed in
this sandbox**. Per the honesty rule I did NOT run a partial scan and I am
**NOT claiming a clean working tree or clean history** — neither was
scanned.

`.gitleaks.toml` exists and is well-formed (built-in ruleset + an allowlist
for target/ outputs, the two committed SHA-256 toolchain pins, and the
cosign/attestation doc strings). It is referenced in a comment as "used by
ci.yml" but is **NOT actually wired into any workflow today** — `ci.yml`
has no secret-scan step. That is the gap gate-7 must close.

**Exact need for S7.5 manifest:** `gitleaks` binary (or the
`gitleaks/gitleaks-action`) available in CI. No network needed beyond the
action download.

### EXACT gate-7 secret-scan commands (for Seam 6)
Working tree + full history, using the repo config, redacted output:
```
# Option A — GitHub Action (preferred; scans full history by default):
#   uses: gitleaks/gitleaks-action@v2
#   env: GITLEAKS_CONFIG: .gitleaks.toml   (GITLEAKS_LICENSE if org-owned)

# Option B — pinned binary, explicit:
gitleaks detect --source . --config .gitleaks.toml --redact \
  --no-banner --exit-code 1 --report-format sarif \
  --report-path gitleaks-report.sarif
```
`gitleaks detect` (without `--no-git`) scans the COMMIT HISTORY; add a
second `gitleaks detect --no-git` pass to also catch un-committed working-
tree changes. `--exit-code 1` fails the gate on any finding.

---

## E-5. Runbook / script credential-handling audit — CLEAN

Audited `ops/runbooks/*` and `ops/scripts/*` (esp. `restore-snapshot.sh`,
`restore-conformance-check.sh`, anything touching secrets / the signing
key), looking for commands that echo/log/print a credential or private key.

Method: grepped for `set -x` / `set -o xtrace`; for `echo|printf|cat|print|log`
of `*TOKEN|*SECRET|*PASSWORD|*PRIVATE_KEY|*CRED|*API_KEY`; and for
`secret|private key|signing key|.pem|id_rsa|bearer|authorization`.

Findings:
- **No `set -x` anywhere** in `ops/` — no inadvertent xtrace dump of a
  secret-bearing command.
- **No echo/printf/cat/log of any credential variable.**
- The only credential touched is `CONFIGD_AUTH_TOKEN`, used in
  `restore-conformance-check.sh` (lines 145–146, 154–155) and in the
  `disaster-recovery.md` / `control-plane-down.md` runbooks. In every case
  it is expanded into a curl argument array
  `-H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}"` — passed as an
  argument, never echoed, logged, or printed. The script's own `log()`
  lines never include the token.
- Signing-key references in `disaster-recovery.md`, `restore-from-snapshot.md`,
  `release.md` concern only the PUBLIC half of the Ed25519/GPG keypair and
  rotation procedure — documentation, no private-key material printed.

**Verdict: CLEAN.** No credential or private-key leak found in the runbooks
or scripts.

Minor hardening note (non-blocking, optional): `restore-conformance-check.sh`
passes the bearer token on the curl command line via an argument array
(`extra=(-H "Authorization: Bearer ...")`). This is safe w.r.t. logging,
but the token is briefly visible in the process argument list (`ps`/`/proc`)
of the curl child. If the threat model includes a local unprivileged
observer on the bastion, prefer `curl --config <(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN")`
or `-H @-` via stdin. Low severity; the bastion is already a trusted host.

---

## Summary of files changed (no commit — left for lead review)
- `pom.xml` — added `project.build.outputTimestamp` (E-3),
  `dependency-check.version` property + `-Pcve-scan` profile (E-1).
- `dependency-check-suppressions.xml` — NEW, empty suppression scaffold (E-1).
- `docs/session-7/sbom/bom.json` — NEW, committed CycloneDX SBOM (E-2).
- `docs/session-7/supply-chain.md` — NEW, this doc.

## ENV-BLOCKED items for the S7.5 manifest
1. **E-1 CVE scan**: needs an `NVD_API_KEY` CI secret + a persistent
   dependency-check data-dir cache + outbound HTTPS to NVD. Command above.
   No scan completed locally; nothing claimed clean.
2. **E-4 secret scan**: needs the `gitleaks` binary / `gitleaks-action` in
   CI. Working tree + git history were NOT scanned locally; nothing claimed
   clean. Commands above.

## Flags for the lead (not my files to edit)
- `.github/workflows/ci.yml` has NO CVE scan and NO secret scan — gate-7
  must add both (commands above).
- `.github/workflows/release.yml` line ~63 uses `-N makeAggregateBom`,
  which yields a 0-component SBOM on this aggregator root. Should drop `-N`
  (use full-reactor aggregate) so the released SBOM is non-empty.
- Docker base images (`docker/Dockerfile.runtime`, `Dockerfile.build`) use
  floating `eclipse-temurin:25-*-noble` tags — not digest-pinned. Known
  reproducibility/supply-chain gap; not fixed here.
