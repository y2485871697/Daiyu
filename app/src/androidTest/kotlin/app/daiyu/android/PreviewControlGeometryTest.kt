package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton

class PreviewControlGeometryTest : WiredActivityTestCase() {
    fun testButtonFamiliesKeepTheirCssHeightWithoutScreenWidthScaling() {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column {
                            AssistantPreviewButton("确认", {}, Modifier.testTag("assistant-button"))
                            ConversationAgentButton("确认", modifier = Modifier.testTag("agent-button"), onClick = {})
                            UsageAction("确认", {}, Modifier.testTag("usage-button"))
                        }
                    }
                }
            }
        }
        node("assistant-button").assertHeightIsEqualTo(33.dp)
        node("agent-button").assertHeightIsEqualTo(31.dp)
        node("usage-button").assertHeightIsEqualTo(34.dp)
    }
}
