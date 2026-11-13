package app.daiyu.android.ui.screens.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalTaskOutputTest {
    @Test fun backgroundPreparationPreservesAnsiStylesAndNormalizesLineEndings() {
        val color = androidx.compose.ui.graphics.Color.White
        val styled = prepareTerminalStyledOutput("plain\r\n\u001b[1mBOLD\u001b[0m", color)
        assertEquals("plain\nBOLD", styled.text)
        assertTrue(styled.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertEquals("", prepareTerminalStyledOutput("", color).text)
        assertEquals("after", prepareTerminalStyledOutput("before\r\u001b[2Kafter", color).text)
    }

    @Test fun emptyAndPlainTextAreNotTurnedIntoSuccessReceipts() {
        assertEquals("", terminalTaskOutput(""))
        assertEquals("观察未确认", terminalTaskOutput("观察未确认"))
        assertEquals("任务操作未完成，结果未确认", terminalTaskOutput("""{"ok":false}"""))
        assertEquals("暂无日志输出", terminalTaskOutput("""{"ok":true,"history_read_only":true}"""))
    }
    @Test fun errorRemainsVisibleAlongsideOutputAndMissingValuesStayAbsent() {
        assertEquals("partial\nstop_failed", terminalTaskOutput("""{"ok":false,"stdout":"partial","error":"stop_failed"}"""))
        assertEquals("暂无日志输出", terminalTaskOutput("""{"ok":true,"stdout":null,"error":null}"""))
    }
    @Test fun base64OnlyHistoryWorksAndMalformedDataDoesNotPretendToBeSuccess() {
        assertEquals("hello", terminalTaskOutput("""{"ok":true,"stdout_base64":"aGVsbG8="}"""))
        assertEquals("任务日志解码失败", terminalTaskOutput("""{"ok":true,"stdout_base64":"???"}"""))
        assertEquals("preferred", terminalTaskOutput("""{"ok":true,"stdout":"preferred","stdout_base64":"???"}"""))
    }
    @Test fun partialOutputCannotHideUnknownOrUnconfirmedStop() {
        assertEquals("partial\n任务操作未完成，结果未确认", terminalTaskOutput("""{"ok":false,"stdout":"partial"}"""))
        assertEquals("partial\n任务尚未确认停止", terminalTaskOutput("""{"ok":true,"stopped":false,"stdout":"partial"}"""))
        assertEquals("partial", terminalTaskOutput("""{"ok":true,"history_read_only":true,"execution":"unknown","stdout":"partial"}"""))
    }
    @Test fun controlModifierProducesTerminalBytesWithoutCommandNewline() {
        assertEquals("\u0003", terminalControlText("c"))
        assertEquals("\u0004", terminalControlText("D"))
        assertEquals("\u001b", terminalControlText("["))
        assertEquals("\u0000", terminalControlText(" "))
        assertEquals("\u007f", terminalControlText("?"))
    }
    @Test fun oscHyperlinksAndTitlesKeepTheVisibleTextBetweenSequences() {
        val linked = "\u001b]8;;https://example.invalid\u001b\\VISIBLE\u001b]8;;\u001b\\"
        assertEquals("VISIBLE", terminalPlainOutput(linked))
        assertEquals("VISIBLE", terminalTaskOutput(org.json.JSONObject().put("ok", true).put("stdout", linked).toString()))
        assertEquals("body", terminalPlainOutput("\u001b]0;title\u001b\\body\u001b]0;next\u0007"))
    }

    @Test fun historicalSummarySeparatesConfirmedSuccessFailureAndMissingExit() {
        assertEquals("状态未知", terminalTaskHistorySummary(null))
        assertEquals("已完成", terminalTaskHistorySummary(0))
        assertEquals("执行失败（退出码 1）", terminalTaskHistorySummary(1))
        assertEquals("执行失败（退出码 127）", terminalTaskHistorySummary(127))
    }

}
