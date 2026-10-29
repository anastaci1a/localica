import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from equivalence import git
from evidence import JOBS
import reuse
from reuse import audit_identity


class IdentityAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.name", "Audit fixture")
        git(self.repo, "config", "user.email", "test@local.invalid")
        for name in ("settings.gradle.kts", ".github/workflows/android.yml", ".github/RELEASE_NOTES.md"):
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
        app = self.repo / "app/build.gradle.kts"
        app.parent.mkdir()
        app.write_text('android {\n defaultConfig {\n  versionCode = 78\n  versionName = "0.29.1-dev78"\n }\n}\n')
        self.producer = self.commit()
        (self.repo / ".github/RELEASE_NOTES.md").write_text("notes only\n")
        self.release = self.commit()
        self.apk = b"original producer APK fixture"
        self.apk_hash = hashlib.sha256(self.apk).hexdigest()
        self.archive = self.root / "artifact.zip"
        with zipfile.ZipFile(self.archive, "w") as bundle:
            bundle.writestr("app-debug.apk", self.apk)
            bundle.writestr("app-debug.apk.sha256", self.apk_hash + "  app-debug.apk\n")
        self.digest = "sha256:" + hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.run = {"id": 10, "run_attempt": 2, "head_sha": self.producer, "head_branch": "main",
                    "repository": {"id": 42, "full_name": "owner/repo"},
                    "head_repository": {"id": 42, "full_name": "owner/repo"},
                    "workflow_id": 3, "path": ".github/workflows/android.yml", "event": "push",
                    "status": "completed", "conclusion": "success"}
        self.jobs = {"total_count": len(JOBS), "jobs": [
            {"id": 100 + i, "name": name, "run_id": 10, "run_attempt": 2, "head_sha": self.producer,
             "status": "completed", "conclusion": "success", "started_at": "2026-10-17T00:00:00Z",
             "completed_at": "2026-10-17T00:05:00Z"} for i, name in enumerate(JOBS)]}
        self.artifacts = {"total_count": 1, "artifacts": [
            {"id": 50, "name": "localica-debug", "expired": False, "digest": self.digest,
             "created_at": "2026-10-17T00:04:00Z", "workflow_run": {
                 "id": 10, "head_sha": self.producer, "head_branch": "main",
                 "repository_id": 42, "head_repository_id": 42}}]}
        self.certificate = "b" * 64

    def commit(self):
        git(self.repo, "add", "--all")
        git(self.repo, "commit", "-qm", "fixture")
        return git(self.repo, "rev-parse", "HEAD").strip().decode()

    def inspect(self, path, tools, **expected):
        self.assertEqual(self.apk, Path(path).read_bytes())
        self.assertNotEqual(self.archive.parent, Path(path).parent)
        self.assertEqual({"version": "0.29.1-dev78", "version_code": 78,
                          "certificate": self.certificate}, expected)
        return {"package": "io.github.anastaci1a.localica.debug", "version_code": 78,
                "version_name": "0.29.1-dev78", "signer_certificates_sha256": [self.certificate]}

    def audit(self):
        return audit_identity(self.repo, self.producer, self.release, self.run, self.jobs,
                              self.artifacts, self.archive, Path("/sdk"), repository="owner/repo",
                              workflow_id=3, certificate=self.certificate)

    @patch("reuse.inspect_apk")
    def test_notes_only_audit_binds_original_bytes_and_remains_ineligible(self, inspect):
        inspect.side_effect = self.inspect
        result = self.audit()
        self.assertTrue(result["identity_audit_passed"])
        self.assertFalse(result["eligible_for_reuse"])
        self.assertEqual(self.apk_hash, result["apk_sha256"])
        self.assertEqual(self.producer, result["equivalence"]["producer"]["commit"])
        self.assertEqual(self.release, result["equivalence"]["release"]["commit"])
        self.assertEqual(self.digest, result["metadata"]["artifact_digest"])
        self.assertEqual(5, len(result["metadata"]["required_jobs"]))
        self.assertEqual({"run", "jobs", "artifacts"}, set(result["canonical_metadata_sha256"]))
        self.assertIn("complete_original_reports", result["unimplemented_admission_checks"])
        self.assertIn("resolved_toolchain_dependencies_environment", result["unimplemented_admission_checks"])
        self.assertFalse(Path(inspect.call_args.args[0]).exists())

    @patch("reuse.inspect_apk")
    def test_changed_input_and_version_changes_stop_before_sdk(self, inspect):
        for content in ('versionCode = 79\nversionName = "0.29.1-dev79"\n', "changed executable input\n"):
            (self.repo / "app/build.gradle.kts").write_text(content)
            self.release = self.commit()
            with self.assertRaisesRegex(ValueError, "equivalent"):
                self.audit()
        inspect.assert_not_called()

    @patch("reuse.inspect_apk")
    def test_mixed_attempt_missing_jobs_and_untrusted_branch_stop_before_sdk(self, inspect):
        self.jobs["jobs"][0]["run_attempt"] = 1
        with self.assertRaises(ValueError):
            self.audit()
        self.jobs["jobs"][0]["run_attempt"] = 2
        self.jobs["total_count"] += 1
        with self.assertRaises(ValueError):
            self.audit()
        self.jobs["total_count"] -= 1
        self.run["head_branch"] = "dev/prototype"
        with self.assertRaises(ValueError):
            self.audit()
        inspect.assert_not_called()

    @patch("reuse.inspect_apk")
    def test_corrupt_archive_stops_before_sdk(self, inspect):
        self.archive.write_bytes(self.archive.read_bytes() + b"changed")
        with self.assertRaises(ValueError):
            self.audit()
        inspect.assert_not_called()

    @patch("reuse.inspect_apk")
    def test_original_path_replacement_cannot_substitute_the_checked_snapshot(self, inspect):
        original_snapshot = reuse.snapshot_archive
        def replace_after_copy(original, destination):
            original_snapshot(original, destination)
            Path(original).write_bytes(b"replacement archive at the caller's path")
        inspect.side_effect = self.inspect
        with patch("reuse.snapshot_archive", side_effect=replace_after_copy):
            result = self.audit()
        self.assertEqual(self.apk_hash, result["apk_sha256"])
        self.assertEqual(self.digest, result["metadata"]["artifact_digest"])

    @patch("reuse.inspect_apk")
    def test_oversized_snapshot_is_rejected_before_sdk(self, inspect):
        with patch("reuse.MAX_ARCHIVE_BYTES", 1), self.assertRaisesRegex(ValueError, "size bound"):
            self.audit()
        inspect.assert_not_called()

    @patch("reuse.inspect_apk")
    def test_sdk_failure_retains_primary_error_and_removes_temporary_apk(self, inspect):
        failure = subprocess.CalledProcessError(1, ["apksigner"])
        seen = []
        def failed(path, *args, **kwargs):
            seen.append(Path(path))
            raise failure
        inspect.side_effect = failed
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            self.audit()
        self.assertIs(failure, raised.exception)
        self.assertFalse(seen[0].exists())

    @patch("reuse.inspect_apk")
    def test_changed_inspected_file_and_contradictory_identity_are_rejected(self, inspect):
        def changed(path, tools, **expected):
            result = self.inspect(path, tools, **expected)
            Path(path).write_bytes(b"substituted APK")
            return result
        inspect.side_effect = changed
        with self.assertRaises(ValueError):
            self.audit()
        def contradictory(path, tools, **expected):
            return dict(self.inspect(path, tools, **expected), version_code=79)
        inspect.side_effect = contradictory
        with self.assertRaises(ValueError):
            self.audit()

    @patch("reuse.inspect_apk")
    def test_unsupported_committed_version_expression_is_rejected(self, inspect):
        (self.repo / "app/build.gradle.kts").write_text('versionCode = providers.code.get()\nversionName = "${version}"\n')
        self.producer = self.release = self.commit()
        self.run["head_sha"] = self.producer
        for job in self.jobs["jobs"]:
            job["head_sha"] = self.producer
        self.artifacts["artifacts"][0]["workflow_run"]["head_sha"] = self.producer
        with self.assertRaisesRegex(ValueError, "literal"):
            self.audit()
        inspect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
