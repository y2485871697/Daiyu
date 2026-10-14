"""Require the owner's fixed certificate on both application and test APKs."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

EXPECTED_CERT = '1e1c8731bd2a7fab53b941cd9869884e7756bed96fadd10f322e70d760d1d193'


def verify_certificate(output):
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', output, re.M)
    if fingerprints != [EXPECTED_CERT]:
        raise ValueError('APK 签名证书与固定证书不一致')
    if not re.search(r'^Verified using v2 scheme .*: true$', output, re.M):
        raise ValueError('APK 缺少通过验证的 v2 签名')


def verify_delivery(apksigner, delivery, env, run=subprocess.run):
    record_path = delivery / '构建记录.json'
    record_path.unlink(missing_ok=True)
    main_apks = list(delivery.glob('*.apk'))
    test_apks = list((delivery / 'tests').glob('*.apk'))
    expected_name = f"代鱼-0.1.0-{env['GITHUB_SHA'][:8]}.apk"
    if [p.name for p in main_apks] != [expected_name] or [p.name for p in test_apks] != ['platform-test.apk']:
        raise ValueError('主 APK 或测试 APK 不完整')
    evidence = []
    for apk in main_apks + test_apks:
        result = run([str(apksigner), 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(apk)],
                     capture_output=True, text=True, check=True)
        verify_certificate(result.stdout)
        evidence.append(f'APK: {apk.relative_to(delivery)}\n{result.stdout}')
    (delivery / '签名证据.txt').write_text('\n'.join(evidence), encoding='utf-8')
    record = dict(source_sha=env['GITHUB_SHA'], version_name='0.1.0', version_code=1,
                  application_id='app.daiyu.android', signing='owner-fixed',
                  certificate_sha256=EXPECTED_CERT, variant='debug', debuggable=True,
                  run_id=env['GITHUB_RUN_ID'], attempt=env['GITHUB_RUN_ATTEMPT'])
    record_path.write_text(json.dumps(record, ensure_ascii=False) + '\n', encoding='utf-8')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--delivery', required=True, type=Path)
    args = parser.parse_args()
    verify_delivery(args.apksigner, args.delivery, os.environ)
