#!/usr/bin/env python3
"""Record complete native coverage and select a green exact-tree release producer."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from collections import Counter
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent/'test-evidence'))
from full_partition import expected_for

DEVICE_SHARDS = 12  # keep equal to LOCALICA_DEVICE_SHARDS and the device-tests matrix in android.yml
REQUIRED_JOBS = (
    'build', 'test-apks', 'static-checks', 'device-tests-summary', 'compatibility (26)', 'compatibility (36)',
    'distribution-smoke (26)', 'distribution-smoke (36)',
) + tuple(f'device-tests ({api}, {shard})' for api in (26, 36) for shard in range(DEVICE_SHARDS))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def gh(path, binary=False):
    command = ['gh', 'api']
    if binary and '/actions/jobs/' in path and path.endswith('/logs'):
        help_text = subprocess.check_output(['gh', 'api', '--help'])
        if b'--allow-escape-sequences' in help_text:
            command.append('--allow-escape-sequences')
    result = subprocess.check_output(command + [path])
    return result if binary else json.loads(result)


def pages(path, key):
    result = []
    for page in range(1, 101):
        response = gh(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
        rows = response[key]
        result.extend(rows)
        if len(rows) < 100:
            require(len(result) == response['total_count'], 'Incomplete API inventory')
            return result
    raise ValueError('API inventory exceeds bounded pagination')


def validate(run, jobs, receipt, unions, repository, tree, expected):
    require(run['repository']['full_name'] == run['head_repository']['full_name'] == repository, 'Untrusted repository')
    require(run['path'] == '.github/workflows/android.yml', 'Wrong workflow')
    require(run['event'] in ('push', 'workflow_dispatch'), 'Untrusted event')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Run is not fully green')
    require(receipt['schema_version'] == 1 and receipt['scope'] == 'complete_native_union', 'Not complete native evidence')
    require(receipt['sha'] == run['head_sha'] and receipt['tree'] == tree, 'Source/tree mismatch')
    require(receipt['run_id'] == str(run['id']) and receipt['attempt'] == str(run['run_attempt']), 'Stale producer receipt')
    require(re.fullmatch('[0-9a-f]{64}', receipt['apk_sha256']), 'Invalid APK identity')
    for name in REQUIRED_JOBS:
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1, 'Missing/duplicate required job: ' + name)
        job = matching[0]
        require(job['conclusion'] == 'success' and job['head_sha'] == run['head_sha']
                and job['run_id'] == run['id'] and job['run_attempt'] == run['run_attempt'],
                'Unsuccessful or stale required job: ' + name)
    for api in ('26', '36'):
        union = unions[api]
        require(union['api'] == api and union['shards'] == DEVICE_SHARDS and union['xml_roots'] >= DEVICE_SHARDS, 'Missing shard union')
        require(union['expected'] == union['executed_once'] == expected[api] > 0, 'Incomplete native union')
        require(union['failure'] == union['error'] == 0, 'Native failures')
        require(union['passed'] + union['skipped'] == expected[api], 'Inconsistent union outcomes')


def record(apk_sha256):
    require(re.fullmatch('[0-9a-f]{64}', apk_sha256), 'Invalid delivery APK hash')
    receipt = dict(schema_version=1, scope='complete_native_union', sha=os.environ['GITHUB_SHA'],
                   tree=subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(),
                   run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'],
                   apk_sha256=apk_sha256)
    Path('verification.json').write_text(json.dumps(receipt, indent=2)+'\n')


def artifact(artifacts, name, run, build):
    matches = [item for item in artifacts if item['name'] == name and not item['expired']]
    require(len(matches) == 1, 'Missing/ambiguous retained artifact: ' + name)
    item = matches[0]
    require(item['workflow_run']['id'] == run['id'] and item['workflow_run']['head_sha'] == run['head_sha'], 'Artifact producer mismatch')
    require(re.fullmatch('sha256:[0-9a-f]{64}', item.get('digest', '')), 'Missing artifact digest')
    if name == 'localica-debug':
        require(build['started_at'] <= item['created_at'] <= build['completed_at'], 'Artifact outside successful build')
    return item


def release_output(run_id, apk_sha, provenance):
    Path('verified-provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'run-id={run_id}\napk-sha256={apk_sha}\n')


def delta_run(repository, run_id, workflow, sha):
    require(type(run_id) is int and run_id > 0, 'Invalid split release run ID')
    run = gh(f'repos/{repository}/actions/runs/{run_id}')
    require(run['id'] == run_id and run['head_sha'] == sha and run['status'] == 'completed'
            and run['event'] == 'workflow_dispatch' and run['path'] == '.github/workflows/'+workflow
            and run['repository']['full_name'] == run['head_repository']['full_name'] == repository,
            'Untrusted split release run')
    jobs = pages(f'repos/{repository}/actions/runs/{run_id}/jobs?filter=latest', 'jobs')
    require(len({job['name'] for job in jobs}) == len(jobs), 'Duplicate split release job')
    require(all(job['run_id'] == run_id and job['head_sha'] == sha
                and job['run_attempt'] == run['run_attempt'] for job in jobs), 'Stale split release job')
    return run, {job['name']: job for job in jobs}


def archived_artifact(repository, item):
    require(item['size_in_bytes'] <= 256*1024*1024, 'Oversized split release archive')
    data = gh(f'repos/{repository}/actions/artifacts/{item["id"]}/zip', binary=True)
    require('sha256:'+hashlib.sha256(data).hexdigest() == item['digest'], 'Split archive digest mismatch')
    return zipfile.ZipFile(io.BytesIO(data))


def provisional_native(repository, run, jobs, artifacts):
    require(run['conclusion'] in ('success', 'failure'), 'Provisional run did not finish device tests')
    summary = jobs['device-tests-summary']
    require(summary['conclusion'] == run['conclusion'], 'Provisional summary did not finish cleanly')
    log = gh(f'repos/{repository}/actions/jobs/{summary["id"]}/logs', binary=True)
    clean = '\n'.join(line.partition(' ')[2] for line in log.decode().splitlines())
    entries = re.findall(r'^\{\n\s+"api": "(?:26|36)",\n.*?^\}', clean, re.M | re.S)
    unions = {entry['api']: entry for entry in map(json.loads, entries)}
    require(len(entries) == 2 and set(unions) == {'26', '36'}, 'Missing provisional complete unions')
    failed = {}
    for api, union in unions.items():
        require(union['shards'] == DEVICE_SHARDS and union['xml_roots'] >= DEVICE_SHARDS
                and union['expected'] == union['executed_once'] > 0 and union['error'] == 0
                and union['passed'] + union['skipped'] + union['failure'] == union['expected'],
                'Incomplete provisional native union')
        for shard in range(DEVICE_SHARDS):
            job = jobs[f'device-tests ({api}, {shard})']
            require(job['conclusion'] in ('success', 'failure'), 'Provisional native shard did not finish')
            if job['conclusion'] == 'success':
                continue
            name = f'device-reports-api-{api}-shard-{shard}'
            item = artifact(artifacts, name, run, job)
            require(job['started_at'] <= item['created_at'] <= job['completed_at'], 'Stale native shard archive')
            count = 0
            with archived_artifact(repository, item) as archive:
                for member in archive.infolist():
                    if '/androidTest-results/' not in member.filename or not member.filename.endswith('.xml'):
                        continue
                    require(member.file_size <= 32*1024*1024, 'Oversized provisional test XML')
                    raw = archive.read(member)
                    require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(),
                            'Provisional XML entity declaration')
                    for case in ET.fromstring(raw).iter('testcase'):
                        failure = case.find('failure')
                        if failure is None:
                            continue
                        identity = (api, case.attrib['classname']+'#'+case.attrib['name'])
                        message = failure.get('message') or (failure.text or '').partition('\n')[0]
                        require(identity not in failed and message.strip(), 'Invalid provisional failure')
                        failed[identity] = hashlib.sha256(message.encode()).hexdigest()
                        count += 1
            require(count > 0, 'Provisional shard failed outside an assertion')
    require(Counter(api for api, _ in failed) == Counter({api: union['failure'] for api, union in unions.items()}),
            'Provisional failures differ from the complete unions')
    return unions, failed


def scoped_native_coverage(repository, commit, scoped_runs):
    require(0 < len(scoped_runs) <= 24 and len(set(scoped_runs)) == len(scoped_runs), 'Invalid scope run IDs')
    covered = {'26': set(), '36': set()}
    for run_id in scoped_runs:
        run, scoped_jobs = delta_run(repository, run_id, 'prototype.yml', commit)
        decision_job = scoped_jobs['Prototype evidence and delivery decision']
        require(run['conclusion'] == 'success' and decision_job['conclusion'] == 'success',
                'Scope workflow failed')
        artifacts = pages(f'repos/{repository}/actions/runs/{run_id}/artifacts', 'artifacts')
        item = artifact(artifacts, 'prototype-delivery-decision', run, decision_job)
        require(decision_job['started_at'] <= item['created_at'] <= decision_job['completed_at'],
                'Stale scope archive')
        with archived_artifact(repository, item) as archive:
            require(archive.namelist().count('manifest.json') == 1
                    and archive.getinfo('manifest.json').file_size <= 8*1024*1024, 'Invalid scope manifest')
            manifest = json.loads(archive.read('manifest.json'))
        require(manifest['sha'] == commit and manifest['run_id'] == str(run_id)
                and manifest['run_attempt'] == str(run['run_attempt']) and manifest['validation_errors'] == []
                and not manifest['build_only'] and not manifest['requested_methods'], 'Invalid whole-class proof')
        for api, evidence in manifest['focused_evidence'].items():
            require(manifest['checks']['focused_'+api] == 'passed' and evidence['sha'] == commit
                    and str(evidence['api']) == api and all(outcome in ('passed', 'skipped')
                    for outcome in evidence['methods'].values()), 'Native scope did not pass')
            covered[api].update(evidence['selectors'])
    return covered


def verify_native_scope(repository, commit, packet, failed):
    # The maintainer's reviewed dependency partition identifies the classes eligible for reuse.
    final_classes = set(packet['affected_classes'])
    carried_classes = set(packet['carried_classes'])
    require(final_classes and final_classes.isdisjoint(carried_classes)
            and len(final_classes)+len(carried_classes) == len(packet['affected_classes'])+len(packet['carried_classes'])
            and all(re.fullmatch(r'io\.github\.anastaci1a\.localica\.[\w.$]+', c)
                    for c in final_classes | carried_classes), 'Invalid reviewed dependency partition')
    final_coverage = scoped_native_coverage(repository, commit, packet['scoped_runs'])
    require(all(final_classes <= classes for classes in final_coverage.values()), 'Final hosted affected-class gap')
    prior_runs = packet['carried_scoped_runs']
    require(bool(carried_classes) == bool(prior_runs)
            and set(prior_runs).isdisjoint(packet['scoped_runs']), 'Invalid carried class run IDs')
    if carried_classes:
        prior = 'fca7f179700c6853509d919b103cdc3e57cf4139'
        comparison = gh(f'repos/{repository}/compare/{prior}...{commit}?per_page=1')
        require(comparison['status'] == 'ahead' and comparison['merge_base_commit']['sha'] == prior,
                'Carried source is not an ancestor of the release')
        carried_coverage = scoped_native_coverage(repository, prior, prior_runs)
        require(all(carried_classes <= classes for classes in carried_coverage.values()), 'Carried hosted class gap')
    for api in ('26', '36'):
        local = packet['local'][api]
        require(local['sha'] == commit and local['exit'] == 0 and re.fullmatch('[0-9a-f]{64}', local['receipt_sha256'])
                and final_classes <= set(local['classes'])
                and all(method in local['passed_methods'] for sdk, method in failed if sdk == api),
                'Final local gate or held-failure verification incomplete')


def select_v03416(repository, commit, tree):
    require(repository == 'anastaci1a/localica' and os.environ.get('GITHUB_ACTOR') == 'anastaci1a',
            'Only the release maintainer can use the v0.34.16 split gate')
    ref = 'refs/tags/v0.34.16'
    require(os.environ.get('GITHUB_REF') == ref, 'Split gate requires the v0.34.16 tag')
    tag = gh(f'repos/{repository}/git/ref/tags/v0.34.16')['object']
    require(tag['type'] == 'tag', 'Split gate requires an annotated tag')
    annotation = gh(f'repos/{repository}/git/tags/{tag["sha"]}')
    require(annotation['object']['type'] == 'commit' and annotation['object']['sha'] == commit
            and annotation['tag'] == 'v0.34.16', 'Split annotation does not name the tagged commit')
    require(len(annotation['message']) <= 128*1024, 'Oversized split annotation')
    packet = json.loads(annotation['message'])
    require(packet['version'] == 'v0.34.16' and packet['source_sha'] == commit and packet['tree'] == tree
            and packet['provisional_run'] == 44665987162 and packet['schema'] == 1
            and re.fullmatch('[0-9a-f]{64}', packet['apk_sha256'])
            and re.fullmatch('[0-9a-f]{64}', packet['review_sha256']), 'Invalid reviewed split annotation')
    provisional, jobs = delta_run(repository, packet['provisional_run'], 'android.yml',
                                  '830f133db21e0211c1eca2e825b2ca90877e4dc0')
    non_native = ['build', 'test-apks', 'static-checks', 'compatibility (26)', 'compatibility (36)',
                  'distribution-smoke (26)', 'distribution-smoke (36)', 'Commit identities and attribution']
    for name in non_native:
        require(jobs[name]['conclusion'] == 'success', 'Provisional non-native job failed: '+name)
    before_artifacts = pages(f'repos/{repository}/actions/runs/{provisional["id"]}/artifacts', 'artifacts')
    unions, failed = provisional_native(repository, provisional, jobs, before_artifacts)
    held = {}
    for item in packet['held_failures']:
        identity = (item['api'], item['method'])
        require(identity not in held and re.fullmatch('[0-9a-f]{64}', item['held_signature_sha256']),
                'Invalid held failure identity')
        held[identity] = item['held_signature_sha256']
    require(held == failed, 'A provisional failure differs from the reviewed held disclosure')

    final, final_jobs = delta_run(repository, packet['final_build_run'], 'android.yml', commit)
    require(final['conclusion'] == 'success' and final['head_branch'].startswith('proof/sol-release-tonight-final-')
            and all(final_jobs[name]['conclusion'] == 'success' for name in non_native),
            'Final signed distribution proof is incomplete')
    final_artifacts = pages(f'repos/{repository}/actions/runs/{final["id"]}/artifacts', 'artifacts')
    delivery = artifact(final_artifacts, 'localica-debug', final, final_jobs['build'])

    verify_native_scope(repository, commit, packet, failed)
    provisional_phone = packet['provisional_a35']
    require(provisional_phone['sha'] == provisional['head_sha'] and provisional_phone['restore_exit'] == 0
            and re.fullmatch('[0-9a-f]{64}', provisional_phone['receipt_sha256']), 'Invalid provisional A35 receipt')
    timed = provisional_phone['exit'] == 0 and provisional_phone['pairs'] == 5
    stopped = (provisional_phone['exit'] == 1 and 0 <= provisional_phone['pairs'] < 5
               and provisional_phone['decision'] == 'a35-restart-2226')
    require(timed or stopped, 'Provisional A35 disposition is missing')
    phone = packet['a35']
    require(phone['sha'] == commit and phone['apk_sha256'] == packet['apk_sha256'] and phone['exit'] == 0
            and phone['smoke_exit'] == 0 and phone['restore_exit'] == 0
            and re.fullmatch('[0-9a-f]{64}', phone['receipt_sha256']), 'Final A35 proof is incomplete')
    release_output(final['id'], packet['apk_sha256'], dict(release_commit=commit, release_tree=tree,
        provisional_run=provisional, provisional_unions=unions, final_run=final, artifact=delivery,
        reviewed_split_gate=packet))


def select():
    repository = os.environ['GITHUB_REPOSITORY']
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    # The tag must identify a commit in main, even when the evidence producer was an integration branch.
    main = gh(f'repos/{repository}/commits/main')['sha']
    relation = gh(f'repos/{repository}/compare/{commit}...{main}')
    require(relation['status'] in ('ahead', 'identical'), 'Tagged commit is outside main')
    expected = {api: len(expected_for(Path.cwd(), api)[2]) for api in ('26','36')}
    # Bounded recent history; missing/expired evidence fails closed and requires a new full run.
    runs = gh(f'repos/{repository}/actions/workflows/android.yml/runs?status=success&per_page=100')['workflow_runs']
    for candidate in runs:
        if candidate['event'] not in ('push', 'workflow_dispatch'):
            continue
        producer = gh(f'repos/{repository}/commits/{candidate["head_sha"]}')
        if producer['commit']['tree']['sha'] != tree:
            continue
        try:
            run = gh(f'repos/{repository}/actions/runs/{candidate["id"]}')
            jobs = pages(f'repos/{repository}/actions/runs/{run["id"]}/jobs?filter=latest', 'jobs')
            artifacts = pages(f'repos/{repository}/actions/runs/{run["id"]}/artifacts', 'artifacts')
            build = next(job for job in jobs if job['name'] == 'build')
            summary = artifact(artifacts, 'complete-verification', run, build)
            delivery = artifact(artifacts, 'localica-debug', run, build)
            data = gh(f'repos/{repository}/actions/artifacts/{summary["id"]}/zip', binary=True)
            require('sha256:'+hashlib.sha256(data).hexdigest() == summary['digest'], 'Summary archive digest mismatch')
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                names = ['verification.json','union-api-26.json','union-api-36.json','class-durations.csv']
                require(sorted(archive.namelist()) == sorted(names), 'Unexpected summary archive members')
                require(sum(item.file_size for item in archive.infolist()) < 1024*1024, 'Oversized summary')
                receipt = json.loads(archive.read('verification.json'))
                unions = {api: json.loads(archive.read(f'union-api-{api}.json')) for api in ('26','36')}
            validate(run, jobs, receipt, unions, repository, tree, expected)
            provenance = dict(release_commit=commit, release_tree=tree, producer_run=run,
                              producer_receipt=receipt, unions=unions, artifact=delivery)
            release_output(run['id'], receipt['apk_sha256'], provenance)
            print(f'Verified run {run["id"]}, producer {run["head_sha"]}, exact tree {tree}')
            return
        except (ValueError, KeyError, StopIteration, zipfile.BadZipFile) as error:
            print(f'Rejected run {candidate["id"]}: {error}', file=sys.stderr)
    if os.environ.get('GITHUB_REF_NAME') == 'v0.34.16':
        select_v03416(repository, commit, tree)
        return
    raise ValueError('No retained fully green exact-tree verification. Run the complete Android workflow with compatibility enabled.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('select')
    commands.add_parser('record').add_argument('--apk-sha256', required=True)
    args = parser.parse_args()
    if args.command == 'record':
        record(args.apk_sha256)
    else:
        select()
