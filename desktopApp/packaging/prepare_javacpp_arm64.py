"""Keep the native JavaCPP core and ARM64 VC runtimes; use Windows 11's UCRT.

The upstream 1.5.14 ARM64 classifier also contains x86 UCRT forwarders and an
x64 vcruntime140_1.dll. These must not enter a native ARM64 portable package.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from verify_windows import require_architecture


def prepare(source: Path, output: Path):
    if source.resolve() == output.resolve():
        raise ValueError("Preserve the upstream input archive")
    removed = []
    with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as outgoing:
        for entry in incoming.infolist():
            name = Path(entry.filename).name.lower()
            if name.startswith("api-ms-win-") or name in {"ucrtbase.dll", "vcruntime140_1.dll"}:
                removed.append(entry.filename)
                continue
            data = incoming.read(entry)
            if name.endswith(".dll"):
                require_architecture(data, "arm64", entry.filename)
            outgoing.writestr(entry, data)
        outgoing.writestr("META-INF/bitchord-native-provenance.json", json.dumps({
            "upstream": "org.bytedeco:javacpp:1.5.14:windows-arm64",
            "upstream_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "removed": removed,
            "system_runtime": "Windows 11 ARM64 Universal CRT",
        }, indent=2))
    return removed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(f"Removed {len(prepare(args.source, args.output))} system or foreign runtime entries")
