"""Read-only historical terminal facts must reach the task rows without restoring control."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = ROOT / 'app/src/main/kotlin/app/daiyu/android/terminal'

class TerminalJobStatusContractTest(unittest.TestCase):
    def test_historical_rows_use_confirmed_exit_instead_of_a_hardcoded_unknown(self):
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('terminalTaskHistorySummary(record.exitCode)', activity)
        self.assertIn('record.copy(exitCode = confirmedExit)', activity)
        self.assertNotIn('record.command ?: record.jobId, "状态未知"', activity)
        output = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/terminal/TerminalTaskOutput.kt').read_text()
        self.assertIn('0 -> "已完成"', output)
        self.assertIn('null -> "状态未知"', output)
        self.assertIn('执行失败（退出码 $exitCode）', output)

    def test_read_only_evidence_never_constructs_a_journal_or_process(self):
        reader = (TERMINAL / 'TerminalJobHistoryReader.kt').read_text()
        self.assertNotIn('OperationJournal(', reader)
        self.assertNotIn('ProcessBuilder(', reader)
        self.assertIn('OsConstants.O_NOFOLLOW', reader)
        self.assertIn('OsConstants.O_NONBLOCK', reader)
        self.assertIn('/proc/self/fd/${anchor.fd}/terminal-journal', reader)
        self.assertIn('MAX_JOURNAL_BYTES = 4 * 1024 * 1024', reader)
        self.assertIn('current.copy(exitCode = null) == original.copy(exitCode = null)', reader)

    def test_completion_is_observed_without_a_later_poll_and_preserves_uncertainty(self):
        registry = (TERMINAL / 'TerminalRegistry.kt').read_text()
        self.assertIn('job.pumpFinished = true', registry)
        self.assertIn('synchronized(jobs) { releaseFinishedResources(job) }', registry)
        self.assertIn('it.execution == "executed" && it.exitCode != null', registry)
        self.assertIn('value.put("completion", JSONObject().put("format", 1)', registry)
        evidence = (TERMINAL / 'TerminalJobCompletionEvidence.kt').read_text()
        self.assertIn('require(matches.size == 1)', evidence)
        self.assertIn('value.opt("output_complete") == true', evidence)
        self.assertIn('!record.scopeDrainRequired || value.opt("scope_drain_confirmed") == true', evidence)
        self.assertIn('require(code is Int || code is Long)', evidence)

    def test_zero_apk_checks_include_ui_projection_and_real_device_status_sources(self):
        workflow = (ROOT / '.github/workflows/terminal-retention-checks.yml').read_text()
        self.assertIn("--tests 'app.daiyu.android.ui.screens.terminal.TerminalTaskOutputTest'", workflow)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/terminal/TerminalCommandPagerTest.kt').read_text()
        self.assertIn('testHistoricalStatusesUseRealExitCodesAndRecoverLegacyJournalWithoutReplay', device)
        self.assertIn('testAsyncCompletionPersistsWithoutAnyPollOrFurtherCommand', device)
        self.assertIn('testLegacyJournalLinksAndFifoAreRejectedWithoutLosingReadOnlyLogs', device)
