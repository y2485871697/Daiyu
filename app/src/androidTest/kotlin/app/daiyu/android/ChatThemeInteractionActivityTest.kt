package app.daiyu.android

import android.app.AlertDialog
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.NoRippleIndication
import app.daiyu.android.ui.screens.chat.conversation.ChildTaskDisclosure
import app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusRow
import app.daiyu.android.ui.screens.chat.conversation.chatActionColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

class ChatThemeInteractionActivityTest : WiredActivityTestCase() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun assertNoRipple() {
        val indication = LocalIndication.current
        val ripple = LocalRippleConfiguration.current
        SideEffect {
            assertSame(NoRippleIndication, indication)
            assertNull(ripple)
        }
    }

    fun testNoRippleLocalsReachButtonsAndDialogWithoutRemovingClicks() {
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            val events = mutableListOf<String>()
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = mode), false) {
                    assertNoRipple()
                    var dialog by androidx.compose.runtime.remember { mutableStateOf(false) }
                    Column {
                        Text("普通点击", Modifier.testTag("plain-click").heightIn(min = 48.dp)
                            .clickable { events.add("plain") }.padding(8.dp))
                        androidx.compose.material3.TextButton(onClick = { events.add("material") },
                            modifier = Modifier.testTag("material-click")) {
                            androidx.compose.material3.Text("Material")
                        }
                        TextButton(text = "Miuix", onClick = { events.add("miuix") },
                            modifier = Modifier.testTag("miuix-click"))
                        TextButton(text = "打开弹窗", onClick = { dialog = true }, modifier = Modifier.testTag("open-dialog"))
                    }
                    if (dialog) WindowDialog(show = true, title = "无涟漪", onDismissRequest = { dialog = false }) {
                        assertNoRipple()
                        TextButton(text = "关闭", modifier = Modifier.testTag("close-dialog"), onClick = { dialog = false })
                    }
                }
            } }
            awaitVisualIdle()
            for (tag in listOf("plain-click", "material-click", "miuix-click"))
                node(tag).performTouchInput { click(center) }
            node("open-dialog").performClick()
            awaitUniqueTag("close-dialog")
            node("close-dialog").assertIsDisplayed().performClick()
            awaitVisualIdle()
            ui.runOnIdle { assertEquals(listOf("plain", "material", "miuix"), events) }
        }
    }

    fun testNativeActivityAndAlertDialogUseTransparentControlHighlight() {
        mainThread {
            val value = TypedValue()
            assertTrue(activity.theme.resolveAttribute(android.R.attr.colorControlHighlight, value, true))
            assertEquals(android.graphics.Color.TRANSPARENT, value.data)
            val dialog = AlertDialog.Builder(activity).setTitle("点击反馈").setPositiveButton("确定", null).create()
            try {
                assertTrue(dialog.context.theme.resolveAttribute(android.R.attr.colorControlHighlight, value, true))
                assertEquals(android.graphics.Color.TRANSPARENT, value.data)
            } finally { dialog.dismiss() }
        }
    }

    fun testSendColorsStayLocalToChatAndFollowDarkMode() {
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = mode), false) {
                    val colors = chatActionColors()
                    SideEffect {
                        assertEquals(if (mode == AppearanceThemeMode.DARK) androidx.compose.ui.graphics.Color(0xFF7862ED)
                            else androidx.compose.ui.graphics.Color(0xFFE9E3FB), colors.sendContainer)
                        assertEquals(if (mode == AppearanceThemeMode.DARK) androidx.compose.ui.graphics.Color(0xFFF1ECFF)
                            else androidx.compose.ui.graphics.Color(0xFF8D7AC9), colors.sendContent)
                    }
                }
            } }
            awaitVisualIdle()
        }
    }

    fun testChildDisclosureIsPerOwnerAndKeepsControlsAvailable() {
        var first by mutableStateOf(SubAgentStatusRow(ChildRunOwner("chat", "run", "group", 1, "one", "message"), "代理一", "执行中"))
        val second = SubAgentStatusRow(first.owner.copy(childRunId = "two"), "代理二", "已暂停")
        var actions = 0
        var reversed by mutableStateOf(false)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), false) {
                Column {
                    val rows = if (reversed) listOf(second, first) else listOf(first, second)
                    rows.forEach { row -> key(row.owner) {
                        ChildTaskDisclosure(row, details = {
                            Text(if (row.owner.childRunId == "one") "第一项详情" else "第二项详情")
                        }, controls = {
                            if (row.owner.childRunId == "one") TextButton(text = "暂停一",
                                modifier = Modifier.testTag("child-action"), onClick = { actions++ })
                        })
                    } }
                }
            }
        } }
        awaitVisualIdle()
        ui.onNodeWithText("第一项详情").assertDoesNotExist()
        node("child-action").assertIsDisplayed().performClick()
        node("tool-child-summary:one").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠")).performClick()
        awaitVisualIdle()
        ui.onNodeWithText("第一项详情").assertIsDisplayed()
        ui.onNodeWithText("第二项详情").assertDoesNotExist()
        ui.runOnIdle { first = first.copy(status = "已暂停") }
        awaitVisualIdle()
        node("tool-child-summary:one").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        ui.runOnIdle { reversed = true }
        awaitVisualIdle()
        node("tool-child-summary:one").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        node("tool-child-summary:two").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
        node("tool-child-summary:two").performClick()
        awaitVisualIdle()
        node("tool-child-summary:one").performClick()
        awaitVisualIdle()
        ui.onNodeWithText("第一项详情").assertDoesNotExist()
        ui.onNodeWithText("第二项详情").assertIsDisplayed()
        node("tool-child-summary:one").performClick()
        awaitVisualIdle()
        ui.onNodeWithText("第一项详情").assertIsDisplayed()
        ui.runOnIdle { first = first.copy(owner = first.owner.copy(taskGroupId = "replacement")) }
        awaitVisualIdle()
        node("tool-child-summary:one").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
        ui.runOnIdle { assertEquals(1, actions) }
    }
}
