import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
import cloud_checks
from evidence import SCOPES, UNIT_REPORTS, validate_record, verify_xml

XML = '<testsuite tests="1" errors="0" failures="0" skipped="0"><testcase name="ok"/></testsuite>'


class UnitScopeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.archive = Path(self.tmp.name) / 'reports.zip'

    def tearDown(self):
        self.tmp.cleanup()

    def write_reports(self, entries):
        with zipfile.ZipFile(self.archive, 'w') as z:
            for name, data in entries:
                z.writestr(name, data)

    def full_reports(self):
        return [(p + '/TEST-demo.xml', XML) for p in UNIT_REPORTS]

    def test_dual_module_reports_pass(self):
        self.write_reports(self.full_reports())
        self.assertEqual(verify_xml(self.archive, 'unit'), {'tests_passed': 2, 'tests_skipped': 0, 'skipped_tests': []})

    def test_missing_each_module_rejected(self):
        for report in self.full_reports():
            with self.subTest(report=report[0]):
                self.write_reports([report])
                with self.assertRaisesRegex(ValueError, 'unit_module_report_missing'):
                    verify_xml(self.archive, 'unit')

    def test_empty_app_suite_rejected(self):
        reports = self.full_reports()
        reports[1] = (reports[1][0], '<testsuite tests="0"/>')
        self.write_reports(reports)
        with self.assertRaisesRegex(ValueError, 'unit_module_report_missing'):
            verify_xml(self.archive, 'unit')

    def test_failed_error_or_skipped_app_rejected(self):
        for field, child in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
            with self.subTest(field=field):
                reports = self.full_reports()
                reports[1] = (reports[1][0], XML.replace(field + '="0"', field + '="1"').replace('name="ok"/>', 'name="ok"><' + child + '/></testcase>'))
                self.write_reports(reports)
                with self.assertRaisesRegex(ValueError, 'unit_report_failed_or_missing'):
                    verify_xml(self.archive, 'unit')

    def test_unsafe_path_still_rejected(self):
        self.write_reports(self.full_reports() + [('../TEST-unsafe.xml', XML)])
        with self.assertRaisesRegex(ValueError, 'unsafe_report_path'):
            verify_xml(self.archive, 'unit')

    def test_old_unit_scope_rejected(self):
        record = dict(source_sha='a'*40, config_id='b'*64, scope='unit',
                      task=':core-domain:test', check_id='test', check_attempt=1,
                      exit_code=0, result='passed')
        with self.assertRaisesRegex(ValueError, 'check_identity_or_terminal_mismatch'):
            validate_record(record, 'a'*40, 'b'*64, 'unit')
        record['task'] = SCOPES['unit']
        validate_record(record, 'a'*40, 'b'*64, 'unit')


class CloudUnitScopeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.cwd = Path.cwd()
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def run_checks(self, scope, missing=None):
        def gradle(command, **kwargs):
            self.command = command
            for i, name in enumerate(UNIT_REPORTS):
                if name == missing:
                    continue
                folder = self.root / name
                folder.mkdir(parents=True, exist_ok=True)
                (folder / 'TEST-demo.xml').write_text(XML.replace('name="ok"', f'name="module-{i}"'))
            lint = self.root / 'app/build/reports'
            lint.mkdir(parents=True, exist_ok=True)
            (lint / 'lint-results-debug.xml').write_text('<issues/>')
            kwargs['stdout'].write(b'BUILD SUCCESSFUL')
            return type('Result', (), {'returncode': 0})()

        with (patch.dict(os.environ, dict(GITHUB_SHA='a'*40, EXPECTED_SHA='a'*40,
                                        GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1', RUNNER_OS='Linux')),
             patch('cloud_checks.configuration', return_value='b'*64), \
             patch('cloud_checks.subprocess.check_output', side_effect=['a'*40, b'']), \
             patch('cloud_checks.subprocess.run', side_effect=gradle)):
            code = cloud_checks.run(scope)
        folder = self.root / 'cooperation-reports' / scope
        return code, json.loads((folder / 'record.json').read_text()), folder / 'reports.zip'

    def test_distinct_task_arguments_and_both_same_named_reports(self):
        code, record, archive = self.run_checks('unit')
        self.assertEqual(code, 0)
        self.assertEqual(self.command[-2:], [':core-domain:test', ':app:testDebugUnitTest'])
        self.assertEqual(record['command'], self.command)
        self.assertEqual(record['task'], SCOPES['unit'])
        with zipfile.ZipFile(archive) as z:
            self.assertEqual(set(z.namelist()), {p + '/TEST-demo.xml' for p in UNIT_REPORTS})
            self.assertNotEqual(z.read(UNIT_REPORTS[0] + '/TEST-demo.xml'), z.read(UNIT_REPORTS[1] + '/TEST-demo.xml'))
        self.assertEqual(verify_xml(archive, 'unit')['tests_passed'], 2)
        self.assertFalse(record['apk_built'])

    def test_gradle_success_without_app_reports_fails(self):
        code, record, _ = self.run_checks('unit', missing=UNIT_REPORTS[1])
        self.assertNotEqual(code, 0)
        self.assertEqual(record['result'], 'failed')

    def test_gradle_success_without_domain_reports_fails(self):
        code, record, _ = self.run_checks('unit', missing=UNIT_REPORTS[0])
        self.assertNotEqual(code, 0)
        self.assertEqual(record['result'], 'failed')

    def test_lint_scope_unchanged(self):
        code, record, archive = self.run_checks('lint')
        self.assertEqual(code, 0)
        self.assertEqual(self.command[-1], ':app:lintDebug')
        self.assertNotIn(':app:testDebugUnitTest', self.command)
        with zipfile.ZipFile(archive) as z:
            self.assertEqual(z.namelist(), ['lint-results-debug.xml'])
        self.assertEqual(verify_xml(archive, 'lint')['lint_errors'], 0)


if __name__ == '__main__':
    unittest.main()
