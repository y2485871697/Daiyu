package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DictationStatusNotice
import app.daiyu.android.voice.ComposerDictationState
import app.daiyu.android.voice.DictationDraftTarget

class DictationStatusNoticeTest : WiredActivityTestCase() {
    fun testProductionStatusDoesNotLeaveTextOrMoveComposer() {
        val state = mutableStateOf(ComposerDictationState())
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column {
                    DictationStatusNotice(state.value, engineeringUi = false)
                    Box(Modifier.size(40.dp).testTag("composer-position"))
                }
            }
        } }
        val before = node("composer-position").fetchSemanticsNode().boundsInRoot
        val target = DictationDraftTarget(null, 1L, 1L)
        for (next in listOf(
            ComposerDictationState(target, status = "正在准备听写…"),
            ComposerDictationState(target, listening = true, status = "正在听写"),
            ComposerDictationState(status = "听写失败；草稿未更改，请手动重试"),
            ComposerDictationState(status = "音频释放失败，请重启应用"),
            ComposerDictationState(),
        )) {
            ui.runOnIdle { state.value = next }
            node("dictation-status").assertDoesNotExist()
            assertEquals(before, node("composer-position").fetchSemanticsNode().boundsInRoot)
        }
    }

    fun testEngineeringStatusRemainsAvailableForDiagnostics() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                DictationStatusNotice(ComposerDictationState(status = "系统语音识别服务不可用"), engineeringUi = true)
            }
        } }
        node("dictation-status").assertTextEquals("系统语音识别服务不可用")
    }
}
