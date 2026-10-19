import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("spotify_core", Path(__file__).with_name("Prepare-SpotifyCore.py"))
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)


class SpotifyCorePatchTests(unittest.TestCase):
    header = "--- a/src/connection/handshake.rs\n+++ b/src/connection/handshake.rs\n"

    def test_exact_context_and_multiple_hunks(self):
        patch = self.header + "@@ -1,1 +1,1 @@\n-old\n+new\n@@ -3,1 +3,1 @@\n-last\n+final\n"
        self.assertEqual(core.patched("old\nkept\nlast\n", patch), "new\nkept\nfinal\n")

    def test_missing_or_duplicate_context_is_rejected(self):
        patch = self.header + "@@ -1,1 +1,1 @@\n-old\n+new\n"
        for source in ("changed\n", "old\nold\n"):
            with self.assertRaises(ValueError):
                core.patched(source, patch)

    def test_other_files_and_unsupported_patch_lines_are_rejected(self):
        for patch in ("--- a/other.rs\n+++ b/other.rs\n", self.header + "@@ -1 +1 @@\n!invalid\n"):
            with self.assertRaises(ValueError):
                core.patched("old\n", patch)

    def test_corrupt_cached_source_is_rejected_before_extraction(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            cache = repo / "desktopApp/native-dependencies/spotify/librespot-core-0.8.0.crate"
            cache.parent.mkdir(parents=True)
            cache.write_bytes(b"unverified source")
            with self.assertRaisesRegex(ValueError, "checksum"):
                core.prepare(repo)
            self.assertFalse((cache.parent / "librespot-core").exists())


if __name__ == "__main__":
    unittest.main()
