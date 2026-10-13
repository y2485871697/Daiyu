"""GitHub checks-only runner: exact revision, per-item exit codes and hashes."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from evidence import SCOPES, atomic_json, configuration, digest


def run(scope):
    root = Path.cwd()
    output = root / 'cooperation-reports'
    output.mkdir(exist_ok=True)
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if sha != os.environ['GITHUB_SHA'] or (os.environ.get('EXPECTED_SHA') and os.environ['EXPECTED_SHA'] != sha):
        raise ValueError('checkout_sha_mismatch')
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
                   '--console=plain', '-I', 'scripts/cooperation/checks_only.init.gradle', SCOPES[item]]
        with (folder / 'check.log').open('wb') as log:
            code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
        reports = (root / 'core-domain/build/test-results/test' if item == 'unit'
                   else root / 'app/build/reports')
        with zipfile.ZipFile(folder / 'reports.zip', 'w', zipfile.ZIP_DEFLATED) as z:
            for p in reports.rglob('*.xml'):
                z.write(p, str(p.relative_to(reports)).replace('\\', '/'))
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
        atomic_json(folder / 'record.json', record)
        print(item, 'exit_code', code, flush=True)
        if code:
            return code
    if packages_found():
        raise ValueError('unexpected_install_package')
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--scope', choices=('all', 'unit', 'lint'), default='all')
    args = parser.parse_args()
    sys.exit(run(args.scope))
