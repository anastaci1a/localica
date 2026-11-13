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


def navigation_geometry(displays):
    """Read current display-zero providers, never a historical last-ANR snapshot."""
    if 'WINDOW MANAGER DISPLAY CONTENTS' in displays:
        displays = displays.rpartition('WINDOW MANAGER DISPLAY CONTENTS')[2]
    display = re.search(r'Display: mDisplayId=0\b(.*?)(?=\n\s*Display: mDisplayId=|\Z)',
                        displays, re.S)
    profile = re.search(r'\bbase=(\d+)x(\d+) (\d+)dpi cur=(\d+)x(\d+)',
                        display[1]) if display else None
    if profile is None:
        return {'ready': False, 'error': 'Missing effective display-zero profile'}
    _, _, density, width, height = map(int, profile.groups())
    expected = int(48 * density / 160 + .5)
    frames = sorted(set(tuple(map(int, values)) for values in re.findall(
        r'\bInsetsSource\b[^\n]*\btype=navigationBars frame=\[(\d+),(\d+)\]'
        r'\[(\d+),(\d+)\] visible=true', display[1])))
    return {'ready': frames == [(0, height - expected, width, height)],
            'density_dpi': density, 'width': width, 'height': height,
            'expected_bottom_inset': expected, 'frames': frames}


def normalize_launcher(device, home):
    """One explicit reset of the disposable API36 image's Launcher3 after resizing."""
    api = int(device.call('shell', 'getprop', 'ro.build.version.sdk'))
    if api != 36:
        return {'status': 'not-applicable', 'api': api}
    serial = device.call('get-serialno')
    if (not re.fullmatch(r'emulator-\d+', serial) or
            device.call('shell', 'getprop', 'ro.kernel.qemu') != '1' or
            home.split('/')[0] != 'com.android.launcher3' or
            device.call('shell', 'settings', 'get', 'secure', 'navigation_mode') != '0'):
        raise ValueError('Navigation normalization requires an API36 emulator with Launcher3 and three-button navigation')
    windows = device.call('shell', 'dumpsys', 'window')
    before = navigation_geometry(device.call('shell', 'dumpsys', 'window', 'displays'))
    # A wm density override can leave rotation-specific providers at the old density.
    # Recreate the owner instead of hiding its scrim or changing any test assertion.
    device.call('shell', 'am', 'force-stop', 'com.android.launcher3')
    return {'status': 'reset', 'api': api, 'serial': serial, 'home': home,
            'before_navigation': before,
            'before_focus': re.findall(r'^\s*mCurrentFocus=(.*)$', windows, re.M)}


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


def screenshot_digest(device):
    frame = device.call('exec-out', 'screencap', '-p', binary=True)
    if not frame.startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('No rendered PNG screenshot')
    return hashlib.sha256(frame).hexdigest()


def wait_ready(device, deadline, clock=time.monotonic, sleep=time.sleep,
               normalize_navigation=False):
    home = None
    previous = None
    stable = 0
    # Force-stopping Launcher3 while it still renders its first post-boot/resized
    # frames races the guest goldfish_sync driver into a kernel panic (#637).
    # Reset it only after three identical frames show an idle display.
    sdk = None
    quiet_previous = None
    quiet = 0
    samples = []
    last_windows = ''
    normalization = None

    def report(is_ready):
        result = {'ready': is_ready, 'samples': samples}
        if not is_ready:
            result['last_window_dump'] = last_windows
        if normalize_navigation:
            result['navigation_normalization'] = normalization
        return result

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
            booted = all(sample[key] for key in ('boot', 'boot_animation_stopped', 'package_manager'))
            awaiting_reset = booted and normalize_navigation and normalization is None
            if awaiting_reset and sdk is None:
                sdk = device.call('shell', 'getprop', 'ro.build.version.sdk')
            if awaiting_reset and sdk == '36' and quiet < 3:
                digest = screenshot_digest(device)
                quiet = quiet + 1 if digest == quiet_previous else 1
                quiet_previous = digest
                sample['pre_reset_frame_sha256'] = digest
                sample['pre_reset_stable_samples'] = quiet
            if booted and not (awaiting_reset and sdk == '36' and quiet < 3):
                if home is None:
                    candidate = home_component(device.call('shell', 'cmd', 'package', 'resolve-activity',
                                                           '--brief', '-a', 'android.intent.action.MAIN',
                                                           '-c', 'android.intent.category.HOME'))
                    if candidate is None:
                        raise ValueError('No resolved HOME activity')
                    if device.call('shell', 'getprop', 'ro.kernel.qemu') == '1':
                        device.call('shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1')
                        sample['hide_error_dialogs'] = device.call('shell', 'settings', 'get', 'global', 'hide_error_dialogs')
                    if normalize_navigation and normalization is None:
                        try:
                            normalization = normalize_launcher(device, candidate)
                        except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
                            normalization = {'status': 'failed', 'error': str(error)}
                            sample['error'] = str(error)
                            samples.append(sample)
                            return report(False)
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
                navigation_ready = True
                if normalization is not None and normalization.get('api') == 36:
                    sample['navigation'] = navigation_geometry(
                        device.call('shell', 'dumpsys', 'window', 'displays'))
                    navigation_ready = sample['navigation']['ready']
                if sample['unlocked'] and sample['focused_home'] and navigation_ready:
                    sample['frame_sha256'] = screenshot_digest(device)
            current = sample.get('frame_sha256')
            stable = stable + 1 if current and current == previous else (1 if current else 0)
            previous = current
            sample['stable_samples'] = stable
            samples.append(sample)
            # Three independently captured frames, with all readiness signals still true.
            if stable >= 3:
                return report(True)
        except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
            previous, stable = None, 0
            quiet_previous, quiet = None, 0
            sample['error'] = str(error)
            samples.append(sample)
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(.5, remaining))
    return report(False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=120, choices=range(1, 181), metavar='1..180')
    parser.add_argument('--output', type=Path, default=Path('emulator-readiness.json'))
    parser.add_argument('--normalize-navigation', action='store_true',
                        help='Recreate disposable API36 Launcher3 after display overrides and require a native 48dp navigation inset')
    args = parser.parse_args()
    start = time.monotonic()
    result = wait_ready(Device(start + args.timeout), start + args.timeout,
                        normalize_navigation=args.normalize_navigation)
    result['elapsed_seconds'] = round(time.monotonic() - start, 3)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    return 0 if result['ready'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
