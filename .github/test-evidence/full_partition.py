"""Fail-closed source/XML union ledger for risk-first, exactly-once native suites.

Source discovery is restricted to the existing direct JUnit4 parser. No inheritance,
generated tests or unknown SDK omission is inferred. Android runner assumptions remain
skips (never passes), with their original per-method log retained in the receipt.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import source_methods as source


def require(value, reason):
    if not value:
        raise ValueError(reason)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def discover(root):
    folder = root / 'app/src/androidTest/java'
    require(folder.is_dir(), 'Missing test source folder')
    selectors, hashes = [], {}
    for path in sorted(folder.rglob('*')):
        if path.suffix not in ('.kt', '.java') or not path.is_file():
            continue
        require(not path.is_symlink(), 'Source symlink refused')
        parsed = source.read_source(path)
        hashes[path.relative_to(root).as_posix()] = digest(path)
        for entry in parsed.classes:
            cls, _, body = entry
            if body is None:
                continue
            depth = parsed.depths[body] + ('{',)
            annotations = [source.annotation(parsed, i)[0]
                           for i in range(body + 1, parsed.pairs[body])
                           if parsed.tokens[i].text == '@' and parsed.depths[i] == depth]
            if any(name in parsed.tests or name.endswith('.Test') or name == 'Test' for name in annotations):
                selectors.append(cls)
    inventory = source.inventory(root, selectors)
    inventory['all_source_sha256'] = hashes
    return inventory


def plan(root, risks, api, sha, run_id='0', attempt='0'):
    require(api in ('26', '36'), 'Unsupported API')
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Invalid SHA')
    require(risks and len(risks) == len(set(risks)), 'Require unique nonempty risk classes')
    require(re.fullmatch('[0-9]+', run_id) and re.fullmatch('[0-9]+', attempt), 'Invalid run/attempt')
    inv = discover(root)
    require(set(risks) <= set(inv['selectors']), 'Risk class outside complete source inventory')
    omissions = []
    # Reviewed API-specific contracts, not missing-ID waivers. Keep exact
    # file/method/annotation guards; every other SDK filter fails closed.
    sdk_contracts = {
        'ScopedSearchOpticsTest': ('searchLensRedrawsWhenItsListBackdropArrivesLater', 33),
        'FloatingImeLifecycleTest': ('actualFloatingWindowPreservesEditingAndEndsOnceAcrossFocusAndReopen', 30),
        'GlassRendererSwitchingTest': ('modernSwitchingDoesNotReplaceAnUnavailableRequestedHomeWithThePrimary', 33),
    }
    for path in inv['all_source_sha256']:
        text = (root/path).read_text()
        if 'SdkSuppress' in text:
            cls = Path(path).stem
            require(cls in sdk_contracts and path == 'app/src/androidTest/java/io/github/anastaci1a/localica/' + cls + '.kt',
                    'Unreviewed SDK-filtered source')
            method, minimum = sdk_contracts[cls]
            declaration = (r'@Test\s+@androidx\.test\.filters\.SdkSuppress\(minSdkVersion = ' +
                           str(minimum) + r'\)\s+fun ' + re.escape(method) + r'\(')
            require(re.search(declaration, text), 'Changed SDK exclusion declaration')
            require(text.count('SdkSuppress') == 1, 'Additional SDK exclusions require review')
            sdk_id = 'io.github.anastaci1a.localica.' + cls + '#' + method
            require(sdk_id in inv['runtime_methods'], 'SDK exclusion lacks source method')
            if int(api) < minimum:
                omissions.append({'id': sdk_id, 'reason': 'Reviewed minSdkVersion=' + str(minimum) + ' annotation',
                                  'source': path, 'source_sha256': inv['all_source_sha256'][path]})
    expected = set(inv['runtime_methods']) - {x['id'] for x in omissions}
    preflight = {x for x in expected if x.split('#')[0] in risks}
    remaining = expected - preflight
    require(preflight and remaining, 'Require nonempty preflight and remainder')
    return {'schema_version': 1, 'sha': sha, 'api': api, 'run_id': run_id, 'attempt': attempt, 'inventory': inv,
            'risk_classes': risks, 'sdk_omissions': omissions,
            'preflight': sorted(preflight), 'remaining': sorted(remaining),
            'expected': sorted(expected)}


def outcomes(folder):
    require(folder.is_dir() and not folder.is_symlink(), 'Missing or symlinked XML root')
    files = sorted(folder.rglob('*.xml'))
    require(files, 'No instrumentation XML')
    rows, evidence = {}, []
    for path in files:
        require(not path.is_symlink() and path.stat().st_size <= 32*1024*1024, 'Invalid XML input')
        raw = path.read_bytes()
        require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(), 'XML entity declaration refused')
        root = ET.fromstring(raw)
        require(root.tag in ('testsuite','testsuites'), 'Unknown XML root')
        suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite')
        require(suites, 'Missing test suite')
        for suite in suites:
            require(not suite.findall('.//testsuite'), 'Nested suites require explicit flattening')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'XML count mismatch')
            for attr, tag in [('failures','failure'),('errors','error'),('skipped','skipped')]:
                require(int(suite.attrib.get(attr, '0')) == sum(len(c.findall(tag)) for c in cases), 'XML outcome total mismatch')
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity not in rows, 'Duplicate XML identity: '+identity)
                statuses = [tag for tag in ('failure','error','skipped') if case.find(tag) is not None]
                require(len(statuses) <= 1 and sum(len(case.findall(tag)) for tag in ('failure','error','skipped')) == len(statuses), 'Contradictory outcomes')
                outcome = statuses[0] if statuses else 'passed'
                row = {'outcome': outcome, 'seconds': case.attrib.get('time'), 'xml': str(path)}
                if outcome == 'skipped':
                    name, method = identity.split('#', 1)
                    logs = list(folder.rglob('logcat-'+name+'-'+method+'.txt'))
                    require(len(logs) == 1 and not logs[0].is_symlink() and logs[0].stat().st_size <= 4*1024*1024, 'Missing bounded assumption log: '+identity)
                    text = logs[0].read_text()
                    marker = method+'('+name+')'
                    require(text.count('TestRunner: started: '+marker) == text.count('TestRunner: assumption failed: '+marker) == 1, 'Ambiguous assumption boundaries')
                    reasons = re.findall(r'TestRunner: org\.junit\.AssumptionViolatedException: ([^\r\n]*)', text)
                    require(len(reasons) == 1, 'Missing observed assumption reason')
                    skipped = case.find('skipped')
                    require(skipped.get('message', '') in ('', reasons[0]) and (skipped.text or '').strip() in ('', reasons[0], 'org.junit.AssumptionViolatedException: '+reasons[0]), 'Contradictory XML assumption reason')
                    row.update(reason=reasons[0], log=str(logs[0]), log_sha256=digest(logs[0]))
                rows[identity] = row
        evidence.append({'file': str(path), 'sha256': digest(path)})
    return rows, evidence


def reconcile(root, manifest, preflight_folder, remaining_folder=None):
    current = plan(root, manifest['risk_classes'], manifest['api'], manifest['sha'], manifest['run_id'], manifest['attempt'])
    require(current == manifest, 'Source/partition changed since planning')
    preflight, preflight_files = outcomes(preflight_folder)
    require(set(preflight) == set(manifest['preflight']), 'Missing/extra preflight identities')
    require(all(x['outcome'] == 'passed' for x in preflight.values()), 'Preflight must pass without skips')
    receipt = {'schema_version': 1, 'sha': manifest['sha'], 'api': manifest['api'],
               'run_id': manifest['run_id'], 'attempt': manifest['attempt'],
               'status': 'preflight_passed', 'preflight': preflight,
               'preflight_xml': preflight_files, 'sdk_omissions': manifest['sdk_omissions']}
    if remaining_folder is not None:
        remaining, files = outcomes(remaining_folder)
        require(not set(remaining) & set(preflight), 'Repeated preflight identity in remainder')
        require(set(remaining) == set(manifest['remaining']), 'Missing/extra remainder identities')
        require(set(preflight) | set(remaining) == set(manifest['expected']), 'Incomplete full-suite union')
        require(all(x['outcome'] in ('passed','skipped') for x in remaining.values()), 'Remaining suite failed')
        receipt.update(status='complete_union', remaining=remaining, remaining_xml=files,
                       executed_once=len(preflight)+len(remaining),
                       skipped=sum(x['outcome']=='skipped' for x in remaining.values()),
                       skip_policy='Original runner assumptions retained as skips, not passes; observed reason recorded')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan','preflight','complete'))
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--risk-classes')
    parser.add_argument('--sha')
    parser.add_argument('--api')
    parser.add_argument('--run-id')
    parser.add_argument('--attempt')
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--remaining', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        if args.action == 'plan':
            result = plan(args.root.resolve(), args.risk_classes.split(','), args.api, args.sha, args.run_id, args.attempt)
            output = args.plan
        else:
            require(args.preflight is not None and args.output is not None, 'Missing reconciliation inputs')
            require(args.action != 'complete' or args.remaining is not None, 'Missing remainder input')
            result = reconcile(args.root.resolve(), json.loads(args.plan.read_text()), args.preflight,
                               args.remaining if args.action == 'complete' else None)
            result['plan_sha256'] = digest(args.plan)
            output = args.output
        with output.open('x') as stream:
            stream.write(json.dumps(result, indent=2)+'\n')
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print('Full-suite partition rejected: '+str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
