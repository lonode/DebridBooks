"""Release guards that run without Android tools, credentials or network access."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPTS = Path(__file__).parent
spec = importlib.util.spec_from_file_location("sign_release", SCRIPTS / "sign-release.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class ReleaseToolsTest(unittest.TestCase):
    def test_independent_apks_must_match_bytes_not_just_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, second = root / "first.apk", root / "second.apk"
            for path, timestamp in [(first, (2020, 1, 1, 0, 0, 0)), (second, (2021, 1, 1, 0, 0, 0))]:
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(zipfile.ZipInfo("classes.dex", timestamp), b"same bytecode")
            command = [sys.executable, str(SCRIPTS / "check-reproducible.py"), str(first), str(second)]
            mismatch = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, mismatch.returncode)
            self.assertIn("ZIP metadata differs", mismatch.stdout)
            second.write_bytes(first.read_bytes())
            match = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, match.returncode, match.stderr)

    def test_changed_apk_entry_is_reported_and_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ["first.apk", "second.apk"]]
            for path, content in zip(paths, [b"original", b"changed"]):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", content)
            result = subprocess.run([sys.executable, str(SCRIPTS / "check-reproducible.py"), *map(str, paths)],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("classes.dex", result.stdout)

    def test_missing_signing_secrets_cannot_create_an_apk(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            output = Path(directory) / "release.apk"
            with self.assertRaisesRegex(ValueError, "four Android signing secrets"):
                signing.sign(Path(directory) / "input.apk", output)
            self.assertFalse(output.exists())

    def test_verifier_rejects_missing_or_multiple_signers(self):
        certificate = "a" * 64
        line = f"Signer #1 certificate SHA-256 digest: {certificate}\n"
        self.assertEqual(certificate, signing.fingerprint(line))
        for invalid in ["", line + line.replace("#1", "#2")]:
            with self.assertRaisesRegex(ValueError, "exactly one"):
                signing.fingerprint(invalid)

    def test_key_creation_never_overwrites_existing_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / "private"
            private.mkdir()
            sentinel = private / "keep"
            sentinel.write_text("existing key backup")
            result = subprocess.run([sys.executable, str(SCRIPTS / "create-release-key.py"),
                                     "--directory", str(private)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("existing key backup", sentinel.read_text())


if __name__ == "__main__":
    unittest.main()
