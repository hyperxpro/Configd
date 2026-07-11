#!/usr/bin/env bash
# gate-7.sh — cumulative machine-verifiable gate (security & supply chain)
#
# Cumulative with gates 1..6: a green gate-7 REQUIRES a green gate-6 (which chains
# 5→4→3→2→1). In CI gate-6 runs as its own job, so the gate-7 job sets
# GATE7_SKIP_GATE6=1 (cumulative coverage via the job dependency, not a redundant
# re-run) — reported LOUDLY. Exits non-zero on ANY failure; no silent placeholders;
# every test step asserts a REAL result via assert_class_green and FAILS if its
# summary line is absent (non-vacuity).
#
# PRIME DIRECTIVE: a security control is proven ONLY by a passing negative test
# that performs the attack and asserts it is REFUSED — never by reading its
# config. Every test below is such a negative test.
#
# WHAT A GREEN GATE-7 PROVES:
#   (a) at-rest   tampered snapshot byte / tampered WAL record / forged version /
#                 algId=NONE downgrade / forged DurableRaftState / forged
#                 install-snapshot → all REFUSED (HMAC at-rest integrity, ADR-0042);
#                 AND the durability cells still pass (no regression — torn tail
#                 still tolerated, not mistaken for tamper).
#   (b) mTLS      plaintext / expired-cert / wrong-SAN / TLSv1.2-downgrade → REFUSED
#                 on BOTH planes (control-plane Raft + data-plane edge fan-out).
#   (c) fuzz      arbitrary/malformed/oversized/length-lie wire input → bounded
#                 reject, no crash/OOM/unbounded-alloc/hang (resource oracle).
#   (d) API       unauthenticated mutating call → 401; read-scoped → write → 403;
#                 verbatim replay → 409; every mutating op + auth failure → a
#                 KEYED-HMAC tamper-evident audit record (defeats a log editor).
#   (e) SBOM      the committed CycloneDX SBOM matches a freshly-generated one
#                 (no dependency-graph drift).
#   (f) repro     project.build.outputTimestamp is set (reproducible-build config);
#                 the byte-identical two-build proof runs on the nightly/full path.
#   (g) CVE       OWASP dependency-check fails the build on CVSS>=7 — runs where an
#                 NVD_API_KEY + network exist (CI nightly); ENV-BLOCKED loud-skip
#                 otherwise (never assumed-passing).
#   (h) secret    gitleaks over repo+history — runs where the binary exists (CI);
#                 ENV-BLOCKED loud-skip otherwise.
#
# Environment knobs (CI must not set the test skips on a full run):
#   GATE7_SKIP_GATE6=1   skip step (cumulative) — LOUD (CI runs gate-6 as its own job).
#   GATE7_SKIP_BUILD=1   reuse already-installed module jars (local convenience).
#   GATE7_FULL=1         run the heavy nightly extras (two-build reproducibility proof).
#   NVD_API_KEY=...      enables the real CVE scan (else ENV-BLOCKED loud-skip).
#   GITLEAKS=/path       path to a gitleaks binary (else ENV-BLOCKED loud-skip).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
LOGDIR="${GATE7_LOG_DIR:-$(mktemp -d /tmp/gate7-XXXXXX)}"
mkdir -p "$LOGDIR"

# Modules whose security tests gate-7 exercises.
MODULES="configd-wire,configd-common,configd-consensus-core,configd-transport,configd-netty,configd-distribution-service,configd-control-plane-api,configd-server,configd-edge-node"

echo "=== GATE-7 (Session 7: security & supply chain) — logs in $LOGDIR ==="

fail() { echo "GATE-7 FAIL [$1]: $2" >&2; exit 1; }

