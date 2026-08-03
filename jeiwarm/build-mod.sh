#!/bin/bash
# Build the mc-criu warm-JEI mod jar.
#
# Same approach as agent/build-mod.sh: plain javac against the jars the game will
# actually run against, rather than a Gradle/MDK setup. It additionally needs
# JEI on the classpath, because every injection point is inside JEI, and
# sponge-mixin for the annotations (NeoForge already ships it).
#
# No refmap is generated or declared: NeoForge 1.21 runs on official Mojang
# names, which are the names this compiles against. -proc:none keeps mixin's
# annotation processor from trying to build one and failing on the missing maps.
# (original note) NeoForge 1.21 runs on official Mojang
# names, which are the names this compiles against, so the mixin targets resolve
# as written. A refmap would only matter for an obfuscated runtime.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
MC=$ROOT/runtime/mc
GAME=${JEIWARM_GAME_DIR:-$ROOT/runtime/build-deps}
OUT=$ROOT/out/jeiwarm

CLIENT_JAR=$(ls "$MC"/libraries/net/minecraft/client/*/client-*-srg.jar 2>/dev/null | head -1)
[ -z "$CLIENT_JAR" ] && { echo "no patched client jar under $MC/libraries" >&2; exit 1; }
JEI_JAR=${JEIWARM_JEI_JAR:-}
if [ -z "$JEI_JAR" ]; then
    JEI_JAR=$(find "$GAME/mods" -maxdepth 1 -type f -name 'jei-*.jar' -print 2>/dev/null | sort | head -1)
fi
[ -z "$JEI_JAR" ] && { echo "no JEI jar under $GAME/mods" >&2; exit 1; }
[ ! -f "$JEI_JAR" ] && { echo "JEI jar does not exist: $JEI_JAR" >&2; exit 1; }

CP=$(find "$MC/libraries" -name '*.jar' | tr '\n' ':')
CP="$CP$CLIENT_JAR:$JEI_JAR"

rm -rf "$OUT"; mkdir -p "$OUT/classes"
echo "== compiling jeiwarm against $(basename "$JEI_JAR") =="
mapfile -d '' JEIWARM_SOURCES < <(find "$ROOT/jeiwarm/src" -name '*.java' -print0)
javac -nowarn -proc:none -cp "$CP" -d "$OUT/classes" "${JEIWARM_SOURCES[@]}"

echo "== packaging =="
# Jar straight into the instance's mods folder, the way agent/build-mod.sh does.
# This used to build only into out/ and leave installing to whoever remembered:
# a rebuilt jar then sat in out/ while the game kept loading a months-old copy,
# and a 25-minute modpack run measured the OLD default and reported a regression
# that did not exist. Build output that is not where the game reads it is a trap.
mkdir -p "$GAME/mods"
jar --create --file "$GAME/mods/mc-criu-jei-addon.jar" \
    -C "$OUT/classes" . \
    -C "$ROOT/jeiwarm/resources" .
echo "built $GAME/mods/mc-criu-jei-addon.jar"
unzip -l "$GAME/mods/mc-criu-jei-addon.jar" | tail -8
