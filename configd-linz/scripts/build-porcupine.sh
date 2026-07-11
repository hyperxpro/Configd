#!/usr/bin/env bash
# Build the trusted Porcupine checker binary. Installs a user-local
# Go toolchain if none is on PATH (no sudo), then builds the ~120-line Go main
# that calls anishathalye/porcupine into configd-linz/bin/porcupine-check.
#
# Porcupine is Go and Go is not a Java build dependency, so this is run out of
# band of Maven. The Go module (go.mod/go.sum) pins the porcupine version for a
# reproducible checker. After running, export PORCUPINE_BIN to the printed path.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$HERE/src/main/go/porcupine-check"
BIN_DIR="$HERE/bin"
mkdir -p "$BIN_DIR"

if command -v go >/dev/null 2>&1; then
  GO=go
elif [ -x "$HOME/sdk/go/bin/go" ]; then
  GO="$HOME/sdk/go/bin/go"
else
  echo "Go not found; installing a user-local Go toolchain to ~/sdk/go ..."
  mkdir -p "$HOME/sdk"
  GOVER="$(curl -fsSL https://go.dev/VERSION?m=text | head -1)"
  ARCH="$(uname -m)"; case "$ARCH" in x86_64) GOARCH=amd64;; aarch64|arm64) GOARCH=arm64;; *) echo "unsupported arch $ARCH"; exit 1;; esac
  curl -fsSL -o /tmp/go.tgz "https://go.dev/dl/${GOVER}.linux-${GOARCH}.tar.gz"
  rm -rf "$HOME/sdk/go"; tar -C "$HOME/sdk" -xzf /tmp/go.tgz
  GO="$HOME/sdk/go/bin/go"
fi

export GOTOOLCHAIN=local   # use the installed toolchain; never auto-download another
echo "Using $($GO version)"
"$GO" -C "$GO_DIR" build -o "$BIN_DIR/porcupine-check" .
echo "built: $BIN_DIR/porcupine-check"
echo "export PORCUPINE_BIN=$BIN_DIR/porcupine-check"
