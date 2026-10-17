"""No APK build or installation: isolated manifest/signature binding contracts."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('synthetic_package', ROOT / 'scripts/verify_synthetic_package.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)
APP = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android.synthetic"><application android:name="app.daiyu.android.ChatApplication"><provider android:authorities="app.daiyu.android.synthetic.shizuku"/></application></manifest>'''
TEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android.synthetic.test"><instrumentation android:name="android.test.InstrumentationTestRunner" android:targetPackage="app.daiyu.android.synthetic"/></manifest>'''
SIGNATURE = ('Number of signers: 1\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
             'Signer #1 certificate SHA-256 digest: ' + package.verify_certificate.__globals__['EXPECTED_CERT'] + '\n')


class SyntheticPackageTests(unittest.TestCase):
    def test_exact_pair_and_disposable_record(self):
        package.verify_manifests(APP, TEST)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'tests').mkdir()
            (root / 'app-synthetic.apk').write_bytes(b'app')
            (root / 'tests/platform-test.apk').write_bytes(b'test')
            calls = []
            def run(argv, **kwargs):
                calls.append(argv)
                output = SIGNATURE if argv[1] == 'verify' else (TEST if Path(argv[-1]).parent.name == 'tests' else APP)
                return SimpleNamespace(stdout=output)
            record = package.verify_pair(root, 'apkanalyzer', 'apksigner',
                dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1'), run)
            self.assertFalse(record['production_delivery'])
            self.assertEqual('app.daiyu.android.synthetic', record['application_id'])
            self.assertEqual(4, len(calls))
            self.assertFalse((root / '构建记录.json').exists())
            self.assertEqual(record, json.loads((root / 'synthetic-record.json').read_text()))
            self.assertEqual(2, len((root / 'SHA256SUMS').read_text().splitlines()))

    def test_production_package_wrong_target_shared_uid_and_authority_rejected(self):
        for app, tests in [
            (APP.replace('package="app.daiyu.android.synthetic"', 'package="app.daiyu.android"'), TEST),
            (APP, TEST.replace('targetPackage="app.daiyu.android.synthetic"', 'targetPackage="app.daiyu.android"')),
            (APP.replace('<application', '<application', 1).replace('package="app.daiyu.android.synthetic"',
                'android:sharedUserId="app.daiyu.android" package="app.daiyu.android.synthetic"'), TEST),
            (APP.replace('app.daiyu.android.synthetic.shizuku', 'app.daiyu.android.shizuku'), TEST),
            (APP.replace('app.daiyu.android.ChatApplication', 'android.app.Application'), TEST),
            (APP, TEST.replace('android.test.InstrumentationTestRunner', 'other.Runner')),
            (APP, TEST.replace('</manifest>', '<instrumentation/></manifest>')),
            (APP, TEST.replace('</manifest>', '<application><provider android:authorities="app.daiyu.android.provider"/></application></manifest>')),
        ]:
            with self.subTest(app=app, tests=tests), self.assertRaises(ValueError):
                package.verify_manifests(app, tests)

    def test_workflow_keeps_production_and_synthetic_artifacts_separate(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        synthetic = workflow.split('  synthetic:\n', 1)[1].split('  smoke:\n', 1)[0]
        build = workflow.split('  build:\n', 1)[1].split('  synthetic:\n', 1)[0]
        self.assertIn('-PdaiyuSyntheticApplication=true', synthetic)
        self.assertNotIn('-PdaiyuSyntheticApplication', build)
        self.assertIn('name: 代鱼合成测试包', synthetic)
        self.assertIn('verify_synthetic_package.py', synthetic)
        self.assertNotIn('verify_signing.py', synthetic)
        self.assertIn('name: 代鱼开发包', build)
