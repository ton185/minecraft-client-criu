#!/bin/bash
# Assemble out/mc-criu.zip: the binary, the jars, install-nonroot.sh and the
# README users actually get.
#
# Deliberately does NOT run ./build.sh, which starts with `rm -rf out` and would
# delete what this is collecting. It calls the per-artefact builds instead, each
# of which writes only its own output.
#
# What is REQUIRED is checked and missing pieces abort the packaging: an archive
# that is quietly missing the mod jar is worse than no archive. The JEI addon is
# the one optional piece -- it compiles against a real instance's JEI jar, so a
# machine without one cannot build it -- and its absence is reported in the
# output and in the archive's own MANIFEST rather than passed over.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
STAGE=$ROOT/out/dist/mc-criu
ZIP=$ROOT/out/mc-criu.zip

command -v zip >/dev/null || { echo "zip is not installed" >&2; exit 1; }

rm -rf "$ROOT/out/dist" "$ZIP"
mkdir -p "$STAGE"

echo "== manager (builds and embeds the mod jar) =="
"$ROOT/manager/build.sh" | sed 's/^/   /'

echo "== wrapper javaagent =="
"$ROOT/wrapper/build-jar.sh" | sed 's/^/   /'

echo "== JEI addon (optional) =="
JEI_NOTE="mc-criu-jei-addon.jar   the warm JEI index"
if "$ROOT/jeiwarm/build-mod.sh" > "$ROOT/out/dist/jeiwarm-build.log" 2>&1; then
    GAME=${JEIWARM_GAME_DIR:-$ROOT/runtime/build-deps}
    cp "$GAME/mods/mc-criu-jei-addon.jar" "$STAGE/"
    echo "   built from $GAME"
else
    JEI_NOTE="mc-criu-jei-addon.jar   NOT INCLUDED -- it compiles against a real
                          instance's JEI jar and none was available on the
                          machine that built this archive"
    echo "   SKIPPED: no instance to compile against."
    echo "   $(tail -1 "$ROOT/out/dist/jeiwarm-build.log")"
    echo "   The archive will say so; everything else is unaffected."
fi

echo "== collecting =="
cp "$ROOT/out/mc-criu-manager"        "$STAGE/"
cp "$ROOT/runtime/mc/mods/mc-criu-mod.jar" "$STAGE/"
cp "$ROOT/out/mc-criu-wrapper.jar"    "$STAGE/"
cp "$ROOT/supervisor/install-nonroot.sh" "$STAGE/"
if [ -f "$ROOT/USER-README.md" ]; then
    RELEASE_README=$ROOT/USER-README.md
else
    RELEASE_README=$ROOT/README.md
fi
cp "$RELEASE_README"                  "$STAGE/README.md"
cp "$ROOT/LICENSE"                    "$STAGE/"
chmod +x "$STAGE/mc-criu-manager" "$STAGE/install-nonroot.sh"

# Every required piece, named, so a truncated archive cannot ship quietly.
for f in mc-criu-manager mc-criu-mod.jar mc-criu-wrapper.jar install-nonroot.sh README.md LICENSE; do
    [ -s "$STAGE/$f" ] || { echo "MISSING from the archive: $f" >&2; exit 1; }
done

# Hashed before MANIFEST.txt exists, and excluding it: a manifest that lists a
# hash for itself lists one that cannot match, and a checksum that always fails
# teaches people to ignore checksums.
SUMS=$(cd "$STAGE" && sha256sum -- * | sed 's/^/  /')
SOURCE_REV=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
if [ "$SOURCE_REV" != unknown ] && [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
    SOURCE_REV="$SOURCE_REV-dirty"
fi

{
    printf 'mc-criu\n'
    printf 'built   %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'commit  %s\n' "$SOURCE_REV"
    printf 'version %s\n\n' "$("$ROOT/out/mc-criu-manager" version)"
    printf 'contents\n'
    printf '  mc-criu-manager         the whole thing; needs criu on PATH\n'
    printf '  mc-criu-mod.jar         the in-game mod (already embedded in the binary)\n'
    printf '  mc-criu-wrapper.jar     javaagent, only for launchers with no wrapper slot\n'
    printf '  %s\n' "$JEI_NOTE"
    printf '  install-nonroot.sh      one-time root setup for unprivileged use\n'
    printf '  README.md               start here\n'
    printf '  LICENSE                 BSD-3-Clause license\n\n'
    printf 'sha256 (of everything except this file)\n'
    printf '%s\n' "$SUMS"
} > "$STAGE/MANIFEST.txt"

(cd "$ROOT/out/dist" && zip -q -r "$ZIP" mc-criu)
echo
echo "built $ZIP"
unzip -l "$ZIP" | sed 's/^/   /'
