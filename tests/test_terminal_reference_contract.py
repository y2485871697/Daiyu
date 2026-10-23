"""Source contracts only. Kotlin compilation and Android behavior are separate checks."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(relative):
    return (APP / relative).read_text(encoding='utf-8')


class TerminalReferenceContractTest(unittest.TestCase):
    def test_original_actions_and_new_theme(self):
        screen = source('ui/screens/terminal/TerminalScreen.kt')
        for label in ['会话', '后台任务', '控制台模式', '简洁模式', '停止', '复制命令', '复制输出', '重新填入']:
            self.assertIn('"' + label + '"', screen)
        self.assertIn('MaterialTheme.colorScheme', screen)
        self.assertIn('Modifier.size(18.dp)', screen)
        self.assertIn('LifecycleEventEffect(Lifecycle.Event.ON_RESUME)', screen)
        self.assertNotIn('SelectionContainer', screen)

    def test_sessions_keep_original_modal_actions(self):
        screen = source('ui/screens/terminal/TerminalScreen.kt')
        self.assertIn('onSelect = { onSelectSession(it); sessions = false }', screen)
        self.assertIn('onNew = { onCreateSession(); sessions = false }', screen)
        self.assertIn('onRestart = onRestart, onClose = onClose', screen)
        self.assertIn('testTag("terminal-session-list")', screen)
        self.assertIn('AssistantPreviewDialog(show = true, title = "会话"', screen)

    def test_console_keeps_hidden_input_and_shortcuts(self):
        console = source('ui/screens/terminal/TerminalConsole.kt')
        self.assertIn('Modifier.size(1.dp).alpha(.01f)', console)
        self.assertIn('value.composition == null', console)
        self.assertIn('"Esc" to', console)
        self.assertIn('"Ctrl" to', console)
        self.assertIn('"Tab" to', console)
        self.assertIn('if (buffer.applicationCursor)', console)
        self.assertNotIn('KeyboardType.Ascii', console)

    def test_logs_are_per_task_without_invented_page_controls(self):
        dialog = source('ui/screens/terminal/TerminalTasksDialog.kt')
        self.assertIn('mutableStateMapOf<String, String>()', dialog)
        self.assertIn('itemsIndexed(rows, key = { _, row -> row.id })', dialog)
        self.assertIn('testTag("terminal-task-list")', dialog)
        self.assertIn('AssistantPreviewDialog(show = true, title = "后台任务"', dialog)
        self.assertIn('heightIn(max = 160.dp)', dialog)
        self.assertNotIn('terminal-tasks-refresh', dialog)
        self.assertNotIn('terminal-tasks-next', dialog)
        output = source('ui/screens/terminal/TerminalTaskOutput.kt')
        self.assertNotIn('历史任务状态未知', output)
        self.assertNotIn('任务操作结果未确认', output)

    def test_prepare_precedes_running_block_and_protocol_uses_separate_fd(self):
        workspace = source('terminal/TerminalSessionWorkspace.kt')
        execute = workspace.split('private fun execute(', 1)[1].split('private fun startProtocol', 1)[0]
        self.assertLess(execute.index('prepareInput('), execute.index('TerminalCommandBlock('))
        self.assertIn('!result.ok', execute)
        self.assertIn('exec 9>&1', workspace)
        self.assertEqual(2, workspace.count('>&9\\r'))
        self.assertIn('delay(10_000)', workspace)
        self.assertIn('takeLast(200)', workspace)
        self.assertIn('combined.take(64_000)', workspace)

    def test_main_uses_retained_workspace(self):
        main = source('MainActivity.kt')
        self.assertIn('val workspace = ready.application.terminalSessions', main)
        self.assertIn('workspace.send(id, text, selected?.id)', main)
        self.assertNotIn('fun openTerminalAndroid(', main)
        self.assertNotIn('fun openTerminalLinux(', main)
        self.assertIn('record.command ?: record.jobId', main)
        self.assertIn('head.optLong("total_bytes") - 65536', main)

    def test_metadata_and_log_tail_preserve_existing_bounds_and_ownership(self):
        registry = source('terminal/TerminalRegistry.kt')
        self.assertIn('job.command.trim().take(2048)', registry)
        self.assertIn('check(bytes.size<=16384)', registry)
        jobs = source('integration/tools/TerminalJobSession.kt')
        self.assertIn('fun inspectTail(id: String)', jobs)
        self.assertIn('registry.inspectJob(id, (head.optLong("total_bytes") - 65536).coerceAtLeast(0), 65536)', jobs)
        controller = source('ChatController.kt')
        log = controller.split('internal fun terminalJobLogOnWorker', 1)[1].split('internal fun stopTerminalJobOnWorker', 1)[0]
        self.assertIn('original in retainedTerminalJobs', log)
        self.assertIn('original.plan.conversationId == conversation', log)
        self.assertIn('original.logTailOnWorker()', log)

    def test_incremental_output_is_wired_for_both_backends(self):
        host = source('terminal/TerminalEnvironmentHost.kt')
        self.assertEqual(2, host.count('session::readAvailable'))
        self.assertEqual(2, host.count('session::resize'))
        self.assertIn('current.pendingUtf8', host)
        self.assertIn('outputStart = outputStart + removed', host)
        self.assertIn('current.decoder.decode(java.nio.ByteBuffer.wrap(current.pendingUtf8), tail, true)', host)
        pty = source('terminal/PtySession.kt')
        self.assertIn('internal fun readAvailable()', pty)
        self.assertIn('uiOutput.clear(); uiBytes = 0', pty)


if __name__ == '__main__':
    unittest.main()
