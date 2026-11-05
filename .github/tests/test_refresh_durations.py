"""Contracts for the measured-duration profile that balances native shards."""
import csv
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'.github/test-evidence'))
import refresh_durations as refresh

PROFILE = ROOT/'.github/test-evidence/class-durations.csv'


def report(run, api, shard, cases):
    folder = run/f'device-reports-api-{api}-shard-{shard}/app/build/outputs/androidTest-results/connected'
    folder.mkdir(parents=True, exist_ok=True)
    body = ''.join(f'<testcase name="{name}" classname="{cls}" time="{seconds}">{extra}</testcase>' for cls, name, seconds, extra in cases)
    (folder/'TEST-emulator.xml').write_text(f'<testsuite name="x" tests="{len(cases)}">{body}</testsuite>')


class RefreshDurationsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def run_dir(self, name, scale):
        run = self.root/name
        for api in ('26', '36'):
            report(run, api, 0, [('a.AlphaTest', 'one', 10*scale, ''), ('a.AlphaTest', 'two', 5*scale, '')])
            report(run, api, 1, [('a.BetaTest', 'one', 7*scale, ''), ('a.BetaTest', 'skip', 0, '<skipped/>')])
        return run

    def test_totals_tests_and_skips_per_api_class(self):
        totals = refresh.run_totals(self.run_dir('one', 1))
        self.assertEqual([2, 15.0, 0], totals[('26', 'a.AlphaTest')])
        self.assertEqual([2, 7.0, 1], totals[('36', 'a.BetaTest')])

    def test_median_across_runs_and_output_matches_the_shard_planner_columns(self):
        runs = [self.run_dir('r1', 1), self.run_dir('r2', 3), self.run_dir('r3', 2)]
        out = self.root/'durations.csv'
        self.assertEqual(0, subprocess.run([sys.executable, str(ROOT/'.github/test-evidence/refresh_durations.py'),
                                            '--output', str(out), *map(str, runs)]).returncode)
        rows = list(csv.DictReader(out.open()))
        self.assertEqual(refresh.COLUMNS, list(rows[0]))
        alpha = next(r for r in rows if r['api'] == '26' and r['class'] == 'a.AlphaTest')
        self.assertEqual(('2', '30.0', '0'), (alpha['tests'], alpha['total_seconds'], alpha['skipped']))

    def test_failed_incomplete_or_foreign_reports_are_rejected(self):
        failed = self.run_dir('failed', 1)
        report(failed, 26, 1, [('a.BetaTest', 'bad', 1, '<failure/>')])
        with self.assertRaises(ValueError):
            refresh.run_totals(failed)
        incomplete = self.run_dir('incomplete', 1)
        for api in ('26',):
            report(incomplete, api, 2, [('a.GammaTest', 'one', 1, '')])
        with self.assertRaises(ValueError):
            refresh.run_totals(incomplete)
        foreign = self.run_dir('foreign', 1)
        (foreign/'device-reports-api-30-shard-0').mkdir()
        with self.assertRaises(ValueError):
            refresh.run_totals(foreign)

    def test_committed_profile_can_fill_every_workflow_shard(self):
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        shards = int(re.search(r"LOCALICA_DEVICE_SHARDS: '(\d+)'", workflow).group(1))
        totals = {'26': [], '36': []}
        for row in csv.DictReader(PROFILE.open()):
            totals[row['api']].append(float(row['total_seconds']))
        for api, seconds in totals.items():
            with self.subTest(api=api):
                # A single class longer than an even share would make the shard count pointless.
                self.assertLessEqual(max(seconds), sum(seconds)/shards)


if __name__ == '__main__':
    unittest.main()
