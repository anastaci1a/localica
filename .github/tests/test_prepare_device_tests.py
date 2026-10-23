"""Host-only safety and runner-contract checks; never contact an Android device."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.google.android.googlequicksearchbox"


class PrepareDeviceTestsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".github").symlink_to(ROOT / ".github", target_is_directory=True)
        self.log = self.root / "commands.jsonl"
        fake_adb = self.root / "adb"
        fake_adb.write_text("""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps(['adb'] + args) + '\\n')
if args == ['shell', 'getprop', 'ro.kernel.qemu']:
    print(os.environ['FAKE_QEMU'] + '\\r')
elif args == ['shell', 'getprop', 'ro.build.version.sdk']:
    if os.environ.get('SDK_READ_FAILURE'):
        sys.exit(17)
    print(os.environ['FAKE_API'] + '\\r')
elif args == ['shell', 'pm', 'list', 'packages', 'com.google.android.googlequicksearchbox']:
    print(os.environ['FAKE_PACKAGES'])
elif args == ['shell', 'am', 'force-stop', '--user', '0', 'com.google.android.googlequicksearchbox']:
    sys.exit(int(os.environ.get('FORCE_STOP_RESULT', '0')))
else:
    sys.exit('Unexpected adb command: ' + repr(args))
""")
        fake_adb.chmod(0o755)
        fake_gradle = self.root / "gradlew"
        fake_gradle.write_text("""#!/usr/bin/env python3
import json, os, sys
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps(['gradlew'] + sys.argv[1:]) + '\\n')
""")
        fake_gradle.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": f"{self.root}:{os.environ['PATH']}",
            "COMMAND_LOG": str(self.log),
            "GITHUB_ACTIONS": "true",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "FAKE_QEMU": "1",
            "FAKE_API": "26",
            "FAKE_PACKAGES": f"package:{PACKAGE}\r\n",
            "RISK_CLASSES": "",
        }

    def run_script(self, script="prepare-device-tests.sh", **environment):
        return subprocess.run(
            ["bash", str(ROOT / ".github" / script)],
            cwd=self.root,
            env={**self.environment, **environment},
            capture_output=True,
            text=True,
            check=False,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def mutations(self):
        return [command for command in self.commands() if command[:3] == ["adb", "shell", "am"]]

    def test_local_run_never_contacts_any_device(self):
        self.assertEqual(self.run_script(GITHUB_ACTIONS="false").returncode, 0)
        self.assertEqual(self.commands(), [])

    def test_self_hosted_run_never_contacts_any_device(self):
        self.assertEqual(self.run_script(RUNNER_ENVIRONMENT="self-hosted").returncode, 0)
        self.assertEqual(self.commands(), [])

    def test_physical_device_is_rejected_without_mutation(self):
        self.assertEqual(self.run_script(FAKE_QEMU="").returncode, 2)
        self.assertEqual(self.commands(), [["adb", "shell", "getprop", "ro.kernel.qemu"]])

    def test_api36_is_untouched(self):
        self.assertEqual(self.run_script(FAKE_API="36").returncode, 0)
        self.assertEqual(len(self.commands()), 2)
        self.assertEqual(self.mutations(), [])

    def test_missing_api_is_rejected_without_mutation(self):
        self.assertEqual(self.run_script(FAKE_API="").returncode, 2)
        self.assertEqual(self.mutations(), [])

    def test_failed_property_read_is_not_silently_ignored(self):
        self.assertEqual(self.run_script(SDK_READ_FAILURE="true").returncode, 17)
        self.assertEqual(self.mutations(), [])

    def test_absent_package_is_untouched(self):
        self.assertEqual(self.run_script(FAKE_PACKAGES="").returncode, 0)
        self.assertEqual(self.mutations(), [])

    def test_similarly_named_package_is_untouched(self):
        self.assertEqual(self.run_script(FAKE_PACKAGES=f"package:{PACKAGE}.unrelated").returncode, 0)
        self.assertEqual(self.mutations(), [])

    def test_only_exact_unrelated_package_is_force_stopped(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.mutations(), [["adb", "shell", "am", "force-stop", "--user", "0", PACKAGE]])
        self.assertEqual(len(self.commands()), 4)

    def test_setup_failure_prevents_starting_instrumentation(self):
        self.assertEqual(self.run_script("run-device-tests.sh", FORCE_STOP_RESULT="23").returncode, 23)
        self.assertFalse(any(command[0] == "gradlew" for command in self.commands()))

    def test_full_suite_still_runs_without_any_selector(self):
        self.assertEqual(self.run_script("run-device-tests.sh").returncode, 0)
        self.assertEqual(self.commands()[-1], ["gradlew", "connectedDebugAndroidTest", "--no-daemon", "--max-workers=2"])
        self.assertEqual(len(self.mutations()), 1)

    def test_invalid_selector_does_not_mutate_environment(self):
        self.assertEqual(self.run_script("run-device-tests.sh", RISK_CLASSES="invalid class").returncode, 2)
        self.assertEqual(self.commands(), [])


if __name__ == "__main__":
    unittest.main()
