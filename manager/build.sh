#!/bin/bash
# Build mc-criu-manager: one static binary with the mod embedded in it.
#
# The mod jar has to be built first and copied into manager/embedded/, because
# go:embed can only take a file that exists at compile time. Doing it here means
# the binary can never ship an embedded jar older than the source it was built
# from -- the failure mode that cost two modpack runs when build output and
# install location drifted apart.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

echo "== building the mod jar =="
"$ROOT/agent/build-mod.sh" >/dev/null
mkdir -p "$ROOT/manager/embedded"
cp -f "$ROOT/runtime/mc/mods/mc-criu-mod.jar" "$ROOT/manager/embedded/mc-criu-mod.jar"
echo "embedded $(stat -c %s "$ROOT/manager/embedded/mc-criu-mod.jar") bytes of mod jar"

echo "== building the binary =="
cd "$ROOT/manager"
# CGO_ENABLED=0 is what makes it static and portable across glibc and musl.
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$ROOT/out/mc-criu-manager" .
cd "$ROOT"
echo "built $ROOT/out/mc-criu-manager"
file "$ROOT/out/mc-criu-manager" | sed 's/^/  /'
# `ldd` exits NON-ZERO on a static binary ("not a dynamic executable"), and this
# script runs under `set -e`. Left bare it aborted here -- AFTER a successful
# build -- so `build.sh && cp ...` silently never installed, and a stale binary
# was then tested for a whole 25-minute modpack run while the fix sat unused.
ldd "$ROOT/out/mc-criu-manager" 2>&1 | sed 's/^/  ldd: /' || true
