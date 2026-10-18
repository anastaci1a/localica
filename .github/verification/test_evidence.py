import copy
import unittest

from evidence import eligible_metadata, JOBS


SHA = "a" * 40


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.run = {"id": 10, "run_attempt": 2, "head_sha": SHA, "head_branch": "main",
                    "repository": {"id": 42, "full_name": "owner/repo"},
                    "head_repository": {"id": 42, "full_name": "owner/repo"},
                    "workflow_id": 3, "path": ".github/workflows/android.yml", "event": "push",
                    "status": "completed", "conclusion": "success"}
        self.jobs = {"total_count": len(JOBS), "jobs": [
            {"id": 100 + i, "name": name, "run_id": 10, "run_attempt": 2, "head_sha": SHA,
             "status": "completed", "conclusion": "success", "started_at": "2026-10-17T00:00:00Z",
             "completed_at": "2026-10-17T00:05:00Z"} for i, name in enumerate(JOBS)]}
        self.artifacts = {"total_count": 1, "artifacts": [
            {"id": 50, "name": "localica-debug", "expired": False, "digest": "sha256:" + "f" * 64,
             "created_at": "2026-10-17T00:04:00Z",
             "workflow_run": {"id": 10, "head_sha": SHA, "head_branch": "main",
                              "repository_id": 42, "head_repository_id": 42}}]}

    def check(self):
        return eligible_metadata(self.run, self.jobs, self.artifacts,
                                 repository="owner/repo", workflow_id=3, producer_commit=SHA)

    def test_complete_producer_preserves_original_identity(self):
        result = self.check()
        self.assertEqual(10, result["producer_run_id"])
        self.assertEqual(2, result["producer_attempt"])
        self.assertEqual(SHA, result["producer_commit"])
        self.assertEqual(50, result["artifact_id"])
        self.assertEqual(list(JOBS), [job["name"] for job in result["required_jobs"]])
        self.assertNotIn("verified_apk", result)

    def test_untrusted_wrong_or_incomplete_run_is_rejected(self):
        original = copy.deepcopy(self.run)
        for key, value in (("event", "pull_request"), ("head_branch", "dev/candidate"),
                           ("workflow_id", 4), ("path", ".github/workflows/other.yml"),
                           ("head_sha", "b" * 40), ("status", "in_progress"),
                           ("conclusion", "failure"), ("run_attempt", None),
                           ("repository", {"id": 42, "full_name": "other/repo"}),
                           ("head_repository", {"id": 43, "full_name": "fork/repo"})):
            with self.subTest(key=key):
                self.run = copy.deepcopy(original)
                self.run[key] = value
                with self.assertRaises(ValueError):
                    self.check()

    def test_every_required_job_must_pass_in_this_exact_attempt(self):
        original = copy.deepcopy(self.jobs)
        for index in range(len(JOBS)):
            for key, value in (("conclusion", "skipped"), ("conclusion", "failure"),
                               ("status", "in_progress"), ("run_attempt", 1),
                               ("run_id", 11), ("head_sha", "b" * 40)):
                with self.subTest(job=JOBS[index], key=key, value=value):
                    self.jobs = copy.deepcopy(original)
                    self.jobs["jobs"][index][key] = value
                    with self.assertRaises(ValueError):
                        self.check()

    def test_missing_duplicate_or_truncated_job_results_fail_closed(self):
        original = copy.deepcopy(self.jobs)
        for rows, total in ((original["jobs"][:-1], 4),
                            (original["jobs"] + [original["jobs"][0]], 6),
                            (original["jobs"], 101)):
            self.jobs = {"jobs": rows, "total_count": total}
            with self.assertRaises(ValueError):
                self.check()

    def test_artifact_must_be_retained_unambiguous_and_from_the_producer(self):
        original = copy.deepcopy(self.artifacts)
        for key, value in (("expired", True), ("digest", ""), ("id", None),
                           ("name", "some-other-apk"),
                           ("workflow_run", {"id": 11, "head_sha": SHA}),
                           ("workflow_run", {"id": 10, "head_sha": "b" * 40}),
                           ("workflow_run", {"id": 10, "head_sha": SHA, "head_branch": "main",
                                             "repository_id": 42, "head_repository_id": 43})):
            with self.subTest(key=key):
                self.artifacts = copy.deepcopy(original)
                self.artifacts["artifacts"][0][key] = value
                with self.assertRaises(ValueError):
                    self.check()
        self.artifacts = {"total_count": 2, "artifacts": original["artifacts"] * 2}
        with self.assertRaises(ValueError):
            self.check()
        self.artifacts = {"total_count": 100, "artifacts": original["artifacts"]}
        with self.assertRaises(ValueError):
            self.check()

    def test_old_attempt_artifact_or_missing_timing_is_rejected(self):
        for created in ("2026-10-16T00:04:00Z", "2026-10-18T00:04:00Z", None, "invalid",
                        "2026-10-17T00:04:00"):
            with self.subTest(created=created):
                self.artifacts["artifacts"][0]["created_at"] = created
                with self.assertRaises(ValueError):
                    self.check()


if __name__ == "__main__":
    unittest.main()
