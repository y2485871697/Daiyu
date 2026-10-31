"""Voice-only cloud verification must not widen checks or build install packages."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]

class CloudVoiceAccountWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / '.github/workflows/voice-account-checks.yml').read_text()

    def test_single_cloud_job_binds_main_and_requested_revision(self):
        for text in ('workflow_dispatch:', 'expected_sha:', 'required: true', "github.ref == 'refs/heads/main'",
                     'runs-on: ubuntu-26.04', '--verify-revision-only', 'cancel-in-progress: false'):
            self.assertIn(text, self.source)
        self.assertNotIn('matrix:', self.source)
        self.assertNotIn('windows-latest', self.source)

    def test_explicit_unit_filters_match_exact_report_classes(self):
        classes = re.findall(r"--tests '([^']+)'", self.source)
        expected = {
            'app.daiyu.android.AccountPageFeedbackTest', 'app.daiyu.android.VoiceAccountPanelLeafTest',
            *('app.daiyu.android.voice.account.' + n for n in ('PersonalVoiceImportTest', 'PersonalVoiceNameSyncTest',
              'PersonalVoiceSlotValidationTest', 'PersonalVoiceOverwriteTest', 'PersonalVoicesTest', 'DoubaoVoiceAliasesTest')),
        }
        self.assertEqual(expected, set(classes))
        self.assertEqual(len(expected), len(classes))
        for name in expected:
            self.assertIn(repr(name), self.source.split('expected = ', 1)[1])
        for text in ('shutil.rmtree(', 'len(names) == len(expected)', "case.attrib['classname']",
                     "('failures', 'errors', 'skipped')", 'key not in cases'):
            self.assertIn(text, self.source)

    def test_compilation_and_reports_do_not_run_devices_or_build_apks(self):
        for text in ('checks_only.init.gradle', '-PdaiyuCheckGraph=', ':app:compileDebugKotlin',
                     ':app:compileDebugAndroidTestKotlin', "('NO-SOURCE', 'SKIPPED')", 'apk_built=False',
                     'instrumentation_run=False', "('.apk', '.aab', '.apks')", 'if: always()',
                     'suite.countTestCases() > 0', 'not result.skipped', 'test_voice_direct_operations_contract.py'):
            self.assertIn(text, self.source)
        for forbidden in (':app:assemble', ':app:bundle', ':app:connected', ':app:install', ':app:lint', ':core-domain:test'):
            self.assertNotIn(forbidden, self.source)

if __name__ == '__main__':
    unittest.main()
