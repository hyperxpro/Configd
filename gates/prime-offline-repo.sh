#!/usr/bin/env bash
# prime-offline-repo.sh — make the local repository complete enough for an offline test run.
#
# The gates install their modules with tests skipped, then run targeted tests offline so the
# run is pinned to the jars just installed. But surefire chooses its test-framework provider
# when the test phase actually executes, and downloads it at that moment. An install that
# skips tests therefore never fetches the provider, and the offline run that needs it cannot.
# On a cold local repository the gate dies on surefire-junit-platform — an artifact with
# nothing to do with whatever the gate is sealing. It only ever worked because some earlier
# online test run had left the provider behind.
#
# Fetching those artifacts by name does not work. Surefire resolves the provider rooted at
# the provider itself, so the junit-platform version the provider's own POM declares wins,
# not the one this project builds against and not the one dependency mediation picks. The
# resolution is also not stable across surefire releases. So this primes the only way that
# cannot drift: run surefire online, for real, once. configd-wire is small, its tests are
# pure codec tests, and the junit version is reactor-wide, so what it pulls is what every
# other module will ask for.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "prime-offline-repo: online surefire run so the offline runs can resolve its provider..."
"$ROOT/mvnw" -B -q -pl configd-wire test \
  || { echo "prime-offline-repo: the priming test run FAILED — the offline runs below would fail on a missing surefire provider" >&2; exit 1; }
echo "prime-offline-repo: OK (surefire provider resolved into the local repository)"
