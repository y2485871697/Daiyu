package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.catalog.ModelConnectionResult
import app.daiyu.android.model.catalog.RemoteModelFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsConnectionMessageTest {
    @Test fun speechSuccessIsConcise() {
        assertEquals("语音合成成功", connectionTestMessage(ModelConnectionResult.SpeechConnected, speechOnly = true))
    }

    @Test fun catalogSuccessKeepsActualModelCount() {
        for (count in listOf(0, 1, 11)) {
            assertEquals("连接成功，返回 $count 个模型", connectionTestMessage(ModelConnectionResult.Connected(count)))
        }
    }

    @Test fun everyFailureUsesOnlyTheMatchingShortResult() {
        for (reason in RemoteModelFailure.entries) {
            val result = ModelConnectionResult.Failed(reason)
            assertEquals("连接失败", connectionTestMessage(result))
            assertEquals("语音合成失败", connectionTestMessage(result, speechOnly = true))
        }
    }
}
