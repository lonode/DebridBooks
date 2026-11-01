"""Offline CLI checks: python3 -m unittest discover -s scripts -p 'test_*.py'."""

from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


RECIPE = """License: AGPL-3.0-only
Repo: https://github.com/lonode/DebridBooks.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: 1.0.0
    subdir: app
    sudo:
      - apt-get update
    gradle:
      - yes

AutoUpdateMode: Version
CurrentVersion: 1.0.0
CurrentVersionCode: 1
"""


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(Path(__file__).with_name("prepare-release.py"), self.root / "scripts/prepare-release.py")
        shutil.copy2(Path(__file__).with_name("check-release.py"), self.root / "scripts/check-release.py")
        (self.root / "version.properties").write_text("versionName=1.0.0\nversionCode=1\n")
        self.recipe = self.root / "fdroid/metadata/io.github.lonode.debridbooks.yml"
        self.recipe.parent.mkdir(parents=True)
        self.recipe.write_text(RECIPE)
        self.notes = self.root / "fastlane/metadata/android/en-US/changelogs/2.txt"

    def run_script(self, version="1.0.1", notes="Fix search cancellation", *extra):
        return subprocess.run([sys.executable, str(self.root / "scripts/prepare-release.py"),
                               version, "--notes", notes, *extra],
                              cwd=self.root, capture_output=True, text=True, timeout=10)

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}

    def test_release_updates_all_files_and_preserves_old_builds(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("versionName=1.0.1\nversionCode=2\n", (self.root / "version.properties").read_text())
        recipe = self.recipe.read_text()
        self.assertIn("    commit: 1.0.0\n", recipe)
        self.assertIn("  - versionName: 1.0.1\n    versionCode: 2\n    commit: 1.0.1\n", recipe)
        self.assertEqual(2, recipe.count("      - apt-get update"))
        self.assertIn("CurrentVersion: 1.0.1\nCurrentVersionCode: 2\n", recipe)
        self.assertEqual("Fix search cancellation\n", self.notes.read_text())
        self.assertIn("No commit, tag or push", result.stdout)
        # Preparing the following release must use the newest build and retain history.
        result = self.run_script("1.1.0", "Improve reading")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("versionCode=3\n", (self.root / "version.properties").read_text())
        self.assertEqual(3, self.recipe.read_text().count("    subdir: app"))
        self.assertTrue(self.notes.exists())

    def test_dry_run_is_read_only(self):
        before = self.snapshot()
        result = self.run_script("1.0.1", "Fix search cancellation", "--dry-run")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("+    commit: 1.0.1", result.stdout)
        self.assertEqual(before, self.snapshot())

    def test_release_check_accepts_only_the_matching_unprefixed_tag(self):
        for tag, success in [("1.0.0", True), ("v1.0.0", False), ("1.0.1", False)]:
            with self.subTest(tag=tag):
                result = subprocess.run(
                    [sys.executable, str(self.root / "scripts/check-release.py")],
                    env={**os.environ, "GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": tag},
                    capture_output=True, text=True, timeout=10,
                )
                self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)

    def test_invalid_versions_and_notes_do_not_modify_files(self):
        for version, notes in [("1.0.0", "Fix"), ("0.9.0", "Fix"), ("v1.0.1", "Fix"),
                               ("1.00.1", "Fix"), ("1.0.1", " "), ("1.0.1", "x" * 500)]:
            with self.subTest(version=version, note_length=len(notes)):
                before = self.snapshot()
                result = self.run_script(version, notes)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, self.snapshot())

    def test_existing_changelog_is_not_overwritten(self):
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text("Existing notes\n")
        before = self.snapshot()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("overwrite", result.stderr)
        self.assertEqual(before, self.snapshot())

    def test_inconsistent_recipe_or_disabled_build_is_rejected(self):
        for recipe in [RECIPE.replace("CurrentVersion: 1.0.0", "CurrentVersion: 0.9.0"),
                       RECIPE.replace("    versionCode: 1", "    versionCode: 7"),
                       RECIPE.replace("    subdir: app", "    disable: Not ready\n    subdir: app")]:
            self.recipe.write_text(recipe)
            before = self.snapshot()
            result = self.run_script()
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(before, self.snapshot())

    def test_version_code_overflow_is_rejected(self):
        (self.root / "version.properties").write_text("versionName=1.0.0\nversionCode=2100000000\n")
        before = self.snapshot()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
