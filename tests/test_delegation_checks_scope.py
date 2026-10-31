import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import CLOUD_SCOPES, SCOPES, DELEGATION_PACKAGES, verify_xml, validate_record, verify_unit_matrix
from unittest.mock import patch


class DelegationChecksScopeTest(unittest.TestCase):
    def archive(self, folder, omit=None, outcome='EXECUTED', extra=False):
        path = Path(folder) / 'reports.zip'
        with zipfile.ZipFile(path, 'w') as z:
            for index, (module, package) in enumerate(DELEGATION_PACKAGES):
                if index == omit:
                    continue
                report = 'core-domain/build/test-results/test' if module == 'core-domain' else 'app/build/test-results/testDebugUnitTest'
                xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + package + 'Fixture" name="ok"/></testsuite>'
                z.writestr(report + '/TEST-' + str(index) + '.xml', xml)
            if extra:
                z.writestr('app/build/test-results/testDebugUnitTest/TEST-unrelated.xml',
                    '<testsuite tests="1"><testcase classname="unrelated.Fixture" name="ok"/></testsuite>')
            z.writestr('androidtest-compile.json', json.dumps(dict(task=':app:compileDebugAndroidTestKotlin',
                outcome=outcome, instrumentation_run=False)))
        return path

    def test_scope_is_explicit_and_does_not_expand_full_checks_or_pc_scopes(self):
        self.assertEqual(set(SCOPES), {'unit', 'lint'})
        tasks = CLOUD_SCOPES['delegation'].split()
        self.assertIn(':app:compileDebugAndroidTestKotlin', tasks)
        self.assertFalse(any('assemble' in task or 'connected' in task for task in tasks))
        self.assertLess(tasks.index(':core-domain:test'), tasks.index('--tests'))
        self.assertIn('--tests app.daiyu.android.integration.tools.*', CLOUD_SCOPES['delegation'])
        record = dict(source_sha='a'*40, config_id='b'*64, scope='delegation', task=CLOUD_SCOPES['delegation'],
            check_id='child-tools', check_attempt=1, exit_code=0, result='passed')
        validate_record(record, 'a'*40, 'b'*64, 'delegation')
        with self.assertRaises(ValueError):
            validate_record(record, 'a'*40, 'b'*64, 'unit')

    def test_all_affected_groups_and_compile_are_required(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(len(DELEGATION_PACKAGES), verify_xml(self.archive(folder), 'delegation')['tests_passed'])
            for index in range(len(DELEGATION_PACKAGES)):
                with self.subTest(missing=index), self.assertRaisesRegex(ValueError, 'coverage_missing|module_report_missing'):
                    verify_xml(self.archive(folder, omit=index), 'delegation')

    def test_unrelated_tests_do_not_substitute_coverage(self):
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(ValueError, 'unexpected_test'):
            verify_xml(self.archive(folder, extra=True), 'delegation')

    def test_no_source_and_skipped_are_not_compile_success(self):
        with tempfile.TemporaryDirectory() as folder:
            for outcome in ('NO-SOURCE', 'SKIPPED', 'FAILED'):
                with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, 'compile_not_verified'):
                    verify_xml(self.archive(folder, outcome=outcome), 'delegation')
            for outcome in ('UP-TO-DATE', 'FROM-CACHE'):
                self.assertEqual(len(DELEGATION_PACKAGES), verify_xml(self.archive(folder, outcome=outcome), 'delegation')['tests_passed'])

    def test_workflow_binds_main_and_runs_matrix_verifier_without_gradle(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build.yml').read_text(encoding='utf-8')
        self.assertIn("if: inputs.mode == 'checks' && github.ref == 'refs/heads/main'", workflow)
        matrix = workflow.split('  delegation-matrix:', 1)[1].split('  build:', 1)[0]
        self.assertIn('needs: checks', matrix)
        self.assertIn('scripts/cooperation/verify_delegation_matrix.py', matrix)
        self.assertIn('代鱼检查报告-ubuntu-26.04', matrix)
        self.assertIn('代鱼检查报告-windows-latest', matrix)
        self.assertNotIn('gradlew', matrix)

    def test_windows_skip_requires_the_same_linux_method_exactly_once(self):
        with tempfile.TemporaryDirectory() as folder:
            windows_dir = Path(folder) / 'windows'; linux_dir = Path(folder) / 'linux'
            windows_dir.mkdir(); linux_dir.mkdir()
            windows = self.archive(windows_dir); linux = self.archive(linux_dir)
            group = next(index for index, item in enumerate(DELEGATION_PACKAGES) if item[1] == 'app.daiyu.android.integration.tools.')
            name = 'app/build/test-results/testDebugUnitTest/TEST-' + str(group) + '.xml'
            classname = DELEGATION_PACKAGES[group][1] + 'Fixture'
            with zipfile.ZipFile(windows) as z:
                entries = {n: z.read(n) for n in z.namelist()}
            entries[name] = ('<testsuite tests="2" failures="0" errors="0" skipped="1">'
                '<testcase classname="' + classname + '" name="ok"><skipped message="unsupported filesystem"/></testcase>'
                '<testcase classname="' + classname + '" name="other"/></testsuite>').encode()
            with zipfile.ZipFile(windows, 'w') as z:
                for n, data in entries.items(): z.writestr(n, data)
            with patch('evidence.read_json', return_value={classname: ['ok']}):
                self.assertEqual(1, verify_unit_matrix(windows, linux, scope='delegation')['windows_skips_verified_on_linux'])
                with zipfile.ZipFile(linux) as z:
                    linux_entries = {n: z.read(n) for n in z.namelist()}
                for replacement in (linux_entries[name].replace(b'name="ok"', b'name="missing"'),
                    linux_entries[name].replace(b'tests="1"', b'tests="2"').replace(b'</testsuite>',
                        ('<testcase classname="' + classname + '" name="ok"/></testsuite>').encode()),
                    linux_entries[name].replace(b'skipped="0"', b'skipped="1"').replace(b'name="ok"/>', b'name="ok"><skipped/></testcase>')):
                    with zipfile.ZipFile(linux, 'w') as z:
                        for n, data in linux_entries.items(): z.writestr(n, replacement if n == name else data)
                    with self.assertRaises(ValueError): verify_unit_matrix(windows, linux, scope='delegation')
