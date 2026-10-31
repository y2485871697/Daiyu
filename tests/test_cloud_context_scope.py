from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import CLOUD_SCOPES, SCOPES, UNIT_SCOPES, UNIT_REPORTS, CONTEXT_PACKAGES, CONTEXT_STORAGE_CLASSES, CONTEXT_RESOLUTION_CLASS, CONTEXT_TOOL_CLASS, verify_xml, validate_record


class ContextChecksScopeTest(unittest.TestCase):
    def archive(self, folder, omit=None, extra=False, skipped=False):
        path = Path(folder) / 'reports.zip'
        classes = [(CONTEXT_PACKAGES[0][0], CONTEXT_PACKAGES[0][1] + 'Fixture'),
                   (CONTEXT_PACKAGES[1][0], CONTEXT_PACKAGES[1][1] + 'Fixture'),
                   ('app', CONTEXT_RESOLUTION_CLASS), ('app', CONTEXT_TOOL_CLASS)]
        classes.extend(('app', name) for name in sorted(CONTEXT_STORAGE_CLASSES))
        with zipfile.ZipFile(path, 'w') as z:
            for index, (module, classname) in enumerate(classes):
                if classname == omit:
                    continue
                root = UNIT_REPORTS[0 if module == 'core-domain' else 1]
                skip = '<skipped message="not executed"/>' if skipped and classname == CONTEXT_RESOLUTION_CLASS else ''
                xml = '<testsuite tests="1" failures="0" errors="0" skipped="' + ('1' if skip else '0') + '"><testcase classname="' + classname + '" name="ok">' + skip + '</testcase></testsuite>'
                z.writestr(root + '/TEST-' + str(index) + '.xml', xml)
            z.writestr('androidtest-compile.json', '{"task":":app:compileDebugAndroidTestKotlin","outcome":"EXECUTED","instrumentation_run":false}')
            if extra:
                z.writestr(UNIT_REPORTS[1] + '/TEST-unrelated.xml', '<testsuite tests="1"><testcase classname="unrelated.Fixture" name="ok"/></testsuite>')
        return path

    def test_scope_is_cloud_only_targeted_and_never_builds_packages(self):
        self.assertEqual({'unit', 'lint'}, set(SCOPES))
        self.assertIn('context', UNIT_SCOPES)
        core, app = CLOUD_SCOPES['context'].split(':app:testDebugUnitTest')
        self.assertIn(':core-domain:test --tests app.daiyu.android.domain.context.*', core)
        self.assertIn('--tests app.daiyu.android.context.*', app)
        self.assertIn(':app:compileDebugAndroidTestKotlin', CLOUD_SCOPES['context'])
        for value in ('assemble', 'connected', 'lintDebug'):
            self.assertNotIn(value, CLOUD_SCOPES['context'])

    def test_both_modules_and_actual_new_resolution_regression_are_required(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(6, verify_xml(self.archive(folder), 'context')['tests_passed'])
            for name in [CONTEXT_PACKAGES[0][1] + 'Fixture', CONTEXT_RESOLUTION_CLASS, CONTEXT_TOOL_CLASS, *CONTEXT_STORAGE_CLASSES]:
                with self.subTest(missing=name), self.assertRaisesRegex(ValueError, 'coverage_missing|module_report_missing'):
                    verify_xml(self.archive(folder, omit=name), 'context')

    def test_unrelated_tests_cannot_substitute_targeted_coverage(self):
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(ValueError, 'unexpected_test'):
            verify_xml(self.archive(folder, extra=True), 'context')

    def test_skipped_resolution_regression_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
            verify_xml(self.archive(folder, skipped=True), 'context', platform='Linux')

    def test_androidtest_compile_evidence_cannot_be_missing_or_skipped(self):
        import json
        with tempfile.TemporaryDirectory() as folder:
            path = self.archive(folder)
            with zipfile.ZipFile(path) as source:
                entries = {n: source.read(n) for n in source.namelist() if n != 'androidtest-compile.json'}
            for outcome in (None, 'NO-SOURCE', 'SKIPPED'):
                with zipfile.ZipFile(path, 'w') as target:
                    for name, data in entries.items():
                        target.writestr(name, data)
                    if outcome is not None:
                        target.writestr('androidtest-compile.json', json.dumps(dict(
                            task=':app:compileDebugAndroidTestKotlin', outcome=outcome, instrumentation_run=False)))
                with self.assertRaises((ValueError, KeyError)):
                    verify_xml(path, 'context')

    def test_record_cannot_be_used_as_full_unit_evidence(self):
        record = dict(source_sha='a' * 40, config_id='b' * 64, scope='context', task=CLOUD_SCOPES['context'],
                      check_id='summary-binding', check_attempt=1, exit_code=0, result='passed')
        validate_record(record, 'a' * 40, 'b' * 64, 'context')
        with self.assertRaises(ValueError):
            validate_record(record, 'a' * 40, 'b' * 64, 'unit')

    def test_workflow_exposes_scope_and_runs_its_contract_on_the_cloud(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build.yml').read_text(encoding='utf-8')
        self.assertIn('options: [all, unit, lint, delegation, context]', workflow)
        self.assertIn('elif [[ "$CHECK_SCOPE" == "context" ]]', workflow)
        self.assertIn("'test_cloud*.py'", workflow)
        self.assertIn("'test_summary*.py'", workflow)
        self.assertIn("if: inputs.mode == 'checks' && github.ref == 'refs/heads/main'", workflow)
        self.assertIn('os: [ubuntu-26.04, windows-latest]', workflow)