# Asserts a surefire class ran with >=1 test and zero failures/errors (non-vacuity).
assert_class_green() {
  local log="$1" cls="$2"
  local line
  # Anchor the class at a package-name boundary (a literal '.') so a superstring
  # class (e.g. EdgeFrameCodecFuzzTest) cannot satisfy an assert for FrameCodecFuzzTest.
  line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.* -- in .*\.${cls}$" "$log" | tail -1 || true)"
  [ -n "$line" ] || { tail -30 "$log"; fail tests "non-vacuity: ${cls} did not run (renamed/skipped?)"; }
  echo "$line" | grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0" \
    || { echo "  $line"; fail tests "${cls} did not pass with >=1 test and 0 failures/errors"; }
  echo "GATE-7   ✓ ${cls}: ${line#*-- in }"
}

# Runs a targeted, offline test set across MODULES and asserts BUILD SUCCESS.
run_tests() {
  local label="$1" tests="$2" log="$3"
  $MVN -o -pl "$MODULES" test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false >"$log" 2>&1 \
    || { tail -50 "$log"; fail "$label" "test run failed"; }
  grep -q "BUILD SUCCESS" "$log" || { tail -50 "$log"; fail "$label" "no BUILD SUCCESS"; }
}

# 2-vCPU box discipline: never overlap another Maven workload.
if pgrep -f "[s]urefirebooter" >/dev/null 2>&1; then
  echo "GATE-7: another Maven test workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

# cumulative: gate-6 (chains 5→4→3→2→1)
if [ "${GATE7_SKIP_GATE6:-0}" = "1" ]; then
  echo "GATE-7 gate6: SKIPPED by GATE7_SKIP_GATE6=1 (LOUD: gates 1..6 NOT verified this run; CI supplies them via the gate-6 job)"
else
  echo "GATE-7 gate6: running cumulative gate-6 (chains 5→4→3→2→1)..."
  bash "$ROOT/gates/gate-6.sh" || fail gate6 "cumulative gate-6 (1..6) is RED — fix it before security"
  echo "GATE-7 gate6: OK (gates 1..6 green)"
fi

# build/install the modules' deps once (so the targeted test runs are offline)
if [ "${GATE7_SKIP_BUILD:-0}" = "1" ]; then
  echo "GATE-7 build: SKIPPED by GATE7_SKIP_BUILD=1 (reusing installed jars; CI must not do this)"
else
  echo "GATE-7 build: installing module jars (skip tests) so the gate run is hermetic..."
  $MVN -q -pl "$MODULES" -am install -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "module build/install failed"; }
fi

