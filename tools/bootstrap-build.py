#!/usr/bin/env python3
"""Fetch the pinned third-party inputs needed to compile mc-criu.

This intentionally provisions only a compiler classpath. It does not download
Minecraft assets, launch a client, install a test modpack, or run tests.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / "runtime"
GAME = RUNTIME / "mc"
DOWNLOADS = RUNTIME / "build-deps" / "downloads"
JEI_MODS = RUNTIME / "build-deps" / "mods"

MC_VERSION = "1.21.1"
MC_METADATA_URL = (
    "https://piston-meta.mojang.com/v1/packages/"
    "6d257dcfa9d74cdd9a83b4f5984674004decfa81/1.21.1.json"
)
MC_METADATA_SHA1 = "6d257dcfa9d74cdd9a83b4f5984674004decfa81"
MC_CLIENT_SHA1 = "30c73b1c5da787909b2f73340419fdf13b9def88"

NEOFORGE_VERSION = "21.1.244"
NEOFORGE_INSTALLER_URL = (
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.244/"
    "neoforge-21.1.244-installer.jar"
)
NEOFORGE_INSTALLER_SHA256 = (
    "ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8"
)

JEI_VERSION = "19.27.0.343"
JEI_FILENAME = "jei-1.21.1-neoforge-19.27.0.343.jar"
JEI_URL = (
    "https://cdn.modrinth.com/data/u6dRKJwZ/versions/iiCpE7cU/"
    + JEI_FILENAME
)
JEI_SHA1 = "de304e36e94ff54997d62ee881c904a4892fd6dc"
JEI_SIZE = 1_529_892

USER_AGENT = "mc-criu build bootstrap/1.0"


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def valid(path: Path, algorithm: str, expected: str, size: int | None) -> bool:
    return (
        path.is_file()
        and (size is None or path.stat().st_size == size)
        and digest(path, algorithm) == expected
    )


def download(
    url: str,
    destination: Path,
    algorithm: str,
    expected: str,
    size: int | None = None,
) -> None:
    if valid(destination, algorithm, expected, size):
        print(f"  cached {destination.relative_to(ROOT)}")
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    print(f"  download {url}")
    last_error: Exception | None = None
    for attempt in range(1, 5):
        partial.unlink(missing_ok=True)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                with partial.open("wb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
            if size is not None and partial.stat().st_size != size:
                raise RuntimeError(
                    f"size mismatch for {url}: expected {size}, got {partial.stat().st_size}"
                )
            actual = digest(partial, algorithm)
            if actual != expected:
                raise RuntimeError(
                    f"{algorithm} mismatch for {url}: expected {expected}, got {actual}"
                )
            partial.replace(destination)
            return
        except Exception as error:
            last_error = error
            partial.unlink(missing_ok=True)
            if attempt < 4:
                print(f"    attempt {attempt} failed ({error}); retrying", file=sys.stderr)
                time.sleep(1.5 * attempt)
    raise RuntimeError(f"failed to download {url}: {last_error}")


def find_java() -> str:
    java_home = os.environ.get("JAVA_HOME")
    candidates = []
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    on_path = shutil.which("java")
    if on_path:
        candidates.append(Path(on_path))
    java = next((path for path in candidates if path.is_file()), None)
    if java is None:
        raise SystemExit("Java was not found; install JDK 21 and put java on PATH")

    result = subprocess.run(
        [str(java), "-version"], capture_output=True, text=True, check=False
    )
    version_text = result.stderr + result.stdout
    first_line = version_text.splitlines()[0] if version_text.splitlines() else ""
    match = re.search(r'version "(?:1\.)?(\d+)', first_line)
    if result.returncode != 0 or match is None or int(match.group(1)) < 21:
        raise SystemExit(f"JDK 21 or newer is required; found: {first_line or java}")
    print(f"  java {java} ({first_line})")
    return str(java)


def prepare_minecraft_client() -> None:
    version_dir = GAME / "versions" / MC_VERSION
    metadata_path = version_dir / f"{MC_VERSION}.json"
    download(
        MC_METADATA_URL,
        metadata_path,
        "sha1",
        MC_METADATA_SHA1,
    )

    metadata = json.loads(metadata_path.read_text())
    client = metadata["downloads"]["client"]
    if client.get("sha1") != MC_CLIENT_SHA1:
        raise RuntimeError(
            "verified Minecraft metadata did not contain the expected client JAR"
        )
    download(
        client["url"],
        version_dir / f"{MC_VERSION}.jar",
        "sha1",
        client["sha1"],
        client.get("size"),
    )

    # The NeoForge installer downloads its own libraries and produces the
    # patched client, but assumes a launcher has already installed Minecraft's
    # ordinary Java libraries. A source checkout has no launcher state, so fetch
    # every platform-neutral artifact named by the verified version metadata.
    libraries = []
    for library in metadata.get("libraries", []):
        artifact = (library.get("downloads") or {}).get("artifact")
        # Classifier artifacts in this metadata are platform-native runtime
        # JARs. javac needs only the ordinary (group:name:version) artifacts.
        if artifact and len(library.get("name", "").split(":")) == 3:
            libraries.append(artifact)
    print(f"  Minecraft libraries: {len(libraries)} artifacts")
    for artifact in libraries:
        download(
            artifact["url"],
            GAME / "libraries" / artifact["path"],
            "sha1",
            artifact["sha1"],
            artifact.get("size"),
        )

    profiles = GAME / "launcher_profiles.json"
    if not profiles.exists():
        profiles.parent.mkdir(parents=True, exist_ok=True)
        profiles.write_text(
            json.dumps(
                {
                    "profiles": {},
                    "settings": {},
                    "version": 3,
                    "clientToken": "0" * 32,
                },
                indent=2,
            )
            + "\n"
        )


def install_neoforge(java: str) -> None:
    installer = DOWNLOADS / f"neoforge-{NEOFORGE_VERSION}-installer.jar"
    download(
        NEOFORGE_INSTALLER_URL,
        installer,
        "sha256",
        NEOFORGE_INSTALLER_SHA256,
    )

    profile = (
        GAME
        / "versions"
        / f"neoforge-{NEOFORGE_VERSION}"
        / f"neoforge-{NEOFORGE_VERSION}.json"
    )
    patched_clients = list(
        (GAME / "libraries" / "net" / "minecraft" / "client").glob(
            "*/client-*-srg.jar"
        )
    )
    neoforge_client = (
        GAME
        / "libraries"
        / "net"
        / "neoforged"
        / "neoforge"
        / NEOFORGE_VERSION
        / f"neoforge-{NEOFORGE_VERSION}-client.jar"
    )
    if profile.is_file() and patched_clients and neoforge_client.is_file():
        print(f"  cached NeoForge {NEOFORGE_VERSION} compiler classpath")
        return

    print(f"  install NeoForge {NEOFORGE_VERSION} compiler classpath")
    log = DOWNLOADS / f"neoforge-{NEOFORGE_VERSION}-install.log"
    result = subprocess.run(
        [java, "-jar", str(installer), "--install-client", str(GAME)],
        cwd=DOWNLOADS,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    log.write_text(result.stdout)
    if result.returncode != 0:
        tail = "\n".join(result.stdout.splitlines()[-80:])
        print(tail, file=sys.stderr)
        raise SystemExit(
            f"NeoForge installer failed with status {result.returncode}; see {log}"
        )
    if not profile.is_file() or not neoforge_client.is_file():
        raise SystemExit(f"NeoForge installer succeeded but build files are missing; see {log}")
    print(f"  wrote {log.relative_to(ROOT)}")


def prepare_jei() -> None:
    download(JEI_URL, JEI_MODS / JEI_FILENAME, "sha1", JEI_SHA1, JEI_SIZE)


def main() -> None:
    print("== mc-criu build dependency bootstrap ==")
    java = find_java()
    prepare_minecraft_client()
    install_neoforge(java)
    prepare_jei()
    print("== build dependencies ready ==")
    print("Run ./package.sh to create out/mc-criu.zip")


if __name__ == "__main__":
    main()
