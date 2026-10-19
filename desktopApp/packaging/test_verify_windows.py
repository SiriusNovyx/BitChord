import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('verify_windows', Path(__file__).with_name('verify_windows.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

def pe(machine):
    data = bytearray(128)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 60, 80)
    data[80:84] = b'PE\0\0'
    struct.pack_into('<H', data, 84, machine)
    return bytes(data)

class WindowsArchitectureTest(unittest.TestCase):
    def test_reads_arm64_and_x64_machine(self):
        self.assertEqual('arm64', verifier.pe_architecture(pe(0xaa64)))
        self.assertEqual('x64', verifier.pe_architecture(pe(0x8664)))

    def test_rejects_renamed_x64_binary(self):
        with self.assertRaisesRegex(ValueError, 'x64.*arm64'):
            verifier.require_architecture(pe(0x8664), 'arm64', 'fake-arm64.dll')

    def test_rejects_truncated_or_non_pe_input(self):
        for data in (b'', b'MZ', b'not a binary', pe(0xaa64)[:83]):
            with self.subTest(data=data), self.assertRaises(ValueError):
                verifier.pe_architecture(data)

    def test_rejects_unknown_machine(self):
        with self.assertRaises(ValueError):
            verifier.pe_architecture(pe(0xa641))

    def test_runtime_requires_native_java_and_jvm(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd() / '.work') as directory:
            root = Path(directory)
            (root / 'bin/server').mkdir(parents=True)
            (root / 'bin/java.exe').write_bytes(pe(0xaa64))
            (root / 'bin/server/jvm.dll').write_bytes(pe(0x8664))
            with self.assertRaisesRegex(ValueError, 'x64.*arm64'):
                verifier.verify_runtime(root, 'arm64')
            (root / 'bin/server/jvm.dll').write_bytes(pe(0xaa64))
            verifier.verify_runtime(root, 'arm64')
            (root / 'bin/server/jvm.dll').unlink()
            with self.assertRaisesRegex(ValueError, 'missing'):
                verifier.verify_runtime(root, 'arm64')

    def test_checks_bytes_in_a_renamed_arm64_jar(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd() / '.work') as directory:
            jar = Path(directory) / 'fake-windows-arm64.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('windows-arm64/decoder.dll', pe(0x8664))
            with self.assertRaisesRegex(ValueError, 'x64.*arm64'):
                verifier.verify_jar(jar, 'arm64')

    def test_universal_jna_and_sqlite_select_only_the_requested_architecture(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd() / '.work') as directory:
            jar = Path(directory) / 'universal.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('com/sun/jna/win32-x86-64/jnidispatch.dll', pe(0x8664))
                archive.writestr('com/sun/jna/win32-aarch64/jnidispatch.dll', pe(0xaa64))
                archive.writestr('org/sqlite/native/Windows/x86_64/sqlitejdbc.dll', pe(0x8664))
                archive.writestr('org/sqlite/native/Windows/aarch64/sqlitejdbc.dll', pe(0xaa64))
                archive.writestr('org/sqlite/native/Windows/x86/sqlitejdbc.dll', pe(0x014c))
            self.assertEqual(2, len(verifier.verify_jar(jar, 'arm64')))
            self.assertEqual(2, len(verifier.verify_jar(jar, 'x64')))

    def test_checks_executable_helpers_inside_application_jars(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd() / '.work') as directory:
            jar = Path(directory) / 'app.jar'
            for architecture, wrong_machine in (('arm64', 0x8664), ('x64', 0xaa64)):
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(f'native/spotify/windows-{architecture}/bitchord-spotify.exe', pe(wrong_machine))
                with self.subTest(architecture=architecture), self.assertRaises(ValueError):
                    verifier.verify_jar(jar, architecture)

    def test_universal_junixsocket_selects_requested_windows_native_and_checks_its_bytes(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd() / '.work') as directory:
            jar = Path(directory) / 'junixsocket-native-common.jar'
            arm = 'lib/aarch64-Windows10-clang/jni/junixsocket-native.dll'
            x64 = 'lib/amd64-Windows10-clang/jni/junixsocket-native.dll'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr(arm, pe(0xaa64))
                archive.writestr(x64, pe(0x8664))
            self.assertEqual([arm], verifier.verify_jar(jar, 'arm64'))
            self.assertEqual([x64], verifier.verify_jar(jar, 'x64'))
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr(x64, pe(0xaa64))
            with self.assertRaisesRegex(ValueError, 'arm64.*x64'):
                verifier.verify_jar(jar, 'x64')

if __name__ == '__main__':
    unittest.main()
