"""Windows POSIX omissions remain visible and require same-matrix Linux execution."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import UNIT_REPORTS, verify_unit_matrix, verify_xml

CLASS = 'app.daiyu.android.ControllerTerminalPreparationTest'
METHOD = 'linuxUnclaimedCancelReleasesButConfirmedCancelWaitsForBindingDrain'


class WindowsUnitSkipsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def archive(self, name, *, skipped=True, classname=CLASS, method=METHOD,
                reason='POSIX unavailable', field=None, omit=False, duplicate=False):
        path = self.root / name
        with zipfile.ZipFile(path, 'w') as z:
            for index, module in enumerate(UNIT_REPORTS):
                suite = ET.Element('testsuite', tests='1', failures='0', errors='0', skipped='0')
                ET.SubElement(suite, 'testcase', classname=f'Module{index}', name='portable')
                if index == 1 and not omit:
                    case = ET.SubElement(suite, 'testcase', classname=classname, name=method)
                    suite.set('tests', '2')
                    if skipped:
                        suite.set('skipped', '1')
                        ET.SubElement(case, 'skipped', message=reason)
                    if field:
                        suite.set(field, '1')
                        ET.SubElement(case, {'failures': 'failure', 'errors': 'error'}[field])
                    if duplicate:
                        ET.SubElement(suite, 'testcase', classname=classname, name=method)
                        suite.set('tests', '3')
                z.writestr(module + '/TEST-demo.xml', ET.tostring(suite))
        return path

    def test_windows_preserves_approved_skip_without_counting_it_passed(self):
        result = verify_xml(self.archive('windows.zip'), 'unit', platform='Windows')
        self.assertEqual((result['tests_passed'], result['tests_skipped']), (2, 1))
        self.assertEqual(result['skipped_tests'], [dict(classname=CLASS, name=METHOD, reason='POSIX unavailable')])

    def test_default_linux_and_unknown_platforms_reject_skips(self):
        for platform in (None, 'Linux', 'Darwin', 'windows'):
            with self.subTest(platform=platform), self.assertRaises(ValueError):
                verify_xml(self.archive('skip.zip'), 'unit', platform=platform)

    def test_unapproved_identity_or_empty_reason_rejected(self):
        for kwargs in ({'classname': 'Unapproved'}, {'method': 'newMethod'}, {'reason': ''}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                verify_xml(self.archive('skip.zip', **kwargs), 'unit', platform='Windows')

    def test_windows_failure_and_error_remain_fatal(self):
        for field in ('failures', 'errors'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_xml(self.archive('skip.zip', field=field), 'unit', platform='Windows')

    def test_corresponding_linux_execution_is_required(self):
        windows = self.archive('windows.zip')
        linux = self.archive('linux.zip', skipped=False)
        self.assertEqual(verify_unit_matrix(windows, linux), {'windows_skips_verified_on_linux': 1})
        for kwargs in ({'omit': True}, {'skipped': True}, {'skipped': False, 'duplicate': True}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                verify_unit_matrix(windows, self.archive('bad-linux.zip', **kwargs))

    def test_allowlist_exactly_lists_unique_test_identities(self):
        allowlist = json.loads((Path(__file__).resolve().parents[1] / 'scripts/cooperation/windows_unit_skips.json').read_text())
        self.assertEqual(sum(len(v) for v in allowlist.values()), 44)
        self.assertTrue(all(len(v) == len(set(v)) for v in allowlist.values()))


if __name__ == '__main__':
    unittest.main()
