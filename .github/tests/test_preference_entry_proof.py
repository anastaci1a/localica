"""Fail-closed admission controls for separate service-only preference entry processes."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / '.github/run-preference-entry-proof.sh'

FAKE_ADB = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
args = sys.argv[1:]
assert args[:2] == ['-s', 'emulator-5554']
args = args[2:]
root = Path(os.environ['FAKE_ROOT'])
mode_file = root / 'mode'
fail = os.environ.get('FAIL_CONTROL', '')
if args[0] == 'install':
    print('Success')
elif args[:3] == ['shell', 'am', 'force-stop']:
    pass
elif args[:2] == ['shell', 'pidof']:
    if fail == 'live-process': print(999)
elif args[:3] == ['shell', 'am', 'instrument']:
    mode = args[args.index('preparedEntry') + 1]
    assert args[args.index('expectFatalPolicy') + 1] == 'true'
    assert args[args.index('expectSeededTracks') + 1] == '13400'
    mode_file.write_text(mode)
    pid = 1001 + ['playback', 'scan', 'tag', 'lastfm'].index(mode)
    if fail == 'reused-pid': pid = 1001
    (root / 'pid').write_text(str(pid))
    if fail == 'failed-instrumentation':
        print('FAILURES!!!\nProcess crashed')
    else:
        if fail != 'missing-receipt':
            print(f'INSTRUMENTATION_STATUS: stream=prepared-entry={mode};pid={pid};activities=0;managedUsed=1;gcCount=0')
        if mode == 'playback' and fail != 'missing-attachment':
            print('INSTRUMENTATION_STATUS: stream=playback-attached=true;play-pause-order=true;controller-released=true;service-stopped=true;durable-position=1234;native-released=true;player-id=ab12')
        print('OK (1 test)')
elif args[0] == 'logcat':
    if not mode_file.exists(): sys.exit(0)
    mode = mode_file.read_text()
    pid = int((root / 'pid').read_text())
    if '-b' in args and args[args.index('-b') + 1] == 'events':
        if mode in ('scan', 'tag') and fail != 'missing-service':
            service = 'ScanService' if mode == 'scan' else 'TagEditService'
            event_pid = pid + 50 if fail == 'wrong-service-pid' else pid
            notification = 9001 if mode == 'scan' else 9010
            if fail.startswith('deferred-'):
                owner_pid = pid + 50 if fail == 'deferred-wrong-pid' else pid
                process = f'11-10 11:00:00.000 100 100 I am_proc_start: [0,{owner_pid},10001,io.github.anastaci1a.localica.debug,added application,io.github.anastaci1a.localica.debug]'
                service_package = 'scanner' if mode == 'scan' else 'editing'
                event = f'11-10 11:00:01.000 100 100 I am_foreground_service_start: [0,io.github.anastaci1a.localica.debug/io.github.anastaci1a.localica.{service_package}.{service},1,ACTIVITY_STARTER,36]'
                if fail == 'deferred-stale': print(event)
                print(process)
                if fail == 'deferred-restarted': print(process.replace(str(owner_pid), str(owner_pid + 60), 1))
                if fail == 'deferred-dead': print(f'11-10 11:00:00.500 100 100 I am_proc_died: [0,{owner_pid},io.github.anastaci1a.localica.debug,0]')
                if fail not in ('deferred-stale', 'deferred-missing'): print(event)
                if fail in ('deferred-dead-after', 'deferred-reused-after'):
                    print(f'11-10 11:00:01.500 100 100 I am_proc_died: [0,{owner_pid},io.github.anastaci1a.localica.debug,0]')
                if fail == 'deferred-restarted-after': print(process.replace(str(owner_pid), str(owner_pid + 60), 1))
                if fail == 'deferred-reused-after': print(process)
                if fail == 'deferred-same-start-after': print(process)
            else:
                print(f'11-10 11:00:00.000 100 100 I notification_enqueue: [10001,{event_pid},io.github.anastaci1a.localica.debug,{notification},NULL,0,Notification(),0]')
    elif '-b' not in args:
        log_pid = pid + 50 if fail == 'wrong-player-pid' else pid
        print(f'11-10 11:00:00.000 {log_pid} {log_pid} I ExoPlayerImpl: Init ab12 [AndroidXMedia3]')
        if fail != 'missing-release':
            print(f'11-10 11:00:01.000 {log_pid} {log_pid} I ExoPlayerImpl: Release ab12 [AndroidXMedia3]')
else:
    raise AssertionError(args)
'''


class PreferenceEntryAdmission(unittest.TestCase):
    def run_protocol(self, control=''):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            fake = root / 'adb'
            fake.write_text(FAKE_ADB)
            fake.chmod(0o755)
            env = {**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                   'FAKE_ROOT': str(root), 'FAIL_CONTROL': control}
            completed = subprocess.run(['bash', str(SCRIPT), 'emulator-5554'], cwd=root,
                                       env=env, capture_output=True, text=True, timeout=20)
            receipts = {p.parent.name: p.read_text() for p in root.glob('prototype-evidence/prepared-entry-cold/*/receipt.txt')}
            diagnostics = list(root.glob('prototype-evidence/prepared-entry-cold/*/logcat.txt'))
            return completed, receipts, bool(diagnostics)

    def test_all_four_distinct_processes_require_matching_native_and_service_receipts(self):
        result, receipts, _ = self.run_protocol()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'playback', 'scan', 'tag', 'lastfm'}, set(receipts))
        for receipt in receipts.values():
            self.assertIn('process-absent-before=true', receipt)
            self.assertIn('tracks=13400\nstrictmode=fatal', receipt)

    def test_deferred_notification_requires_component_in_matching_live_process_interval(self):
        result, receipts, _ = self.run_protocol('deferred-valid')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('foreground-component=true;process-owner-pid=1002', receipts['scan'])
        for control in ('deferred-wrong-pid', 'deferred-stale', 'deferred-restarted', 'deferred-dead', 'deferred-missing', 'deferred-dead-after', 'deferred-restarted-after', 'deferred-reused-after', 'deferred-same-start-after'):
            with self.subTest(control=control):
                result, _, _ = self.run_protocol(control)
                self.assertNotEqual(0, result.returncode)

    def test_missing_or_failed_instrumentation_receipts_are_rejected(self):
        for control in ('failed-instrumentation', 'missing-receipt'):
            with self.subTest(control=control):
                result, receipts, diagnostics = self.run_protocol(control)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(diagnostics)

    def test_missing_or_wrong_pid_service_entry_is_rejected(self):
        for control in ('missing-service', 'wrong-service-pid'):
            with self.subTest(control=control):
                result, receipts, diagnostics = self.run_protocol(control)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(diagnostics)

    def test_missing_attachment_release_or_matching_player_pid_is_rejected(self):
        for control in ('missing-attachment', 'missing-release', 'wrong-player-pid'):
            with self.subTest(control=control):
                result, receipts, diagnostics = self.run_protocol(control)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(diagnostics)
                self.assertNotIn("strictmode=fatal", receipts["playback"])

    def test_live_or_reused_process_is_rejected(self):
        for control in ('live-process', 'reused-pid'):
            with self.subTest(control=control):
                result, _, _ = self.run_protocol(control)
                self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
