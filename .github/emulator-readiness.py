#!/usr/bin/env python3
"""Bounded cold-emulator readiness gate, before any test APK is launched."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def home_component(text):
    return next((line.strip() for line in reversed(text.splitlines())
                 if re.fullmatch(r'[\w.]+/[\w.$]+', line.strip())), None)


def unlocked(policy):
    # KeyguardServiceDelegate.dump() exposes these on both supported APIs.
    delegate = policy.rpartition('KeyguardServiceDelegate')[2]
    return bool(re.search(r'^\s*showing=false\s*$', delegate, re.M) and
                re.search(r'^\s*inputRestricted=false\s*$', delegate, re.M) and
                # API 26 prints the enum integers; API 36 prints their names.
                re.search(r'^\s*screenState=(?:2|SCREEN_STATE_ON)\s*$', delegate, re.M) and
                re.search(r'^\s*interactiveState=(?:1|INTERACTIVE_STATE_AWAKE)\s*$', delegate, re.M))


def focused_home(windows, package):
    # A full dump can include an earlier ANR snapshot. The current display state
    # follows it; a current null/error focus must override a historical launcher.
    focus = re.findall(r'^\s*mCurrentFocus=(.*)$', windows, re.M)
    return bool(focus and focus[-1].startswith('Window{') and
                re.search(r'\b' + re.escape(package) + r'/', focus[-1]))


class Device:
    def __init__(self, deadline):
        self.deadline = deadline

    def call(self, *args, binary=False):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Emulator readiness deadline reached')
        result = subprocess.run(['adb', *args], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=min(10, remaining), check=True)
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def wait_ready(device, deadline, clock=time.monotonic, sleep=time.sleep):
    home = None
    previous = None
    stable = 0
    samples = []
    last_windows = ''
    while clock() < deadline:
        sample = {'elapsed_remaining': round(deadline - clock(), 2)}
        try:
            sample['boot'] = device.call('shell', 'getprop', 'sys.boot_completed') == '1'
            # With -no-boot-anim the image may never start this optional service.
            # An absent service is not a boot failure; actual focus and frames below
            # must still prove the launcher is ready. Preserve the raw diagnostic.
            sample['boot_animation_state'] = device.call('shell', 'getprop', 'init.svc.bootanim')
            sample['boot_animation_stopped'] = sample['boot_animation_state'] in ('', 'stopped')
            sample['package_manager'] = device.call('shell', 'pm', 'path', 'android').startswith('package:')
            if all(sample[key] for key in ('boot', 'boot_animation_stopped', 'package_manager')):
                if home is None:
                    candidate = home_component(device.call('shell', 'cmd', 'package', 'resolve-activity',
                                                           '--brief', '-a', 'android.intent.action.MAIN',
                                                           '-c', 'android.intent.category.HOME'))
                    if candidate is None:
                        raise ValueError('No resolved HOME activity')
                    device.call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                    device.call('shell', 'wm', 'dismiss-keyguard')
                    # Wait for the launcher draw after wm size/density changes.
                    launch = device.call('shell', 'am', 'start', '-W', '-n', candidate,
                                         '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
                    sample['home_launch_result'] = launch[:4096]
                    if 'Error:' in launch:
                        raise ValueError('HOME launch failed: ' + launch)
                    home = candidate
                sample['home'] = home
                # API 36 puts current focus in the displays section, not `windows`.
                windows = device.call('shell', 'dumpsys', 'window')
                last_windows = windows[:262144]
                sample['current_focus_entries'] = re.findall(r'^\s*mCurrentFocus=(.*)$', windows, re.M)
                sample['unlocked'] = unlocked(windows)
                sample['focused_home'] = focused_home(windows, home.split('/')[0])
                if sample['unlocked'] and sample['focused_home']:
                    frame = device.call('exec-out', 'screencap', '-p', binary=True)
                    if not frame.startswith(b'\x89PNG\r\n\x1a\n'):
                        raise ValueError('No rendered PNG screenshot')
                    sample['frame_sha256'] = hashlib.sha256(frame).hexdigest()
            current = sample.get('frame_sha256')
            stable = stable + 1 if current and current == previous else (1 if current else 0)
            previous = current
            sample['stable_samples'] = stable
            samples.append(sample)
            # Three independently captured frames, with all readiness signals still true.
            if stable >= 3:
                return {'ready': True, 'samples': samples}
        except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
            previous, stable = None, 0
            sample['error'] = str(error)
            samples.append(sample)
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(.5, remaining))
    return {'ready': False, 'samples': samples, 'last_window_dump': last_windows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=120, choices=range(1, 181), metavar='1..180')
    parser.add_argument('--output', type=Path, default=Path('emulator-readiness.json'))
    args = parser.parse_args()
    start = time.monotonic()
    result = wait_ready(Device(start + args.timeout), start + args.timeout)
    result['elapsed_seconds'] = round(time.monotonic() - start, 3)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    return 0 if result['ready'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
