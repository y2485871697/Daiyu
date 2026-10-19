"""No APK build or installation: isolated manifest/signature binding contracts."""
import importlib.util
import json
import re
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
TEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android.synthetic.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="app.daiyu.android.synthetic"/></manifest>'''
SIGNATURE = ('Number of signers: 1\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
             'Signer #1 certificate SHA-256 digest: ' + package.verify_certificate.__globals__['EXPECTED_CERT'] + '\n')


class SyntheticPackageTests(unittest.TestCase):
    def test_performance_setup_activity_stays_outside_measurement_and_guards_teardown(self):
        source = (ROOT / 'app/src/performanceTest/kotlin/app/daiyu/android/ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertLess(source.index('setupActivity = instrumentation.startActivitySync'),
                        source.index('FullPerformanceDataset.seed('))
        self.assertLess(source.index('finishActivity(checkNotNull(setupActivity))'),
                        source.index('application.controller = activeFixture.controller'))
        self.assertLess(source.index('application.controller = activeFixture.controller'),
                        source.index('SyntheticFrameCollector(instrumentation, launched.window)'))
        self.assertIn('if (activitiesDestroyed) {', source)
        self.assertIn('if (controllerRestored) cleanup {', source)
        self.assertIn('"fixture_resources_retained"', source)

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

    def test_only_exact_androidx_runner_is_accepted_in_both_variants(self):
        release = APP.replace('</application>', '<profileable android:shell="true"/></application>')
        for variant, app in [('debug-synthetic', APP), ('release-synthetic', release)]:
            package.verify_manifests(app, TEST, variant=variant)
            for runner in ('android.test.InstrumentationTestRunner',
                           'androidx.test.runner.OtherRunner',
                           'androidx.test.runner.AndroidJUnitRunnerSubclass'):
                with self.subTest(variant=variant, runner=runner), self.assertRaises(ValueError):
                    package.verify_manifests(app, TEST.replace('androidx.test.runner.AndroidJUnitRunner', runner),
                                            variant=variant)

    def test_production_package_wrong_target_shared_uid_and_authority_rejected(self):
        for app, tests in [
            (APP.replace('package="app.daiyu.android.synthetic"', 'package="app.daiyu.android"'), TEST),
            (APP, TEST.replace('targetPackage="app.daiyu.android.synthetic"', 'targetPackage="app.daiyu.android"')),
            (APP.replace('<application', '<application', 1).replace('package="app.daiyu.android.synthetic"',
                'android:sharedUserId="app.daiyu.android" package="app.daiyu.android.synthetic"'), TEST),
            (APP.replace('app.daiyu.android.synthetic.shizuku', 'app.daiyu.android.shizuku'), TEST),
            (APP.replace('app.daiyu.android.ChatApplication', 'android.app.Application'), TEST),
            (APP, TEST.replace('androidx.test.runner.AndroidJUnitRunner', 'other.Runner')),
            (APP, TEST.replace('</manifest>', '<instrumentation/></manifest>')),
            (APP, TEST.replace('</manifest>', '<application><provider android:authorities="app.daiyu.android.provider"/></application></manifest>')),
            (APP, TEST.replace('package="app.daiyu.android.synthetic.test"', 'package="app.daiyu.android.test"')),
            (APP, TEST.replace('package="app.daiyu.android.synthetic.test"',
                'android:sharedUserId="app.daiyu.android" package="app.daiyu.android.synthetic.test"')),
            (APP, TEST[:TEST.index('<instrumentation')] + '</manifest>'),
            (APP[:APP.index('<application')] + '</manifest>', TEST),
            (APP.replace('android:authorities="app.daiyu.android.synthetic.shizuku"', ''), TEST),
            (APP.replace('app.daiyu.android.synthetic.shizuku',
                'app.daiyu.android.synthetic.shizuku;app.daiyu.android.provider'), TEST),
        ]:
            with self.subTest(app=app, tests=tests), self.assertRaises(ValueError):
                package.verify_manifests(app, tests)

    def test_release_manifest_requires_non_debuggable_profileable_app(self):
        release = APP.replace('<application ', '<application android:debuggable="false" ', 1).replace(
            '</application>', '<profileable android:shell="true"/></application>')
        package.verify_manifests(release, TEST, variant='release-synthetic')
        for app in (APP, release.replace('debuggable="false"', 'debuggable="true"'),
                    release.replace('shell="true"', 'shell="false"'),
                    release.replace(' android:shell="true"', ''),
                    release.replace('<profileable android:shell="true"/>',
                                    '<profileable android:shell="true"/><profileable android:shell="true"/>'),
                    release.replace('package="app.daiyu.android.synthetic"', 'package="app.daiyu.android"'),
                    release.replace('app.daiyu.android.synthetic.shizuku', 'app.daiyu.android.shizuku')):
            with self.subTest(app=app), self.assertRaises(ValueError):
                package.verify_manifests(app, TEST, variant='release-synthetic')
        with self.assertRaisesRegex(ValueError, 'synthetic_variant_invalid'):
            package.verify_manifests(release, TEST, variant='signed-debug-is-release')

    def test_release_record_is_explicit_and_never_production_delivery(self):
        release = APP.replace('</application>', '<profileable android:shell="true"/></application>')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'tests').mkdir()
            (root / 'app-synthetic.apk').write_bytes(b'release')
            (root / 'tests/platform-test.apk').write_bytes(b'release-tests')
            def run(argv, **kwargs):
                text = SIGNATURE if argv[1] == 'verify' else (TEST if Path(argv[-1]).parent.name == 'tests' else release)
                return SimpleNamespace(stdout=text)
            record = package.verify_pair(root, 'apkanalyzer', 'apksigner',
                dict(GITHUB_SHA='b' * 40, GITHUB_RUN_ID='124', GITHUB_RUN_ATTEMPT='1'),
                run, variant='release-synthetic')
            self.assertEqual('release-synthetic', record['variant'])
            self.assertEqual('release', record['build_type'])
            self.assertFalse(record['debuggable'])
            self.assertTrue(record['profileable'])
            self.assertFalse(record['production_delivery'])
            self.assertFalse((root / '构建记录.json').exists())

    def test_performance_build_is_isolated_and_uses_separate_test_sources(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        synthetic = workflow.split('  synthetic:\n', 1)[1].split('  smoke:\n', 1)[0]
        self.assertIn('check(!daiyuPhysicalPerformance || daiyuSyntheticApplication)', gradle)
        self.assertIn('check(!daiyuPhysicalPerformance || fixedSigning)', gradle)
        self.assertIn('if (daiyuPhysicalPerformance || daiyuProductionRelease) testBuildType = "release"', gradle)
        self.assertIn('setRoot("src/performanceTest")', gradle)
        release = gradle.split('        release {', 1)[1].split('\n        }', 1)[0]
        self.assertIn('isDebuggable = false', release)
        self.assertIn('isProfileable = daiyuPhysicalPerformance', release)
        self.assertNotIn('src/performance/AndroidManifest.xml', gradle)
        self.assertIn('if (daiyuSyntheticApplication) applicationIdSuffix = ".synthetic"', release)
        self.assertIn(':app:assembleRelease :app:assembleReleaseAndroidTest', synthetic)
        self.assertIn('name: 代鱼Release性能测试包', synthetic)
        self.assertIn('variant=release-synthetic', synthetic)
        self.assertNotIn('verify_signing.py', synthetic)
        self.assertNotIn(':app:connected', synthetic)

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

    def test_performance_source_and_entry_count(self):
        root = ROOT / 'app/src/performanceTest'
        manifest = root / 'AndroidManifest.xml'
        self.assertTrue(manifest.is_file())
        sources = sorted((root / 'kotlin').rglob('*.kt'))
        names = {path.name for path in sources}
        self.assertTrue({'ReleaseSyntheticPerformanceTest.kt', 'PersistentColdStartPerformanceTest.kt',
                         'SyntheticFrameCollector.kt', 'FullPerformanceDataset.kt'} <= names)
        entry = next(path for path in sources if path.name == 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertRegex(entry, r'class\s+ReleaseSyntheticPerformanceTest\s*:\s*InstrumentationTestCase\(\)')
        entries = [(path.name, name) for path in sources for name in
                   re.findall(r'^\s*fun\s+(test\w+)\s*\(\s*\)', path.read_text(), re.MULTILINE)]
        self.assertEqual([('PersistentColdStartPerformanceTest.kt',
                           'testPreparePersistentColdStartDataset'),
                          ('PersistentColdStartPerformanceTest.kt',
                           'testVerifyPersistentColdStartDataset'),
                          ('PersistentColdStartPerformanceTest.kt', 'testCollectOneColdStart'),
                          ('ReleaseSyntheticPerformanceTest.kt',
                           'testCollectRawFramesAndSyntheticInputUpperBounds'),
                          ('ReleaseSyntheticPerformanceTest.kt',
                           'testCollectFullScaleScrollInputAndBackendMigration')], entries)
        self.assertIn('args.getString("daiyuFullPerformance") == "true"', entry)
        self.assertIn('explicit_full_performance_opt_in_required', entry)
