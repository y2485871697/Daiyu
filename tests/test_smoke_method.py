"""Single-method diagnostics keep source, inventory and actual runner identity exact."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/acceptance'))
from smoke_method import diagnostic_selection
from instrumentation_result import instrumentation, ResultError

NAME = 'app.daiyu.android.SampleTest'
TARGET = NAME + '#testFirst'
ENV = dict(SMOKE_MODE='smoke', SMOKE_DIAGNOSTIC='true', SMOKE_TEST_CLASSES=NAME,
           SMOKE_TEST_METHOD='testFirst', SMOKE_SHARD_COUNT='1', SMOKE_SHARD_INDEX='0')
SOURCE = ('package app.daiyu.android\nclass SampleTest : TestCase() {\n'
          '    fun testFirst() {}\n    fun testSecond() {}\n}\n')


def report(name=NAME, method='testFirst', statuses=(1, 0), total=1):
    frames = ''.join(f'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
                     f'INSTRUMENTATION_STATUS: class={name}\n'
                     f'INSTRUMENTATION_STATUS: test={method}\n'
                     f'INSTRUMENTATION_STATUS: numtests={total}\n'
                     f'INSTRUMENTATION_STATUS: current=1\n'
                     f'INSTRUMENTATION_STATUS_CODE: {status}\n' for status in statuses)
    return (frames + f'OK ({total} tests)\nINSTRUMENTATION_CODE: -1\n').encode()


class SmokeMethodTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'app/src/androidTest/kotlin/app/daiyu/android/SampleTest.kt'
        self.path.parent.mkdir(parents=True)
        self.path.write_text(SOURCE)

    def test_default_path_stays_unfiltered(self):
        self.assertIsNone(diagnostic_selection(self.root, dict(SMOKE_MODE='release')))

    def test_method_selects_one_known_test(self):
        selected = diagnostic_selection(self.root, ENV)
        self.assertEqual(1, selected['expected_tests'])
        self.assertEqual(TARGET, selected['target'])

    def test_blank_method_selects_only_one_class(self):
        selected = diagnostic_selection(self.root, dict(ENV, SMOKE_TEST_METHOD=''))
        self.assertEqual(2, selected['expected_tests'])
        self.assertEqual(NAME, selected['target'])

    def test_method_rejects_implicit_or_non_smoke_use(self):
        for changes in ({'SMOKE_DIAGNOSTIC': ''}, {'SMOKE_DIAGNOSTIC': 'false'},
                        {'SMOKE_MODE': 'release'}, {'SMOKE_MODE': 'candidate'},
                        {'SMOKE_MODE': 'daily'}, {'SMOKE_MODE': 'compile'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                diagnostic_selection(self.root, dict(ENV, **changes))

    def test_single_shard_is_mandatory(self):
        for changes in ({'SMOKE_SHARD_COUNT': '4'}, {'SMOKE_SHARD_INDEX': '1'},
                        {'SMOKE_SHARD_COUNT': '0'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                diagnostic_selection(self.root, dict(ENV, **changes))

    def test_no_wildcards_lists_whitespace_or_class_method_injection(self):
        for value in (NAME + ',' + NAME, NAME + '#testFirst', ' ' + NAME, NAME + ' ',
                      'app.daiyu.android.*', '../SampleTest', '', NAME + '\n'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                diagnostic_selection(self.root, dict(ENV, SMOKE_TEST_CLASSES=value))
        for value in ('*', 'testFirst,testSecond', 'testFirst#testSecond', ' testFirst',
                      'testFirst ', 'testFirst\n', 'testMissing', 'testFirst;echo bad'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                diagnostic_selection(self.root, dict(ENV, SMOKE_TEST_METHOD=value))

    def test_source_must_be_unique_and_match_package_class_and_method(self):
        for text in (SOURCE.replace('app.daiyu.android', 'other.package'),
                     SOURCE.replace('class SampleTest', 'class OtherTest'),
                     SOURCE.replace('testFirst', 'testSecond'),
                     SOURCE + 'class OtherTest : TestCase() {}\n', ''):
            self.path.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                diagnostic_selection(self.root, ENV)

    def test_java_junit3_source_is_supported(self):
        self.path.unlink()
        java = self.root / 'app/src/androidTest/java/app/daiyu/android/SampleTest.java'
        java.parent.mkdir(parents=True)
        java.write_text('package app.daiyu.android;\npublic class SampleTest extends TestCase {\n'
                        '    public void testFirst() {}\n}\n')
        self.assertEqual(TARGET, diagnostic_selection(self.root, ENV)['target'])

    def test_duplicate_or_symlinked_source_is_rejected(self):
        java = self.root / 'app/src/androidTest/java/app/daiyu/android/SampleTest.java'
        java.parent.mkdir(parents=True)
        java.write_text('ignored duplicate')
        with self.assertRaises(ValueError):
            diagnostic_selection(self.root, ENV)
        java.unlink()
        other = self.root / 'source.kt'
        self.path.rename(other)
        self.path.symlink_to(other)
        with self.assertRaises(ValueError):
            diagnostic_selection(self.root, ENV)

    def test_real_selection_script_emits_exact_method_and_nonacceptance_receipt(self):
        script = (ROOT / 'scripts/smoke_boot.sh').read_text().split("<<'PYCOUNT'", 1)[1].split('\nPYCOUNT', 1)[0]
        (self.root / 'smoke').mkdir()
        env = dict(os.environ, **ENV, SMOKE_SCRIPT_DIR=str(ROOT / 'scripts'))
        result = subprocess.run([sys.executable, '-c', script], cwd=self.root, env=env,
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['1', TARGET], result.stdout.splitlines())
        selected = json.loads((self.root / 'smoke/selection.json').read_text())
        self.assertEqual([TARGET], selected['selected_targets'])
        self.assertEqual(1, selected['expected_tests'])
        self.assertEqual('planned', selected['status'])
        self.assertFalse(selected['formal_acceptance'])
        self.assertFalse(selected['full_regression_passed'])

    def test_public_junit3_declarations_use_validated_inventory(self):
        self.path.write_text(SOURCE.replace('class SampleTest', 'public class SampleTest')
                            .replace('fun test', 'public fun test').replace('testFirst()', 'testFirst ()'))
        self.test_real_selection_script_emits_exact_method_and_nonacceptance_receipt()

    def test_runtime_requires_exact_started_and_passed_method(self):
        self.assertEqual(1, instrumentation(report(), 1, TARGET))
        self.assertEqual(1, instrumentation(report().replace(b'\n', b'\r\n'), 1, TARGET))

    def test_same_count_wrong_class_or_method_does_not_pass(self):
        for raw in (report(name='app.daiyu.android.OtherTest'), report(method='testSecond')):
            with self.subTest(raw=raw), self.assertRaises(ResultError):
                instrumentation(raw, 1, TARGET)

    def test_missing_duplicate_reversed_or_unpaired_status_does_not_pass(self):
        for statuses in ((), (0,), (1,), (0, 1), (1, 0, 1, 0)):
            with self.subTest(statuses=statuses), self.assertRaises(ResultError):
                instrumentation(report(statuses=statuses), 1, TARGET)
        raw = report().replace(b'INSTRUMENTATION_STATUS: test=testFirst\n', b'', 1)
        with self.assertRaises(ResultError):
            instrumentation(raw, 1, TARGET)

    def test_zero_extra_tests_failure_and_skip_never_pass(self):
        for raw in (report(total=0), report(total=2), report() + b'FAILURES!!!\n',
                    report().replace(b'CODE: 0', b'CODE: -3')):
            with self.subTest(raw=raw), self.assertRaises(ResultError):
                instrumentation(raw, 1, TARGET)
        with self.assertRaises(ResultError):
            instrumentation(report(total=2), 2, TARGET)

    def test_duplicate_metadata_is_rejected(self):
        raw = report().replace(b'INSTRUMENTATION_STATUS: class=' + NAME.encode() + b'\n',
                               (b'INSTRUMENTATION_STATUS: class=' + NAME.encode() + b'\n') * 2, 1)
        with self.assertRaises(ResultError):
            instrumentation(raw, 1, TARGET)

    def test_each_frame_requires_its_own_identity_and_counts(self):
        for key in ('id', 'numtests', 'current'):
            prefix = ('INSTRUMENTATION_STATUS: ' + key + '=').encode()
            raw = b'\n'.join(line for line in report().split(b'\n') if not line.startswith(prefix))
            with self.subTest(key=key), self.assertRaises(ResultError):
                instrumentation(raw, 1, TARGET)
        for key, value in (('id', 'OtherRunner'), ('current', '2'), ('numtests', '2')):
            before = ('INSTRUMENTATION_STATUS: ' + key + '=' + ('AndroidJUnitRunner' if key == 'id' else '1')).encode()
            after = ('INSTRUMENTATION_STATUS: ' + key + '=' + value).encode()
            with self.subTest(key=key), self.assertRaises(ResultError):
                instrumentation(report().replace(before, after, 1), 1, TARGET)

    def test_legacy_summary_parser_is_unchanged_without_target(self):
        raw = b'OK (2 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertEqual(2, instrumentation(raw, 2))

    def test_workflow_is_single_api_reuses_both_apks_and_never_builds(self):
        text = (ROOT / '.github/workflows/smoke-diagnostic.yml').read_text()
        self.assertIn("options: ['26', '37.2']", text)
        self.assertIn("SMOKE_SHARD_COUNT: '1'", text)
        self.assertIn("SMOKE_SHARD_INDEX: '0'", text)
        self.assertIn('SOURCE_VARIANT: compile', text)
        self.assertIn('name: 代鱼增量Release包', text)
        self.assertIn('run-id: ${{ inputs.source_run_id }}', text)
        self.assertIn('verify_smoke_source.py --verify-checkout-only', text)
        self.assertIn('python3 .smoke-validation-tools/scripts/verify_smoke_source.py\n', text)
        self.assertIn('sha256sum -c SHA256SUMS', text)
        self.assertIn('code=$?;', text)
        self.assertIn('exit "$code"', text)
        for forbidden in ('./gradlew', 'assemble', 'continue-on-error', 'matrix:', 'strategy:'):
            self.assertNotIn(forbidden, text)
        smoke = (ROOT / 'scripts/smoke_boot.sh').read_text()
        self.assertIn('result_options=(--expected-target "$test_classes")', smoke)
        self.assertIn('"${result_options[@]}"', smoke)
