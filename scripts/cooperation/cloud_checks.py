"""GitHub revision guard and checks-only runner with per-item evidence."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from evidence import SCOPES, UNIT_REPORTS, atomic_json, configuration, digest, verify_xml


def verify_revision():
    """Fail closed on dispatch drift; an empty manual target still binds checkout."""
    expected = os.environ.get('EXPECTED_SHA', '')
    if expected and not re.fullmatch(r'[0-9a-fA-F]{40}', expected):
        raise ValueError('invalid_expected_sha')
    github_sha = os.environ.get('GITHUB_SHA', '')
    if not re.fullmatch(r'[0-9a-fA-F]{40}', github_sha):
        raise ValueError('invalid_github_sha')
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if not re.fullmatch(r'[0-9a-fA-F]{40}', sha):
        raise ValueError('invalid_checkout_sha')
    if sha != github_sha or (expected and expected != sha):
        raise ValueError('checkout_sha_mismatch')
    return sha


def run(scope):
    sha = verify_revision()
    root = Path.cwd()
    output = root / 'cooperation-reports'
    output.mkdir(exist_ok=True)
    config = configuration(root)
    tracked = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    source_hashes = {n: digest(root / n) for n in tracked if n}

    def packages_found():
        return [str(p.relative_to(root)) for p in root.rglob('*')
                if p.is_file() and p.suffix.lower() in ('.apk', '.aab', '.apks')]

    for item in (list(SCOPES) if scope == 'all' else [scope]):
        folder = output / item
        folder.mkdir(exist_ok=True)
        command = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                   '--no-daemon', '--parallel', '--build-cache', '--warning-mode=fail',
                   '--console=plain', '-I', 'scripts/cooperation/checks_only.init.gradle', *SCOPES[item].split()]
        with (folder / 'check.log').open('wb') as log:
            code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
        report_roots = [root / p for p in UNIT_REPORTS] if item == 'unit' else [root / 'app/build/reports']
        with zipfile.ZipFile(folder / 'reports.zip', 'w', zipfile.ZIP_DEFLATED) as z:
            for reports in report_roots:
                for p in reports.rglob('*.xml'):
                    base = root if item == 'unit' else reports
                    z.write(p, p.relative_to(base).as_posix())
        report_summary = {}
        if code == 0:
            try:
                report_summary = verify_xml(folder / 'reports.zip', item, platform=os.environ['RUNNER_OS'])
            except ValueError as error:
                print(item, 'report_validation_failed', str(error), flush=True)
                code = 1
        packages = packages_found()
        source_changed = [n for n, expected in source_hashes.items() if not (root / n).is_file() or digest(root / n) != expected]
        if source_changed: code = code or 1
        if packages: code = code or 1
        record = dict(check_id='github-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['RUNNER_OS'],
                      check_attempt=int(os.environ['GITHUB_RUN_ATTEMPT']), source_sha=sha,
                      config_id=config, scope=item, task=SCOPES[item], command=command,
                      incremental_base_sha=sha, diff='none; exact checkout, Gradle cache enabled',
                      environment=dict(os=os.environ['RUNNER_OS'], jdk='27', gradle='9.8.1', agp='9.5.0-alpha09'),
                      exit_code=code, result='passed' if code == 0 else 'failed', apk_built=bool(packages), package_scan='passed' if not packages else 'failed', discovered_packages=packages, source_changed=source_changed,
                      log_sha256=digest(folder / 'check.log'), report_sha256=digest(folder / 'reports.zip'))
        record['report_summary'] = report_summary
        atomic_json(folder / 'record.json', record)
        print(item, 'exit_code', code, flush=True)
        if code:
            return code
    if packages_found():
        raise ValueError('unexpected_install_package')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--scope', choices=('all', 'unit', 'lint'), default='all')
    parser.add_argument('--verify-revision-only', action='store_true')
    args = parser.parse_args(argv)
    if args.verify_revision_only:
        verify_revision()
        return 0
    return run(args.scope)


if __name__ == '__main__':
    sys.exit(main())
