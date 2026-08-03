#!/bin/bash
# Build mc-criu-wrapper.jar: the javaagent that relaunches the game under
# `mc-criu-manager auto`, for launchers with no wrapper-command option.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=$ROOT/out/wrapper
rm -rf "$OUT"; mkdir -p "$OUT/classes"

mapfile -d '' WRAPPER_SOURCES < <(find "$ROOT/wrapper/src" -name '*.java' -print0)
javac -nowarn -d "$OUT/classes" "${WRAPPER_SOURCES[@]}"

mkdir -p "$OUT/META-INF"
cat > "$OUT/META-INF/MANIFEST.MF" <<'MF'
Manifest-Version: 1.0
Premain-Class: mccriu.wrapper.Wrapper
Agent-Class: mccriu.wrapper.Wrapper
Can-Retransform-Classes: false
MF

jar --create --file "$ROOT/out/mc-criu-wrapper.jar" \
    --manifest "$OUT/META-INF/MANIFEST.MF" \
    -C "$OUT/classes" .
echo "built $ROOT/out/mc-criu-wrapper.jar"
unzip -l "$ROOT/out/mc-criu-wrapper.jar" | tail -5
