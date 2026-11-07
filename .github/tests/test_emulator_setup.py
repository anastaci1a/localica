import importlib.util
import itertools
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import yaml

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / '.github' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ready = load('emulator-readiness')
cache = load('emulator-system-image')


class Clock:
    time = 0

    def now(self):
        return self.time

    def sleep(self, seconds):
        self.time += seconds


class FakeDevice:
    def __init__(self, states):
        self.states = states
        self.index = -1
        self.calls = []
        self.launcher_resets = 0

    def call(self, *args, binary=False):
        self.calls.append(args)
        if args == ('shell', 'getprop', 'sys.boot_completed'):
            self.index += 1
        state = self.states[min(self.index, len(self.states) - 1)]
        if args == ('shell', 'getprop', 'ro.build.version.sdk'):
            return state.get('sdk', '36')
        if args == ('shell', 'getprop', 'ro.kernel.qemu'):
            return state.get('qemu', '1')
        if args == ('get-serialno',):
            return state.get('serial', 'emulator-5554')
        if args == ('shell', 'settings', 'get', 'secure', 'navigation_mode'):
            return state.get('navigation_mode', '0')
        if args == ('shell', 'am', 'force-stop', 'com.android.launcher3'):
            self.launcher_resets += 1
            return ''
        if args == ('shell', 'getprop', 'sys.boot_completed'):
            return state.get('boot', '1')
        if args == ('shell', 'getprop', 'init.svc.bootanim'):
            return state.get('bootanim', 'stopped')
        if args == ('shell', 'pm', 'path', 'android'):
            return state.get('pm', 'package:/system/framework/framework-res.apk')
        if args[:4] == ('shell', 'cmd', 'package', 'resolve-activity'):
            return 'priority=0\n' + state.get('home', 'com.android.launcher3/.Launcher')
        if args == ('shell', 'dumpsys', 'window', 'displays'):
            if state.get('missing_navigation'):
                return ''
            bottom = state.get('navigation_bottom', 84) if self.launcher_resets else 126
            return native_display(bottom=bottom)
        if args == ('shell', 'dumpsys', 'window'):
            if state.get('error'):
                raise subprocess.TimeoutExpired('adb', 10)
            focus = state.get('focus', 'com.android.launcher3/.Launcher')
            if self.launcher_resets:
                focus = state.get('focus_after_reset', focus)
            locked = str(state.get('locked', False)).lower()
            screen = 'SCREEN_STATE_OFF' if state.get('asleep') else 'SCREEN_STATE_ON'
            return (f'WINDOW MANAGER DISPLAY CONTENTS\nmCurrentFocus=Window{{abc u0 {focus}}}\n'
                    f'KeyguardServiceDelegate\n showing={locked}\n inputRestricted={locked}\n'
                    f' screenState={screen}\n interactiveState=INTERACTIVE_STATE_AWAKE\n')
        if args == ('exec-out', 'screencap', '-p'):
            return state.get('frame', b'\x89PNG\r\n\x1a\nframe')
        return ''


def native_display(bottom=84, density=280, display=0):
    return (f'Display: mDisplayId={display} (organized)\n'
            f' init=1080x1920 420dpi base=720x1280 {density}dpi cur=720x1280\n'
            f' InsetsSource id=123 type=navigationBars frame=[0,{1280 - bottom}][720,1280] visible=true\n')


