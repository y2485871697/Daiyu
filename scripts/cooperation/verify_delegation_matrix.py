"""Verify this run's targeted matrix; never executes Gradle or builds packages."""
import json
import os
from pathlib import Path
import sys
from cloud_checks import verify_revision
from evidence import configuration, digest, read_json, validate_record, verify_unit_matrix


def verify(windows, linux):
    sha = verify_revision()
    config = configuration(Path.cwd())
    for folder, platform in ((Path(windows), 'Windows'), (Path(linux), 'Linux')):
        record = read_json(folder / 'record.json')
        validate_record(record, sha, config, 'delegation')
        if (record['check_id'] != 'github-' + os.environ['GITHUB_RUN_ID'] + '-' + platform
                or record['check_attempt'] != int(os.environ['GITHUB_RUN_ATTEMPT'])
                or record.get('environment', {}).get('os') != platform
                or record.get('source_changed') != [] or record.get('discovered_packages') != []
                or record.get('apk_built') is not False or record.get('package_scan') != 'passed'
                or digest(folder / 'check.log') != record['log_sha256']
                or digest(folder / 'reports.zip') != record['report_sha256']):
            raise ValueError('delegation_matrix_identity_mismatch')
    return dict(source_sha=sha, run_id=os.environ['GITHUB_RUN_ID'], attempt=int(os.environ['GITHUB_RUN_ATTEMPT']),
        scope='delegation', **verify_unit_matrix(Path(windows) / 'reports.zip', Path(linux) / 'reports.zip', scope='delegation'))


if __name__ == '__main__':
    print(json.dumps(verify(*sys.argv[1:]), ensure_ascii=False))
