package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme

class MessageActionAvailabilityTest : WiredActivityTestCase() {
    private var enabled by mutableStateOf(false)
    private var theme by mutableStateOf(AppearanceThemeMode.LIGHT)
    private val clicked = mutableListOf<String>()

    private fun actions() = ChatScreenActions(messageActionsEnabled = enabled,
        onCopy = { clicked += "copy" }, onBranch = { clicked += "branch" },
        onEditUser = { clicked += "edit" }, onSpeak = { clicked += "speak" },
        onRegenerate = { clicked += "regenerate" }, onDeleteTurn = { clicked += "delete" })

    fun testReadOnlyActionsRemainEnabledAndMutationsRecover() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = theme), applyInterfaceScale = false) {
                Column(Modifier.width(320.dp)) {
                    MessageActionBar("answer", actions(), showEditAction = true)
                }
            }
        } }
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { theme = mode; enabled = false }
            for (label in listOf("编辑", "重新生成", "删除")) {
                ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput { click() }
            }
            ui.onNode(hasContentDescription("复制回答") or hasContentDescription("已复制")).assertIsEnabled().performClick()
            ui.onNodeWithContentDescription("分支").assertIsEnabled().performClick()
            ui.onNodeWithContentDescription("朗读").assertIsEnabled().performClick()
            assertEquals(listOf("copy", "branch", "speak"), clicked.takeLast(3))
            assertTrue(clicked.all { it in listOf("copy", "branch", "speak") })
        }
        mainThread { enabled = true }
        for (label in listOf("编辑", "朗读", "重新生成", "删除")) {
            ui.onNodeWithContentDescription(label).assertIsEnabled().performClick()
        }
        assertEquals(listOf("edit", "speak", "regenerate", "delete"), clicked.takeLast(4))
        assertEquals(0, fixture.opens.get())
    }


    fun testLiveAndStoppedWorkflowProseRetainCopyAndSpeak() {
        var status by mutableStateOf(RunStatus.RUNNING)
        val tool = WorkStepUi("tool", "工具", "结果不朗读", WorkStepState.OK)
        val content = listOf(MessageContentUi.Tool(tool), MessageContentUi.Text("prose", "这是卡片外的文本"),
            MessageContentUi.Tool(tool.copy(id = "second")))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.width(320.dp)) {
                    AssistantTurn(ChatTurnUi.Assistant("reply", status = status, content = content), actions())
                }
            }
        } }
        for (label in listOf("重新生成", "删除")) ui.onNodeWithContentDescription(label).assertIsNotEnabled()
        ui.onNodeWithContentDescription("朗读").assertIsEnabled().performClick()
        mainThread { status = RunStatus.STOPPED; enabled = true }
        ui.onNodeWithContentDescription("朗读").assertIsEnabled().performClick()
        ui.onNodeWithContentDescription("复制回答").assertIsEnabled().performClick()
        assertEquals(listOf("speak", "speak", "copy"), clicked)
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptyTerminalReplyDoesNotLeaveIdentityOrActions() {
        var reply by mutableStateOf(ChatTurnUi.Assistant("empty-reply", status = RunStatus.RUNNING))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(reply, actions())
            }
        } }
        val identity = ui.onNodeWithTag("reply-identity:empty-reply")
        val regenerate = ui.onNodeWithContentDescription("重新生成")
        for (status in listOf(RunStatus.STOPPED, RunStatus.COMPLETED)) {
            mainThread { reply = reply.copy(status = status) }
            identity.assertDoesNotExist()
            regenerate.assertDoesNotExist()
        }
        mainThread { reply = reply.copy(status = RunStatus.STOPPED, finalText = "已收到一部分正文") }
        identity.assertIsDisplayed()
        ui.onNodeWithContentDescription("复制回答").assertIsEnabled().performClick()
        mainThread { reply = reply.copy(finalText = "") }
        identity.assertDoesNotExist()
        regenerate.assertDoesNotExist()
        assertEquals(listOf("copy"), clicked)
        assertEquals(0, fixture.opens.get())
    }

    fun testCoveredFooterKeepsItsColorsAndCannotInvokeActions() {
        var covered by mutableStateOf(false)
        mainThread {
            enabled = true
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    MessageActionBar("reply", actions().copy(messageActionsCovered = covered),
                        modifier = Modifier.testTag("covered-actions"), showEditAction = true)
                }
            }
        }
        val labels = listOf("复制回答", "编辑", "朗读", "分支", "重新生成", "删除")
        for (label in labels) ui.onNodeWithContentDescription(label).assertIsEnabled()
        val before = node("covered-actions").captureToImage().toPixelMap()
        mainThread { covered = true }
        for (label in labels) ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput { click() }
        val after = node("covered-actions").captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (y in 0 until before.height) for (x in 0 until before.width) {
            assertEquals(before[x, y], after[x, y])
        }
        assertTrue(clicked.isEmpty())
        mainThread { covered = false }
        ui.onNodeWithContentDescription("朗读").assertIsEnabled().performClick()
        assertEquals(listOf("speak"), clicked)
    }

    fun testPendingUserShowsActionBarImmediatelyAndKeepsSyntheticTargetReadOnly() {
        var pending by mutableStateOf(true)
        var busy by mutableStateOf(false)
        val received = mutableListOf<String>()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.width(320.dp)) {
                    UserTurn(ChatTurnUi.User("preview-user", "just sent", pending = pending),
                        ChatScreenActions(messageActionsEnabled = !busy,
                            onCopy = { received += "copy:$it" }, onBranch = { received += "branch:$it" },
                            onEditUser = { received += "edit:$it" }, onDeleteTurn = { received += "delete:$it" }))
                }
            }
        } }
        ui.onNodeWithTag("chat-send-preview").assertIsDisplayed()
        ui.onNodeWithContentDescription("复制回答").assertIsDisplayed().assertIsEnabled().performClick()
        for (label in listOf("编辑", "分支", "删除")) {
            ui.onNodeWithContentDescription(label).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        }
        assertEquals(listOf("copy:preview-user"), received)
        mainThread { pending = false; busy = true }
        ui.onNodeWithContentDescription("分支").assertIsDisplayed().assertIsEnabled()
        for (label in listOf("编辑", "删除")) ui.onNodeWithContentDescription(label).assertIsDisplayed().assertIsNotEnabled()
        mainThread { busy = false }
        for (label in listOf("编辑", "分支", "删除")) ui.onNodeWithContentDescription(label).assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithContentDescription("朗读").assertDoesNotExist()
        ui.onNodeWithContentDescription("重新生成").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testAlreadyExpandedUserMenuDisablesEditsAndDeletionWithoutHidingThem() {
        var expanded by mutableStateOf(true)
        mainThread {
            enabled = true
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    UserTurnMenu(expanded, { expanded = false }, "user", actions())
                }
            }
        }
        ui.onNodeWithText("编辑").assertIsEnabled()
        mainThread { enabled = false }
        for (label in listOf("编辑", "删除")) {
            ui.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        }
        assertTrue(expanded); assertTrue(clicked.isEmpty())
        ui.onNodeWithText("复制").assertIsEnabled().performClick()
        assertEquals(listOf("copy"), clicked)
        mainThread { expanded = true }
        ui.onNodeWithText("分支").assertIsEnabled().performClick()
        assertEquals(listOf("copy", "branch"), clicked)
    }
}
