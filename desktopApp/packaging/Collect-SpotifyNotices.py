"""Collect notices from the exact, locally resolved Cargo sources; no downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

parser = argparse.ArgumentParser()
parser.add_argument("metadata", type=Path)
parser.add_argument("destination", type=Path)
parser.add_argument("--supplemental", type=Path, default=Path(__file__).resolve().parents[1] / "native/spotify/licenses")
args = parser.parse_args()
metadata = json.loads(args.metadata.read_text(encoding="utf-8-sig"))
destination = args.destination.resolve()
destination.mkdir(parents=True, exist_ok=True)
nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
pending = [metadata["resolve"]["root"]]
reachable = set()
while pending:
    package = pending.pop()
    if package in reachable:
        continue
    reachable.add(package)
    pending.extend(dep["pkg"] for dep in nodes[package]["deps"])
records = []
missing = []
supplemental_sources = json.loads((args.supplemental / "sources.json").read_text(encoding="utf-8"))
for package in sorted(metadata["packages"], key=lambda item: (item["name"], item["version"])):
    if package["id"] not in reachable or package["id"] == metadata["resolve"]["root"]:
        continue
    name = package["name"] + "-" + package["version"]
    assert re.fullmatch(r"[A-Za-z0-9_.+-]+", name)
    root = Path(package["manifest_path"]).parent.resolve()
    files = [item for item in root.iterdir() if item.is_file() and
             item.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE", "UNLICENSE"))]
    if package.get("license_file"):
        declared = (root / package["license_file"]).resolve()
        assert declared.is_relative_to(root)
        if declared.is_file() and declared not in files:
            files.append(declared)
    record = {key: package.get(key) for key in ("name", "version", "license", "repository", "authors")}
    record["source"] = package["source"]
    provenance = root / ".bitchord-source.json"
    if package["source"] is None:
        assert package["name"] == "librespot-core" and provenance.is_file()
        source_record = json.loads(provenance.read_text(encoding="utf-8"))
        record["source"] = {key: source_record[key] for key in ("url", "sha256", "patch", "patch_sha256")}
        repo = Path(__file__).resolve().parents[2]
        patch = (repo / source_record["patch"]).resolve()
        assert patch.is_relative_to(repo) and hashlib.sha256(patch.read_bytes()).hexdigest() == source_record["patch_sha256"]
        output = destination / name / patch.name
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(patch, output)
    record["notices"] = []
    if not files:
        for notice in supplemental_sources:
            if not notice["file"].startswith(name + "/"):
                continue
            source = (args.supplemental / notice["file"]).resolve()
            assert source.is_relative_to(args.supplemental.resolve())
            assert hashlib.sha256(source.read_bytes()).hexdigest() == notice["sha256"]
            files.append(source)
            record.setdefault("upstream_notices", []).append(notice)
    for source in files:
        assert source.stat().st_size < 2_000_000
        output = destination / name / source.name
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, output)
        record["notices"].append({"file": output.relative_to(destination).as_posix(),
                                  "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
    if not files:
        missing.append(name)
    records.append(record)
(destination / "dependencies.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
(destination / "README.txt").write_text(
    "BitChord Spotify PCM uses librespot 0.8.0 under the MIT license.\n"
    "This directory contains notices from the locked Cargo dependency sources, including build dependencies.\n"
    "Cargo.lock and the helper source are in desktopApp/native/spotify.\n"
    "Dependency sources are available at the upstream repositories in dependencies.json;\n"
    "librespot-core has the included Windows ARM protocol patch; its base crate and checksum are recorded.\n"
    "supplemental notice URLs name the exact source commit. priority-queue is used under MPL-2.0.\n"
    "Missing source notice files (SPDX and authors remain in dependencies.json):\n" +
    "\n".join(missing) + "\n", encoding="utf-8")
print(f"Collected notices for {len(records)} locked dependencies; {len(missing)} lack notice files")
if missing:
    print("Missing notice files: " + ", ".join(missing))
    raise SystemExit(1)
