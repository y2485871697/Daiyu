"""用合成记录测试诊断包的源码复用校验。"""
import copy
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_smoke_source as source
from test_release_package import APP, TEST


class DiagnosticSmokeSourceTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123', SOURCE_VARIANT='diagnostic',
                        GITHUB_SHA='a' * 40, SMOKE_MODE='smoke',
                        SMOKE_TEST_CLASSES='app.daiyu.android.ChatGlassMenuSurfaceTest')
        self.record = dict(source_sha='a' * 40, run_id='123', attempt='1',
                           application_id='app.daiyu.android', test_application_id='app.daiyu.android.test',
                           variant='release-diagnostic', debuggable=False, production_delivery=False,
                           profileable=True, jank_diagnostics=True, validation_scope='compile-only',
                           full_regression_passed=False, unit_tests_run=False, instrumentation_run=False)
        self.run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main',
                        workflow_id=397560064, status='completed', conclusion='success')
        self.jobs = [dict(name='增量Release编译（不运行全量）', conclusion='success')]

    def test_only_unchanged_application_explicit_diagnostic_smoke_is_accepted(self):
        source.verify(self.env, self.record, self.run, self.jobs, [])
        for key, value in (('SOURCE_VARIANT', 'compile'), ('SOURCE_VARIANT', 'release'),
                           ('GITHUB_SHA', 'b' * 40), ('SMOKE_MODE', 'compile'),
                           ('SMOKE_TEST_CLASSES', '')):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                source.verify(dict(self.env, **{key: value}), self.record, self.run, self.jobs, [])
        source.verify(dict(self.env, GITHUB_SHA='b' * 40), self.record, self.run, self.jobs, ['tests/changed.py'])
        source.verify(dict(self.env, GITHUB_SHA='b' * 40), self.record, self.run, self.jobs,
                      ['app/src/test/kotlin/ChangedTest.kt'])
        for changed in (['app/src/main/kotlin/Changed.kt'], ['app/src/androidTest/kotlin/Changed.kt'],
                        ['app/src/test/../main/kotlin/Changed.kt'], ['app/src/testFixtures/kotlin/Changed.kt'],
                        ['app/src/debug/kotlin/Changed.kt'], ['app/build.gradle.kts'],
                        ['.github/workflows/build.yml'], ['tests/../app/file']):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                source.verify(dict(self.env, GITHUB_SHA='b' * 40), self.record, self.run, self.jobs, changed)

    def test_compile_and_diagnostic_allow_only_the_reviewed_check_only_workflows(self):
        for path in ('.github/workflows/reconnect-checks.yml',
                     '.github/workflows/popup-layout-checks.yml'):
            for variant in ('compile', 'diagnostic'):
                env = dict(self.env, SOURCE_VARIANT=variant, GITHUB_SHA='b' * 40)
                record = dict(self.record)
                if variant == 'compile':
                    record.update(variant='release', production_delivery=True, profileable=False)
                source.verify(env, record, self.run, self.jobs, [path])
                for changed in ('.github/workflows/build.yml', '.github/workflows/other.yml',
                                path + '/extra',
                                path.replace('/workflows/', '/workflows/../workflows/', 1),
                                'app/build.gradle.kts', 'app/src/androidTest/kotlin/Changed.kt'):
                    with self.subTest(variant=variant, changed=changed), self.assertRaises(ValueError):
                        source.verify(env, record, self.run, self.jobs, [changed])
            release = dict(self.record, variant='release', production_delivery=True, profileable=False)
            with self.assertRaises(ValueError):
                source.verify(dict(self.env, SOURCE_VARIANT='release', GITHUB_SHA='b' * 40),
                              release, self.run, [dict(name='单测、检查与生产Release', conclusion='success')], [path])

    def test_diagnostic_flags_cannot_be_relabelled_or_omitted(self):
        for key, value in (('variant', 'release'), ('debuggable', True), ('profileable', False),
                           ('jank_diagnostics', False), ('production_delivery', True),
                           ('validation_scope', 'full'), ('full_regression_passed', True),
                           ('unit_tests_run', True), ('instrumentation_run', True),
                           ('test_application_id', 'other.test')):
            for record in (dict(self.record, **{key: value}),
                           {k: v for k, v in self.record.items() if k != key}):
                with self.subTest(key=key), self.assertRaises((ValueError, KeyError)):
                    source.verify(self.env, record, self.run, self.jobs, [])
        with self.assertRaises(ValueError):
            source.verify(self.env, self.record, self.run, [], [])
        for key, value in (('run_attempt', 2), ('head_sha', 'b' * 40),
                           ('head_branch', 'other'), ('workflow_id', 1), ('status', 'in_progress')):
            with self.subTest(key=key), self.assertRaises(ValueError):
                source.verify(self.env, self.record, dict(self.run, **{key: value}), self.jobs, [])

    def test_real_manifest_flags_and_both_manifest_hashes_are_required(self):
        diagnostic = APP.replace('</application>', '<profileable android:shell="true"/>'
                                 '<meta-data android:name="app.daiyu.android.JANK_DIAGNOSTICS" '
                                 'android:value="true"/></application>')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record = copy.deepcopy(self.record)
            record['manifest_sha256'] = {}
            record['sha256'] = {}
            for name in ('代鱼-0.1.0-aaaaaaaa.apk', 'tests/platform-test.apk'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture-apk:' + name.encode())
                record['sha256'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
            for name, text in (('app-manifest.xml', diagnostic), ('test-manifest.xml', TEST)):
                (root / name).write_text(text)
                record['manifest_sha256'][name] = hashlib.sha256(text.encode()).hexdigest()
            source.verify_diagnostic_files(root, record)
            for name in ('代鱼-0.1.0-aaaaaaaa.apk', 'tests/platform-test.apk'):
                path = root / name
                original = path.read_bytes()
                path.write_bytes(original + b'tampered')
                with self.assertRaises(ValueError):
                    source.verify_diagnostic_files(root, record)
                path.write_bytes(original)
            for name in ('app-manifest.xml', 'test-manifest.xml'):
                wrong = copy.deepcopy(record)
                wrong['manifest_sha256'][name] = '0' * 64
                with self.assertRaises(ValueError):
                    source.verify_diagnostic_files(root, wrong)
            (root / 'app-manifest.xml').write_text(APP)
            record['manifest_sha256']['app-manifest.xml'] = hashlib.sha256(APP.encode()).hexdigest()
            with self.assertRaises(ValueError):
                source.verify_diagnostic_files(root, record)

    def test_workflow_downloads_the_explicit_diagnostic_artifact_without_rebuilding(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('options: [debug, release, candidate, daily, compile, diagnostic]', workflow)
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertIn("inputs.source_variant == 'diagnostic') && '代鱼诊断Release包'", smoke)
        self.assertNotIn('assembleRelease', smoke)
        self.assertIn("'candidate', 'daily', 'compile', 'diagnostic'", (ROOT / 'scripts/verify_smoke_source.py').read_text())


if __name__ == '__main__':
    unittest.main()
