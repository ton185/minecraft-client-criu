#!/bin/bash
# Build the mc-criu NeoForge mod jar.
#
# Compiled with plain javac against the provisioned instance's own jars rather
# than through a NeoForge MDK/Gradle setup: the mod is a handful of classes with
# no build-time code generation, and depending on the exact jars the game will
# actually run against removes a whole class of version-skew problems.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
MC=$ROOT/runtime/mc
OUT=$ROOT/out/mod

CLIENT_JAR=$(ls "$MC"/libraries/net/minecraft/client/*/client-*-srg.jar 2>/dev/null | head -1)
[ -z "$CLIENT_JAR" ] && { echo "no patched client jar under $MC/libraries; run provisioning first" >&2; exit 1; }

# Everything NeoForge puts on the game classpath, plus the client jar itself.
CP=$(find "$MC/libraries" -name '*.jar' | tr '\n' ':')
# Deliberately NOT including $ROOT/out/classes: this script compiles core itself,
# and a stale copy there would shadow the fresh classes.
CP="$CP$CLIENT_JAR"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "== compiling core (shipped inside the mod jar) =="
mapfile -d '' CORE_SOURCES < <(find "$ROOT/core/src" -name '*.java' -print0)
javac -nowarn -cp "$CP" -d "$OUT/classes" "${CORE_SOURCES[@]}"

echo "== compiling mod =="
# The mixin is written against official runtime names and needs no refmap.
# Suppress Mixin's annotation processor just as the JEI addon build does.
mapfile -d '' AGENT_SOURCES < <(find "$ROOT/agent/src" -name '*.java' -print0)
javac -nowarn -proc:none -cp "$CP:$OUT/classes" -d "$OUT/classes" "${AGENT_SOURCES[@]}"

echo "== packaging =="
mkdir -p "$MC/mods"
# Build straight from both roots rather than copying into a staging directory.
# `cp -r` recreates directories at mode 0755, which on a checkout carrying
# default ACLs narrows the ACL mask to r-x and leaves a second user unable to
# delete the build output. Fewer moving parts, and no permissions to get wrong.
jar --create --file "$MC/mods/mc-criu-mod.jar" \
    -C "$OUT/classes" . \
    -C "$ROOT/agent/resources" .

echo "built $MC/mods/mc-criu-mod.jar"
unzip -l "$MC/mods/mc-criu-mod.jar" | tail -5

# Drop the pre-1.0 name if it is still lying about: two jars carrying the same
# mod id is a duplicate-mod crash, and the manager now installs as
# mc-criu-mod.jar everywhere.
find "$ROOT/runtime" -name 'mccriu-0.1.0.jar' -delete 2>/dev/null || true

# Refresh every OTHER game directory that already carries this mod.
#
# runtime/atm10 is a second instance with its own mods/ folder, and it had a
# hand-copied jar that this script never touched. It went stale by two days, so a
# 25-minute modpack run exercised code that had not been rebuilt and reported a
# feature as not working when it had simply never been installed. That happened
# twice in one session, once here and once for the JEI addon.
#
# Only instances that ALREADY have the jar are updated: this refreshes existing
# installs, it does not decide to install anywhere new.
for other in "$ROOT"/runtime/*/mods/mc-criu-mod.jar; do
    [ -e "$other" ] || continue
    [ "$other" -ef "$MC/mods/mc-criu-mod.jar" ] && continue
    cp -f "$MC/mods/mc-criu-mod.jar" "$other"
    echo "refreshed $other"
done
