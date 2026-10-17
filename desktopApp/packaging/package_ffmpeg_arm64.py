"""Package verified FFmpeg 8.1 libraries and locally built JavaCPP JNI wrappers."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from verify_windows import require_architecture


def package(shared: Path, jni: Path, source_archive: Path, output: Path):
    wrappers = list(jni.glob('jni*.dll'))
    if {p.stem for p in wrappers} != {
        'jniavcodec', 'jniavformat', 'jniavutil', 'jniavdevice', 'jniavfilter', 'jniswresample', 'jniswscale',
    }:
        raise ValueError('All seven JNI wrappers are required')
    files = wrappers + list((shared / 'bin').glob('*.dll'))
    licenses = [p for p in shared.rglob('*') if p.is_file() and any(x in p.name.lower() for x in ('license', 'copying'))]
    if not licenses:
        raise ValueError('FFmpeg license files are required')
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            data = file.read_bytes()
            require_architecture(data, 'arm64', str(file))
            archive.writestr('org/bytedeco/ffmpeg/windows-arm64/' + file.name, data)
        for file in licenses:
            archive.write(file, 'META-INF/licenses/' + str(file.relative_to(shared)).replace('\\', '/'))
        archive.writestr('META-INF/bitchord-native-provenance.json', json.dumps({
            'java_api': 'org.bytedeco:ffmpeg:8.1.2-1.5.14',
            'jni_generator': 'org.bytedeco:javacpp:1.5.14',
            'native_archive': source_archive.name,
            'native_archive_sha256': hashlib.sha256(source_archive.read_bytes()).hexdigest(),
            'toolchain': 'MSVC ARM64',
            'files': {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in files},
        }, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('shared', type=Path)
    parser.add_argument('jni', type=Path)
    parser.add_argument('source_archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    package(args.shared, args.jni, args.source_archive, args.output)
    print('Verified FFmpeg ARM64 JNI archive:', args.output)