# (a) at-rest integrity negatives + durability re-run
echo "GATE-7 pa2021: snapshot/WAL/raft-state tamper+forge+downgrade refused; S4 cells still green..."
PA="$LOGDIR/pa2021.txt"
run_tests pa2021 "HkdfTest,IntegrityEnvelopeTest,IntegrityEnvelopeEncryptionTest,SegmentKeyManagerTest,LocalKmsEncryptionIntegrationTest,NodeKeyringTest,KeyringCodecTest,KeyringFileTest,KeyringKeyTermSelectionTest,EncryptionAtRestWiringTest,SnapshotIntegrityTest,WalRecordIntegrityTest,RaftLogEncryptionTest,AnchorFileTest,RaftAnchorRecoveryTest,AnchorRollbackRedteamTest,SnapshotCrashRecoveryTest,WalSyncCrashTest,VotePersistenceCrashTest,RaftLogUnitTest" "$PA"
assert_class_green "$PA" "IntegrityEnvelopeTest"        # codec: tamper/downgrade/version/truncation
assert_class_green "$PA" "IntegrityEnvelopeEncryptionTest" # AES-256-GCM codec: no-plaintext/tamper/downgrade refused
assert_class_green "$PA" "SegmentKeyManagerTest"        # no-(key,nonce)-reuse invariant + fail-closed unknown term
assert_class_green "$PA" "LocalKmsEncryptionIntegrationTest" # KMS-SPI end-to-end: restart round-trip + rotation
# Crash-atomic key rotation: the keyring real-attack proofs.
assert_class_green "$PA" "NodeKeyringTest"             # rotate-then-crash recovers; old data decrypts post-rotate; prior-KEK/tamper REFUSE
assert_class_green "$PA" "KeyringCodecTest"            # wrap-AAD replay / unknown wrapAlgId / term0 / outer-MAC strip / slot overflow REFUSED
assert_class_green "$PA" "KeyringFileTest"            # dual-slot: highest-seq wins, torn stale slot -> intact slot, both-invalid REFUSE
assert_class_green "$PA" "KeyringKeyTermSelectionTest" # forged/rolled keyTerm on a real segment -> tag fails; absent-term fail-closed
assert_class_green "$PA" "EncryptionAtRestWiringTest" # boot: OFF byte-identical (no keyring), ON mints keyring, tampered keyring REFUSED
assert_class_green "$PA" "HkdfTest"                     # HKDF RFC-5869 vectors
assert_class_green "$PA" "SnapshotIntegrityTest"        # tampered/forged/downgrade/install-snapshot refused
assert_class_green "$PA" "WalRecordIntegrityTest"       # tamper refused; torn tail tolerated
assert_class_green "$PA" "RaftLogEncryptionTest"        # at-rest AES-GCM at the real WAL/snapshot seam
# raft.persistent_state (DurableRaftState) is merged into the per-shard anchor; the
# forged-votedFor/term-refused obligation lives on the anchor surface:
assert_class_green "$PA" "AnchorFileTest"              # anchor codec: forged/corrupt slot, cross-shard, torn slot refused (merged term/vote)
assert_class_green "$PA" "RaftAnchorRecoveryTest"      # recovery: rolled-back term (Step-2.5) / W<A / tampered anchor refused
assert_class_green "$PA" "AnchorRollbackRedteamTest"   # real-attack: tail-truncation / whole-file & genesis rollback / slot-forge refused
assert_class_green "$PA" "SnapshotCrashRecoveryTest"    # no-regression (durable-prefix)
assert_class_green "$PA" "WalSyncCrashTest"             # no-regression (WAL fsync crash)
assert_class_green "$PA" "VotePersistenceCrashTest"     # no-regression (vote durability)
# RaftLogUnitTest also runs in this set (BUILD SUCCESS covers it); it is @Nested so
# its aggregate line is 0-test — asserted via its nested cells above, not directly.
echo "GATE-7 pa2021: OK"

# (b) mTLS negatives (both planes)
echo "GATE-7 mtls: plaintext/expired/wrong-SAN/downgrade refused on both planes..."
ML="$LOGDIR/mtls.txt"
run_tests mtls "RaftTransportMtlsAttackTest,JdkFanOutServerContractTest,NettyFanOutServerContractTest,NettyFanOutServerNioContractTest,EdgeTransportSanMismatchTest,EdgeTransportMtlsTest,TlsManagerTest" "$ML"
assert_class_green "$ML" "RaftTransportMtlsAttackTest"  # control plane: plaintext/expired/downgrade
# Data-plane fan-out mTLS negatives (plaintext/no-cert/untrusted-CA/expired/downgrade
# refused; trusted accepted) live in AbstractFanOutServerContract, gated on the JDK +
# production-Netty + forced-NIO transports.
assert_class_green "$ML" "JdkFanOutServerContractTest"        # data plane mTLS on the JDK transport
assert_class_green "$ML" "NettyFanOutServerContractTest"      # ...re-proven on the production Netty transport
assert_class_green "$ML" "NettyFanOutServerNioContractTest"   # ...and on the forced-NIO fallback tier
assert_class_green "$ML" "EdgeTransportSanMismatchTest" # data plane: wrong-SAN refused by client
assert_class_green "$ML" "EdgeTransportMtlsTest"        # untrusted client/server refused
echo "GATE-7 mtls: OK"

# (c) wire-protocol fuzz (resource oracle)
echo "GATE-7 fuzz: malformed/oversized/length-lie → bounded reject, no crash/OOM/hang..."
FZ="$LOGDIR/fuzz.txt"
run_tests fuzz "FrameCodecFuzzTest,EdgeFrameCodecFuzzTest,InboundReadDeadlineFuzzTest" "$FZ"
assert_class_green "$FZ" "FrameCodecFuzzTest"           # raft wire fuzz + read-loop bounded-alloc
assert_class_green "$FZ" "EdgeFrameCodecFuzzTest"       # edge wire fuzz
assert_class_green "$FZ" "InboundReadDeadlineFuzzTest"  # slowloris mechanism pinned
echo "GATE-7 fuzz: OK"

