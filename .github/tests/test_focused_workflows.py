"""Frozen pre-extraction workflow oracle; offline YAML comparison, no Actions execution."""
import copy
import hashlib
import json
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).with_name("fixtures") / "focused-workflows"
ACTION = "./.github/actions/focused-android-setup"
ORIGINAL = "1888c9a990363cdce3a6b48790d680c09454e002"
NAMES = (
    "glass-renderer-settings.yml", "player-live-transition-contrast.yml",
    "renderer-next-preparation.yml", "font-profile-gates.yml",
)


def decode(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def ubuntu_shells(workflow):
    """Only the reviewed implicit Ubuntu bash / explicit bash -e {0} equivalence."""
    result = copy.deepcopy(workflow)
    for job in result["jobs"].values():
        if job["runs-on"] != "ubuntu-24.04":
            raise AssertionError("Unreviewed runner shell default")
        if "defaults" in job or "defaults" in result:
            raise AssertionError("Unreviewed shell defaults")
        for step in job["steps"]:
            if "run" in step and "shell" not in step:
                step["shell"] = "bash -e {0}"
    return result


def expand(workflow, action):
    # Reject invocation-level conditions/inputs/env rather than dropping their effects.
    if set(action) != {"name", "description", "runs"}:
        raise AssertionError("Unreviewed setup action metadata")
    if set(action["runs"]) != {"using", "steps"} or action["runs"]["using"] != "composite":
        raise AssertionError("Expected the reviewed composite action")
    result = copy.deepcopy(workflow)
    for job in result["jobs"].values():
        steps = []
        calls = 0
        for step in job["steps"]:
            if step.get("uses") == ACTION:
                if step != {"uses": ACTION}:
                    raise AssertionError("Setup invocation changes job semantics")
                steps.extend(copy.deepcopy(action["runs"]["steps"]))
                calls += 1
            else:
                steps.append(step)
        if calls != 1:
            raise AssertionError("Expected exactly one setup invocation per job")
        job["steps"] = steps
    return result


class FocusedWorkflowsContract(unittest.TestCase):
    def setUp(self):
        self.action = decode(ROOT / ".github/actions/focused-android-setup/action.yml")

    def assert_preserved(self, name, workflow=None, action=None):
        current = workflow if workflow is not None else decode(ROOT / ".github/workflows" / name)
        original = decode(FIXTURES / name)
        self.assertEqual(ubuntu_shells(original), ubuntu_shells(expand(
            current, self.action if action is None else action)))

    def changed(self, name=NAMES[0]):
        return decode(ROOT / ".github/workflows" / name)

    def rejects(self, workflow, name=NAMES[0], action=None):
        with self.assertRaises(AssertionError):
            self.assert_preserved(name, workflow, action)

    def test_frozen_fixture_bytes_and_provenance(self):
        provenance = json.loads((FIXTURES / "provenance.json").read_text())
        self.assertEqual(ORIGINAL, provenance["source"])
        self.assertEqual("1a523385bd411a0ad23785988c9ad9085f93a9b8", provenance["extraction"])
        self.assertEqual(set(NAMES), {row["name"] for row in provenance["fixtures"]})
        self.assertEqual(len(NAMES), len(provenance["fixtures"]))
        for row in provenance["fixtures"]:
            with self.subTest(name=row["name"]):
                self.assertEqual(".github/workflows/" + row["name"], row["source"])
                data = (FIXTURES / row["name"]).read_bytes()
                git_blob = b"blob " + str(len(data)).encode("ascii") + b"\0" + data
                self.assertEqual(row["blob"], hashlib.sha1(git_blob).hexdigest())
                self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest())

    def test_complete_four_workflows_match_frozen_originals_after_setup_expansion(self):
        for name in NAMES:
            with self.subTest(name=name):
                self.assert_preserved(name)

    def test_only_implicit_ubuntu_bash_equivalence_is_normalized(self):
        original = decode(FIXTURES / NAMES[0])
        explicit = copy.deepcopy(original)
        for job in explicit["jobs"].values():
            for step in job["steps"]:
                if "run" in step:
                    step["shell"] = "bash -e {0}"
        self.assertEqual(ubuntu_shells(original), ubuntu_shells(explicit))
        explicit["jobs"]["contrast_26"]["steps"][4]["shell"] = "bash --noprofile --norc -eo pipefail {0}"
        self.assertNotEqual(ubuntu_shells(original), ubuntu_shells(explicit))

    def test_selector_mutation_is_rejected(self):
        for name in NAMES:
            with self.subTest(name=name):
                current = self.changed(name)
                env = current["jobs"]["contrast_26"]["env"]
                key = "AFFECTED_CLASSES" if "AFFECTED_CLASSES" in env else "SELECTORS"
                env[key] += ",sample.WrongTest"
                self.rejects(current, name)
        current = self.changed(NAMES[3])
        current["jobs"]["contrast_26"]["strategy"]["matrix"]["include"][0]["selectors"] += ",sample.WrongTest"
        self.rejects(current, NAMES[3])

    def test_profile_and_matrix_mutations_are_rejected(self):
        for key, value in (("api-level", 35), ("target", "wrong"), ("arch", "arm64-v8a"),
                           ("profile", "pixel"), ("emulator-options", "-gpu host"),
                           ("disable-animations", False)):
            with self.subTest(field=key):
                current = self.changed()
                current["jobs"]["contrast_26"]["steps"][3]["with"][key] = value
                self.rejects(current)
        current = self.changed(NAMES[3])
        current["jobs"]["contrast_26"]["strategy"]["matrix"]["include"][0]["pixels"] = "100x100"
        self.rejects(current, NAMES[3])

    def test_script_and_compile_mutations_are_rejected(self):
        for name in NAMES:
            for index, key in ((2, "run"), (3, "script")):
                with self.subTest(name=name, field=key):
                    current = self.changed(name)
                    step = current["jobs"]["contrast_26"]["steps"][index]
                    owner = step if key == "run" else step["with"]
                    owner[key] += "\ntrue\n"
                    self.rejects(current, name)

    def test_artifact_retention_name_and_path_mutations_are_rejected(self):
        for key, value in (("retention-days", 7), ("name", "wrong"), ("path", "wrong/"),
                           ("if-no-files-found", "warn")):
            with self.subTest(field=key):
                current = self.changed()
                current["jobs"]["contrast_26"]["steps"][5]["with"][key] = value
                self.rejects(current)

    def test_failure_status_mutations_are_rejected(self):
        for index, key, value in ((3, "continue-on-error", True), (4, "if", "always()"),
                                  (5, "if", "success()"), (1, "if", "always()")):
            with self.subTest(step=index, field=key):
                current = self.changed()
                current["jobs"]["contrast_26"]["steps"][index][key] = value
                self.rejects(current)

    def test_setup_command_and_condition_mutations_are_rejected(self):
        for key, value in (("run", "true"), ("if", "always()"), ("continue-on-error", True)):
            with self.subTest(field=key):
                action = copy.deepcopy(self.action)
                action["runs"]["steps"][3][key] = value
                self.rejects(self.changed(), action=action)

    def test_other_workflow_metadata_mutations_are_rejected(self):
        for field, value in (("name", "wrong required status"), ("needs", "other"),
                             ("timeout-minutes", 90), ("continue-on-error", True)):
            with self.subTest(field=field):
                current = self.changed()
                current["jobs"]["contrast_26"][field] = value
                self.rejects(current)
        current = self.changed()
        current["permissions"]["contents"] = "write"
        self.rejects(current)
        current = self.changed()
        current["on"]["push"]["branches"] = ["wrong"]
        self.rejects(current)


if __name__ == "__main__":
    unittest.main()