class ReadinessTests(unittest.TestCase):
    def run_gate(self, states, timeout=4, normalize_navigation=False):
        clock, device = Clock(), FakeDevice(states)
        result = ready.wait_ready(device, timeout, clock.now, clock.sleep,
                                  normalize_navigation=normalize_navigation)
        return result, device, clock

    def test_boot_package_focus_unlock_and_three_stable_frames(self):
        result, device, clock = self.run_gate([{'boot': '0'}, {'pm': ''}, {}, {}, {}])
        self.assertTrue(result['ready'])
        self.assertEqual(5, len(result['samples']))
        self.assertEqual(2, clock.time)
        self.assertEqual(1, sum(args[:3] == ('shell', 'am', 'start') for args in device.calls))
        self.assertEqual(3, sum(args[:2] == ('exec-out', 'screencap') for args in device.calls))

    def test_focus_loss_resets_stable_frames(self):
        result, _, _ = self.run_gate([{}, {}, {'focus': 'Application Error'}, {}, {}, {}])
        self.assertTrue(result['ready'])
        self.assertEqual([1, 2, 0, 1, 2, 3], [s['stable_samples'] for s in result['samples']])

    def test_disabled_boot_animation_service_still_requires_live_focus_and_frames(self):
        result, _, _ = self.run_gate([{'bootanim': ''}])
        self.assertTrue(result['ready'])
        self.assertEqual(3, len(result['samples']))
        self.assertEqual('', result['samples'][-1]['boot_animation_state'])
        result, _, _ = self.run_gate([{'bootanim': '', 'focus': 'Application Error'}])
        self.assertFalse(result['ready'])

    def test_animation_must_settle_before_success(self):
        png = b'\x89PNG\r\n\x1a\n'
        result, _, _ = self.run_gate([{'frame': png + b'one'}, {'frame': png + b'two'}, {}, {}, {}])
        self.assertTrue(result['ready'])
        self.assertEqual(5, len(result['samples']))

    def test_missing_focus_locked_screen_boot_animation_and_broken_adb_fail_bounded(self):
        for state in ({'focus': 'com.android.systemui/.Dialog'}, {'locked': True},
                      {'bootanim': 'running'}, {'error': True}, {'frame': b''}, {'asleep': True}):
            with self.subTest(state=state):
                result, _, clock = self.run_gate([state], timeout=2)
                self.assertFalse(result['ready'])
                self.assertEqual(2, clock.time)

    def test_missing_keyguard_signal_is_not_unlocked(self):
        self.assertFalse(ready.unlocked('showing=false\ninputRestricted=false'))
        self.assertFalse(ready.unlocked('KeyguardServiceDelegate\nshowing=false'))
        self.assertFalse(ready.focused_home('mFocusedApp=com.android.launcher3/.Launcher', 'com.android.launcher3'))

    def test_failed_focus_retains_the_observed_obstruction(self):
        result, _, _ = self.run_gate([{'focus': 'Application Error: com.android.launcher3'}])
        self.assertFalse(result['ready'])
        self.assertEqual(['Window{abc u0 Application Error: com.android.launcher3}'],
                         result['samples'][-1]['current_focus_entries'])
        self.assertIn('Application Error: com.android.launcher3', result['last_window_dump'])
        self.assertIn('home_launch_result', result['samples'][0])

    def test_historical_anr_launcher_does_not_override_current_focus(self):
        historical = 'mCurrentFocus=Window{old u0 com.android.launcher3/.Launcher}\n'
        for current in ('null', 'Window{now u0 Application Error: com.android.launcher3}'):
            self.assertFalse(ready.focused_home(historical + 'mCurrentFocus=' + current,
                                                'com.android.launcher3'))

    def test_api26_numeric_and_api36_named_awake_states(self):
        prefix = 'KeyguardServiceDelegate\n showing=false\n inputRestricted=false\n'
        for state in ('screenState=2\ninteractiveState=1',
                      'screenState=SCREEN_STATE_ON\ninteractiveState=INTERACTIVE_STATE_AWAKE'):
            self.assertTrue(ready.unlocked(prefix + state))
        for state in ('screenState=1\ninteractiveState=1', 'screenState=2\ninteractiveState=2'):
            self.assertFalse(ready.unlocked(prefix + state))

    def test_adb_call_uses_remaining_deadline_not_an_unbounded_wait(self):
        with mock.patch.object(ready.time, 'monotonic', return_value=98), \
                mock.patch.object(ready.subprocess, 'run') as command:
            command.return_value.stdout = b'1\n'
            self.assertEqual('1', ready.Device(100).call('shell', 'getprop', 'sys.boot_completed'))
            self.assertEqual(2, command.call_args.kwargs['timeout'])
        with mock.patch.object(ready.time, 'monotonic', return_value=101):
            with self.assertRaises(TimeoutError):
                ready.Device(100).call('shell', 'getprop', 'sys.boot_completed')

    def test_explicit_reset_replaces_stale_geometry_and_cold_launcher_anr_once(self):
        result, device, _ = self.run_gate([{
            'focus': 'Application Not Responding: com.android.launcher3',
            'focus_after_reset': 'com.android.launcher3/.Launcher'}], normalize_navigation=True)
        self.assertTrue(result['ready'])
        self.assertEqual(1, device.launcher_resets)
        before = result['navigation_normalization']
        self.assertEqual([(0, 1154, 720, 1280)], before['before_navigation']['frames'])
        self.assertIn('Not Responding', before['before_focus'][0])
        self.assertEqual([0, 0, 1, 2, 3], [s['stable_samples'] for s in result['samples']])
        self.assertEqual([1, 2, 3], [s['pre_reset_stable_samples'] for s in result['samples'][:3]])
        self.assertEqual(84, result['samples'][-1]['navigation']['expected_bottom_inset'])
        stop = device.calls.index(('shell', 'am', 'force-stop', 'com.android.launcher3'))
        launch = next(i for i, args in enumerate(device.calls) if args[:3] == ('shell', 'am', 'start'))
        self.assertLess(stop, launch)

    def test_launcher_reset_waits_for_an_idle_display(self):
        # #637: a force-stop during the first post-boot frames panicked goldfish_sync.
        png = b'\x89PNG\r\n\x1a\n'
        result, device, _ = self.run_gate(
            [{'frame': png + b'one'}, {'frame': png + b'two'}, {}, {}, {}, {}, {}],
            normalize_navigation=True)
        self.assertTrue(result['ready'])
        self.assertEqual(1, device.launcher_resets)
        self.assertEqual([1, 1, 1, 2, 3], [s['pre_reset_stable_samples'] for s in result['samples'][:5]])
        stop = device.calls.index(('shell', 'am', 'force-stop', 'com.android.launcher3'))
        captures = [i for i, args in enumerate(device.calls) if args[:2] == ('exec-out', 'screencap')]
        self.assertEqual(5, sum(i < stop for i in captures))
        result, device, clock = self.run_gate(
            [{'frame': png + bytes([n])} for n in range(8)], timeout=2, normalize_navigation=True)
        self.assertFalse(result['ready'])
        self.assertEqual(0, device.launcher_resets)
        self.assertIsNone(result['navigation_normalization'])
        self.assertEqual(2, clock.time)

    def test_navigation_change_resets_stable_frames_and_cannot_be_waived(self):
        result, device, _ = self.run_gate([
            {'navigation_bottom': n} for n in (126, 126, 126, 84, 126, 84, 84, 84)], normalize_navigation=True)
        self.assertTrue(result['ready'])
        self.assertEqual([0, 0, 0, 1, 0, 1, 2, 3], [s['stable_samples'] for s in result['samples']])
        self.assertEqual(1, device.launcher_resets)
        for state in ({'navigation_bottom': 126}, {'missing_navigation': True},
                      {'focus': 'Application Error: another.package'}):
            with self.subTest(state=state):
                result, device, clock = self.run_gate([state], timeout=2, normalize_navigation=True)
                self.assertFalse(result['ready'])
                self.assertEqual(1, device.launcher_resets)
                self.assertEqual(2, clock.time)

    def test_navigation_reset_refuses_physical_foreign_home_and_gesture_devices(self):
        for state in ({'qemu': '0'}, {'serial': 'physical-device'},
                      {'home': 'another.package/.Home'}, {'navigation_mode': '2'}):
            with self.subTest(state=state):
                result, device, _ = self.run_gate([state], normalize_navigation=True)
                self.assertFalse(result['ready'])
                self.assertEqual('failed', result['navigation_normalization']['status'])
                self.assertEqual(0, device.launcher_resets)

    def test_api26_does_not_reset_launcher_or_require_api36_inset_dump(self):
        result, device, _ = self.run_gate([{'sdk': '26'}], normalize_navigation=True)
        self.assertTrue(result['ready'])
        self.assertEqual('not-applicable', result['navigation_normalization']['status'])
        self.assertEqual(0, device.launcher_resets)

    def test_native_geometry_uses_effective_density_and_only_current_display_zero(self):
        for density, bottom in ((280, 84), (420, 126), (295, 89)):
            with self.subTest(density=density):
                self.assertTrue(ready.navigation_geometry(native_display(bottom, density))['ready'])
        self.assertFalse(ready.navigation_geometry(native_display(126))['ready'])
        self.assertFalse(ready.navigation_geometry('no current native providers')['ready'])
        full_dump = (native_display(126) + '\nWINDOW MANAGER DISPLAY CONTENTS\n' +
                     native_display() + '\n' + native_display(126, display=1))
        self.assertEqual([(0, 1196, 720, 1280)], ready.navigation_geometry(full_dump)['frames'])