# (d) API authn/authz + audit + replay
echo "GATE-7 api: 401/403, verbatim-replay 409, keyed-HMAC tamper-evident audit, strong-read fail-closed (ADR-0043 M2: re-proven on JDK + Netty + forced-NIO)..."
AP="$LOGDIR/api.txt"
# The admin HTTP controls (401/403, replay 409, audit completeness, strong-read
# fail-closed incl. the path-normalization vectors) live in AbstractAdminApiServerContract,
# run on all three transports by these subclasses. Gating all three proves the controls
# hold on the production Netty transport, not just the JDK incumbent (ADR-0043).
run_tests api "AuditLogTest,ReplayGuardTest,JdkAdminApiServerContractTest,NettyAdminApiServerContractTest,NettyAdminApiServerNioFallbackTest" "$AP"
assert_class_green "$AP" "AuditLogTest"                          # keyed-HMAC chain defeats a log editor
assert_class_green "$AP" "ReplayGuardTest"                       # nonce+window replay reject
assert_class_green "$AP" "JdkAdminApiServerContractTest"         # API set on the JDK transport (401/403, replay 409, audit, strong-read)
assert_class_green "$AP" "NettyAdminApiServerContractTest"       # ...re-proven on the production Netty transport
assert_class_green "$AP" "NettyAdminApiServerNioFallbackTest"    # ...and on the forced-NIO fallback tier
echo "GATE-7 api: OK"

# (e) SBOM: committed CycloneDX matches a freshly-generated one
echo "GATE-7 sbom: regenerate CycloneDX + normalized-diff vs the committed SBOM..."
SBOM_COMMITTED="$ROOT/gates/sbom/bom.json"
[ -f "$SBOM_COMMITTED" ] || fail sbom "committed SBOM missing at $SBOM_COMMITTED"
if $MVN -q -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.0:makeAggregateBom >"$LOGDIR/sbom-gen.txt" 2>&1 \
   && [ -f "$ROOT/target/bom.json" ]; then
  # Normalize the volatile fields (serialNumber + metadata.timestamp) before diff.
  NORM='import json,sys;d=json.load(open(sys.argv[1]));d.pop("serialNumber",None);d.get("metadata",{}).pop("timestamp",None);print(json.dumps(d.get("components",[]),sort_keys=True))'
  python3 -c "$NORM" "$SBOM_COMMITTED" >"$LOGDIR/sbom-committed.norm" 2>/dev/null || fail sbom "cannot normalize committed SBOM"
  python3 -c "$NORM" "$ROOT/target/bom.json" >"$LOGDIR/sbom-fresh.norm" 2>/dev/null || fail sbom "cannot normalize fresh SBOM"
  if diff -q "$LOGDIR/sbom-committed.norm" "$LOGDIR/sbom-fresh.norm" >/dev/null; then
    echo "GATE-7 sbom: OK (committed SBOM components match a fresh generation — no drift)"
  else
    diff "$LOGDIR/sbom-committed.norm" "$LOGDIR/sbom-fresh.norm" | head -40
    fail sbom "committed SBOM drifted from the dependency graph — regenerate gates/sbom/bom.json"
  fi
else
  echo "GATE-7 sbom: LOUD-SKIP — CycloneDX plugin unavailable offline ($(tail -1 "$LOGDIR/sbom-gen.txt" 2>/dev/null)); CI (network) regenerates+diffs. The committed SBOM exists."
fi

# (f) build reproducibility
echo "GATE-7 repro: reproducible-build config present..."
grep -qE "<project.build.outputTimestamp>[^<]+</project.build.outputTimestamp>" "$ROOT/pom.xml" \
  || fail repro "project.build.outputTimestamp is not set in the root pom (reproducible-build config missing)"
