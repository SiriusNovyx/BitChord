"""Inspect Windows PE architecture without loading executable code."""
import argparse
from pathlib import Path
import struct
import zipfile

MACHINES = {0x8664: 'x64', 0xaa64: 'arm64'}

def pe_architecture(data):
    if len(data) < 64 or data[:2] != b'MZ':
        raise ValueError('missing or truncated DOS header')
    offset = struct.unpack_from('<I', data, 60)[0]
    if offset < 64 or offset + 24 > len(data) or data[offset:offset + 4] != b'PE\0\0':
        raise ValueError('missing or truncated PE header')
    machine = struct.unpack_from('<H', data, offset + 4)[0]
    if machine not in MACHINES:
        raise ValueError(f'unsupported PE machine 0x{machine:04x}')
    return MACHINES[machine]

def require_architecture(data, expected, label):
    actual = pe_architecture(data)
    if actual != expected:
        raise ValueError(f'{label}: {actual} binary, expected {expected}')

def verify_runtime(root, expected):
    for relative in ('bin/java.exe', 'bin/server/jvm.dll'):
        file = root / relative
        if not file.is_file():
            raise ValueError(f'runtime is missing {relative}')
        require_architecture(file.read_bytes(), expected, str(file))
    for file in root.rglob('*'):
        if file.suffix.lower() in ('.exe', '.dll'):
            require_architecture(file.read_bytes(), expected, str(file))

def verify_jar(file, expected):
    """Check selected Windows resources; universal jars may carry unused other targets."""
    windows_tag = 'windows-arm64' if expected == 'arm64' else 'windows-x86_64'
    ort_tag = 'win-aarch64' if expected == 'arm64' else 'win-x64'
    jna_tag = 'win32-aarch64' if expected == 'arm64' else 'win32-x86-64'
    quickjs_tag = 'windows_aarch64' if expected == 'arm64' else 'windows_x64'
    sqlite_arch = 'aarch64' if expected == 'arm64' else 'x86_64'
    found = []
    with zipfile.ZipFile(file) as jar:
        for entry in jar.namelist():
            if not entry.lower().endswith(('.dll', '.exe')):
                continue
            parts = entry.lower().split('/')
            selected = (windows_tag, ort_tag, 'windows-x64' if expected == 'x64' else 'windows-arm64')
            if any(p.startswith(('windows-', 'win-')) and p not in selected for p in parts):
                continue
            if any(p.startswith('win32-') and p != jna_tag for p in parts):
                continue
            if any(p.startswith('windows_') and p != quickjs_tag for p in parts):
                continue
            socket_arch = 'aarch64' if expected == 'arm64' else 'amd64'
            if any(p.startswith(('amd64-windows', 'aarch64-windows'))
                   and not p.startswith(socket_arch + '-windows') for p in parts):
                continue
            if 'windows' in parts:
                index = parts.index('windows')
                if index + 1 < len(parts) and parts[index + 1] != sqlite_arch:
                    continue
            # Unqualified graphics/JNI libraries and application helpers must match too.
            require_architecture(jar.read(entry), expected, f'{file.name}!/{entry}')
            found.append(entry)
    return found

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', required=True, choices=MACHINES.values())
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--jar', type=Path, action='append', default=[])
    args = parser.parse_args()
    if not args.runtime and not args.jar:
        parser.error('provide --runtime and/or --jar')
    if args.runtime:
        verify_runtime(args.runtime, args.arch)
        print(f'Runtime PE architecture verified: {args.arch}')
    for jar in args.jar:
        entries = verify_jar(jar, args.arch)
        if not entries:
            raise ValueError(f'{jar.name}: no selected {args.arch} Windows DLLs')
        print(f'{jar.name}: {len(entries)} selected Windows DLLs verified as {args.arch}')
