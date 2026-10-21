package app.daiyu.android.ui.screens.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalTaskOutputTest {
    @Test fun emptyAndPlainTextAreNotTurnedIntoSuccessReceipts() {
        assertEquals("", terminalTaskOutput(""))
        assertEquals("观察未确认", terminalTaskOutput("观察未确认"))
        assertEquals("任务操作未完成，结果未确认", terminalTaskOutput("""{"ok":false}"""))
        assertEquals("暂无日志 · 历史任务状态未知", terminalTaskOutput("""{"ok":true,"history_read_only":true}"""))
    }
    @Test fun errorRemainsVisibleAlongsideOutputAndMissingValuesStayAbsent() {
        assertEquals("partial\nstop_failed", terminalTaskOutput("""{"ok":false,"stdout":"partial","error":"stop_failed"}"""))
        assertEquals("已收到任务回执，未返回日志", terminalTaskOutput("""{"ok":true,"stdout":null,"error":null}"""))
    }
    @Test fun base64OnlyHistoryWorksAndMalformedDataDoesNotPretendToBeSuccess() {
        assertEquals("hello", terminalTaskOutput("""{"ok":true,"stdout_base64":"aGVsbG8="}"""))
        assertEquals("任务日志解码失败", terminalTaskOutput("""{"ok":true,"stdout_base64":"???"}"""))
        assertEquals("preferred", terminalTaskOutput("""{"ok":true,"stdout":"preferred","stdout_base64":"???"}"""))
    }
    @Test fun partialOutputCannotHideUnknownOrUnconfirmedStop() {
        assertEquals("partial\n任务操作未完成，结果未确认", terminalTaskOutput("""{"ok":false,"stdout":"partial"}"""))
        assertEquals("partial\n任务尚未确认停止", terminalTaskOutput("""{"ok":true,"stopped":false,"stdout":"partial"}"""))
        assertEquals("partial\n历史任务状态未知", terminalTaskOutput("""{"ok":true,"history_read_only":true,"execution":"unknown","stdout":"partial"}"""))
    }
    @Test fun controlModifierProducesTerminalBytesWithoutCommandNewline() {
        assertEquals("\u0003", terminalControlText("c"))
        assertEquals("\u0004", terminalControlText("D"))
        assertEquals("\u001b", terminalControlText("["))
        assertEquals("\u0000", terminalControlText(" "))
        assertEquals("\u007f", terminalControlText("?"))
    }

}
