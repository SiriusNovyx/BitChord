"""Replace only native resources in the matching QuickJS JVM API archive."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
from verify_windows import require_architecture


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream_jar', type=Path)
    parser.add_argument('library', type=Path)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if hashlib.sha1(args.upstream_jar.read_bytes()).hexdigest() != '6133030becc259261a7739fd4d3717718141e607':
        raise ValueError('Expected the resolved quickjs-kt-jvm 1.0.14 API artifact')
    revision = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != '6e51ae878be54f22272381e62118535790ba9940':
        raise ValueError('Expected quickjs-kt v1.0.14 source')
    data = args.library.read_bytes()
    require_architecture(data, 'arm64', str(args.library))
    with zipfile.ZipFile(args.upstream_jar) as source, zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if not entry.filename.startswith('jni/'):
                target.writestr(entry, source.read(entry))
        target.writestr('jni/windows_aarch64/libquickjs.dll', data)
        for subdir in ('', 'quickjs/native/quickjs', 'quickjs/native/c-vector', 'quickjs/native/winpthreads'):
            folder = args.source / subdir
            for file in folder.glob('*'):
                if file.is_file() and file.name.lower().startswith(('license', 'copying')):
                    target.write(file, 'META-INF/licenses/quickjs/' + str(file.relative_to(args.source)).replace('\\', '/'))
        target.writestr('META-INF/bitchord-native-provenance.json', json.dumps({
            'api': 'io.github.dokar3:quickjs-kt-jvm:1.0.14',
            'source_revision': revision,
            'engine_revision': '04be246001599f5995fa2f2d8c91a0f198d3f34c',
            'c_vector_revision': '774773d4cb1e66dd736e90e7f482d4f22af464c6',
            'compiler': 'LLVM-MinGW 20261006 UCRT ARM64; static winpthread',
            'compiler_archive_sha256': '9a835d5179c9f3a5a783c11a7f14062a249b4765dd11add64a66786864e82ab2',
            'library_sha256': hashlib.sha256(data).hexdigest(),
        }, indent=2))
    print('Packaged QuickJS ARM64:', args.output)
