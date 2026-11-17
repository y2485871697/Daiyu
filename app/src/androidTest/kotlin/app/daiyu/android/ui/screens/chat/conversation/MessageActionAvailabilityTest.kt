package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
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

    fun testOnlyCopyAndBranchRemainEnabledAndAllOtherButtonsRecover() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = theme), applyInterfaceScale = false) {
                Column(Modifier.width(320.dp)) {
                    MessageActionBar("answer", actions(), showEditAction = true)
                }
            }
        } }
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { theme = mode; enabled = false }
            for (label in listOf("编辑", "朗读", "重新生成", "删除")) {
                ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput { click() }
            }
            ui.onNode(hasContentDescription("复制回答") or hasContentDescription("已复制")).assertIsEnabled().performClick()
            ui.onNodeWithContentDescription("分支").assertIsEnabled().performClick()
            assertEquals(listOf("copy", "branch"), clicked.takeLast(2))
            assertTrue(clicked.all { it == "copy" || it == "branch" })
        }
        mainThread { enabled = true }
        for (label in listOf("编辑", "朗读", "重新生成", "删除")) {
            ui.onNodeWithContentDescription(label).assertIsEnabled().performClick()
        }
        assertEquals(listOf("edit", "speak", "regenerate", "delete"), clicked.takeLast(4))
        assertEquals(0, fixture.opens.get())
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
