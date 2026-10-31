"""Offline-safe evidence verification. Never imports SSH or calls the network."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET
import zipfile

SCOPES = {'unit': ':core-domain:test :app:testDebugUnitTest', 'lint': ':app:lintDebug'}
CLOUD_SCOPES = {**SCOPES, 'delegation': (
    ':core-domain:test --tests app.daiyu.android.domain.delegation.* --tests app.daiyu.android.domain.tools.* '
    ':app:testDebugUnitTest --tests app.daiyu.android.delegation.* --tests app.daiyu.android.integration.tools.* '
    '--tests app.daiyu.android.backup.* --tests app.daiyu.android.browser.* --tests app.daiyu.android.shizuku.* '
    '--tests app.daiyu.android.terminal.* '
    '--tests app.daiyu.android.ui.screens.chat.conversation.BatchDelegationPresentationTest '
    '--tests app.daiyu.android.ui.screens.chat.conversation.ChildTaskPresentationTest '
    '--tests app.daiyu.android.ui.screens.chat.conversation.ToolRichDetailsTest '
    '--tests app.daiyu.android.ui.screens.chat.conversation.ToolCardPresentationTest '
    '--tests app.daiyu.android.ui.screens.chat.conversation.ToolBodyLayoutTest '
    '--tests app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusRowsTest '
    ':app:compileDebugAndroidTestKotlin')}
CLOUD_SCOPES['context'] = (
    ':core-domain:test --tests app.daiyu.android.domain.context.* '
    ':app:testDebugUnitTest --tests app.daiyu.android.context.* '
    '--tests app.daiyu.android.storage.ProviderRepositoryDeletionTest '
    '--tests app.daiyu.android.storage.ProviderRegistryTest')
UNIT_SCOPES = {'unit', 'delegation', 'context'}
CONTEXT_PACKAGES = (
    ('core-domain', 'app.daiyu.android.domain.context.'),
    ('app', 'app.daiyu.android.context.'),
)
CONTEXT_STORAGE_CLASSES = {
    'app.daiyu.android.storage.ProviderRepositoryDeletionTest',
    'app.daiyu.android.storage.ProviderRegistryTest',
}
CONTEXT_RESOLUTION_CLASS = 'app.daiyu.android.context.SavedCatalogSummaryResolutionTest'
DELEGATION_PACKAGES = (
    ('core-domain', 'app.daiyu.android.domain.delegation.'),
    ('core-domain', 'app.daiyu.android.domain.tools.'),
    ('app', 'app.daiyu.android.delegation.'),
    ('app', 'app.daiyu.android.integration.tools.'),
    ('app', 'app.daiyu.android.backup.'),
    ('app', 'app.daiyu.android.browser.'),
    ('app', 'app.daiyu.android.shizuku.'),
    ('app', 'app.daiyu.android.terminal.'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.BatchDelegationPresentationTest'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.ChildTaskPresentationTest'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.ToolRichDetailsTest'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.ToolCardPresentationTest'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.ToolBodyLayoutTest'),
    ('app', 'app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusRowsTest'),
)
UNIT_REPORTS = ('core-domain/build/test-results/test', 'app/build/test-results/testDebugUnitTest')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def configuration(repo):
    paths = ['build.gradle.kts', 'gradle.properties', 'settings.gradle.kts',
             'app/build.gradle.kts', 'core-domain/build.gradle.kts',
             'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
             'scripts/cooperation/checks_only.init.gradle', 'scripts/cooperation/cloud_checks.py',
             'scripts/cooperation/pc_checks.ps1', 'scripts/cooperation/evidence.py',
             'scripts/cooperation/windows_unit_skips.json', 'scripts/cooperation/verify_delegation_matrix.py',
             '.github/workflows/build.yml']
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
            or record.get('scope') != scope or record.get('task') != CLOUD_SCOPES[scope]
            or not record.get('check_id') or not isinstance(record.get('check_attempt'), int)
            or record['check_attempt'] < 1 or record.get('exit_code') is None):
        raise ValueError('check_identity_or_terminal_mismatch')
    if record['exit_code'] != 0 or record.get('result') != 'passed':
        raise ValueError('known_check_failure')


def verify_xml(archive, scope, platform=None):
    with zipfile.ZipFile(archive) as z:
        for n in z.namelist():
            p = PurePosixPath(n.replace('\\', '/'))
            if p.is_absolute() or '..' in p.parts or ':' in n:
                raise ValueError('unsafe_report_path')
        if scope in UNIT_SCOPES:
            named_reports = [(n.replace('\\', '/'), ET.fromstring(z.read(n))) for n in z.namelist()
                             if n.replace('\\', '/').split('/')[-1].startswith('TEST-') and n.endswith('.xml')]
            reports = [t for _, t in named_reports]
            if (not reports or any(t.tag != 'testsuite' for t in reports)
                    or any(int(t.get(k, '0')) for t in reports for k in ('failures', 'errors'))
                    or any(t.findall('.//failure') or t.findall('.//error') for t in reports)
                    or any(len(t.findall('testcase')) != int(t.get('tests', '0')) for t in reports)):
                raise ValueError('unit_report_failed_or_missing')
            for prefix in UNIT_REPORTS:
                module = [t for name, t in named_reports if name.startswith(prefix + '/')]
                if not module or sum(int(t.get('tests', '0')) for t in module) < 1:
                    raise ValueError('unit_module_report_missing')
            allowed = read_json(Path(__file__).with_name('windows_unit_skips.json'))
            skipped = []
            seen = set()
            for name, suite in named_reports:
                nodes = suite.findall('testcase')
                suite_skips = [c for c in nodes if c.find('skipped') is not None]
                if (any(int(suite.get(k, '0')) < 0 for k in ('tests', 'failures', 'errors', 'skipped'))
                        or int(suite.get('skipped', '0')) != len(suite_skips)
                        or len(suite.findall('.//skipped')) != len(suite_skips)):
                    raise ValueError('unit_report_failed_or_missing')
                for case in suite_skips:
                    identity = (case.get('classname', ''), case.get('name', ''))
                    node = case.find('skipped')
                    reason = (node.get('message', '') + '\n' + (node.text or '')).strip()
                    if (platform != 'Windows' or not name.startswith(UNIT_REPORTS[1] + '/')
                            or identity[1] not in allowed.get(identity[0], []) or not reason
                            or identity in seen):
                        raise ValueError('unit_report_failed_or_missing')
                    seen.add(identity)
                    skipped.append(dict(classname=identity[0], name=identity[1], reason=reason))
            if scope == 'context':
                covered = set()
                classes = set()
                for name, suite in named_reports:
                    module = name.split('/')[0]
                    for case in suite.findall('testcase'):
                        classname = case.get('classname', '')
                        group = next((p for p in CONTEXT_PACKAGES
                                      if p[0] == module and classname.startswith(p[1])), None)
                        if group is None and not (module == 'app' and classname in CONTEXT_STORAGE_CLASSES):
                            raise ValueError('context_unexpected_test')
                        if case.find('skipped') is None:
                            classes.add(classname)
                            if group is not None:
                                covered.add(group)
                if (covered != set(CONTEXT_PACKAGES)
                        or not (CONTEXT_STORAGE_CLASSES | {CONTEXT_RESOLUTION_CLASS}) <= classes):
                    raise ValueError('context_package_coverage_missing')
            if scope == 'delegation':
                covered = set()
                for name, suite in named_reports:
                    module = name.split('/')[0]
                    for case in suite.findall('testcase'):
                        group = next((p for p in DELEGATION_PACKAGES
                                      if p[0] == module and case.get('classname', '').startswith(p[1])), None)
                        if group is None:
                            raise ValueError('delegation_unexpected_test')
                        if case.find('skipped') is None:
                            covered.add(group)
                if covered != set(DELEGATION_PACKAGES):
                    raise ValueError('delegation_package_coverage_missing')
                summary = json.loads(z.read('androidtest-compile.json'))
                if (summary.get('task') != ':app:compileDebugAndroidTestKotlin'
                        or summary.get('outcome') not in ('EXECUTED', 'UP-TO-DATE', 'FROM-CACHE')
                        or summary.get('instrumentation_run') is not False):
                    raise ValueError('androidtest_compile_not_verified')
            count = sum(int(t.get('tests', '0')) for t in reports)
            return {'tests_passed': count - len(skipped), 'tests_skipped': len(skipped),
                    'skipped_tests': skipped}
        if scope != 'lint':
            raise ValueError('unknown_check_scope')
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


def verify_unit_matrix(windows_archive, linux_archive, scope='unit'):
    windows = verify_xml(windows_archive, scope, platform='Windows')
    verify_xml(linux_archive, scope, platform='Linux')
    passed = set()
    duplicates = set()
    with zipfile.ZipFile(linux_archive) as z:
        for name in z.namelist():
            if not name.startswith(UNIT_REPORTS[1] + '/') or not PurePosixPath(name).name.startswith('TEST-') or not name.endswith('.xml'):
                continue
            for case in ET.fromstring(z.read(name)).findall('testcase'):
                identity = (case.get('classname', ''), case.get('name', ''))
                if identity in passed:
                    duplicates.add(identity)
                passed.add(identity)
    for case in windows['skipped_tests']:
        identity = (case['classname'], case['name'])
        if identity not in passed or identity in duplicates:
            raise ValueError('windows_skip_without_linux_execution')
    return {'windows_skips_verified_on_linux': len(windows['skipped_tests'])}


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
            matrix_directories = {}
            for platform in ('ubuntu-26.04', 'windows-latest'):
                sibling = directory.parents[1] / f'github-{proof["run_id"]}-{proof["attempt"]}-{platform}' / scope
                sibling_proof = read_json(sibling / 'proof.json')
                if (sibling_proof.get('origin') != 'github' or sibling_proof.get('run_id') != proof['run_id']
                        or sibling_proof.get('attempt') != proof['attempt']):
                    raise ValueError('cloud_matrix_source_mismatch')
                member = verify_bundle(sibling, sha, config, scope, receiving=True)
                if member['environment']['os'] != {'ubuntu-26.04': 'Linux', 'windows-latest': 'Windows'}[platform]:
                    raise ValueError('cloud_matrix_platform_mismatch')
                matrix_directories[platform] = sibling
            if scope in UNIT_SCOPES:
                verify_unit_matrix(matrix_directories['windows-latest'] / 'reports.zip',
                                   matrix_directories['ubuntu-26.04'] / 'reports.zip', scope)
        artifact = read_json(base / (str(proof['artifact_id']) + '.json'))
        archive = base / (str(proof['artifact_id']) + '.zip')
        run = read_json(base / 'run.json')
        jobs = read_json(base / 'jobs.json')
        if ('sha256:' + digest(archive) != artifact['digest']
                or digest(archive) != proof['artifact_sha256']
                or run['id'] != proof['run_id'] or run['run_attempt'] != proof['attempt']
                or run['head_sha'] != sha or run['conclusion'] != 'success'
                or run['workflow_id'] != 397560064 or run['head_branch'] != 'main'
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
    return dict(record, **verify_xml(directory / 'reports.zip', scope,
                platform=record.get('environment', {}).get('os') if proof['origin'] == 'github' else None),
                origin=proof['origin'])


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
