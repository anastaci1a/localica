"""Preserve original native XML outcomes before strict prototype qualification.

This receipt never grants acceptance. The existing runner qualification must finish
before qualification_passed can become true; missing and skipped methods stay raw.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(root, output, exit_code, source, api):
    evidence = root / 'prototype-evidence'
    errors = []

    def load(path):
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError) as error:
            errors.append(f'{path}: {error}')
            return {}

    observed = load(evidence / 'live/observed/manifest.json')
    inventory_path = evidence / 'expected-methods.json'
    inventory = load(inventory_path)
    if observed.get('sha') != source or str(observed.get('api')) != str(api):
        errors.append('Native observer source/API differs from the requested source/API')
    if inventory.get('api') != api:
        errors.append('Original source inventory API differs from the requested API')
    selected = inventory.get('selectors', [])
    expected = set(inventory.get('runtime_methods', []))
    methods, counts, cases, xml_files = {}, {}, [], []
    files = sorted((root / 'app/build/outputs/androidTest-results').rglob('*.xml'))
    if not files:
        errors.append('No original final instrumentation XML')
    for file in files:
        try:
            tree = ET.parse(file).getroot()
            suites = [tree] if tree.tag == 'testsuite' else tree.findall('.//testsuite')
            if not suites:
                errors.append(f'No test suites in {file}')
            xml_files.append({'path': str(file), 'sha256': digest(file)})
            for suite in suites:
                rows = suite.findall('testcase')
                if int(suite.attrib['tests']) != len(rows):
                    errors.append(f'Incomplete XML test count in {file}')
                outcomes = {'passed': 0, 'failed': 0, 'errors': 0, 'skipped': 0}
                for case in rows:
                    name, method = case.attrib['classname'], case.attrib['name']
                    identity = name + '#' + method
                    outcome = ('failed' if case.find('failure') is not None else
                               'errors' if case.find('error') is not None else
                               'skipped' if case.find('skipped') is not None else 'passed')
                    cases.append({'id': identity, 'outcome': outcome, 'xml': str(file)})
                    if identity in methods:
                        errors.append('Duplicate original XML method: ' + identity)
                    else:
                        methods[identity] = outcome
                    counts.setdefault(name, dict.fromkeys(outcomes, 0))[outcome] += 1
                    outcomes[outcome] += 1
                    if name not in selected:
                        errors.append('Unexpected original XML class: ' + name)
                for attr, status in [('failures', 'failed'), ('errors', 'errors'), ('skipped', 'skipped')]:
                    if attr in suite.attrib and int(suite.attrib[attr]) != outcomes[status]:
                        errors.append(f'Inconsistent XML {attr} in {file}')
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            errors.append(f'{file}: {error}')
    missing, extra = sorted(expected - methods.keys()), sorted(methods.keys() - expected)
    if missing or extra:
        errors.append('Missing or extra source-declared methods')
    producer = evidence / 'test-input-apks.sha256'
    producer_rows = []
    try:
        for line in producer.read_text().splitlines():
            expected_hash, relative = line.split(maxsplit=1)
            apk = root / relative.lstrip('* ')
            actual = digest(apk) if apk.is_file() else None
            producer_rows.append({'path': relative, 'expected_sha256': expected_hash,
                                  'actual_sha256': actual, 'unchanged': actual == expected_hash})
    except (OSError, ValueError) as error:
        errors.append(f'{producer}: {error}')
    receipt = {
        'schema_version': 1, 'receipt_kind': 'native-raw-outcomes-before-qualification',
        'sha': observed.get('sha'), 'api': observed.get('api'),
        'requested_sha': source, 'requested_api': api, 'native_runner_exit_code': exit_code,
        'qualification_passed': False, 'release_certified': False,
        'selectors': selected, 'source_inventory_sha256': digest(inventory_path) if inventory_path.is_file() else None,
        'methods': methods, 'counts': counts, 'raw_cases': cases, 'xml_files': xml_files,
        'missing_methods': missing, 'extra_methods': extra, 'reconciliation_errors': errors,
        'qualified_skips': [], 'producer_apks': producer_rows,
        'producer_unchanged': len(producer_rows) == 2 and all(row['unchanged'] for row in producer_rows),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--exit-code', type=int, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--api', type=int, choices=[26, 36], required=True)
    args = parser.parse_args()
    record(args.root, args.output, args.exit_code, args.source, args.api)
