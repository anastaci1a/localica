"""Repeat orchestration contracts; fake instrumentation, real archive and failure handling."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import time
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / '.github/run-prototype-repeat.py'


class RepeatContract(unittest.TestCase):
    def test_bounds(self):
        spec = importlib.util.spec_from_file_location('repeat_runner', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for value in ('1', '20', '30'):
            self.assertEqual(int(value), module.repeat_count(value))
        for value in ('0', '31', '-1', '1.5', '01', '', '1\n', 'abc'):
            with self.assertRaises(ValueError):
                module.repeat_count(value)

    def run_case(self, fail_at=0, missing_at=0, count=3):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / '.github').mkdir()
        (root / '.github/run-prototype-tests.sh').write_text('''#!/bin/bash
set -eu
[[ "$PROTOTYPE_REPEAT" == 1 ]]
[[ ! -e prototype-evidence && ! -e app/build/outputs/androidTest-results ]]
n=$(cat calls 2>/dev/null || echo 0)
n=$((n+1))
echo "$n" > calls
mkdir -p prototype-evidence app/build/outputs/androidTest-results app/build/reports/androidTests app/build/outputs/connected_android_test_additional_output
printf '<testsuite iteration="%s"/>' "$n" > app/build/outputs/androidTest-results/result.xml
printf '{"iteration":%s}' "$n" > prototype-evidence/reconciled-tests.json
if [[ "$n" == "$MISSING_AT" ]]; then rm prototype-evidence/reconciled-tests.json; fi
if [[ "$n" == "$FAIL_AT" ]]; then exit 7; fi
''')
        env = dict(os.environ, PROTOTYPE_REPEAT=str(count), GITHUB_SHA='a'*40,
                   TEST_API='26', FAIL_AT=str(fail_at), MISSING_AT=str(missing_at))
        result = subprocess.run(['python3', str(SCRIPT)], cwd=root, env=env, capture_output=True, text=True)
        summary = json.loads((root / 'prototype-evidence/iterations/summary.json').read_text())
        return root, result, summary

    def test_twenty_independent_iterations_keep_xml(self):
        root, result, summary = self.run_case(count=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(20, len(summary['iterations']))
        for index, item in enumerate(summary['iterations'], 1):
            self.assertEqual('passed', item['status'])
            xml = root / f'prototype-evidence/iterations/iteration-{index:02d}/androidTest-results/result.xml'
            self.assertIn(f'iteration="{index}"', xml.read_text())
        self.assertEqual({'iteration': 20}, json.loads((root / 'prototype-evidence/reconciled-tests.json').read_text()))

    def test_failure_preserves_xml_and_never_retries(self):
        root, result, summary = self.run_case(fail_at=2)
        self.assertEqual(7, result.returncode)
        self.assertEqual(['passed', 'failed', 'not-run'], [i['status'] for i in summary['iterations']])
        self.assertEqual('2', (root / 'calls').read_text().strip())
        self.assertTrue((root / 'prototype-evidence/iterations/iteration-02/androidTest-results/result.xml').exists())

    def test_cancellation_retains_partial_iteration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.github').mkdir()
            (root / '.github/run-prototype-tests.sh').write_text('''#!/bin/bash
trap 'exit 143' TERM
mkdir prototype-evidence
touch ready
while true; do sleep .05; done
''')
            env = dict(os.environ, PROTOTYPE_REPEAT='3', GITHUB_SHA='a'*40, TEST_API='26')
            child = subprocess.Popen(['python3', str(SCRIPT)], cwd=root, env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 5
                while not (root/'ready').exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue((root/'ready').exists())
                child.send_signal(signal.SIGTERM)
                child.communicate(timeout=5)
                self.assertEqual(143, child.returncode)
                summary = json.loads((root/'prototype-evidence/iterations/summary.json').read_text())
                self.assertEqual(['failed', 'not-run', 'not-run'], [i['status'] for i in summary['iterations']])
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate()

    def test_missing_receipt_fails_closed(self):
        _, result, summary = self.run_case(missing_at=1)
        self.assertEqual(2, result.returncode)
        self.assertEqual(['failed', 'not-run', 'not-run'], [i['status'] for i in summary['iterations']])


if __name__ == '__main__':
    unittest.main()
