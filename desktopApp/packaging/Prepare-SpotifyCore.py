"""Prepare the checksum-pinned librespot core with BitChord's reviewed protocol patch."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request

CORE_SHA256 = "168bbe1c416980ddd9a969ebd6b50fb6c924eb1a3ded194285fa8ec0e2b1c68b"
CORE_URL = "https://static.crates.io/crates/librespot-core/librespot-core-0.8.0.crate"


def patched(source, patch):
    if not patch.startswith("--- a/src/connection/handshake.rs\n+++ b/src/connection/handshake.rs\n"):
        raise ValueError("Unexpected patch target")
    hunks = []
    for line in patch.splitlines(keepends=True)[2:]:
        if line.startswith("@@ "):
            hunks.append([])
        elif not hunks or line[0] not in " +-":
            raise ValueError("Unsupported patch line")
        else:
            hunks[-1].append(line)
    if not hunks:
        raise ValueError("Empty source patch")
    for hunk in hunks:
        before = "".join(line[1:] for line in hunk if line[0] in " -")
        after = "".join(line[1:] for line in hunk if line[0] in " +")
        if not before or source.count(before) != 1:
            raise ValueError("Source patch context changed")
        source = source.replace(before, after, 1)
    return source


def prepare(repo):
    destination = repo / "desktopApp/native-dependencies/spotify/librespot-core"
    cache = repo / "desktopApp/native-dependencies/spotify/librespot-core-0.8.0.crate"
    patch_file = repo / "desktopApp/native/spotify/patches/windows-arm64-platform.patch"
    if not destination.resolve().is_relative_to(repo.resolve()) or not cache.resolve().is_relative_to(repo.resolve()):
        raise ValueError("Generated source must remain inside the repository")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        with urllib.request.urlopen(CORE_URL, timeout=30) as response:
            archive = response.read(32_000_001)
        if hashlib.sha256(archive).hexdigest() != CORE_SHA256:
            raise ValueError("librespot core download checksum mismatch")
        cache.write_bytes(archive)
    archive = cache.read_bytes()
    if hashlib.sha256(archive).hexdigest() != CORE_SHA256:
        raise ValueError("librespot core cache checksum mismatch")
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
        for entry in package.getmembers():
            path = PurePosixPath(entry.name)
            if path.is_absolute() or ".." in path.parts or path.parts[0] != "librespot-core-0.8.0":
                raise ValueError("Unsafe source path")
            if entry.isdir():
                continue
            if not entry.isfile() or entry.size > 16_000_000:
                raise ValueError("Unsupported source entry")
            name = path.relative_to("librespot-core-0.8.0").as_posix()
            files[name] = package.extractfile(entry).read()
    patch = patch_file.read_text(encoding="utf-8")
    handshake = "src/connection/handshake.rs"
    files[handshake] = patched(files[handshake].decode("utf-8"), patch).encode("utf-8")
    manifest = destination / ".bitchord-source.json"
    previous = json.loads(manifest.read_text()) if manifest.exists() else None
    if destination.exists():
        for existing in destination.rglob("*"):
            if existing.is_symlink():
                raise ValueError("Source directory contains a link")
            if existing.is_file() and existing != manifest:
                name = existing.relative_to(destination).as_posix()
                if previous is None or hashlib.sha256(existing.read_bytes()).hexdigest() != previous["files"].get(name):
                    raise ValueError("Preserve edits in generated librespot source: " + name)
    for name, content in files.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() or target.read_bytes() != content:
            target.write_bytes(content)
    provenance = {"url": CORE_URL, "sha256": CORE_SHA256,
                  "patch": patch_file.relative_to(repo).as_posix(),
                  "patch_sha256": hashlib.sha256(patch_file.read_bytes()).hexdigest(),
                  "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    manifest.write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    print("Prepared checksum-verified librespot core 0.8.0 and protocol regression")


if __name__ == "__main__":
    prepare(Path(__file__).resolve().parents[2])
