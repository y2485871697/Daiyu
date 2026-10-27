package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectTimelineLabelTest {
    @Test fun runningShowsTheElapsedMinutesAndSeconds() {
        assertEquals("正在重连：0:00", reconnectLabel("running", 0))
        assertEquals("正在重连：0:01", reconnectLabel("running", 1000))
        assertEquals("正在重连：10:23", reconnectLabel("running", 623_000))
        assertEquals("正在重连：10:24", reconnectLabel("running", 624_000))
    }
    @Test fun terminalLabelsReplaceTheRunningClock() {
        assertEquals("已重连", reconnectLabel("succeeded", 623_000))
        assertEquals("已停止重连", reconnectLabel("stopped", 623_000))
        assertEquals("重连失败", reconnectLabel("failed", 623_000))
    }
    @Test fun formattingRetainsHoursAndNeverFormatsANegativeDuration() {
        assertEquals("0:00", formatReconnectElapsed(-1))
        assertEquals("59:59", formatReconnectElapsed(3_599_999))
        assertEquals("1:00:00", formatReconnectElapsed(3_600_000))
        assertEquals("1:10:23", formatReconnectElapsed(4_223_000))
    }
}
