"""Bind the disposable test pair; never reuse production delivery metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from verify_signing import verify_certificate

APP_ID = 'app.daiyu.android.synthetic'
TEST_ID = APP_ID + '.test'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def verify_manifests(app_xml, test_xml):
    app, tests = ET.fromstring(app_xml), ET.fromstring(test_xml)
    if app.get('package') != APP_ID or tests.get('package') != TEST_ID:
        raise ValueError('synthetic_package_identity_mismatch')
    if app.get(ANDROID + 'sharedUserId') or tests.get(ANDROID + 'sharedUserId'):
        raise ValueError('synthetic_shared_uid_forbidden')
    application = app.find('application')
    if application is None or application.get(ANDROID + 'name') != 'app.daiyu.android.ChatApplication':
        raise ValueError('synthetic_application_mismatch')
    for manifest, package_id in ((app, APP_ID), (tests, TEST_ID)):
        for provider in manifest.findall('application/provider'):
            if any(not value.startswith(package_id + '.') for value in provider.get(ANDROID + 'authorities', '').split(';')):
                raise ValueError('synthetic_provider_identity_mismatch')
    runners = tests.findall('instrumentation')
    if len(runners) != 1 or runners[0].get(ANDROID + 'targetPackage') != APP_ID or \
            runners[0].get(ANDROID + 'name') != 'android.test.InstrumentationTestRunner':
        raise ValueError('synthetic_instrumentation_mismatch')


def verify_pair(directory, apkanalyzer, apksigner, env, run=subprocess.run):
    app, tests = directory / 'app-synthetic.apk', directory / 'tests/platform-test.apk'
    sha = env['GITHUB_SHA']
    if len(sha) != 40 or any(c not in '0123456789abcdef' for c in sha):
        raise ValueError('synthetic_source_sha_invalid')
    manifests, signatures, sums = [], [], {}
    for apk in (app, tests):
        if not apk.is_file() or apk.stat().st_size == 0:
            raise ValueError('synthetic_apk_missing')
        manifests.append(run([str(apkanalyzer), 'manifest', 'print', str(apk)],
                             capture_output=True, text=True, check=True).stdout)
        signature = run([str(apksigner), 'verify', '--verbose', '--print-certs',
                         '--min-sdk-version', '26', str(apk)], capture_output=True, text=True, check=True).stdout
        verify_certificate(signature)
        signatures.append(signature)
        digest = hashlib.sha256()
        with apk.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        sums[apk.relative_to(directory).as_posix()] = digest.hexdigest()
    verify_manifests(*manifests)
    for name, manifest in zip(('app', 'test'), manifests):
        (directory / (name + '-manifest.xml')).write_text(manifest, encoding='utf-8')
    (directory / 'signatures.txt').write_text('\n'.join(signatures), encoding='utf-8')
    record = dict(source_sha=sha, run_id=env['GITHUB_RUN_ID'], attempt=env['GITHUB_RUN_ATTEMPT'],
                  application_id=APP_ID, test_application_id=TEST_ID, variant='debug-synthetic',
                  production_delivery=False, sha256=sums)
    (directory / 'synthetic-record.json').write_text(json.dumps(record, ensure_ascii=False) + '\n', encoding='utf-8')
    (directory / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sums.items()), encoding='utf-8')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--apkanalyzer', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    args = parser.parse_args()
    verify_pair(args.directory, args.apkanalyzer, args.apksigner, os.environ)
