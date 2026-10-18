import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from equivalence import compare, git


class EquivalenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "test@local.invalid")
        git(self.repo, "config", "user.name", "Verification test")
        git(self.repo, "config", "core.filemode", "true")
        for name in ("settings.gradle.kts", "app/build.gradle.kts", ".github/workflows/android.yml",
                     ".github/RELEASE_NOTES.md", "app/source.kt", "app/test.kt", "README.md"):
            self.write(name, "initial\n")
        self.original = self.commit()

    def write(self, name, text):
        target = self.repo / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self):
        git(self.repo, "add", "--all")
        git(self.repo, "commit", "-qm", "fixture", "--allow-empty")
        return git(self.repo, "rev-parse", "HEAD").strip().decode()

    def test_identical_tree_with_different_commit_is_equivalent(self):
        result = compare(self.repo, self.original, self.commit())
        self.assertTrue(result["equivalent"])
        self.assertNotEqual(result["producer"]["commit"], result["release"]["commit"])
        self.assertEqual(result["producer"]["tree"], result["release"]["tree"])

    def test_notes_only_preserves_fingerprint_and_both_provenances(self):
        self.write(".github/RELEASE_NOTES.md", "new release\n")
        result = compare(self.repo, self.original, self.commit())
        self.assertTrue(result["equivalent"])
        self.assertNotEqual(result["producer"]["tree"], result["release"]["tree"])
        self.assertEqual(result["producer"]["inputs_sha256"], result["release"]["inputs_sha256"])

    def test_any_other_path_change_requires_verification(self):
        for path in ("app/source.kt", "app/test.kt", "app/build.gradle.kts",
                     ".github/workflows/android.yml", "README.md", "new\tpath\nfile"):
            with self.subTest(path=path):
                git(self.repo, "reset", "--hard", self.original)
                self.write(path, "changed\n")
                result = compare(self.repo, self.original, self.commit())
                self.assertFalse(result["equivalent"])
                self.assertEqual([path], result["changed_inputs"])
                self.assertNotEqual(result["producer"]["inputs_sha256"], result["release"]["inputs_sha256"])

    def test_executable_mode_change_is_an_input_change(self):
        os.chmod(self.repo / "app/source.kt", 0o755)
        self.assertEqual(["app/source.kt"], compare(self.repo, self.original, self.commit())["changed_inputs"])

    def test_symlink_destination_change_is_an_input_change(self):
        (self.repo / "link").symlink_to("app/source.kt")
        before = self.commit()
        (self.repo / "link").unlink()
        (self.repo / "link").symlink_to("app/test.kt")
        self.assertEqual(["link"], compare(self.repo, before, self.commit())["changed_inputs"])

    def test_missing_required_or_excluded_file_fails_closed(self):
        for path in ("app/build.gradle.kts", ".github/RELEASE_NOTES.md"):
            with self.subTest(path=path):
                git(self.repo, "reset", "--hard", self.original)
                (self.repo / path).unlink()
                with self.assertRaises(ValueError):
                    compare(self.repo, self.original, self.commit())

    def test_excluded_file_cannot_be_executable_or_symlink(self):
        notes = self.repo / ".github/RELEASE_NOTES.md"
        os.chmod(notes, 0o755)
        with self.assertRaises(ValueError):
            compare(self.repo, self.original, self.commit())
        notes.unlink()
        notes.symlink_to("../README.md")
        with self.assertRaises(ValueError):
            compare(self.repo, self.original, self.commit())

    def test_invalid_revision_fails_closed(self):
        with self.assertRaises(subprocess.CalledProcessError):
            compare(self.repo, self.original, "missing-commit")

    def test_local_replace_ref_cannot_hide_changed_inputs(self):
        self.write("app/source.kt", "changed\n")
        changed = self.commit()
        git(self.repo, "replace", changed, self.original)
        self.assertEqual(["app/source.kt"], compare(self.repo, self.original, changed)["changed_inputs"])

    def test_cli_reports_non_equivalence_without_claiming_verification(self):
        self.write("app/test.kt", "changed\n")
        after = self.commit()
        result = subprocess.run(["python3", str(Path(__file__).with_name("equivalence.py")),
                                 self.original, after, "--repo", str(self.repo)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertFalse(json.loads(result.stdout)["equivalent"])
        self.assertNotIn("verified", json.loads(result.stdout))


if __name__ == "__main__":
    unittest.main()
