package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CopyOnWriteArrayList

class ToolCardPaintFeedbackTest : WiredActivityTestCase() {
    fun testCardDoesNotReportUntilDrawnAndReportsOnlyOnceAfterReopen() {
        val paint = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        val scope = WorkflowVisualScope(baseline = false)
        val reported = CopyOnWriteArrayList<String>()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalWorkflowVisualScope provides scope,
                    LocalToolCardPaintFeedback provides { id, header ->
                        if (header.isAttached) { reported += id; true } else false
                    }) {
                    Box(Modifier.drawWithContent { if (paint.value) drawContent() }) {
                        if (mounted.value) WorkStepRow(WorkStepUi("live", "工具", "详情"))
                        CompositionLocalProvider(LocalWorkflowVisualScope provides WorkflowVisualScope(baseline = true)) {
                            WorkStepRow(WorkStepUi("history", "旧工具", "旧详情"))
                        }
                    }
                }
            }
        } }
        ui.waitForIdle()
        mainThread { assertTrue(reported.isEmpty()); paint.value = true }
        ui.waitUntil(5_000) { reported.isNotEmpty() }
        mainThread { assertEquals(listOf("live"), reported); mounted.value = false }
        ui.waitForIdle()
        mainThread { mounted.value = true }
        ui.waitForIdle()
        mainThread { assertEquals(listOf("live"), reported) }
    }
}