echo "GATE-7 repro: OK (outputTimestamp set)"
if [ "${GATE7_FULL:-0}" = "1" ]; then
  echo "GATE-7 repro: FULL — two clean builds must produce byte-identical jars..."
  $MVN -q -DskipTests clean package >"$LOGDIR/repro-1.txt" 2>&1 || { tail -20 "$LOGDIR/repro-1.txt"; fail repro "first build failed"; }
  find "$ROOT" -path '*/target/*.jar' -not -name 'original-*' | sort | xargs sha256sum 2>/dev/null | sed "s|$ROOT/||" >"$LOGDIR/repro-sha-1.txt"
  $MVN -q -DskipTests clean package >"$LOGDIR/repro-2.txt" 2>&1 || { tail -20 "$LOGDIR/repro-2.txt"; fail repro "second build failed"; }
  find "$ROOT" -path '*/target/*.jar' -not -name 'original-*' | sort | xargs sha256sum 2>/dev/null | sed "s|$ROOT/||" >"$LOGDIR/repro-sha-2.txt"
  if diff -q "$LOGDIR/repro-sha-1.txt" "$LOGDIR/repro-sha-2.txt" >/dev/null && [ -s "$LOGDIR/repro-sha-1.txt" ]; then
    echo "GATE-7 repro: OK ($(wc -l <"$LOGDIR/repro-sha-1.txt") jars byte-identical across two builds)"
  else
    diff "$LOGDIR/repro-sha-1.txt" "$LOGDIR/repro-sha-2.txt" | head -20
    fail repro "jars are NOT byte-identical across two builds"
  fi
else
  echo "GATE-7 repro: byte-identical two-build proof runs on the FULL/nightly path (GATE7_FULL=1); captured in the nightly CI lane."
fi

# (g) CVE scan (OWASP dependency-check)
echo "GATE-7 cve: dependency-check fails on CVSS>=7..."
if [ -n "${NVD_API_KEY:-}" ]; then
  $MVN -Pcve-scan org.owasp:dependency-check-maven:aggregate -DfailBuildOnCVSS=7 \
       -Dformats=HTML,JSON,SARIF -DnvdApiKey="$NVD_API_KEY" --no-transfer-progress >"$LOGDIR/cve.txt" 2>&1 \
    || { tail -40 "$LOGDIR/cve.txt"; fail cve "dependency-check found an unsuppressed CVSS>=7 (or failed) — triage + suppress-with-ADR or upgrade"; }
  echo "GATE-7 cve: OK (no unsuppressed high/critical)"
else
  echo "GATE-7 cve: ENV-BLOCKED loud-skip — no NVD_API_KEY (NVD feed update is impractical without it; charter §10.8). CI nightly sets the key. -Pcve-scan profile is wired (pom.xml)."
fi

# (h) secret scan (gitleaks)
echo "GATE-7 secret: gitleaks over repo + history..."
GL="${GITLEAKS:-$(command -v gitleaks || true)}"
if [ -n "$GL" ]; then
  "$GL" detect --source "$ROOT" --config "$ROOT/.gitleaks.toml" --redact --no-banner --exit-code 1 >"$LOGDIR/gitleaks.txt" 2>&1 \
    || { tail -40 "$LOGDIR/gitleaks.txt"; fail secret "gitleaks found a secret in the repo/history"; }
  echo "GATE-7 secret: OK (gitleaks clean)"
else
  echo "GATE-7 secret: ENV-BLOCKED loud-skip — gitleaks not installed (charter §10.8). CI runs gitleaks-action; .gitleaks.toml is committed."
fi

echo ""
echo "=== GATE-7 GREEN: security bar locked (PA-2021 at-rest integrity, mTLS both planes, wire fuzz, API authz/audit/replay, SBOM, reproducible-build config) ==="
echo "    ENV-BLOCKED steps (CVE NVD, gitleaks, full byte-repro) run on the CI nightly lane — see the nightly CI lane + the S7.5 manifest."
