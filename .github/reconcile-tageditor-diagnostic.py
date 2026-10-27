#!/usr/bin/env python3
"""Fail-closed evidence reconciliation for one diagnostic-only API36 case at one immutable source.
No result is release certification, optical acceptance, or a causal comparison. Never modifies original evidence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PREFIX = 'io.github.anastaci1a.localica.'
TEST_CLASS = PREFIX + 'TagEditorThemeInheritanceTest'
PIN = '1e31403cb042e9896686200a4800faffce9b2a59'
SOURCE_PIN = '1640d1e84c557a4e240b3f30b6cc553cadc34098'
SELECTED_METHODS = ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost']
DECLARED_INVENTORY = {'schema_version': 1,
 'selectors': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest'],
 'methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching'],
 'runtime_methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching'],
 'classes': [{'class': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt',
              'sha256': '6807d35797a937f26cbe353253ff9f8db9f90483cb5cb87ea3904422992c322c',
              'methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                                'line': 124},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 166},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 167},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 168},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                                'line': 215},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                                'line': 275},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching',
                                'line': 303},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                                'line': 324},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
                                'line': 349}]}],
 'method_count': 9,
 'runtime_discovery_proven': False,
 'limits': 'Static direct JUnit4 declarations only. Runtime missing/extra/duplicate IDs must fail closed; '
           'inherited/generated/parameterized/SDK-filter discovery is not inferred.'}
CASES = {case: (SOURCE_PIN, api, SELECTED_METHODS, SELECTED_METHODS) for case, api in [('tag36', '36')]}
SOURCE_TREES = {'tageditor': ('77314e80885f4649d58482991ac3e2f9898b7d8a', '513319bf057e6e1d21440cf7111ec15f8c7b75e8')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def data(path):
    require(path.is_file() and not path.is_symlink(), 'Missing or symlink evidence: '+str(path))
    return path.read_bytes()


def digest(path):
    return hashlib.sha256(data(path)).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, 'Duplicate JSON key: '+k)
            result[k] = v
        return result
    return json.loads(data(path), object_pairs_hook=unique)


def git(source, *args):
    return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()


def exact_files(root, pattern, count=1):
    roots = root if isinstance(root, list) else [root]
    files = []
    for base in roots:
        for path in sorted(base.glob(pattern)):
            require(path.resolve().is_relative_to(base.resolve()), 'Evidence escaped root')
            files.append(path)
    # AGP export and explicit device pull may preserve identical original bytes.
    # Distinct byte sequences are never silently selected/replaced.
    if isinstance(root, list):
        unique = {}
        for path in files:
            unique.setdefault((path.name, digest(path)), path)
        files = list(unique.values())
    require(len(files) == count, 'Expected '+str(count)+' originals for '+pattern+', found '+str(len(files)))
    for path in files:
        data(path)
    return files


def xml_methods(root, expected):
    require(root.is_dir() and not any(p.is_symlink() for p in [root, *root.parents]), 'Missing/symlink XML root')
    require(not any(p.is_symlink() for p in root.rglob('*')), 'Symlink in XML tree')
    files = exact_files(root, '**/*.xml', count=len(list(root.glob('**/*.xml'))))
    require(files, 'No final instrumentation XML')
    methods = {}
    for path in files:
        document = ET.fromstring(data(path))
        require(document.tag in ('testsuite', 'testsuites'), 'Unsupported XML root')
        # Contradictory aggregate or suite-level terminal markers are failures,
        # even if every nested testcase happens to look green.
        require(not any(node.tag in ('failure', 'error', 'skipped') for node in document.iter()), 'Nonpassing XML marker')
        for node in document.iter():
            if node.tag in ('testsuites', 'testsuite', 'testcase'):
                if 'status' in node.attrib:
                    require(node.attrib['status'] in ('run', 'passed', 'pass'), 'Nonpassing XML status')
                if 'result' in node.attrib:
                    require(node.attrib['result'] in ('completed', 'passed', 'pass'), 'Nonpassing XML result')
        if document.tag == 'testsuites':
            require(all(node.tag in ('testsuite', 'properties', 'system-out', 'system-err') for node in document), 'Unsupported XML wrapper child')
            total = len(list(document.iter('testcase')))
            for attr, value in [('tests', total), ('failures', 0), ('errors', 0), ('skipped', 0)]:
                if attr in document.attrib:
                    require(int(document.attrib[attr]) == value, 'Contradictory XML wrapper '+attr)

        suites = [document] if document.tag == 'testsuite' else list(document.iter('testsuite'))
        require(suites, 'Missing XML suites')
        for suite in suites:
            require(not suite.findall('testsuite'), 'Nested XML suite unsupported')
            require(all(node.tag in ('testcase', 'properties', 'system-out', 'system-err') for node in suite), 'Unsupported XML suite child')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'Incomplete XML tests count')
            for attr, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                actual = sum(len(case.findall(tag)) for case in cases)
                require(int(suite.attrib.get(attr, '0')) == actual, 'Inconsistent XML '+attr)
                require(actual == 0, 'Nonpassing XML '+attr)
            require(not suite.findall('failure') and not suite.findall('error'), 'Suite-level error')
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity in expected and identity not in methods, 'Unexpected or duplicate test: '+identity)
                require(not any(case.find(t) is not None for t in ('failure', 'error', 'skipped')), 'Nonpassing test')
                methods[identity] = 'passed'
    require(set(methods) == set(expected), 'Missing expected test methods')
    return methods, {str(p.relative_to(root)): digest(p) for p in files}


TRACE_PHASES = ['before-artwork-tap', 'after-artwork-tap', 'tap-popup-title', 'before-artwork-hold', 'before-menu-action', 'after-menu-action', 'menu-popup-title']
TRACE_CAVEAT = 'Descriptive, non-atomic owner/observer/frame/session samples; logging and observation may perturb scheduling. Matching samples do not establish readiness, exact launch causality, or optical/release acceptance.'

def trace_summary(path, run_id, attempt, sha):
    """Keep every parsed partial record while rejecting incomplete/replayed/ambiguous green evidence."""
    result = dict(schema_version=1, status='partial', run_id=run_id, attempt=attempt, source_sha=sha,
                  records=[], errors=[], caveat=TRACE_CAVEAT, causal_claim=False)
    try:
        raw=data(path);result['raw_sha256']=hashlib.sha256(raw).hexdigest()
        lines=raw.decode('utf-8',errors='strict').splitlines()
    except (OSError, ValueError, UnicodeError) as error:
        result['errors'].append(str(error));return result
    identity='run='+run_id+' attempt='+attempt+' source='+sha
    boundary=re.compile(r'\bTagEditorDiagBoundary\s*:\s*(.*)$')
    diag=re.compile(r'\bTagEditorThemeDiag\s*:\s*(.*)$')
    bounds=[(i,m.group(1)) for i,line in enumerate(lines) if (m:=boundary.search(line))]
    expected=['START '+identity,'END '+identity]
    if len(bounds)!=2 or [b[1] for b in bounds]!=expected:
        result['errors'].append('Missing, repeated, foreign, or unordered run boundaries')
    starts=[i for i,v in bounds if v=='START '+identity]
    ends=[i for i,v in bounds if v=='END '+identity]
    start=starts[0] if len(starts)==1 else -1
    end=ends[0] if len(ends)==1 and ends[0]>start else len(lines)
    # Even a failed capture retains parsed in-window records, but no invented completion.
    for i,line in enumerate(lines):
        match=diag.search(line)
        if not match:continue
        if not start<i<end:
            result['errors'].append('Diagnostic record outside unique run boundary at line '+str(i+1));continue
        prefix=re.fullmatch(r'phase=([a-z-]+) target=(true|false) uptime=([0-9]+) virtual=([0-9]+) (.*)',match.group(1))
        if not prefix:
            result['errors'].append('Malformed diagnostic prefix at line '+str(i+1));continue
        phase,target,uptime,virtual,payload=prefix.groups()
        record=dict(line=i+1,phase=phase,target=target,uptime=int(uptime),virtual=int(virtual),payload=payload)
        if payload.startswith('decision='):
            names=['decision','composedDecision','sameDecision','sameFrame','providerInk','popupTitle','session','acceptedInk','acceptedOnSurface','acceptedAppearance'];record['kind']='decision'
        elif payload.startswith('frame='):
            names=['frame','receipt'];record['kind']='frame'
        else:
            result['errors'].append('Unknown diagnostic record type');record['kind']='unknown';result['records'].append(record);continue
        if record['kind']=='decision' and any(len(re.findall(r'(?:^| )'+re.escape(name)+'=',payload)) != 1 for name in names):
            result['errors'].append('Duplicate or missing diagnostic field at line '+str(i+1))
        pattern=' '.join(re.escape(name)+'=(.*?)' for name in names)
        fields=re.fullmatch(pattern,payload)
        if not fields:
            result['errors'].append('Incomplete diagnostic fields at line '+str(i+1))
        else:
            record['fields']=dict(zip(names,fields.groups()))
            if record['kind']=='decision' and any(record['fields'][key] not in ('true','false') for key in ('sameDecision','sameFrame')):
                result['errors'].append('Malformed identity flags')
            if any(value=='' for key,value in record['fields'].items() if key!='session'):
                result['errors'].append('Empty diagnostic value')
        result['records'].append(record)
    expected_records=[(target,phase,kind) for target in ('true','false') for phase in TRACE_PHASES for kind in ('decision','frame')]
    actual=[(v['target'],v['phase'],v['kind']) for v in result['records']]
    if actual!=expected_records:result['errors'].append('Missing, duplicated, or out-of-order diagnostic phase pair')
    for a,b in zip(result['records'][::2],result['records'][1::2]):
        if any(a[k]!=b[k] for k in ('phase','target','uptime','virtual')):
            result['errors'].append('Nonmatching diagnostic pair prefix')
    for a,b in zip(result['records'],result['records'][1:]):
        if b['uptime']<a['uptime'] or b['virtual']<a['virtual']:
            result['errors'].append('Regressing diagnostic clock')
    result['complete_pairs']=sum(1 for a,b in zip(result['records'][::2],result['records'][1::2]) if a['kind']=='decision' and b['kind']=='frame' and all(a[k]==b[k] for k in ('phase','target','uptime','virtual')))
    if not result['errors']:result['status']='complete'
    return result


def reconcile(args):
    source, evidence = Path(args.source).resolve(), Path(args.evidence).resolve()
    tooling = Path(args.tooling).resolve()
    require(args.case in CASES, 'Unknown diagnostic case')
    sha, api, selectors, expected = CASES[args.case]
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Unbound corrected source pin')
    require(args.runner_exit == 0, 'Native runner did not complete successfully')
    require(re.fullmatch('[0-9a-f]{40}', args.workflow_sha) and re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[1-9][0-9]*', args.attempt), 'Invalid run identity')
    require(git(source, 'rev-parse', 'HEAD') == sha, 'Source checkout differs from immutable pin')
    parent, tree = SOURCE_TREES['tageditor']
    require(re.fullmatch('[0-9a-f]{40}', tree), 'Unbound diagnostic source tree')
    require(git(source, 'show', '-s', '--format=%P', 'HEAD') == parent and git(source, 'rev-parse', 'HEAD^{tree}') == tree, 'Corrected source parent/tree mismatch')
    require(re.fullmatch('[0-9a-f]{40}', parent), 'Unbound integrated commit pin')
    require(git(source, 'show', '-s', '--format=%P', parent) == 'b41eb52caaebba70b434c5e88749293eec3ecdc9' and git(source, 'rev-parse', parent+'^{tree}') == '83c3655bf4a1700bb60d7c75b37d2b33ad63f4b1', 'Integrated source ancestry/tree mismatch')
    require(git(source, 'diff-tree', '--no-commit-id', '--name-status', '-r', parent, sha) == 'M\tapp/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt', 'Test-only child contains unrelated changes')
    require(not git(source, 'diff', 'HEAD', '--name-only'), 'Tracked source changed')
    context = read_json(evidence/'context.json')
    harness = source.parent/'harness'
    require(git(harness, 'rev-parse', 'HEAD') == args.workflow_sha and not git(harness, 'diff', 'HEAD', '--name-only'), 'Harness checkout drift')
    harness_paths = ['.github/workflows/hosted-tageditor-diagnostic.yml', '.github/run-tageditor-diagnostic.sh', '.github/reconcile-tageditor-diagnostic.py']
    require(set(context['harness_sha256']) == set(harness_paths), 'Incomplete harness hash inventory')
    for path in harness_paths:
        require(digest(harness/path) == context['harness_sha256'][path], 'Harness receipt hash mismatch')
        require(git(harness, 'hash-object', str(harness/path)) == git(harness, 'rev-parse', args.workflow_sha+':'+path), 'Harness differs from workflow tree')

    for key, value in dict(case=args.case, source_sha=sha, source_parent=parent, source_tree=tree, integrated_parent='b41eb52caaebba70b434c5e88749293eec3ecdc9', integrated_tree='83c3655bf4a1700bb60d7c75b37d2b33ad63f4b1', source_changes=['app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt'], workflow_sha=args.workflow_sha, api=api, run_id=args.run_id, attempt=args.attempt, selectors=selectors).items():
        require(context[key] == value, 'Wrong context '+key)
    argv = ['./gradlew', 'connectedDebugAndroidTest', '--no-daemon', '--max-workers=2', '-Pandroid.testInstrumentationRunnerArguments.class='+','.join(selectors), '-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output', '-Pandroid.enableAdditionalTestOutput=true', '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true']
    require(context['argv'] == argv and context['expected_methods'] == expected, 'Wrong diagnostic command/methods')
    require(all(context[key] is False for key in ('release_certified', 'full_suite_run', 'compatibility_run', 'optical_acceptance', 'causal_claim')) and context['tooling_sha'] == PIN, 'Wrong tooling/scope identity')
    require(git(tooling, 'rev-parse', 'HEAD') == PIN and not git(tooling, 'diff', 'HEAD', '--name-only'), 'Tooling checkout drift')
    helpers = ['.github/test-evidence/observe.py', '.github/test-evidence/supervise.py', '.github/test-evidence/publish.mjs', '.github/test-evidence/source_methods.py', '.github/test-evidence/package.json', '.github/test-evidence/package-lock.json', '.github/prepare-device-tests.sh', '.github/test-evidence/environment.sh']
    require(set(context['helper_sha256']) == set(helpers), 'Incomplete tooling hash inventory')
    for helper in helpers:
        require(digest(tooling/helper) == context['helper_sha256'][helper], 'Tooling receipt hash mismatch')
        require(git(tooling, 'hash-object', str(tooling/helper)) == git(tooling, 'rev-parse', PIN+':'+helper), 'Tool differs from pinned tree')
    supervisor_argv = ['python3', str(tooling/'.github/test-evidence/supervise.py'), '--output', str(evidence/'live'), '--run-id', args.run_id, '--attempt', args.attempt, '--sha', sha, '--api', api, '--lane', 'tageditor-'+args.case, '--', *argv]
    require(context['supervisor_argv'] == supervisor_argv, 'Wrong supervisor invocation provenance')
    status = read_json(evidence/'status.json')
    require(status['native_exit'] == 0 and status['cleanup_exit'] == 0 and status['cancel_signal'] is None, 'Native execution/cleanup not successful')
    require(data(evidence/'runner-exit.txt').strip() == b'0' and status['runner_exit'] == 0 and status['phase'] == 'native-terminal', 'Runner exit receipt missing/nonzero')
    captures = {}
    for line in data(evidence/'capture-status.txt').decode().splitlines():
        name, value = line.split('=', 1)
        require(name not in captures and value.isdigit(), 'Ambiguous capture status')
        captures[name] = int(value)
    required_captures = {'logcat', 'crash-buffer', 'windows', 'activities', 'device-output-pull', 'boundary-start', 'boundary-end'}
    require(set(captures) == required_captures, 'Incomplete capture statuses')
    require(all(captures[k] == 0 for k in required_captures - {'device-output-pull'}), 'Native diagnostic capture degraded')
    certificates = data(evidence/'test-input-certificates.txt').decode()
    fingerprints = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', certificates, re.M)
    require(len(fingerprints) == 2 and fingerprints[0] == fingerprints[1], 'App/test signing identity mismatch')
    device = read_json(evidence/'device.json')
    require(device['api'] == api and device['serial'] == 'emulator-5554', 'Wrong native device/API')
    require(device['effective_size'] == [720,1280] and all(type(v) is int for v in device['effective_size']), 'Wrong device size')
    require(type(device['effective_density']) is int and device['effective_density'] == 280, 'Wrong device density')
    scales = device['animation_scales']
    require(isinstance(scales,dict) and set(scales) == {'animator_duration_scale','transition_animation_scale','window_animation_scale'}, 'Incomplete animation settings')
    require(all(type(v) in (int,float) and math.isfinite(v) and v == 1.0 for v in scales.values()), 'Wrong animation settings')
    inventory = read_json(evidence/'expected-methods.json')
    classes = sorted({value.split('#')[0] for value in selectors})
    require(inventory['selectors'] == classes and set(expected).issubset(inventory['runtime_methods']), 'Wrong source method inventory')
    require(len(inventory['classes']) == len(classes), 'Wrong source class count')
    for entry in inventory['classes']:
        path = source/entry['source']
        require(path.resolve().is_relative_to(source) and entry['class'] in classes, 'Unexpected source class')
        require(digest(path) == entry['sha256'], 'Source inventory hash mismatch')
        require(git(source, 'hash-object', str(path)) == git(source, 'rev-parse', sha+':'+entry['source']), 'Source file differs from pinned tree')
    require(inventory == DECLARED_INVENTORY, 'Pinned declared source inventory differs')
    apk_paths = ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    require(data(evidence/'build-input-apks.sha256') == data(evidence/'test-input-apks.sha256'), 'Build/native APK inputs differ')
    require(data(evidence/'APK-STATUS.txt').decode().startswith('UNVALIDATED DIAGNOSTIC DEBUG INPUTS.'), 'Missing unvalidated APK qualification')
    lines = data(evidence/'test-input-apks.sha256').decode().splitlines()
    require(len(lines) == 2, 'Incomplete APK hash receipt')
    for line, path in zip(lines, apk_paths):
        require(line == digest(source/path)+'  '+path, 'APK checksum receipt mismatch')
    live = evidence/'live'
    observed = read_json(live/'observed/manifest.json')
    supervised = read_json(live/'supervision.json')
    published = read_json(live/'publishing/complete.json')
    for receipt in (observed, supervised, published):
        require(receipt['sha'] == sha and receipt['api'] == api, 'Monitoring source/API mismatch')
    require(observed['lane'] == published['lane'] == 'tageditor-'+args.case, 'Wrong monitored lane')
    require(observed['argv'] == argv and observed['command_id'] == published['command_id'], 'Monitoring command mismatch')
    require(re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', observed['command_id']), 'Invalid command identity')
    require(observed['status'] == 'completed' and observed['ended_utc'] is not None, 'Observer incomplete')
    for key in ('child_returncode', 'observer_exit_status', 'observed_failure_lines', 'oversize_lines_not_parsed'):
        require(observed[key] == 0, 'Degraded/nonpassing observation '+key)
    require(observed['cancel_signal'] is None and observed['observer_error'] is None, 'Observer canceled/degraded')
    require(supervised['observer_exit'] == supervised['publisher_exit'] == 0 and supervised['monitoring_complete'] is True and supervised['supervisor_error'] is None, 'Supervisor degraded')
    require(published['status'] == 'monitoring_complete' and published['observer_status'] == 'completed', 'Publisher incomplete')
    require(published['observed_failure_lines'] == published['observed_failures'] == published['uploaded_packets'] == published['read_offset'] == 0, 'Contradictory publisher evidence')
    require(data(live/'observed/events.jsonl') == b'', 'Provisional failure contradicts pass')
    require(not list((live/'publishing').glob('packet-*.json')) and not list((live/'publishing').glob('receipt-*.json')) and not (live/'publishing/monitoring-degraded.json').exists(), 'Contradictory publication state')
    require(data(live/'observed/command.log').strip(), 'Empty native command output')
    methods, xml = xml_methods(source/'app/build/outputs/androidTest-results', expected)
    trace = trace_summary(evidence/'logcat.txt', args.run_id, args.attempt, sha)
    require(trace['status'] == 'complete', 'Incomplete diagnostic trace: '+str(trace['errors']))
    originals = {}
    for name in ('logcat', 'crash-buffer', 'windows', 'activities'):
        originals['evidence/'+name+'.txt'] = digest(evidence/(name+'.txt'))
    require(data(evidence/'logcat.txt').strip(), 'Empty native logcat')
    return {'schema_version': 1, 'case': args.case, 'source_sha': sha, 'integrated_sha': parent, 'integrated_tree': '83c3655bf4a1700bb60d7c75b37d2b33ad63f4b1', 'test_only_delta': ['app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt'], 'workflow_sha': args.workflow_sha, 'api': api, 'run_id': args.run_id, 'attempt': args.attempt, 'methods': methods, 'xml_sha256': xml, 'original_sha256': originals, 'source_inventory_sha256': digest(evidence/'expected-methods.json'), 'monitoring_complete': True, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=CASES, required=True)
    for name in ('source', 'tooling', 'evidence', 'workflow-sha', 'run-id', 'attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--runner-exit', type=int, required=True)
    args = parser.parse_args()
    result = {'case': args.case, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False, 'status': 'rejected'}
    code = 3
    try:
        sha = CASES[args.case][0]
        trace = trace_summary(Path(args.evidence)/'logcat.txt', args.run_id, args.attempt, sha)
        with (Path(args.evidence)/'trace-summary.json').open('x') as output:
            json.dump(trace, output, indent=2)
            output.write('\n')
        result.update(reconcile(args), status='diagnostic-complete')
        code = 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        result['error'] = type(error).__name__+': '+str(error)
        print('Diagnostic reconciliation rejected: '+result['error'], file=sys.stderr)
    try:
        with (Path(args.evidence)/'reconciled-tests.json').open('x') as output:
            json.dump(result, output, indent=2)
            output.write('\n')
    except OSError as error:
        print('Cannot preserve fresh reconciliation receipt: '+str(error), file=sys.stderr)
        return 3
    return code


if __name__ == '__main__':
    sys.exit(main())
