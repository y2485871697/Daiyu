"""Local fixtures for daily Release policy; no APK build or device acceptance."""
import json
import os
import subprocess
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import daily_release as daily


class DailyReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = dict(GITHUB_SHA='a' * 40, DAILY_BASE_SHA='b' * 40,
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                        SMOKE_TEST_CLASSES='app.daiyu.android.ChangedTest')
        for name in (*daily.CORE, 'app.daiyu.android.ChangedTest'):
            path = self.root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + '\nclass ' + name.rsplit('.', 1)[1]
                            + ' : TestCase() {\n fun testOne() {}\n}\n')
        self.changed = daily.UI + 'components/Example.kt'
        self.file(self.changed)

    def file(self, path):
        value = self.root / path
        value.parent.mkdir(parents=True, exist_ok=True)
        value.write_text('fixture')
        return value

    def plan(self, paths=None, env=None):
        paths = [self.changed] if paths is None else paths
        with patch.object(daily.subprocess, 'check_output', side_effect=[
                (env or self.env)['GITHUB_SHA'] + '\n', ('\0'.join(paths) + '\0').encode()]), \
                patch.object(daily.subprocess, 'run') as ancestor:
            value = daily.make_plan(self.root, env or self.env)
            ancestor.assert_called_once_with(['git', 'merge-base', '--is-ancestor',
                (env or self.env)['DAILY_BASE_SHA'], (env or self.env)['GITHUB_SHA']], cwd=self.root, check=True)
            return value

    def test_targeted_union_and_two_api_single_shards(self):
        plan = self.plan()
        self.assertEqual(set(plan['classes']), set(daily.CORE) | {'app.daiyu.android.ChangedTest'})
        self.assertEqual(plan['expected_tests'], 5)
        self.assertEqual(plan['apis'], ['26', '37.2'])
        self.assertEqual(plan['shard_count'], 1)
        self.assertIs(plan['full_regression_passed'], False)

    def test_complete_inventory_exists_in_current_repository(self):
        classes = daily.discover(ROOT)
        self.assertTrue(set(daily.CORE) <= classes.keys())
        self.assertEqual(classes['app.daiyu.android.ui.screens.settings.SettingsDisclosureMotionTest']['methods'], [
            'testBothDirectionsMoveCardAndFollowingContent',
            'testCollapsingFieldsLoseFocusAndActions',
            'testRapidReversalFollowsTheLatestTarget',
            'testZeroAndDoubleDurationScale',
        ])

    def test_private_multiline_constructor_is_discovered_without_losing_ambiguity_guards(self):
        path = self.root / 'app/src/androidTest/kotlin/PrivateConstructorTest.kt'
        source = ('package app.daiyu.android\n'
                  'class PrivateConstructorTest private constructor(\n'
                  '    private val scale: MotionScale,\n'
                  ') : TestCase(scale) {\n'
                  '    constructor() : this(MotionScale())\n'
                  '    fun testOne() {}\n'
                  '    fun testTwo() {}\n'
                  '}\n')
        path.write_text(source)
        self.assertEqual(daily.discover(self.root)['app.daiyu.android.PrivateConstructorTest']['methods'],
                         ['testOne', 'testTwo'])
        path.write_text(source + 'class AnotherTest : TestCase() {}\n')
        with self.assertRaisesRegex(ValueError, 'ambiguous_daily_test_inventory'):
            daily.discover(self.root)

    def test_missing_short_or_identical_baseline_is_rejected(self):
        for base in ('', 'b' * 8, 'a' * 40, '$(exit 99)'):
            with self.subTest(base=base), self.assertRaises(ValueError):
                daily.make_plan(self.root, dict(self.env, DAILY_BASE_SHA=base))

    def test_core_unknown_build_and_deleted_inputs_require_full(self):
        for path in ('app/src/main/AndroidManifest.xml', 'app/build.gradle.kts',
                     'core-domain/src/main/Example.kt', '.github/workflows/build.yml',
                     'scripts/prepare-speech-runtime.py', daily.UI + 'app/MainScreen.kt',
                     daily.UI + 'screens/chat/ExampleViewModel.kt',
                     daily.UI + 'screens/terminal/TerminalScreen.kt',
                     'app/src/main/res/xml/network_security.xml'):
            self.file(path)
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'requires_full'):
                self.plan([path])
        (self.root / self.changed).unlink()
        with self.assertRaisesRegex(ValueError, 'requires_full'):
            self.plan()

    def test_production_change_requires_affected_regression(self):
        with self.assertRaisesRegex(ValueError, 'requires_affected'):
            self.plan(env=dict(self.env, SMOKE_TEST_CLASSES=''))

    def test_modified_device_test_is_automatically_selected(self):
        path = 'app/src/androidTest/kotlin/app/daiyu/android/ChangedTest.kt'
        plan = self.plan([self.changed, path], dict(self.env, SMOKE_TEST_CLASSES=''))
        self.assertIn('app.daiyu.android.ChangedTest', plan['classes'])

    def test_shared_device_fixture_requires_full(self):
        path = 'app/src/androidTest/kotlin/app/daiyu/android/WiredActivityTestCase.kt'
        self.file(path)
        with self.assertRaisesRegex(ValueError, 'test_fixtures'):
            self.plan([path])

    def test_unknown_or_injected_affected_class_is_rejected(self):
        for name in ('app.daiyu.android.MissingTest', '$(echo bad)', 'app.daiyu.android.ChangedTest,'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.plan(env=dict(self.env, SMOKE_TEST_CLASSES=name))

    def test_presentation_paths_do_not_admit_traversal_or_sensitive_resources(self):
        for path in (daily.UI + 'components/Card.kt', 'app/src/main/res/drawable/icon.xml',
                     'app/src/main/res/values-zh/strings.xml', 'docs/note.md'):
            self.assertTrue(daily.presentation_only(path), path)
        for path in ('../docs/note.md', 'docs/../secret', 'docs\\note.md',
                     'app/src/main/res/values/config.xml', daily.UI + 'navigation/Router.kt'):
            self.assertFalse(daily.presentation_only(path), path)

    def package(self, plan):
        directory = self.root / 'delivery'
        directory.mkdir()
        (directory / 'tests').mkdir()
        names = [f"代鱼-0.1.0-{self.env['GITHUB_SHA'][:8]}.apk", 'tests/platform-test.apk']
        for name in names:
            (directory / name).write_bytes(b'fixture apk')
        record = dict(source_sha=self.env['GITHUB_SHA'], run_id='123', attempt='1',
                      variant='release', production_delivery=True, debuggable=False, profileable=False,
                      sha256={name: daily.digest(directory / name) for name in names})
        (directory / '构建记录.json').write_text(json.dumps(record))
        (directory / 'daily-plan.json').write_text(json.dumps(plan))
        daily.bind(directory, plan, self.env)
        return directory

    def test_binding_never_claims_full_regression(self):
        plan = self.plan()
        directory = self.package(plan)
        daily.verify(directory, plan, self.env)
        record = json.loads((directory / '构建记录.json').read_text())
        self.assertEqual(record['validation_profile'], 'daily')
        self.assertEqual(record['validation_status'], 'pending')
        self.assertIs(record['full_regression_passed'], False)

    def test_package_mutation_scope_and_identity_are_rejected(self):
        plan = self.plan()
        directory = self.package(plan)
        original = (directory / '构建记录.json').read_text()
        for key, value in (('source_sha', 'c' * 40), ('run_id', '124'), ('attempt', '2'),
                           ('validation_profile', 'full'), ('full_regression_passed', True),
                           ('debuggable', True), ('profileable', True), ('variant', 'debug')):
            changed = json.loads(original)
            changed[key] = value
            (directory / '构建记录.json').write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                daily.verify(directory, plan, self.env)
        (directory / '构建记录.json').write_text(original)
        (directory / 'tests/platform-test.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'digest_mismatch'):
            daily.verify(directory, plan, self.env)

    def raw(self, plan, cases=None):
        cases = cases if cases is not None else [(name, method) for name, methods in plan['classes'].items() for method in methods]
        rows = []
        for name, method in cases:
            rows += [f'INSTRUMENTATION_STATUS: class={name}', f'INSTRUMENTATION_STATUS: test={method}',
                     'INSTRUMENTATION_STATUS_CODE: 0']
        return ('\n'.join(rows) + f"\nOK ({plan['expected_tests']} tests)\nINSTRUMENTATION_CODE: -1\n").encode()

    def test_each_selected_method_must_complete_not_just_total_count(self):
        plan = self.plan()
        daily.verify_methods(self.raw(plan), plan)
        cases = [(name, method) for name, methods in plan['classes'].items() for method in methods]
        for bad in (cases[:-1], cases[:-1] + [cases[0]], cases[:-1] + [('app.daiyu.android.OtherTest', 'testOne')]):
            with self.subTest(cases=bad), self.assertRaises(ValueError):
                daily.verify_methods(self.raw(plan, bad), plan)

    def test_failure_or_interrupted_instrumentation_cannot_pass(self):
        plan = self.plan()
        for raw in (self.raw(plan).replace(b'INSTRUMENTATION_CODE: -1', b'INSTRUMENTATION_CODE: 0'),
                    self.raw(plan) + b'FATAL EXCEPTION\n', self.raw(plan).replace(b'OK (5 tests)', b'')):
            with self.assertRaises(ValueError):
                daily.verify_methods(raw, plan)

    @unittest.skipIf(os.name == 'nt', 'POSIX smoke runner fixture')
    def test_actual_smoke_selector_consumes_the_same_plan(self):
        plan = self.plan()
        self.package(plan)
        (self.root / 'smoke').mkdir()
        binary = self.root / 'bin'
        binary.mkdir()
        git = binary / 'git'
        git.write_text('#!/usr/bin/env python3\nimport sys\n'
                       'if sys.argv[1] == "rev-parse": print(' + repr(self.env['GITHUB_SHA']) + ')\n'
                       'elif sys.argv[1] == "diff": sys.stdout.buffer.write(' + repr((self.changed + '\0').encode()) + ')\n')
        git.chmod(0o755)
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        selector = script.split("<<'PYCOUNT'\n", 1)[1].split('\nPYCOUNT', 1)[0]
        env = dict(os.environ, **self.env, SMOKE_MODE='daily', SMOKE_SHARD_COUNT='1',
                   SMOKE_SHARD_INDEX='0', SMOKE_SCRIPT_DIR=str(ROOT / 'scripts'))
        env['PATH'] = str(binary) + os.pathsep + env['PATH']
        result = subprocess.run([sys.executable, '-c', selector], cwd=self.root,
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[0], '5')
        selection = json.loads((self.root / 'smoke/selection.json').read_text())
        self.assertEqual(selection['selected_classes'], sorted(plan['classes']))
        self.assertEqual(selection['shard_count'], 1)
        self.assertEqual(selection['mode'], 'daily')
        self.assertIs(selection['full_regression_passed'], False)
        env['SMOKE_SHARD_COUNT'] = '4'
        result = subprocess.run([sys.executable, '-c', selector], cwd=self.root,
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('one complete targeted shard', result.stderr)

    def test_workflow_keeps_full_matrix_and_uses_separate_daily_artifact(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('          - daily\n', workflow)
        release = workflow.split('  release:\n', 1)[1].split('  synthetic:\n', 1)[0]
        self.assertLess(release.index('scripts/daily_release.py plan'), release.index('setup-java'))
        self.assertIn(':core-domain:test :app:testReleaseUnitTest :app:lintRelease', release)
        self.assertIn('-PdaiyuProductionRelease=true :app:assembleRelease :app:assembleReleaseAndroidTest', release)
        self.assertIn('scripts/daily_release.py bind', release)
        self.assertIn('代鱼日常Release包-daily', release)
        smoke = workflow.split('  smoke:\n', 1)[1]
        matrices = [json.loads(value) for value in re.findall(r"'(\[[^']*\])'", smoke)]
        self.assertEqual([len(value) for value in matrices], [2, 4, 4, 8])
        self.assertEqual({(row['api'], row['shard']) for row in matrices[0]}, {('26', 0), ('37.2', 0)})
        self.assertIn("SMOKE_SHARD_COUNT: ${{ inputs.mode == 'daily' && '1' || '4' }}", smoke)
        self.assertIn('scripts/daily_release.py verify', smoke)
        self.assertIn('DAILY_API: ${{ matrix.api }}', smoke)
        self.assertIn('代鱼日常Release证据', smoke)
        self.assertIn('daily_release.py" result', (ROOT / 'scripts/smoke_boot.sh').read_text())


if __name__ == '__main__':
    unittest.main()
