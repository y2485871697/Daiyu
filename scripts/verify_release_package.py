"""Verify an actual production Release pair, not a relabelled debug/performance APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from verify_signing import EXPECTED_CERT, verify_certificate

APP_ID = 'app.daiyu.android'
TEST_ID = APP_ID + '.test'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def verify_manifests(app_xml, test_xml):
    app, tests = ET.fromstring(app_xml), ET.fromstring(test_xml)
    if app.tag != 'manifest' or tests.tag != 'manifest' or app.get('package') != APP_ID or tests.get('package') != TEST_ID:
        raise ValueError('release_package_identity_mismatch')
    if app.get(ANDROID + 'versionName') != '0.1.0' or app.get(ANDROID + 'versionCode') != '1':
        raise ValueError('release_version_mismatch')
    applications = app.findall('application')
    if len(applications) != 1 or applications[0].get(ANDROID + 'name') != APP_ID + '.ChatApplication':
        raise ValueError('release_application_mismatch')
    application = applications[0]
    if application.get(ANDROID + 'debuggable', 'false') != 'false' or application.get(ANDROID + 'testOnly', 'false') != 'false':
        raise ValueError('release_debug_or_test_only_forbidden')
    if any(node.get(ANDROID + 'shell', 'false') != 'false' for node in application.findall('profileable')):
        raise ValueError('release_shell_profileable_forbidden')
    for manifest, package in ((app, APP_ID), (tests, TEST_ID)):
        if manifest.get(ANDROID + 'sharedUserId'):
            raise ValueError('release_shared_uid_forbidden')
        for provider in manifest.findall('application/provider'):
            if any(not value.startswith(package + '.') for value in provider.get(ANDROID + 'authorities', '').split(';')):
                raise ValueError('release_provider_identity_mismatch')
    runners = tests.findall('instrumentation')
    if len(runners) != 1 or runners[0].get(ANDROID + 'targetPackage') != APP_ID or runners[0].get(ANDROID + 'name') != 'androidx.test.runner.AndroidJUnitRunner':
        raise ValueError('release_instrumentation_mismatch')


def verify_delivery(apkanalyzer, apksigner, delivery, env, run=subprocess.run):
    record_path = delivery / '构建记录.json'
    record_path.unlink(missing_ok=True)
    sha = env['GITHUB_SHA']
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or any(not re.fullmatch(r'[1-9][0-9]*', env[key]) for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')):
        raise ValueError('release_source_identity_invalid')
    name = f'代鱼-0.1.0-{sha[:8]}.apk'
    app, test = delivery / name, delivery / 'tests/platform-test.apk'
    if list(delivery.glob('*.apk')) != [app] or list((delivery / 'tests').glob('*.apk')) != [test]:
        raise ValueError('release_apk_pair_missing')
    manifests, signatures, sums = [], [], {}
    for apk in (app, test):
        if not apk.is_file() or apk.stat().st_size == 0:
            raise ValueError('release_apk_pair_missing')
        manifests.append(run([str(apkanalyzer), 'manifest', 'print', str(apk)], capture_output=True, text=True, check=True).stdout)
        signature = run([str(apksigner), 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(apk)], capture_output=True, text=True, check=True).stdout
        verify_certificate(signature)
        signatures.append(f'APK: {apk.relative_to(delivery).as_posix()}\n{signature}')
        digest = hashlib.sha256()
        with apk.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        sums[apk.relative_to(delivery).as_posix()] = digest.hexdigest()
    verify_manifests(*manifests)
    manifest_sums = {}
    for name, text in zip(('app-manifest.xml', 'test-manifest.xml'), manifests):
        raw = text.encode('utf-8')
        (delivery / name).write_bytes(raw)
        manifest_sums[name] = hashlib.sha256(raw).hexdigest()
    (delivery / '签名证据.txt').write_text('\n'.join(signatures), encoding='utf-8')
    record = dict(source_sha=sha, version_name='0.1.0', version_code=1, application_id=APP_ID,
                  test_application_id=TEST_ID, signing='daiyu-fixed', certificate_sha256=EXPECTED_CERT,
                  variant='release', debuggable=False, profileable=False, production_delivery=True,
                  run_id=env['GITHUB_RUN_ID'], attempt=env['GITHUB_RUN_ATTEMPT'],
                  sha256=sums, manifest_sha256=manifest_sums)
    record_path.write_text(json.dumps(record, ensure_ascii=False) + '\n', encoding='utf-8')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apkanalyzer', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--delivery', required=True, type=Path)
    args = parser.parse_args()
    verify_delivery(args.apkanalyzer, args.apksigner, args.delivery, os.environ)
