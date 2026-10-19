"""Identity for targeted CI packages, never eligible for formal delivery."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from verify_signing import EXPECTED_CERT, verify_certificate


def record(root, env):
    sha = env['GITHUB_SHA']
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('invalid_candidate_sha')
    classes = env['SMOKE_TEST_CLASSES'].split(',')
    if not all(re.fullmatch(r'app\.daiyu\.android\.(?:[A-Za-z_][A-Za-z_0-9]*\.)*[A-Za-z_][A-Za-z_0-9]*', c) for c in classes):
        raise ValueError('invalid_candidate_classes')
    names = [f'candidate-{sha[:8]}.apk', 'tests/platform-test.apk']
    if sorted(p.name for p in root.glob('*.apk')) != [names[0]] or sorted(p.name for p in (root / 'tests').glob('*.apk')) != ['platform-test.apk']:
        raise ValueError('candidate_apk_set_mismatch')
    if (root / '构建记录.json').exists():
        raise ValueError('candidate_must_not_contain_formal_record')
    digests = {}
    for name in names:
        content = (root / name).read_bytes()
        if not content:
            raise ValueError('empty_candidate_apk')
        digests[name] = hashlib.sha256(content).hexdigest()
    return dict(schema_version=1, mode='candidate', source_sha=sha,
                run_id=env['GITHUB_RUN_ID'], attempt=env['GITHUB_RUN_ATTEMPT'],
                variant='debug', production_delivery=False, formal_acceptance=False,
                certificate_sha256=EXPECTED_CERT, requested_classes=sorted(set(classes)), sha256=digests)


def create(root, env, apksigner):
    value = record(root, env)
    signatures = []
    for name in value['sha256']:
        output = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs',
                                 '--min-sdk-version', '26', str(root / name)],
                                capture_output=True, text=True, check=True).stdout
        verify_certificate(output)
        signatures.append(f'APK: {name}\n{output}')
    (root / 'signing.txt').write_text('\n'.join(signatures), encoding='utf-8')
    (root / 'candidate.json').write_text(json.dumps(value, sort_keys=True) + '\n', encoding='utf-8')
    (root / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in value['sha256'].items()), encoding='utf-8')


def verify(root, env):
    if json.loads((root / 'candidate.json').read_text()) != record(root, env):
        raise ValueError('candidate_identity_mismatch')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('create', 'verify'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--apksigner')
    args = parser.parse_args()
    if args.action == 'create':
        if not args.apksigner:
            parser.error('--apksigner is required for create')
        create(args.directory, os.environ, args.apksigner)
    else:
        verify(args.directory, os.environ)


if __name__ == '__main__':
    main()
