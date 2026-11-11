"""Fail-closed source/XML union ledger for risk-first, exactly-once native suites.

Source discovery is restricted to the existing direct JUnit4 parser. No inheritance,
generated tests or unknown SDK omission is inferred. Android runner assumptions remain
skips (never passes). Their reason comes from UTP's test-result.pb; the original per-method log
is retained in the receipt and may confirm, never contradict, that reason.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import statistics
import xml.etree.ElementTree as ET

import source_methods as source
import utp_results


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


# Lead-ruled single-method deferrals (never whole classes, never relaxed bounds). Each entry is
# removed from the expected inventory and reported as DEFERRED; the source method must still exist
# so a rename or removal cannot silently retire the deferral.
DEFERRED = {
    'io.github.anastaci1a.localica.PopupShadowContinuityTest#sharedNeighborShadowHasNoOwnershipHandoffThroughRepeatedCycles':
        'DEFERRED v0.34.2 #815',
}


def deferred_for(inv):
    return [{'id': identity, 'reason': reason}
            for identity, reason in sorted(DEFERRED.items())
            if identity.split('#')[0] in inv['selectors']]


def plan(root, risks, api, sha, run_id='0', attempt='0'):
    require(api in ('26', '36'), 'Unsupported API')
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Invalid SHA')
    require(risks and len(risks) == len(set(risks)), 'Require unique nonempty risk classes')
    require(re.fullmatch('[0-9]+', run_id) and re.fullmatch('[0-9]+', attempt), 'Invalid run/attempt')
    inv, omissions, expected = expected_for(root, api)
    require(set(risks) <= set(inv['selectors']), 'Risk class outside complete source inventory')
    preflight = {x for x in expected if x.split('#')[0] in risks}
    remaining = expected - preflight
    require(preflight and remaining, 'Require nonempty preflight and remainder')
    return {'schema_version': 1, 'sha': sha, 'api': api, 'run_id': run_id, 'attempt': attempt, 'inventory': inv,
            'risk_classes': risks, 'sdk_omissions': omissions, 'deferred': deferred_for(inv),
            'preflight': sorted(preflight), 'remaining': sorted(remaining),
            'expected': sorted(expected)}


def expected_for(root, api):
    inv = discover(root)
    omissions = []
    # Reviewed API-specific contracts, not missing-ID waivers. Keep exact
    # file/method/annotation guards; every other SDK filter fails closed.
    # Optical-detail contracts require runtime shaders; None coverage runs on both APIs.
    sdk_contracts = {
        # Independent HardwareRenderer field conformance; the software API26 oracle remains covered separately.
        'NativeShadowFieldConformanceTest': [
            ('independentUncutFieldMatchesBareNativeAtBothOrigins', 29),
        ],
        'NoneAtlasTest': [
            ('structuralParentChangesMatchThePerSurfaceChainWithUnchangedUnionBounds', 33),
            ('memberCapacityOverflowUsesThePerSurfaceFallback', 33),
            ('distinctSourceAtlasesKeepRetainedRecordingContainmentAcyclic', 33),
            ('undeclaredContentUsesItsOwnChainEvenWithAnAtlasHost', 33),
            ('debugAssertionsRejectRetainedReferenceAndAttachmentChanges', 33),
            ('replacingTheLastConsumerKeepsARegisteredLiveAtlasOwner', 33),
            ('readdingTheSameEntryRebuildsItsRetainedRecordPlan', 33),
        ],
        'BackdropInvalidationTest': [
            ('rasterlessBackingRecordInvalidatesItsDependentRecording', 32, 'maxSdkVersion'),
            ('queuedFinalRecordingSurvivesASuspendedThumbnailCaptureAndThenSettles', 32, 'maxSdkVersion'),
        ],
        'FlatNoneRendererPopupTest': [
            ('rendererPopupCanLeaveFlatNoneForSingleToneAndNormal', 33),
            ('rendererPopupAccessibilityCanLeaveFlatNoneForSingleToneAndNormal', 33),
        ],
        'ScopedSearchOpticsTest': [
            ('searchLensRedrawsWhenItsListBackdropArrivesLater', 33),
        ],
        # The native onEnd latch requires API30 IME inset animation callbacks.
        'PopupImeGeometryTest': [
            ('tagEditorNativeImeTrace', 30),
            ('addToPlaylistsNativeImeTrace', 30),
            ('playerAddToPlaylistsNativeImeTrace', 30),
            ('playerTagEditorNativeImeTrace', 30),
            ('mixedContentDuringNativeImeTrace', 30),
        ],
        'FloatingImeLifecycleTest': [
            ('actualFloatingWindowPreservesEditingAndEndsOnceAcrossFocusAndReopen', 30),
        ],
        'GlassAppearanceTest': [
            ('frostedPanelsStillScatterTheParentScene', 33),
            ('blurTintAndShadowChangeIndependentlyWhileForegroundStaysSharp', 33),
        ],
        'GlassOpticsTest': [
            ('changingDepthBendsARecordedGridAndKeepsTheOutsideUntouched', 33),
        ],
        'GlassToneShaderParityTest': [
            ('shaderMatchesKotlinAtSamplePoints', 33),
        ],
        'GlassReadabilityTest': [
            ('entireGlassUsesTheBoundedCurveWithoutTintingItsForeground', 33),
        ],
        'LiquidGlassTest': [
            ('tintLiftsShadowsMoreThanHighlightsWithoutBleachingYellow', 33),
            ('lightPillTransmitsAndRefreshesSceneWithoutBlurringForeground', 33),
            ('darkBubbleTransmitsAndRefreshesSceneWithoutBlurringForeground', 33),
        ],
        'LyricsReturnBackdropTest': [
            ('movingAndReemphasizingGlyphsRefreshesTheActualReturnLens', 33),
            ('movingPlayerViewportKeepsTheActiveReturnLensRegisteredThroughReversal', 33),
            ('overlappingDocumentsShareTheReturnSourceAndFadeAfterCompositing', 33),
            ('interruptedPanelSourcesAndSongsReopenWithFreshSamplingAndWorkingReturn', 33),
        ],
        'NestedGlassRegistrationTest': [
            ('transformedGlassSamplesItsSceneThroughForwardReverseFitAndScrolling', 33),
        ],
        'SeekCancelGlassTest': [
            ('cancelTargetRefractsTheUiBehindItAndOnlyTheSceneRecordingDoesSo', 33),
        ],
        'SelectorRegistrationTest': [
            ('centerLandmarkStaysRegisteredThroughHorizontalAndVerticalDeformation', 33),
            ('centerLandmarkStaysRegisteredInsideOffsetModal', 33),
        ],
    }
    for path in inv['all_source_sha256']:
        text = (root/path).read_text()
        if 'SdkSuppress' in text:
            cls = Path(path).stem
            require(cls in sdk_contracts and path == 'app/src/androidTest/java/io/github/anastaci1a/localica/' + cls + '.kt',
                    'Unreviewed SDK-filtered source')
            reviewed = sdk_contracts[cls]
            # This reviewed source uses the exact imported spelling; normalize only
            # its annotation representation, without changing source or bounds.
            if cls == 'NoneAtlasTest':
                imported = 'import androidx.test.filters.SdkSuppress\n'
                require(text.count(imported) == 1, 'Changed None atlas SDK import')
                text = text.replace(imported, '').replace(
                    '@SdkSuppress(', '@androidx.test.filters.SdkSuppress(')
            if cls == 'FlatNoneRendererPopupTest':
                imported = 'import androidx.test.filters.SdkSuppress\n'
                require(text.count(imported) == 1, 'Changed flat None popup SDK import')
                text = text.replace(imported, '').replace(
                    '@SdkSuppress(', '@androidx.test.filters.SdkSuppress(')
            require(text.count('SdkSuppress') == len(reviewed), 'Additional SDK exclusions require review')
            for contract in reviewed:
                method, bound = contract[:2]
                argument = contract[2] if len(contract) == 3 else 'minSdkVersion'
                annotation = r'@androidx\.test\.filters\.SdkSuppress\(' + argument + ' = ' + str(bound) + r'\)'
                declaration = (r'(?:@Test\s+' + annotation + r'|' + annotation + r'\s+@Test)' +
                               r'\s+fun ' + re.escape(method) + r'\(')
                require(re.search(declaration, text), 'Changed SDK exclusion declaration')
                sdk_id = 'io.github.anastaci1a.localica.' + cls + '#' + method
                require(sdk_id in inv['runtime_methods'], 'SDK exclusion lacks source method')
                if (int(api) < bound if argument == 'minSdkVersion' else int(api) > bound):
                    omissions.append({'id': sdk_id, 'reason': 'Reviewed ' + argument + '=' + str(bound) + ' annotation',
                                      'source': path, 'source_sha256': inv['all_source_sha256'][path]})
    deferred = deferred_for(inv)
    for row in deferred:
        require(row['id'] in inv['runtime_methods'], 'Deferred method missing from source: ' + row['id'])
    return inv, omissions, set(inv['runtime_methods']) - {x['id'] for x in omissions} - {x['id'] for x in deferred}


def outcomes(folder):
    require(folder.is_dir() and not folder.is_symlink(), 'Missing or symlinked XML root')
    files = sorted(folder.rglob('*.xml'))
    require(files, 'No instrumentation XML')
    rows, evidence = {}, []
    observed = observed_records = None
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
                    # logd can drop this log's entries under load (#669). The reason comes from UTP's
                    # instrumentation record; the log may only confirm it, never contradict it.
                    require(text.count('TestRunner: started: '+marker) <= 1 and text.count('TestRunner: assumption failed: '+marker) <= 1, 'Ambiguous assumption boundaries')
                    if observed is None:
                        records = sorted(folder.rglob('test-result.pb'))
                        require(records, 'Missing UTP test-result.pb')
                        observed = {}
                        observed_records = [{'file': str(record), 'sha256': digest(record)} for record in records]
                        for record in records:
                            for key, values in utp_results.assumption_reasons(record).items():
                                observed.setdefault(key, []).extend(values)
                    reasons = sorted(set(observed.get(identity, [])), key=str)
                    require(len(reasons) == 1 and reasons[0] is not None, 'Missing observed assumption reason')
                    logged = re.findall(r'TestRunner: org\.junit\.AssumptionViolatedException: ([^\r\n]*)', text)
                    require(logged in ([], reasons), 'Contradictory logged assumption reason')
                    skipped = case.find('skipped')
                    require(skipped.get('message', '') in ('', reasons[0]) and (skipped.text or '').strip() in ('', reasons[0], 'org.junit.AssumptionViolatedException: '+reasons[0]), 'Contradictory XML assumption reason')
                    row.update(reason=reasons[0], reason_records=observed_records, log=str(logs[0]), log_sha256=digest(logs[0]))
                rows[identity] = row
        evidence.append({'file': str(path), 'sha256': digest(path)})
    return rows, evidence


def reconcile(root, manifest, preflight_folder, remaining_folder=None, shard=False):
    current = plan(root, manifest['risk_classes'], manifest['api'], manifest['sha'], manifest['run_id'], manifest['attempt'])
    require(current == manifest, 'Source/partition changed since planning')
    preflight, preflight_files = outcomes(preflight_folder)
    # A native shard owns a disjoint subset; the summary job proves the complete union.
    require(set(preflight) <= set(manifest['preflight']) and (shard or set(preflight) == set(manifest['preflight'])), 'Missing/extra preflight identities')
    require(all(x['outcome'] == 'passed' for x in preflight.values()), 'Preflight must pass without skips')
    receipt = {'schema_version': 1, 'sha': manifest['sha'], 'api': manifest['api'],
               'run_id': manifest['run_id'], 'attempt': manifest['attempt'],
               'status': 'preflight_passed', 'preflight': preflight,
               'preflight_xml': preflight_files, 'sdk_omissions': manifest['sdk_omissions']}
    if remaining_folder is not None:
        remaining, files = outcomes(remaining_folder)
        require(not set(remaining) & set(preflight), 'Repeated preflight identity in remainder')
        require(set(remaining) <= set(manifest['remaining']) and (shard or set(remaining) == set(manifest['remaining'])), 'Missing/extra remainder identities')
        require(shard or set(preflight) | set(remaining) == set(manifest['expected']), 'Incomplete full-suite union')
        require(all(x['outcome'] in ('passed','skipped') for x in remaining.values()), 'Remaining suite failed')
        receipt.update(status='complete_union', remaining=remaining, remaining_xml=files,
                       executed_once=len(preflight)+len(remaining),
                       skipped=sum(x['outcome']=='skipped' for x in remaining.values()),
                       skip_policy='Original runner assumptions retained as skips, not passes; observed reason recorded')
    return receipt


def union(root, api, folders, shards):
    """Prove the shard XML is the complete once-only inventory for this API."""
    require(api in ('26', '36') and shards > 0, 'Invalid union request')
    inv, _, expected = expected_for(root, api)
    deferred = deferred_for(inv)
    rows = {}
    for folder in folders:
        part, _ = outcomes(folder)
        require(not set(part) & set(rows), 'Duplicate identity across shards: ' + str(sorted(set(part) & set(rows))[:5]))
        rows.update(part)
    require(not set(rows) - expected, 'Extra identities: ' + str(sorted(set(rows) - expected)[:5]))
    require(not expected - set(rows), 'Missing identities (' + str(len(expected - set(rows))) + '): ' + str(sorted(expected - set(rows))[:5]))
    counts = {k: sum(r['outcome'] == k for r in rows.values()) for k in ('passed', 'skipped', 'failure', 'error')}
    for row in deferred:
        require(row['id'] not in rows, 'Deferred method ran: ' + row['id'])
        print(row['reason'] + ' API ' + api + ': ' + row['id'], file=sys.stderr)
    return {'api': api, 'shards': shards, 'xml_roots': len(folders), 'expected': len(expected), 'executed_once': len(rows),
            'deferred': deferred, **counts}


def shards(root, api, count, durations, sha, risks=(), scroll_performance=False):
    """Longest measured class first; retain every method and explicit unknown costs."""
    require(api in ('26', '36') and 1 <= count <= 32, 'Invalid shard API/count')
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Invalid SHA')
    inv, omissions, expected = expected_for(root, api)
    require(len(risks) == len(set(risks)) and set(risks) <= set(inv['selectors']), 'Invalid risk classes')
    measured, per_method = {}, []
    seen = set()
    with durations.open(newline='') as stream:
        rows = csv.DictReader(stream)
        require(rows.fieldnames == ['api', 'class', 'tests', 'total_seconds', 'skipped'], 'Invalid duration columns')
        for row in rows:
            identity = (row['api'], row['class'])
            require(identity not in seen, 'Duplicate measured class')
            seen.add(identity)
            tests, skipped, seconds = int(row['tests']), int(row['skipped']), float(row['total_seconds'])
            require(row['api'] in ('26', '36') and re.fullmatch(r'[A-Za-z_]\w*(\.[A-Za-z_]\w*)+', row['class']), 'Invalid duration identity')
            require(tests > 0 and 0 <= skipped <= tests and math.isfinite(seconds) and seconds >= 0, 'Invalid measured cost')
            if row['api'] == api:
                measured[row['class']] = (tests, seconds)
                if seconds > 0:
                    per_method.append(seconds / tests)
    require(per_method, 'No measured durations for this API')
    fallback = statistics.median(per_method)
    methods = {}
    for identity in sorted(expected):
        cls = identity.split('#')[0]
        if cls not in risks:
            methods.setdefault(cls, []).append(identity)
    require(len(methods) >= count, 'Would create an empty native shard')
    costs, estimated, opt_in = {}, [], []
    for cls, identities in methods.items():
        if cls in measured:
            tests, seconds = measured[cls]
            costs[cls] = seconds * len(identities) / tests
        else:
            costs[cls] = fallback * len(identities)
            estimated.append(cls)
        # The historical scan predates the existing opt-in. These methods still run
        # and record assumptions, but their optical benchmark body is not enabled.
        if cls == 'io.github.anastaci1a.localica.ScrollPerformanceTest' and not scroll_performance:
            costs[cls] = 0.01 * len(identities)
            opt_in.append(cls)
    result = [{'index': i, 'classes': [], 'methods': [], 'estimated_seconds': 0.0} for i in range(count)]
    for cls in sorted(methods, key=lambda name: (-costs[name], name)):
        target = min(result, key=lambda shard: (shard['estimated_seconds'], shard['index']))
        target['classes'].append(cls)
        target['methods'].extend(methods[cls])
        target['estimated_seconds'] += costs[cls]
    for shard in result:
        shard['classes'].sort()
        shard['excluded_classes'] = sorted(set(inv['selectors']) - set(shard['classes']))
        shard['methods'].sort()
        shard['excluded_methods'] = [x['id'] for x in deferred_for(inv)]
        shard['estimated_seconds'] = round(shard['estimated_seconds'], 6)
    return {'schema_version': 1, 'sha': sha, 'api': api, 'shard_count': count,
            'algorithm': 'longest measured class first, least estimated load, stable class/index ties',
            'inventory_sha256': hashlib.sha256(json.dumps(inv, sort_keys=True).encode()).hexdigest(),
            'duration_sha256': digest(durations), 'risk_classes': list(risks),
            'class_seconds': {cls: round(costs[cls], 6) for cls in sorted(costs)},
            'preflight_partition': 'Existing AndroidJUnitRunner method shards; risks excluded from weighted remainder',
            'estimated_classes': sorted(estimated), 'unknown_seconds_per_method': fallback,
            'opt_in_disabled_classes': opt_in, 'opt_in_assumption_seconds_per_method': 0.01,
            'sdk_omissions': omissions, 'deferred': deferred_for(inv), 'shards': result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan','preflight','complete','union','shards','deferred'))
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--shard', action='store_true')
    parser.add_argument('--shards', type=int)
    parser.add_argument('--xml-root', type=Path, action='append', default=[])
    parser.add_argument('--risk-classes')
    parser.add_argument('--sha')
    parser.add_argument('--api')
    parser.add_argument('--run-id')
    parser.add_argument('--attempt')
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--remaining', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--durations', type=Path, default=Path(__file__).with_name('class-durations.csv'))
    parser.add_argument('--scroll-performance', action='store_true')
    args = parser.parse_args()
    try:
        if args.action == 'deferred':
            # Comma-separated Class#method ids for the runner's notClass filter, plus a visible notice.
            rows = deferred_for(discover(args.root.resolve()))
            for row in rows:
                print(row['reason'] + ': ' + row['id'], file=sys.stderr)
            print(','.join(row['id'] for row in rows))
            return 0
        if args.action == 'shards':
            require(args.shards and args.output, 'Missing shard plan inputs')
            result = shards(args.root.resolve(), args.api, args.shards, args.durations, args.sha,
                            args.risk_classes.split(',') if args.risk_classes else [], args.scroll_performance)
            with args.output.open('x') as stream:
                stream.write(json.dumps(result, indent=2)+'\n')
            return 0
        if args.action == 'union':
            require(args.api and args.shards and args.xml_root, 'Missing union inputs')
            result = union(args.root.resolve(), args.api, args.xml_root, args.shards)
            print(json.dumps(result, indent=2))
            return 0 if not (result['failure'] or result['error']) else 1
        require(args.plan is not None, 'Missing plan')
        if args.action == 'plan':
            result = plan(args.root.resolve(), args.risk_classes.split(','), args.api, args.sha, args.run_id, args.attempt)
            output = args.plan
        else:
            require(args.preflight is not None and args.output is not None, 'Missing reconciliation inputs')
            require(args.action != 'complete' or args.remaining is not None, 'Missing remainder input')
            result = reconcile(args.root.resolve(), json.loads(args.plan.read_text()), args.preflight,
                               args.remaining if args.action == 'complete' else None, args.shard)
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
