package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs

class MessageActionBarLayoutTest : WiredActivityTestCase() {
    private val timestamp = "2026-10-23 01:27"
    private var width by mutableStateOf(320.dp)
    private var endAligned by mutableStateOf(false)
    private var label by mutableStateOf(timestamp)
    private var appearance by mutableStateOf(AppearanceSettings())
    private var copies = 0

    private fun mount() {
        mainThread {
            activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                        Box(Modifier.width(width)) {
                            MessageActionBar("turn", ChatScreenActions(onCopy = { copies++ }),
                                modifier = Modifier.testTag("timestamp-action-bar"), timestamp = label,
                                endAligned = endAligned)
                        }
                    }
                }
            }
        }
    }

    fun testTimestampUsesRetainedElevenSpAndTheTrailingEdgeInBothThemes() {
        mount()
        for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { appearance = appearance.copy(themeMode = theme) }
            val bar = ui.onNodeWithTag("timestamp-action-bar").fetchSemanticsNode().boundsInRoot
            val time = ui.onNodeWithText(timestamp).fetchSemanticsNode().boundsInRoot
            val delete = ui.onNodeWithContentDescription("删除").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(bar.right - time.right) <= 1f)
            assertTrue(time.left >= delete.right)
            assertTrue(abs(time.center.y - delete.center.y) <= 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(timestamp).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(11.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(1, layouts.single().lineCount)
            assertFalse("timestamp layout=${layouts.single().size}, overflowWidth=${layouts.single().didOverflowWidth}, " +
                "overflowHeight=${layouts.single().didOverflowHeight}, bar=$bar, time=$time", layouts.single().hasVisualOverflow)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testUserTurnHidesItsTimeAndKeepsActionsWhileAssistantTimeRemains() {
        val userTime = "2026-10-27 12:34"
        val clicked = mutableListOf<String>()
        var density = 1f
        mainThread {
            activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    androidx.compose.foundation.layout.Column(Modifier.width(width)) {
                        Box(Modifier.testTag("user-time-turn")) {
                            UserTurn(ChatTurnUi.User("question", "用户正文", timestamp = userTime),
                                ChatScreenActions(
                                    onCopy = { clicked += "copy:$it" },
                                    onEditUser = { clicked += "edit:$it" },
                                    onBranch = { clicked += "branch:$it" },
                                    onDeleteTurn = { clicked += "delete:$it" },
                                ))
                        }
                        AssistantTurn(ChatTurnUi.Assistant("reply", timestamp = timestamp), ChatScreenActions())
                    }
                }
            }
        }
        fun userAction(description: String) = ui.onNode(hasContentDescription(description) and
            hasAnyAncestor(hasTestTag("user-time-turn")))
        for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { appearance = appearance.copy(themeMode = theme) }
            for (size in listOf(240.dp, 320.dp)) {
                mainThread { width = size }
                ui.onNodeWithText(userTime).assertDoesNotExist()
                ui.onNodeWithText(timestamp).assertIsDisplayed()
                val host = ui.onNodeWithTag("user-time-turn").fetchSemanticsNode().boundsInRoot
                val delete = userAction("删除").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("User actions must remain at the trailing edge", host.right - delete.right in 0f..(13f * density))
                for (label in listOf("复制回答", "编辑", "分支")) userAction(label).assertIsDisplayed()
            }
        }
        userAction("复制回答").performClick()
        userAction("编辑").performClick()
        userAction("分支").performClick()
        userAction("删除").performClick()
        assertEquals(listOf("copy:question", "edit:question", "branch:question", "delete:question"), clicked)
        assertEquals(0, fixture.opens.get())
    }

    fun testGrowingWidthMovesOnlyAssistantTimeAndKeepsUserActionsEndAligned() {
        mount()
        mainThread { width = 240.dp }
        val copyBefore = ui.onNodeWithContentDescription("复制回答").fetchSemanticsNode().boundsInRoot
        val timeBefore = ui.onNodeWithText(timestamp).fetchSemanticsNode().boundsInRoot
        mainThread { width = 320.dp }
        val copyAfter = ui.onNodeWithContentDescription("复制回答").fetchSemanticsNode().boundsInRoot
        val timeAfter = ui.onNodeWithText(timestamp).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(copyBefore.left - copyAfter.left) <= 1f)
        assertTrue(timeAfter.right > timeBefore.right)
        mainThread { endAligned = true }
        val copyUser = ui.onNodeWithContentDescription("复制回答").fetchSemanticsNode().boundsInRoot
        assertTrue(copyUser.left > copyAfter.left)
        val bar = ui.onNodeWithTag("timestamp-action-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(bar.right - ui.onNodeWithText(timestamp).fetchSemanticsNode().boundsInRoot.right) <= 1f)
        ui.onNodeWithContentDescription("复制回答").performClick()
        assertEquals(1, copies)
        mainThread { label = " " }
        ui.onNodeWithText(timestamp).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
