#!/usr/bin/env python3
"""Same-snapshot correctness precheck, then uninstrumented eight-method ABBA.

Validation only: never a complete-suite or release certificate. The published
support module is imported unchanged for evidence and process lifecycle utilities.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

BASELINE_SHA = '39f5b6a4a27fc46c907b2d4c184c2ef5e3a0bb56'
SUPPORT_SHA = 'e3ac4f31ed506b55f39e9fa806010106ea0965bd'
TEST_SOURCE = 'app/src/androidTest/java/io/github/anastaci1a/localica/PinchReleaseHandoffTest.kt'
CLASS = 'io.github.anastaci1a.localica.PinchReleaseHandoffTest'
TEST_APK = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
METHODS = (
    'longHeldOutwardPinchDoesNotRecenterForOneReleaseFrame',
    'longHeldInwardPinchDoesNotRecenterForOneReleaseFrame',
    'longHeldCancelledPinchRestoresOriginalAnchor',
    'longHeldCancelledInwardPinchRestoresOriginalAnchor',
    'galleryFiveToLargeKeepsItsPresentedAnchorThroughRelease',
    'galleryFiveToFourKeepsItsPresentedAnchorThroughRelease',
    'cancelledGalleryToListRestoresItsArtworkWithoutReplay',
    'cancelledGalleryDensityRestoresItsArtworkWithoutReplay',
)
EXPECTED = {CLASS+'#'+method for method in METHODS}
HELPERS = ['.github/prepare-device-tests.sh', '.github/test-evidence/source_methods.py',
           '.github/test-evidence/observe.py', '.github/test-evidence/supervise.py',
           '.github/test-evidence/publish.mjs']
SHA = re.compile(r'[0-9a-f]{40}')
SHA256 = re.compile(r'[0-9a-f]{64}')
EQUIVALENCE = re.compile(r'rows=([0-9]+) top=([0-9]+) bottom=([0-9]+) width=([0-9]+) fullWidth=(true|false)')
FRAME = re.compile(r'outward=(true|false) cancelled=(true|false) frame=([0-9]+) t=([0-9]+) center=([^ ]+) expected=([^ ]+)')
LOG_RECORD = re.compile(r'(?P<time>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+'
                        r'(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEF])\s+'
                        r'(?P<tag>[^:]+): (?P<message>.*)')
BOUNDARY = re.compile(r'(started|finished): ([A-Za-z0-9_]+)\(([^()]+)\)')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_config(config):
    require(set(config) == {'baseline_sha', 'candidate_sha', 'equivalence_sha', 'test_sha256'}, 'Unexpected config schema')
    require(config['baseline_sha'] == BASELINE_SHA, 'Wrong immutable baseline')
    refs = [config[key+'_sha'] for key in ('baseline', 'candidate', 'equivalence')]
    require(all(isinstance(x, str) and SHA.fullmatch(x) for x in refs) and len(set(refs)) == 3, 'Invalid/distinct source SHAs required')
    hashes = config['test_sha256']
    require(isinstance(hashes, dict) and set(hashes) == {'baseline', 'candidate', 'equivalence'}, 'Wrong test hash schema')
    require(all(isinstance(x,str) and SHA256.fullmatch(x) for x in hashes.values()) and len(set(hashes.values())) == 3,
            'Invalid/distinct test hashes required')
    return config


def load_support(root):
    require(git(root, 'rev-parse', 'HEAD') == SUPPORT_SHA, 'Wrong reviewed support SHA')
    require(git(root, 'status', '--porcelain', '--untracked-files=no') == '', 'Dirty support checkout')
    path = root/'.github/suite-cost/run_paired.py'
    require(not path.is_symlink(), 'Symlinked support module')
    spec = importlib.util.spec_from_file_location('reviewed_paired', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    require(module.LOG_RECORD.pattern == LOG_RECORD.pattern and module.BOUNDARY.pattern == BOUNDARY.pattern,
            'Reviewed support log contract changed')
    return module


def check_inventory(inv):
    require(inv['selectors'] == [CLASS] and inv['method_count'] == 8 and len(inv['runtime_methods']) == 8
            and set(inv['runtime_methods']) == EXPECTED, 'Wrong exact eight-method source inventory')


def check_source_probes(source, equivalence=False):
    paths = list((source/'app/src').rglob('*'))
    require(paths, 'Missing app sources')
    for path in paths:
        require('NativeSuiteCost' not in path.name, 'Diagnostic source file present')
        if path.is_file() and path.suffix in ('.kt', '.java'):
            raw = path.read_bytes()
            require(b'NativeSuiteCost' not in raw, 'Timing probe source present')
            if not equivalence or path != source/TEST_SOURCE:
                require(b'PinchRowEquivalence' not in raw, 'Equivalence probe in clean/unexpected source')
    if equivalence:
        require(b'PinchRowEquivalence' in (source/TEST_SOURCE).read_bytes(), 'Missing equivalence source probe')


def check_logs(root, equivalence):
    files = sorted(root.rglob('logcat-*.txt'))
    names = {'logcat-'+identity.replace('#','-')+'.txt': identity for identity in EXPECTED}
    require(len(files) == 8 and {p.name for p in files} == set(names), 'Missing/extra/duplicate native method logs')
    receipts = []
    for path in files:
        require(not path.is_symlink() and path.stat().st_size <= 16*1024*1024, 'Unsafe method log')
        identity = names[path.name]
        boundaries, pid, frames, samples = [], None, [], []
        raw_samples = set()
        for index, line in enumerate(path.read_text(errors='strict').splitlines(), 1):
            require('NativeSuiteCost' not in line, 'Timing probe present in native output')
            if not equivalence:
                require('PinchRowEquivalence' not in line, 'Equivalence probe in timed trial')
            record = LOG_RECORD.fullmatch(line)
            if record is None:
                require(not any(tag in line for tag in ('PinchRowEquivalence', 'TestRunner:', 'PinchReleaseHandoff:')),
                        'Malformed required native record')
                continue
            tag, message = record['tag'].strip(), record['message']
            if tag == 'TestRunner' and message.startswith(('started:', 'finished:')):
                boundary = BOUNDARY.fullmatch(message)
                require(boundary is not None and boundary[3]+'#'+boundary[2] == identity, 'Wrong method boundary')
                boundaries.append(boundary[1])
                require(boundaries in (['started'], ['started','finished']), 'Missing/duplicate/reversed boundary')
                pid = pid or record['pid']
                require(record['pid'] == pid, 'Boundary process changed')
            elif tag == 'PinchRowEquivalence':
                require(equivalence and boundaries == ['started'] and record['pid'] == pid, 'Equivalence outside method/process')
                require(line not in raw_samples, 'Duplicate raw equivalence record')
                raw_samples.add(line)
                sample = EQUIVALENCE.fullmatch(message)
                require(sample is not None, 'Malformed equivalence result')
                rows, top, bottom, width = map(int, sample.groups()[:4])
                require(0 <= rows <= bottom-top and width > 0, 'Invalid equivalence geometry')
                gallery = 'Gallery' in identity or '#gallery' in identity
                require((sample[5] == 'true') == gallery, 'Wrong sampling mode for native method')
                samples.append({'line':index, 'rows':rows, 'top':top, 'bottom':bottom, 'width':width,
                                'full_width':sample[5] == 'true'})
            elif tag == 'PinchReleaseHandoff' and message.startswith('outward='):
                require(boundaries == ['started'] and record['pid'] == pid, 'Frame outside method/process')
                frame = FRAME.fullmatch(message)
                require(frame is not None, 'Malformed frame evidence')
                frames.append(int(frame[3]))
        require(boundaries == ['started','finished'], 'Incomplete method boundary')
        require(frames == list(range(60)), 'Missing/duplicate/reordered release frames')
        require(len(samples) == (62 if equivalence else 0), 'Missing/extra same-snapshot equivalence comparisons')
        receipts.append({'method':identity, 'log':str(path), 'sha256':digest(path),
                         'release_frames':len(frames), 'equivalence_samples':samples})
    return receipts


def check_distinct_test_apks(sha, actual, history):
    for previous_sha, previous in history.items():
        if previous_sha != sha:
            require(actual[TEST_APK] != previous[TEST_APK], 'Different test variants produced identical instrumentation APKs')


def trial_plan():
    return [('E0','equivalence'), ('A1','baseline'), ('B1','candidate'), ('B2','candidate'), ('A2','baseline')]


def run(args):
    config = validate_config(json.loads(args.config.read_text()))
    roots = {kind:getattr(args,kind).resolve() for kind in ('baseline','candidate','equivalence')}
    support, output = args.reviewed_harness.resolve(), args.output.resolve()
    all_roots = [*roots.values(), support, output]
    for index,a in enumerate(all_roots):
        for b in all_roots[index+1:]:
            require(a != b and a not in b.parents and b not in a.parents, 'Independent non-nested directories required')
    require(not output.exists() and not output.is_symlink(), 'Stale evidence refused')
    require(args.api in ('26','36'), 'Unsupported API')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Hosted runner required')
    for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[0-9]+',os.environ.get(key,'')), 'Missing run identity')
    serial = os.environ.get('ANDROID_SERIAL','emulator-5554')
    require(re.fullmatch(r'emulator-[0-9]+',serial), 'Emulator serial required')
    def adb(*argv):
        return subprocess.check_output(['adb','-s',serial,*argv],text=True).strip()
    require(adb('shell','getprop','ro.kernel.qemu') == '1' and adb('shell','getprop','ro.build.version.sdk') == args.api, 'Wrong emulator/API')
    h = load_support(support)
    runner = h.Run(roots['baseline'],output)
    helper_hashes = {p:digest(roots['baseline']/p) for p in HELPERS}
    for kind,root in roots.items():
        runner.source = root
        runner.clean(config[kind+'_sha'])
        require(digest(root/TEST_SOURCE) == config['test_sha256'][kind], 'Wrong pinned native test bytes')
        check_source_probes(root,kind == 'equivalence')
        require({p:digest(root/p) for p in HELPERS} == helper_hashes, 'Native helper drift')
        for relative in h.OUTPUTS:
            require(not (root/relative).exists() and not (root/relative).is_symlink(), 'Stale initial native output')
    for left,right in (('baseline','candidate'),('candidate','equivalence')):
        changed = git(roots['baseline'],'diff','--name-only',config[left+'_sha'],config[right+'_sha']).splitlines()
        require(changed == [TEST_SOURCE], 'Unexpected source changes outside the one test helper')
    output.mkdir(parents=True)
    h.write_json(output/'identity.json',{'config':config,'support_sha':SUPPORT_SHA,
        'support_module_sha256':digest(support/'.github/suite-cost/run_paired.py'), 'helpers':helper_hashes,
        'roots':{k:str(v) for k,v in roots.items()},'api':args.api,'order':'E0,A1,B1,B2,A2','release_certified':False})
    apk_history, rows = {}, []
    for name,kind in trial_plan():
        source, sha = roots[kind],config[kind+'_sha']
        runner.source = source
        trial = output/name;trial.mkdir();status = 0
        h.write_json(trial/'identity.json',{'trial':name,'variant':kind,'sha':sha,'api':args.api,
            'run_id':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'], 'source_root':str(source),
            'included_in_timing':kind != 'equivalence'})
        try:
            runner.clean(sha);check_source_probes(source,kind == 'equivalence')
            require({p:digest(source/p) for p in HELPERS} == helper_hashes,'Helper drift')
            for relative in h.OUTPUTS:
                require(not (source/relative).exists() and not (source/relative).is_symlink(),'Stale trial output')
            status,build_seconds = runner.command(['./gradlew','assembleDebug','assembleDebugAndroidTest','--no-daemon','--max-workers=2'],log=trial/'compile.log')
            h.write_json(trial/'build.json',{'exit':status,'wall_seconds':build_seconds})
            require(status == 0,'Compile failed');runner.clean(sha)
            runner.command([sys.executable,'.github/test-evidence/source_methods.py','--classes',CLASS,'--output',str(trial/'expected-methods.json')])
            check_inventory(json.loads((trial/'expected-methods.json').read_text()))
            apks = h.check_input_apks(source,trial,sha,apk_history)
            folder = trial/'apks';folder.mkdir()
            for path in h.APKS:shutil.copyfile(source/path,folder/Path(path).name)
            check_distinct_test_apks(sha,apks,apk_history)
            resets=[]
            for package in ('io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'):
                present='package:'+package in adb('shell','pm','list','packages','--user','0',package).splitlines()
                receipt={'package':package,'present':present,'action':'absent'}
                if present:
                    response=adb('shell','pm','clear','--user','0',package)
                    receipt.update(action='clear',response=response)
                    h.write_json(trial/(package+'.clear.json'),receipt)
                    require(response == 'Success','Owned app reset failed')
                resets.append(receipt)
            h.write_json(trial/'owned-app-reset.json',resets)
            argv=['./gradlew','connectedDebugAndroidTest','--no-daemon','--max-workers=2',
                  '-Pandroid.testInstrumentationRunnerArguments.class='+CLASS]
            lane='pinch-row-'+name
            env=dict(os.environ,GITHUB_SHA=sha,TEST_API=args.api)
            invocation=[sys.executable,'.github/test-evidence/supervise.py','--output',str(trial/'live'),
                '--run-id',env['GITHUB_RUN_ID'],'--attempt',env['GITHUB_RUN_ATTEMPT'],'--sha',sha,
                '--api',args.api,'--lane',lane,'--',*argv]
            h.write_json(trial/'invocation.json',{'supervisor_argv':invocation,'native_argv':argv,
                'run_id':env['GITHUB_RUN_ID'],'attempt':env['GITHUB_RUN_ATTEMPT']})
            status,wall_seconds=runner.command(invocation,log=trial/'supervised-console.log',env=env,supervisor=True)
            h.write_json(trial/'execution.json',{'exit':status,'wall_seconds':wall_seconds,'included_in_timing':kind != 'equivalence'})
            runner.archive(trial)
            after={p:digest(source/p) for p in h.APKS};h.write_json(trial/'output-apks.json',after)
            if after != apks:h.preserve_apk_mismatch(source,trial,after,apks,'APK changed during native run','output')
            require(status == 0,'Native/monitor failed');runner.clean(sha)
            monitoring=h.check_monitor(trial/'live',sha,args.api,lane,argv)
            result=h.parse_xml(trial/'raw/app/build/outputs/androidTest-results',EXPECTED)
            h.write_json(trial/'native-xml-verified.json',result)
            logs=check_logs(trial/'raw/app/build/outputs/androidTest-results',kind == 'equivalence')
            result.update(trial=name,variant=kind,sha=sha,api=args.api,wall_seconds=wall_seconds,
                          build_seconds=build_seconds,monitoring=monitoring,logs=logs,included_in_timing=kind != 'equivalence')
            h.write_json(trial/'verified.json',result)
            if kind != 'equivalence':rows.append(result)
        except BaseException as error:
            try:runner.archive(trial)
            except BaseException as e:(trial/'archive-error.txt').write_text(str(e))
            try:runner.failure_context(trial)
            except BaseException as e:(trial/'failure-context-error.txt').write_text(str(e))
            h.write_json(trial/'failed.json',{'error':str(error),'type':type(error).__name__,'invocation_exit':status})
            return status or (128+runner.cancelled if runner.cancelled else 2)
    summary={'config':config,'api':args.api,'order':'ABBA','equivalence_trial':'E0','equivalence_cases':8,
             'equivalence_captured_snapshots':496,'methods_per_trial':8,'measured_native_cases':32,'all_passed':True,
             'trials':rows,'release_certified':False,
             'interpretation':'Two paired focused samples per variant on one emulator; not significance or whole-suite savings. Precheck excluded.'}
    for metric in ('method_seconds','suite_seconds','wall_seconds'):
        a=sum(r[metric] for r in rows if r['variant']=='baseline')/2
        b=sum(r[metric] for r in rows if r['variant']=='candidate')/2
        summary[metric]={'baseline_mean':a,'candidate_mean':b,'candidate_minus_baseline':b-a,'percent':100*(b/a-1) if a else None}
    h.write_json(output/'comparison.json',summary)
    return 0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--print-refs',action='store_true')
    for key in ('baseline','candidate','equivalence','reviewed-harness','output'):parser.add_argument('--'+key,type=Path)
    parser.add_argument('--api')
    args=parser.parse_args()
    try:
        if args.print_refs:
            config=validate_config(json.loads(args.config.read_text()))
            for key in ('baseline','candidate','equivalence'):print(key+'_sha='+config[key+'_sha'])
            return 0
        require(all(getattr(args,key) is not None for key in ('baseline','candidate','equivalence','reviewed_harness','output','api')), 'Missing runner argument')
        return run(args)
    except (ValueError,OSError,KeyError,TypeError,subprocess.CalledProcessError) as error:
        print('Pinch validation rejected: '+str(error),file=sys.stderr);return 2


if __name__ == '__main__':sys.exit(main())