class CacheTests(unittest.TestCase):
    def test_available_revision_ignores_installed_and_other_images(self):
        package = 'system-images;android-26;google_apis;x86_64'
        text = (f'Installed packages:\n{package} | 15 | Old\nAvailable Packages:\n'
                f'{package} | 16 | Google APIs\nsystem-images;android-36;default;x86_64 | 9 | Other\n')
        self.assertEqual('16', cache.available_revision(text, package))
        with self.assertRaises(ValueError):
            cache.available_revision(text.split('Available Packages:')[0], package)

    def test_only_complete_installed_images_supply_a_revision(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'source.properties').write_text('Pkg.Revision=16\n')
            self.assertIsNone(cache.installed_revision(directory))
            (directory / 'system.img').write_bytes(b'image')
            self.assertEqual('16', cache.installed_revision(directory))

    def test_changed_installed_revision_cannot_be_saved_under_previous_key(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            image = root / 'system-images/android-26/google_apis/x86_64'
            image.mkdir(parents=True)
            (image / 'source.properties').write_text('Pkg.Revision=17\n')
            (image / 'system.img').write_bytes(b'image')
            (root / 'localica-image-cache-26.json').write_text(json.dumps({
                'revision': '16', 'path': str(image), 'key': 'image-r16'}))
            output = root / 'outputs'
            with mock.patch.dict(os.environ, {'RUNNER_TEMP': temp, 'ANDROID_HOME': temp,
                                              'GITHUB_OUTPUT': str(output)}), \
                    mock.patch('sys.argv', ['cache', 'save', '--api', '26']):
                cache.main()
                self.assertIn('save=false\n', output.read_text())
                (image / 'source.properties').write_text('Pkg.Revision=16\n')
                output.write_text('')
                cache.main()
                self.assertIn('save=true\n', output.read_text())


class WorkflowTests(unittest.TestCase):
    def test_prototypes_use_at_most_two_runners_without_skipping_failed_checks(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/prototype.yml').read_text())['jobs']
        parents = {'compatibility_26': 'build', 'compatibility_36': 'compatibility_26',
                   'focused_36': 'focused_26'}
        for job, parent in parents.items():
            self.assertEqual(parent, jobs[job]['needs'])
            self.assertEqual('${{ !cancelled() }}', jobs[job]['if'])
        names = set(jobs) - {'evidence'}
        self.assertEqual({'build', 'focused_26', 'focused_36', 'compatibility_26', 'compatibility_36'}, names)

        def ancestors(name):
            result = set()
            while name in parents:
                name = parents[name]
                result.add(name)
            return result

        # No three checks can run together, regardless of individual durations.
        for triple in itertools.combinations(names, 3):
            self.assertTrue(any(a in ancestors(b) or b in ancestors(a)
                                for a, b in itertools.combinations(triple, 2)))
        self.assertEqual(names, set(jobs['evidence']['needs']))
        self.assertEqual('${{ always() }}', jobs['evidence']['if'])

    def test_every_emulator_lane_polls_and_caches_with_retained_diagnostics(self):
        count = 0
        for filename in ('android.yml', 'prototype.yml'):
            workflow = yaml.safe_load((ROOT / '.github/workflows' / filename).read_text())
            for job in workflow['jobs'].values():
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    if not step.get('uses', '').startswith('reactivecircus/android-emulator-runner@'):
                        continue
                    count += 1
                    script = step['with']['script']
                    self.assertNotIn('sleep 60', script)
                    self.assertLess(script.index('wm density'), script.index('emulator-readiness.py'))
                    self.assertIn('emulator-readiness.py --normalize-navigation', script)
                    self.assertEqual('./.github/actions/system-image-cache', steps[index - 1]['uses'])
                    self.assertEqual(step['with']['api-level'], steps[index - 1]['with']['api'])
                    self.assertEqual('save', steps[index + 1]['with']['mode'])
                    self.assertEqual('always()', steps[index + 2]['if'])
                    self.assertEqual('emulator-readiness.json', steps[index + 2]['with']['path'])
        self.assertEqual(7, count)

    def test_image_cache_uses_exact_key_and_revision_checked_save(self):
        action = yaml.safe_load((ROOT / '.github/actions/system-image-cache/action.yml').read_text())
        restore, save = action['runs']['steps'][1:]
        self.assertNotIn('restore-keys', restore['with'])
        self.assertIn("steps.image.outputs.save == 'true'", save['if'])
        self.assertRegex(restore['uses'], r'@\w{40}$')

    def test_complete_run_preserves_next_release_profile_after_union_check(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/android.yml').read_text())
        steps = workflow['jobs']['device-tests-summary']['steps']
        union = next(i for i, step in enumerate(steps) if 'full_partition.py union' in step.get('run', ''))
        refresh = next(i for i, step in enumerate(steps) if 'refresh_durations.py' in step.get('run', ''))
        self.assertGreater(refresh, union)
        artifact = next(step for step in steps if step.get('with', {}).get('name') == 'complete-verification')
        self.assertIn('class-durations.csv', artifact['with']['path'].splitlines())


if __name__ == '__main__':
    unittest.main()
