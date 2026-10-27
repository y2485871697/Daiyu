"""Plan and verify targeted production Releases without claiming full acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

CORE = tuple('app.daiyu.android.' + name for name in (
    'ActivityTest', 'RunControlActivityTest', 'ConversationPaneStartupActivityTest',
    'SettingsProductActivityTest'))
UI = 'app/src/main/kotlin/app/daiyu/android/ui/'
SAFE_UI = ('components/', 'layout/', 'icons/', 'haptics/', 'screens/')
HIGH_RISK_UI = ('screens/shizuku/', 'screens/terminal/', 'screens/browser/')


def discover(root):
    classes = {}
    for path in sorted((root / 'app/src/androidTest').rglob('*')):
        if not path.is_file() or path.suffix not in ('.kt', '.java'):
            continue
        text = path.read_text(encoding='utf-8')
        if path.suffix == '.kt':
            methods = re.findall(r'^\s*fun (test\w+)\(', text, re.M)
            packages = re.findall(r'^package\s+([\w.]+)\s*$', text, re.M)
            names = re.findall(r'^class\s+(\w+Test)(?:\s+private\s+constructor\s*\([^)]*\))?\s*:', text, re.M)
        else:
            methods = re.findall(r'^\s*public\s+void\s+(test\w+)\s*\(\s*\)', text, re.M)
            packages = re.findall(r'^package\s+([\w.]+)\s*;\s*$', text, re.M)
            names = re.findall(r'^public\s+(?:final\s+)?class\s+(\w+Test)\s+extends\b', text, re.M)
        if not methods:
            continue
        if len(packages) != 1 or len(names) != 1 or len(set(methods)) != len(methods):
            raise ValueError('ambiguous_daily_test_inventory')
        name = packages[0] + '.' + names[0]
        if name in classes:
            raise ValueError('duplicate_daily_test_class')
        classes[name] = dict(path=path.relative_to(root).as_posix(), methods=sorted(methods))
    return classes


def presentation_only(path):
    parts = PurePosixPath(path).parts
    if not parts or '\\' in path or any(p in ('', '.', '..') for p in path.split('/')):
        return False
    if path.startswith(('docs/', 'tests/', 'app/src/test/', 'app/src/androidTest/')) or path == '更新说明.md':
        return True
    if path.startswith(UI):
        relative = path[len(UI):]
        return (relative.startswith(SAFE_UI) and not relative.startswith(HIGH_RISK_UI)
                and not re.search(r'(Controller|ViewModel|Coordinator|Runtime|Migration|Permission|Store|Repository|Gateway|State)\.(kt|java)$', path))
    if path.startswith('app/src/main/res/'):
        folder, _, name = path[len('app/src/main/res/'):].partition('/')
        return (folder.startswith(('drawable', 'mipmap')) or
                folder.startswith('values') and name in ('strings.xml', 'colors.xml', 'dimens.xml'))
    return False


def make_plan(root, env):
    sha, base = env['GITHUB_SHA'], env.get('DAILY_BASE_SHA', '')
    if any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in (sha, base)) or sha == base:
        raise ValueError('daily_requires_distinct_full_baseline_sha')
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip() != sha:
        raise ValueError('daily_source_sha_mismatch')
    subprocess.run(['git', 'merge-base', '--is-ancestor', base, sha], cwd=root, check=True)
    paths = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', base, sha], cwd=root).decode().split('\0')[:-1]
    if not paths or any(not presentation_only(path) or (not (root / path).is_file() or (root / path).is_symlink()) for path in paths):
        raise ValueError('daily_requires_full_regression_for_core_unknown_or_deleted_inputs')
    classes = discover(root)
    requested = env.get('SMOKE_TEST_CLASSES', '')
    extra = set(requested.split(',')) if requested else set()
    if any(not re.fullmatch(r'app\.daiyu\.android\.(?:[A-Za-z_][A-Za-z_0-9]*\.)*[A-Za-z_][A-Za-z_0-9]*', name) for name in extra):
        raise ValueError('invalid_daily_affected_classes')
    modified = {name for name, row in classes.items() if row['path'] in paths}
    test_paths = {row['path'] for row in classes.values()}
    if any(path.startswith('app/src/androidTest/') and path not in test_paths for path in paths):
        raise ValueError('daily_requires_full_regression_for_test_fixtures')
    if any(path.startswith('app/src/main/') for path in paths) and not (extra or modified):
        raise ValueError('daily_requires_affected_regression_classes')
    selected = set(CORE) | extra | modified
    if not selected <= classes.keys():
        raise ValueError('unknown_or_missing_daily_test_class')
    return dict(schema_version=1, mode='daily', source_sha=sha, baseline_sha=base,
                changed_files=sorted(paths), requested_classes=sorted(extra),
                classes={name: classes[name]['methods'] for name in sorted(selected)},
                expected_tests=sum(len(classes[name]['methods']) for name in selected),
                apis=['26', '37.2'], shard_count=1, full_regression_passed=False)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def bind(directory, plan, env):
    record_path = directory / '构建记录.json'
    record = json.loads(record_path.read_text())
    if (record['source_sha'] != plan['source_sha'] or record['run_id'] != env['GITHUB_RUN_ID']
            or record['attempt'] != env['GITHUB_RUN_ATTEMPT'] or record['variant'] != 'release'
            or record['production_delivery'] is not True or record['debuggable'] is not False
            or record['profileable'] is not False):
        raise ValueError('daily_requires_verified_production_release')
    record.update(validation_profile='daily', full_regression_passed=False,
                  validation_status='pending', validation_plan_sha256=digest(directory / 'daily-plan.json'))
    record_path.write_text(json.dumps(record, ensure_ascii=False) + '\n')


def verify(directory, plan, env):
    if json.loads((directory / 'daily-plan.json').read_text()) != plan:
        raise ValueError('daily_plan_identity_mismatch')
    record = json.loads((directory / '构建记录.json').read_text())
    if (record.get('source_sha') != env['GITHUB_SHA'] or record.get('run_id') != env['GITHUB_RUN_ID']
            or record.get('attempt') != env['GITHUB_RUN_ATTEMPT']
            or record.get('validation_profile') != 'daily' or record.get('full_regression_passed') is not False
            or record.get('validation_plan_sha256') != digest(directory / 'daily-plan.json')
            or record.get('variant') != 'release' or record.get('production_delivery') is not True
            or record.get('debuggable') is not False or record.get('profileable') is not False):
        raise ValueError('daily_package_identity_mismatch')
    expected = {f"代鱼-0.1.0-{env['GITHUB_SHA'][:8]}.apk", 'tests/platform-test.apk'}
    if set(record['sha256']) != expected:
        raise ValueError('daily_apk_pair_mismatch')
    for name, value in record['sha256'].items():
        if digest(directory / name) != value:
            raise ValueError('daily_apk_digest_mismatch')


def verify_methods(raw, plan):
    sys.path.insert(0, str(Path(__file__).resolve().parent / 'acceptance'))
    from instrumentation_result import instrumentation
    instrumentation(raw, plan['expected_tests'])
    completed, pending = set(), {}
    for line in raw.decode().replace('\r\n', '\n').splitlines():
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test)=(.+)', line)
        if match:
            pending[match[1]] = match[2]
        if line.startswith('INSTRUMENTATION_STATUS_CODE:'):
            if line == 'INSTRUMENTATION_STATUS_CODE: 0':
                case = (pending.get('class'), pending.get('test'))
                if case in completed:
                    raise ValueError('duplicate_daily_method_result')
                completed.add(case)
            pending = {}
    expected = {(name, method) for name, methods in plan['classes'].items() for method in methods}
    if completed != expected:
        raise ValueError('daily_method_coverage_mismatch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan', 'bind', 'verify', 'result'))
    parser.add_argument('--directory', type=Path, default=Path('delivery'))
    parser.add_argument('--output', type=Path, default=Path('daily-plan.json'))
    args = parser.parse_args()
    env = os.environ
    plan = make_plan(Path('.'), env)
    if args.action == 'plan':
        args.output.write_text(json.dumps(plan, ensure_ascii=False, sort_keys=True) + '\n')
    elif args.action == 'bind':
        if json.loads((args.directory / 'daily-plan.json').read_text()) != plan:
            raise ValueError('daily_plan_identity_mismatch')
        bind(args.directory, plan, env)
    else:
        verify(args.directory, plan, env)
        if args.action == 'result':
            if env.get('DAILY_API') not in plan['apis']:
                raise ValueError('invalid_daily_api')
            raw = Path('smoke/platform-tests.txt').read_bytes()
            verify_methods(raw, plan)
            result = dict(source_sha=env['GITHUB_SHA'], run_id=env['GITHUB_RUN_ID'],
                          attempt=env['GITHUB_RUN_ATTEMPT'], api=env['DAILY_API'],
                          validation_profile='daily', full_regression_passed=False,
                          expected_tests=plan['expected_tests'], status='passed',
                          plan_sha256=digest(args.directory / 'daily-plan.json'),
                          instrumentation_sha256=hashlib.sha256(raw).hexdigest())
            Path('smoke/daily-result.json').write_text(json.dumps(result, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
