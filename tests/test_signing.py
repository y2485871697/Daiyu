import base64
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from prepare_signing import prepare
from verify_signing import EXPECTED_CERT, verify_certificate, verify_delivery

GOOD = f'Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: {EXPECTED_CERT}\n'


class SigningTests(unittest.TestCase):
    def test_prepare_private_file_and_reject_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(RUNNER_TEMP=tmp, DAIYU_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'synthetic').decode(),
                       DAIYU_RELEASE_KEY_ALIAS='test', DAIYU_RELEASE_KEY_PASSWORD='test', DAIYU_RELEASE_STORE_PASSWORD='test')
            path = prepare(env)
            self.assertEqual(path.read_bytes(), b'synthetic')
            if os.name != 'nt':
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            with self.assertRaises(FileExistsError):
                prepare(env)

    def test_missing_empty_and_invalid_material(self):
        env = dict(DAIYU_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'synthetic').decode(),
                   DAIYU_RELEASE_KEY_ALIAS='a', DAIYU_RELEASE_KEY_PASSWORD='p', DAIYU_RELEASE_STORE_PASSWORD='p')
        for name in env:
            partial = env.copy(); del partial[name]
            empty = {**env, name: ''}
            for invalid in (partial, empty):
                with self.assertRaises(ValueError):
                    prepare(invalid)
        for payload in ('***', '', base64.b64encode(b'x' * (16 * 1024 * 1024 + 1)).decode()):
            with self.assertRaises(ValueError):
                prepare({**env, 'DAIYU_RELEASE_KEYSTORE_BASE64': payload})

    @unittest.skipIf(os.name == 'nt', 'CI private-file contract is Linux')
    def test_symlink_destination_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'untouched'; target.write_bytes(b'unchanged')
            (Path(tmp) / 'daiyu-release.p12').symlink_to(target)
            env = dict(RUNNER_TEMP=tmp, DAIYU_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'synthetic').decode(),
                       DAIYU_RELEASE_KEY_ALIAS='a', DAIYU_RELEASE_KEY_PASSWORD='p', DAIYU_RELEASE_STORE_PASSWORD='p')
            with self.assertRaises(FileExistsError):
                prepare(env)
            self.assertEqual(target.read_bytes(), b'unchanged')

    def test_exact_certificate_and_v2_required(self):
        verify_certificate(GOOD)
        for bad in (GOOD.replace(EXPECTED_CERT, '0' * 64), GOOD.replace(': true', ': false'),
                    GOOD + f'Signer #2 certificate SHA-256 digest: {EXPECTED_CERT}\n', ''):
            with self.assertRaises(ValueError):
                verify_certificate(bad)

    def test_both_apks_checked_before_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'tests').mkdir()
            (root / '代鱼-0.1.0-aaaaaaaa.apk').write_bytes(b'app')
            (root / 'tests/platform-test.apk').write_bytes(b'test')
            env = dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1')
            commands = []
            def run(command, **kwargs):
                commands.append(command)
                return subprocess.CompletedProcess(command, 0, GOOD, '')
            record = verify_delivery('apksigner', root, env, run)
            self.assertEqual(len(commands), 2)
            self.assertEqual(record['signing'], 'owner-fixed')
            self.assertEqual(record['certificate_sha256'], EXPECTED_CERT)
            self.assertIn('--min-sdk-version', commands[1])
            def wrong_test(command, **kwargs):
                text = GOOD.replace(EXPECTED_CERT, '0' * 64) if command[-1].endswith('platform-test.apk') else GOOD
                return subprocess.CompletedProcess(command, 0, text, '')
            with self.assertRaises(ValueError):
                verify_delivery('apksigner', root, env, wrong_test)
            self.assertFalse((root / '构建记录.json').exists())
            def invalid(command, **kwargs):
                raise subprocess.CalledProcessError(1, command)
            with self.assertRaises(subprocess.CalledProcessError):
                verify_delivery('apksigner', root, env, invalid)
            (root / 'tests/platform-test.apk').unlink()
            with self.assertRaises(ValueError):
                verify_delivery('apksigner', root, env, run)

    def test_workflow_signing_only_build_and_cleanup(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text(encoding='utf-8')
        checks, build = workflow.split('  build:\n', 1)
        self.assertNotIn('secrets.DAIYU_', checks)
        self.assertIn("if: inputs.mode == 'build' && github.ref == 'refs/heads/main'", build)
        self.assertIn("DAIYU_REQUIRE_SIGNING: 'true'", build)
        self.assertIn('if: always()\n        run: rm -f -- "$RUNNER_TEMP/daiyu-release.p12"', build)
        self.assertLess(build.index('scripts/verify_signing.py'), build.index('- name: 上传开发包'))
