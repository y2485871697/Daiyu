"""Offline-safe evidence verification. Never imports SSH or calls the network."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET
import zipfile

SCOPES = {'unit': ':core-domain:test :app:testDebugUnitTest', 'lint': ':app:lintDebug'}
UNIT_REPORTS = ('core-domain/build/test-results/test', 'app/build/test-results/testDebugUnitTest')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def configuration(repo):
    paths = ['build.gradle.kts', 'gradle.properties', 'settings.gradle.kts',
             'app/build.gradle.kts', 'core-domain/build.gradle.kts',
             'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
             'scripts/cooperation/checks_only.init.gradle', 'scripts/cooperation/cloud_checks.py',
             'scripts/cooperation/pc_checks.ps1', 'scripts/cooperation/evidence.py', '.github/workflows/build.yml']
    h = hashlib.sha256()
    for name in paths:
        h.update(name.encode() + b'\0' + Path(repo, name).read_bytes() + b'\0')
    return h.hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    tmp.replace(path)


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def validate_record(record, sha, config, scope):
    if (record.get('source_sha') != sha or record.get('config_id') != config
            or record.get('scope') != scope or record.get('task') != SCOPES[scope]
            or not record.get('check_id') or not isinstance(record.get('check_attempt'), int)
            or record['check_attempt'] < 1 or record.get('exit_code') is None):
        raise ValueError('check_identity_or_terminal_mismatch')
    if record['exit_code'] != 0 or record.get('result') != 'passed':
        raise ValueError('known_check_failure')


def verify_xml(archive, scope):
    with zipfile.ZipFile(archive) as z:
        for n in z.namelist():
            p = PurePosixPath(n.replace('\\', '/'))
            if p.is_absolute() or '..' in p.parts or ':' in n:
                raise ValueError('unsafe_report_path')
        if scope == 'unit':
            named_reports = [(n.replace('\\', '/'), ET.fromstring(z.read(n))) for n in z.namelist()
                             if n.replace('\\', '/').split('/')[-1].startswith('TEST-') and n.endswith('.xml')]
            reports = [t for _, t in named_reports]
            if (not reports or any(t.tag != 'testsuite' for t in reports)
                    or any(int(t.get(k, '0')) for t in reports for k in ('failures', 'errors', 'skipped'))
                    or any(t.findall('.//failure') or t.findall('.//error') or t.findall('.//skipped') for t in reports)
                    or any(len(t.findall('testcase')) != int(t.get('tests', '0')) for t in reports)):
                raise ValueError('unit_report_failed_or_missing')
            for prefix in UNIT_REPORTS:
                module = [t for name, t in named_reports if name.startswith(prefix + '/')]
                if not module or sum(int(t.get('tests', '0')) for t in module) < 1:
                    raise ValueError('unit_module_report_missing')
            count = sum(int(t.get('tests', '0')) for t in reports)
            return {'tests_passed': count}
        names = [n for n in z.namelist() if n.endswith('lint-results-debug.xml')]
        if len(names) != 1:
            raise ValueError('lint_report_missing')
        report = ET.fromstring(z.read(names[0]))
        if report.tag != 'issues':
            raise ValueError('invalid_lint_report_structure')
        issues = report.findall('issue')
        if any(i.get('severity') in ('Error', 'Fatal') for i in issues):
            raise ValueError('lint_report_failed')
        return {'lint_errors': 0, 'lint_warnings': sum(i.get('severity') == 'Warning' for i in issues)}


def verify_bundle(directory, sha, config, scope, receiving=False):
    directory = Path(directory)
    proof = read_json(directory / 'proof.json')
    if proof.get('origin') not in {'github', 'windows-pc'}:
        raise ValueError('unsupported_evidence_origin')
    for filename, expected in proof['files'].items():
        if filename not in ('record.json', 'reports.zip', 'check.log') or digest(directory / filename) != expected:
            raise ValueError('evidence_hash_mismatch')
    if set(proof['files']) != {'record.json', 'reports.zip', 'check.log'}:
        raise ValueError('incomplete_evidence')
    if proof['origin'] == 'github':
        base = directory.parents[2] / 'cloud' / str(proof['run_id'])
        if not receiving:
            complete = read_json(base / 'complete.json')
            if complete['source_sha'] != sha or complete['attempt'] != proof['attempt'] or scope not in complete['scopes'] or set(complete['platforms']) != {'ubuntu-26.04','windows-latest'}:
                raise ValueError('cloud_matrix_not_complete')
            for platform in ('ubuntu-26.04', 'windows-latest'):
                sibling = directory.parents[1] / f'github-{proof["run_id"]}-{proof["attempt"]}-{platform}' / scope
                sibling_proof = read_json(sibling / 'proof.json')
                if (sibling_proof.get('origin') != 'github' or sibling_proof.get('run_id') != proof['run_id']
                        or sibling_proof.get('attempt') != proof['attempt']):
                    raise ValueError('cloud_matrix_source_mismatch')
                member = verify_bundle(sibling, sha, config, scope, receiving=True)
                if member['environment']['os'] != {'ubuntu-26.04': 'Linux', 'windows-latest': 'Windows'}[platform]:
                    raise ValueError('cloud_matrix_platform_mismatch')
        artifact = read_json(base / (str(proof['artifact_id']) + '.json'))
        archive = base / (str(proof['artifact_id']) + '.zip')
        run = read_json(base / 'run.json')
        jobs = read_json(base / 'jobs.json')
        if ('sha256:' + digest(archive) != artifact['digest']
                or digest(archive) != proof['artifact_sha256']
                or run['id'] != proof['run_id'] or run['run_attempt'] != proof['attempt']
                or run['head_sha'] != sha or run['conclusion'] != 'success'
                or run['workflow_id'] != 383377276 or run['head_branch'] != 'main'
                or run['repository']['full_name'] != 'y2485871697/Daiyu'
                or not any(j['id'] == proof['job_id'] and j['conclusion'] == 'success'
                           and j['run_attempt'] == proof['attempt'] for j in jobs)):
            raise ValueError('cloud_source_metadata_mismatch')
        with zipfile.ZipFile(archive) as z:
            for filename in proof['files']:
                names = [n for n in z.namelist() if n.replace('\\', '/').endswith(scope+'/'+filename)]
                if len(names) != 1 or hashlib.sha256(z.read(names[0])).hexdigest() != proof['files'][filename]:
                    raise ValueError('cloud_bundle_not_in_verified_artifact')
    record = read_json(directory / 'record.json')
    if proof['origin'] == 'github':
        platform = {'Linux': 'ubuntu-26.04', 'Windows': 'windows-latest'}.get(record.get('environment', {}).get('os'))
        if (not platform or artifact['name'] != '代鱼检查报告-' + platform
                or artifact['id'] != proof['artifact_id'] or run['status'] != 'completed'
                or record['check_id'] != f'github-{proof["run_id"]}-{record["environment"]["os"]}'
                or record['check_attempt'] != proof['attempt']
                or record.get('apk_built') is not False or record.get('package_scan') != 'passed'
                or not any(j['id'] == proof['job_id'] and j['name'] == '只检查 ' + platform
                           and j['status'] == 'completed' for j in jobs)):
            raise ValueError('cloud_platform_identity_mismatch')
    if proof['origin'] == 'windows-pc':
        status = read_json(directory / 'status.json')
        if status.get('source_sha') != sha or status.get('config_id') != config or status.get('check_attempt') != record['check_attempt']:
            raise ValueError('pc_terminal_identity_mismatch')
        if status.get('result') == 'failed' or status.get('exit_code') not in (None,0):
            raise ValueError('known_check_failure')
        if status.get('result') != 'passed' or status.get('exit_code') != 0 or status.get('package_scan') != 'passed':
            raise ValueError('pc_terminal_not_verified')

    validate_record(record, sha, config, scope)
    if record.get('incremental_base_sha') != sha or not record.get('environment') or not record.get('command'):
        raise ValueError('check_configuration_missing')
    if digest(directory / 'check.log') != record['log_sha256']:
        raise ValueError('log_hash_mismatch')
    if digest(directory / 'reports.zip') != record['report_sha256']:
        raise ValueError('report_hash_mismatch')
    log = (directory / 'check.log').read_bytes()
    text = log.decode('utf-16') if log.startswith((b'\xff\xfe', b'\xfe\xff')) else log.decode('utf-8', errors='replace')
    if 'Configuration.setVisible' in text or 'Deprecated Gradle features' in text:
        raise ValueError('deprecation_remaining')
    return dict(record, **verify_xml(directory / 'reports.zip', scope), origin=proof['origin'])


def coverage(state, sha, config):
    """Rehash evidence on every process restart; never trust a passed flag."""
    if (Path(state) / 'known-failure.json').exists():
        raise ValueError('known_check_failure')
    result = {}
    for scope in SCOPES:
        matches = []
        for p in Path(state).glob('evidence/*/' + scope):
            try:
                matches.append(verify_bundle(p, sha, config, scope))
            except ValueError as e:
                if str(e) == 'known_check_failure':
                    raise
                continue
            except (OSError, KeyError, json.JSONDecodeError, zipfile.BadZipFile):
                continue
        if matches:
            result[scope] = matches[0]
    return result
