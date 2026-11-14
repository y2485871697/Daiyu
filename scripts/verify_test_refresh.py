"""Bind a newly compiled test APK to an unchanged, previously built production APK."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from verify_release_package import verify_manifests
from verify_signing import verify_certificate
from verify_smoke_source import verify as verify_source

VALIDATION_FILES = {
    'docs/重构清单.md', '更新说明.md',
    '.github/workflows/test-refresh-regression.yml',
    '.github/workflows/chat-workflow-checks.yml',
    'scripts/verify_test_refresh.py', 'scripts/cooperation/test_refresh_only.init.gradle',
}
VALIDATION_DIRS = ('tests/', 'app/src/test/', 'app/src/androidTest/')


def validate_changes(changed):
    for name in changed:
        if (not isinstance(name, str) or '\\' in name or
                any(part in ('', '.', '..') for part in name.split('/')) or
                not (name in VALIDATION_FILES or name.startswith(VALIDATION_DIRS))):
            raise ValueError('Application or build inputs changed: ' + str(name))


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(command('gh', 'api', 'repos/y2485871697/Daiyu/' + path))


def verify_origin(env, record, directory, record_path, local_apks=None):
    sha, run_id = env['SOURCE_SHA'], env['SOURCE_RUN_ID']
    if env.get('GITHUB_REPOSITORY') != 'y2485871697/Daiyu':
        raise ValueError('Wrong repository')
    if command('git', 'branch', '--show-current') != 'main' and env.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('main is required')
    if command('git', 'rev-parse', 'HEAD') != env['GITHUB_SHA']:
        raise ValueError('Check revision mismatch')
    if not re.fullmatch('[0-9a-f]{40}', sha) or not re.fullmatch('[1-9][0-9]*', run_id):
        raise ValueError('Full source SHA and run ID required')
    command('git', 'merge-base', '--is-ancestor', sha, env['GITHUB_SHA'])
    changed = command('git', 'diff', '--no-renames', '--name-only', '-z', sha, env['GITHUB_SHA']).split('\0')
    changed = [name for name in changed if name]
    validate_changes(changed)
    for tree in (sha, env['GITHUB_SHA']):
        for entry in command('git', 'ls-tree', '-r', '-z', tree, '--', *changed).split('\0') if changed else []:
            if entry and entry.split(' ', 1)[0] not in ('100644', '100755'):
                raise ValueError('Non-regular validation file')
    source_run = api('actions/runs/' + run_id)
    if source_run.get('conclusion') != 'success':
        raise ValueError('Source run must be successful')
    jobs = api('actions/runs/' + run_id + '/attempts/1/jobs?per_page=100')['jobs']
    artifacts = api('actions/runs/' + run_id + '/artifacts?per_page=100')['artifacts']
    matches = [a for a in artifacts if a['name'] == '代鱼增量Release包' and not a['expired']]
    if len(matches) != 1:
        raise ValueError('Source artifact is not unique or expired')
    origin = matches[0]['workflow_run']
    if (str(origin['id']) != run_id or origin['head_sha'] != sha or
            origin['repository_id'] != 1434933237):
        raise ValueError('Wrong source artifact owner')
    # 原 APK 对按源构建记录校验；当前验证版本和变更已在上方单独核验。
    
    source_env = dict(env, GITHUB_SHA=sha, SOURCE_VARIANT='compile')
    verify_source(source_env, record, source_run, jobs, [])
    source_binding = verify_archive(matches[0], directory, record_path, record, local_apks=local_apks)
    return changed, source_binding


def verify_hashes(directory, sums):
    for name, expected in sums.items():
        if name not in ('tests/platform-test.apk',) and not re.fullmatch('代鱼-0\\.1\\.0-[0-9a-f]{8}\\.apk', name):
            raise ValueError('Unexpected APK record path')
        path = directory / name
        if not path.is_file() or path.is_symlink() or digest(path) != expected:
            raise ValueError('APK digest mismatch: ' + name)


def validate_graph(graph):
    if not isinstance(graph, list) or not all(isinstance(task, str) for task in graph):
        raise ValueError('Invalid task graph')
    if ':app:assembleReleaseAndroidTest' not in graph:
        raise ValueError('Test packaging did not execute')
    for task in graph:
        name = task.rsplit(':', 1)[-1]
        if (re.match('(?i)^(install|uninstall|connected|managedDevice)', name) or
                ((re.match('(?i)^(assemble|sign)', name) or
                  re.fullmatch('(?i)(package|bundle).*(apk|debug|release|androidtest)', name)) and
                 'androidtest' not in name.lower())):
            raise ValueError('Application packaging in task graph: ' + task)


def verify_archive(artifact, directory, record_path, record, archive=None, local_apks=None):
    if archive is None:
        archive = subprocess.check_output(['gh', 'api',
            f"repos/y2485871697/Daiyu/actions/artifacts/{artifact['id']}/zip"])
    expected = artifact.get('digest', '')
    if expected != 'sha256:' + hashlib.sha256(archive).hexdigest():
        raise ValueError('Source artifact archive digest mismatch')
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        names = package.namelist()
        if len(names) != len(set(names)) or any(
                name.startswith('/') or '\\' in name or '..' in name.split('/') for name in names):
            raise ValueError('Invalid artifact archive entries')
        if package.read('构建记录.json') != record_path.read_bytes():
            raise ValueError('Original source record was changed')
        if json.loads(record_path.read_text()) != record:
            raise ValueError('Embedded original record was changed')
        names_to_check = set(record['sha256']) if local_apks is None else set(local_apks)
        if not names_to_check or not names_to_check <= set(record['sha256']):
            raise ValueError('Invalid local source APK selection')
        for name in names_to_check:
            path = directory / name
            if not path.is_file() or path.is_symlink() or hashlib.sha256(package.read(name)).hexdigest() != digest(path):
                raise ValueError('Local source bytes differ from artifact')
    return dict(artifact_id=artifact['id'], artifact_digest=expected, record_sha256=digest(record_path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('source', 'bind', 'check'))
    parser.add_argument('--apkanalyzer')
    parser.add_argument('--apksigner')
    args = parser.parse_args()
    env = os.environ
    source = Path('source-delivery')
    delivery = Path('delivery')
    evidence = Path('test-refresh')
    evidence.mkdir(exist_ok=True)
    if args.action == 'source':
        record = json.loads((source / '构建记录.json').read_text())
        changed, origin = verify_origin(env, record, source, source / '构建记录.json')
        expected = {f"代鱼-0.1.0-{env['SOURCE_SHA'][:8]}.apk", 'tests/platform-test.apk'}
        if set(record['sha256']) != expected:
            raise ValueError('Original APK pair is incomplete')
        verify_hashes(source, record['sha256'])
        (evidence / 'source.json').write_text(json.dumps(dict(app_run=env['SOURCE_RUN_ID'], app_attempt=1,
            app_sha=env['SOURCE_SHA'], check_sha=env['GITHUB_SHA'], changed_validation_files=changed,
            original_record=record, artifact=origin), ensure_ascii=False) + '\n')
        return
    if args.action == 'bind':
        source_evidence = json.loads((evidence / 'source.json').read_text())
        if source_evidence['check_sha'] != env['GITHUB_SHA']:
            raise ValueError('Preparation revision mismatch')
        record = source_evidence['original_record']
        changed, origin = verify_origin(env, record, source, source / '构建记录.json')
        if source_evidence != dict(app_run=env['SOURCE_RUN_ID'], app_attempt=1, app_sha=env['SOURCE_SHA'],
                check_sha=env['GITHUB_SHA'], changed_validation_files=changed, original_record=record, artifact=origin):
            raise ValueError('Source preparation binding changed')
        verify_hashes(source, record['sha256'])
        graph = json.loads((evidence / 'task-graph.json').read_text())
        validate_graph(graph)
        apks = list(Path('app/build/outputs/apk').rglob('*.apk'))
        test = Path('app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')
        if apks != [test] or not test.is_file():
            raise ValueError('Application APK was produced or test APK missing')
        app = source / f"代鱼-0.1.0-{env['SOURCE_SHA'][:8]}.apk"
        if not args.apkanalyzer or not args.apksigner:
            parser.error('bind requires --apkanalyzer and --apksigner')
        manifests = []
        signatures = []
        for apk in (app, test):
            manifests.append(command(args.apkanalyzer, 'manifest', 'print', str(apk)))
            signature = command(args.apksigner, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(apk))
            verify_certificate(signature)
            signatures.append(signature)
        verify_manifests(*manifests)
        (delivery / 'tests').mkdir(parents=True, exist_ok=True)
        shutil.copy2(app, delivery / app.name)
        shutil.copy2(test, delivery / 'tests/platform-test.apk')
        shutil.copy2(source / '构建记录.json', delivery / '应用原始构建记录.json')
        result = dict(source=source_evidence, test_sha=env['GITHUB_SHA'], test_run=env['GITHUB_RUN_ID'],
            test_attempt=env['GITHUB_RUN_ATTEMPT'], application_rebuilt=False,
            sha256={app.name: digest(app), 'tests/platform-test.apk': digest(test)}, task_graph=graph)
        (delivery / 'test-refresh.json').write_text(json.dumps(result, ensure_ascii=False) + '\n')
        (delivery / '签名证据.txt').write_text('\n'.join(signatures))
        return
    result = json.loads((delivery / 'test-refresh.json').read_text())
    if (result['test_sha'] != env['GITHUB_SHA'] or result['test_run'] != env['GITHUB_RUN_ID'] or
            result['test_attempt'] != env['GITHUB_RUN_ATTEMPT'] or result['application_rebuilt'] is not False):
        raise ValueError('New test artifact identity mismatch')
    record = json.loads((delivery / '应用原始构建记录.json').read_text())
    app_name = f"代鱼-0.1.0-{env['SOURCE_SHA'][:8]}.apk"
    changed, origin = verify_origin(env, record, delivery, delivery / '应用原始构建记录.json', local_apks={app_name})
    if result['source'] != dict(app_run=env['SOURCE_RUN_ID'], app_attempt=1, app_sha=env['SOURCE_SHA'],
            check_sha=env['GITHUB_SHA'], changed_validation_files=changed, original_record=record, artifact=origin):
        raise ValueError('Embedded application source mismatch')
    validate_graph(result['task_graph'])
    if result['sha256'][app_name] != record['sha256'][app_name]:
        raise ValueError('Application bytes changed')
    if set(result['sha256']) != {app_name, 'tests/platform-test.apk'}:
        raise ValueError('Execution APK pair is incomplete')
    verify_hashes(delivery, result['sha256'])
    Path('smoke').mkdir(exist_ok=True)
    shutil.copy2(delivery / 'test-refresh.json', 'smoke/source.json')


if __name__ == '__main__':
    main()
