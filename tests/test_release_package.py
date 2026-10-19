"""Synthetic manifest/tool fixtures; never a real Release build or device result."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_release_package as package

APP = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android" android:versionName="0.1.0" android:versionCode="1"><application android:name="app.daiyu.android.ChatApplication"><provider android:authorities="app.daiyu.android.shizuku"/></application></manifest>'''
TEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="app.daiyu.android"/></manifest>'''
SIGNATURE = ('Number of signers: 1\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
             'Signer #1 certificate SHA-256 digest: ' + package.EXPECTED_CERT + '\n')


class ReleasePackageTests(unittest.TestCase):
    def test_manifest_requires_production_identity_and_release_flags(self):
        package.verify_manifests(APP, TEST)
        package.verify_manifests(APP.replace('<application ', '<application android:debuggable="false" android:testOnly="false" '), TEST)
        for old, new in (
            ('package="app.daiyu.android"', 'package="app.daiyu.android.synthetic"'),
            ('versionName="0.1.0"', 'versionName="other"'), ('versionCode="1"', 'versionCode="2"'),
            ('<application ', '<application android:debuggable="true" '),
            ('<application ', '<application android:testOnly="true" '),
            ('</application>', '<profileable android:shell="true"/></application>'),
            ('ChatApplication', 'OtherApplication'),
            ('package="app.daiyu.android"', 'android:sharedUserId="shared" package="app.daiyu.android"'),
            ('app.daiyu.android.shizuku', 'old.app.shizuku'),
            ('android:authorities="app.daiyu.android.shizuku"', ''),
            ('</application>', '</application><application/>'),
        ):
            with self.subTest(new=new), self.assertRaises(ValueError):
                package.verify_manifests(APP.replace(old, new), TEST)
        for old, new in (
            ('app.daiyu.android.test', 'app.daiyu.android.synthetic.test'),
            ('targetPackage="app.daiyu.android"', 'targetPackage="app.daiyu.android.synthetic"'),
            ('AndroidJUnitRunner', 'OtherRunner'),
            ('</manifest>', '<instrumentation/></manifest>'),
            ('</manifest>', '<application><provider android:authorities="old.app.test"/></application></manifest>'),
            ('package="app.daiyu.android.test"', 'android:sharedUserId="shared" package="app.daiyu.android.test"'),
        ):
            with self.subTest(new=new), self.assertRaises(ValueError):
                package.verify_manifests(APP, TEST.replace(old, new))

    def test_pair_actual_manifests_signatures_and_hashes_before_record(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'tests').mkdir()
            app = root / '代鱼-0.1.0-aaaaaaaa.apk'; app.write_bytes(b'app')
            test = root / 'tests/platform-test.apk'; test.write_bytes(b'tests')
            env = dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1')
            calls = []
            def run(argv, **kwargs):
                calls.append(argv)
                output = SIGNATURE if argv[1] == 'verify' else TEST if Path(argv[-1]) == test else APP
                return SimpleNamespace(stdout=output)
            record = package.verify_delivery('apkanalyzer', 'apksigner', root, env, run)
            self.assertEqual(4, len(calls))
            self.assertEqual('release', record['variant'])
            self.assertIs(record['debuggable'], False)
            self.assertIs(record['profileable'], False)
            self.assertEqual(hashlib.sha256(b'app').hexdigest(), record['sha256'][app.name])
            for name, digest in record['manifest_sha256'].items():
                self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest)
            self.assertEqual(record, json.loads((root / '构建记录.json').read_text()))
            def debug_app(argv, **kwargs):
                result = run(argv, **kwargs)
                if argv[1] == 'manifest' and Path(argv[-1]) == app:
                    result.stdout = APP.replace('<application ', '<application android:debuggable="true" ')
                return result
            with self.assertRaisesRegex(ValueError, 'release_debug_or_test_only_forbidden'):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, debug_app)
            self.assertFalse((root / '构建记录.json').exists())
            def wrong_signer(argv, **kwargs):
                result = run(argv, **kwargs)
                if argv[1] == 'verify' and Path(argv[-1]) == test:
                    result.stdout = result.stdout.replace(package.EXPECTED_CERT, '0' * 64)
                return result
            with self.assertRaises(ValueError):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, wrong_signer)
            self.assertFalse((root / '构建记录.json').exists())
            def failed(argv, **kwargs):
                raise subprocess.CalledProcessError(1, argv)
            with self.assertRaises(subprocess.CalledProcessError):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, failed)
            for key, value in [('GITHUB_SHA', 'a' * 8), ('GITHUB_RUN_ID', '0'), ('GITHUB_RUN_ATTEMPT', 'bad')]:
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'release_source_identity_invalid'):
                    package.verify_delivery('apkanalyzer', 'apksigner', root, {**env, key: value}, run)
            app.write_bytes(b'')
            with self.assertRaisesRegex(ValueError, 'release_apk_pair_missing'):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, run)

    def test_release_workflow_is_separate_full_unfiltered_dual_api_path(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        release = workflow.split('  release:\n', 1)[1].split('  synthetic:\n', 1)[0]
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertIn("if: inputs.mode == 'release' && github.ref == 'refs/heads/main'", release)
        self.assertIn(':core-domain:test :app:testReleaseUnitTest :app:lintRelease', release)
        self.assertIn('-PdaiyuProductionRelease=true :app:assembleRelease :app:assembleReleaseAndroidTest', release)
        self.assertNotIn('daiyuSyntheticApplication', release)
        self.assertIn('scripts/verify_release_package.py --apkanalyzer', release)
        self.assertIn('scripts/verify_license_bundle.py', release)
        self.assertIn('name: 代鱼正式候选包', release)
        self.assertLess(release.index('scripts/verify_release_package.py'), release.index('name: 上传生产Release候选包'))
        self.assertIn('needs: [build, release]', smoke)
        self.assertIn("inputs.mode == 'release' && needs.release.result == 'success'", smoke)
        self.assertIn("(inputs.mode == 'release' || (inputs.mode == 'smoke' && inputs.source_variant == 'release')) && '代鱼正式候选包' || '代鱼开发包'", smoke)
        self.assertIn('test -z "$SMOKE_TEST_CLASSES" || test "$SMOKE_MODE" = smoke', release)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('!daiyuProductionRelease || (!daiyuSyntheticApplication && !daiyuPhysicalPerformance)', gradle)
        self.assertIn('if (daiyuPhysicalPerformance || daiyuProductionRelease) testBuildType = "release"', gradle)
        self.assertIn('check(!daiyuProductionRelease || fixedSigning)', gradle)
