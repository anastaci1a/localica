#!/usr/bin/env python3
"""Resolve the available SDK image revision and cache only that installed revision."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def available_revision(listing, package):
    available = False
    for line in listing.splitlines():
        if line.strip() == 'Available Packages:':
            available = True
            continue
        if line.strip() == 'Available Updates:':
            available = False
        columns = [value.strip() for value in line.split('|')]
        if available and len(columns) >= 3 and columns[0] == package:
            if re.fullmatch(r'\d+(?:\.\d+)*', columns[1]):
                return columns[1]
    raise ValueError('No stable available revision for ' + package)


def installed_revision(directory):
    props = directory / 'source.properties'
    if not props.is_file() or not (directory / 'system.img').is_file():
        return None
    match = re.search(r'^Pkg.Revision\s*=\s*(\d+(?:\.\d+)*)\s*$', props.read_text(), re.M)
    return match.group(1) if match else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['restore', 'save'])
    parser.add_argument('--api', choices=['26', '36'], required=True)
    args = parser.parse_args()
    target = 'default' if args.api == '36' else 'google_apis'
    package = f'system-images;android-{args.api};{target};x86_64'
    state = Path(os.environ['RUNNER_TEMP']) / f'localica-image-cache-{args.api}.json'
    directory = Path(os.environ['ANDROID_HOME']) / Path(*package.split(';'))
    outputs = {}
    if args.mode == 'restore':
        sdkmanager = Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/sdkmanager'
        listing = subprocess.check_output([str(sdkmanager), '--list', '--channel=0'], text=True, timeout=120)
        revision = available_revision(listing, package)
        outputs = {'path': str(directory),
                   'key': f'android-image-v1-linux-{args.api}-{target}-x86_64-r{revision}',
                   'revision': revision}
        state.write_text(json.dumps(outputs))
    elif state.is_file():
        outputs = json.loads(state.read_text())
        # A repository update during emulator setup must never poison the earlier key.
        outputs['save'] = str(installed_revision(directory) == outputs['revision']).lower()
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        for key, value in outputs.items():
            stream.write(f'{key}={value}\n')
    print(json.dumps(outputs))


if __name__ == '__main__':
    main()
