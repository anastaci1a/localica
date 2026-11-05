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

sys.path.insert(0, str(Path(__file__).resolve().parent/'test-evidence'))
from full_partition import expected_for

DEVICE_SHARDS = 7  # keep equal to LOCALICA_DEVICE_SHARDS and the device-tests matrix in android.yml
REQUIRED_JOBS = (
    'build', 'static-checks', 'device-tests-summary', 'compatibility (26)', 'compatibility (36)',
    'distribution-smoke (26)', 'distribution-smoke (36)',
) + tuple(f'device-tests ({api}, {shard})' for api in (26, 36) for shard in range(DEVICE_SHARDS))

# Locked-down local runner (issue #592): a separate workflow with its own shard count. Its runs are
# admitted only when owner-dispatched on main or integration/* and the self-hosted jobs ran on
# single-job JIT runners created by feedback/lead/tools/local-runner.sh.
LOCAL_WORKFLOW = '.github/workflows/android-local.yml'
LOCAL_DEVICE_SHARDS = 3  # keep equal to LOCALICA_DEVICE_SHARDS and the device-tests matrix in android-local.yml
LOCAL_OWNER = 'anastaci1a'
LOCAL_RUNNER_PREFIX = 'localica-jit-'
LOCAL_REQUIRED_JOBS = (
    'build', 'static-checks', 'device-tests-summary', 'compatibility (26)', 'compatibility (36)',
    'distribution-smoke (26)', 'distribution-smoke (36)',
) + tuple(f'device-tests ({api}, {shard})' for api in (26, 36) for shard in range(LOCAL_DEVICE_SHARDS))
LOCAL_HOSTED_JOBS = ('device-tests-summary',)
WORKFLOW_PATHS = ('.github/workflows/android.yml', LOCAL_WORKFLOW)


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def gh(path, binary=False):
    result = subprocess.check_output(['gh', 'api', path])
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
    require(run['path'] in WORKFLOW_PATHS, 'Wrong workflow')
    local = run['path'] == LOCAL_WORKFLOW
    required_jobs, shards = (LOCAL_REQUIRED_JOBS, LOCAL_DEVICE_SHARDS) if local else (REQUIRED_JOBS, DEVICE_SHARDS)
    if local:
        require(run['event'] == 'workflow_dispatch', 'Local runs are workflow_dispatch only')
        require(run['actor']['login'] == run['triggering_actor']['login'] == LOCAL_OWNER, 'Local run not dispatched by the owner')
        require(run['head_branch'] == 'main' or run['head_branch'].startswith('integration/'), 'Local run outside main or integration/*')
    require(run['event'] in ('push', 'workflow_dispatch'), 'Untrusted event')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Run is not fully green')
    require(receipt['schema_version'] == 1 and receipt['scope'] == 'complete_native_union', 'Not complete native evidence')
    require(receipt['sha'] == run['head_sha'] and receipt['tree'] == tree, 'Source/tree mismatch')
    require(receipt['run_id'] == str(run['id']) and receipt['attempt'] == str(run['run_attempt']), 'Stale producer receipt')
    require(re.fullmatch('[0-9a-f]{64}', receipt['apk_sha256']), 'Invalid APK identity')
    for name in required_jobs:
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1, 'Missing/duplicate required job: ' + name)
        job = matching[0]
        require(job['conclusion'] == 'success' and job['head_sha'] == run['head_sha']
                and job['run_id'] == run['id'] and job['run_attempt'] == run['run_attempt'],
                'Unsuccessful or stale required job: ' + name)
        if local and name not in LOCAL_HOSTED_JOBS:
            require(str(job.get('runner_name', '')).startswith(LOCAL_RUNNER_PREFIX) and 'self-hosted' in job.get('labels', []),
                    'Local job did not run on a single-job JIT runner: ' + name)
    for api in ('26', '36'):
        union = unions[api]
        require(union['api'] == api and union['shards'] == shards and union['xml_roots'] >= shards, 'Missing shard union')
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
    runs = []
    for workflow in ('android.yml', 'android-local.yml'):
        try:
            runs += gh(f'repos/{repository}/actions/workflows/{workflow}/runs?status=success&per_page=100')['workflow_runs']
        except subprocess.CalledProcessError:
            if workflow == 'android.yml':
                raise
            # The local workflow may not exist yet (404): no local producers.
    runs.sort(key=lambda item: item.get('created_at', ''), reverse=True)
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
                names = ['verification.json','union-api-26.json','union-api-36.json']
                require(sorted(archive.namelist()) == sorted(names), 'Unexpected summary archive members')
                require(sum(item.file_size for item in archive.infolist()) < 1024*1024, 'Oversized summary')
                receipt = json.loads(archive.read('verification.json'))
                unions = {api: json.loads(archive.read(f'union-api-{api}.json')) for api in ('26','36')}
            validate(run, jobs, receipt, unions, repository, tree, expected)
            provenance = dict(release_commit=commit, release_tree=tree, producer_run=run,
                              producer_receipt=receipt, unions=unions, artifact=delivery)
            Path('verified-provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'run-id={run["id"]}\napk-sha256={receipt["apk_sha256"]}\n')
            print(f'Verified run {run["id"]}, producer {run["head_sha"]}, exact tree {tree}')
            return
        except (ValueError, KeyError, StopIteration, zipfile.BadZipFile) as error:
            print(f'Rejected run {candidate["id"]}: {error}', file=sys.stderr)
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
