package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.AssistantDisplay
import app.daiyu.android.ui.components.ConversationSidePaneScaffold
import app.daiyu.android.ui.model.ConversationFolderUi
import app.daiyu.android.ui.model.ConversationModeUi
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import java.util.concurrent.atomic.AtomicReference

class ConversationPaneVisualTest : WiredActivityTestCase() {
    private val density = AtomicReference<Density>()
    private var action: String? = null
    private var generation = 0
    private val conversations = listOf(
        ConversationSummaryUi("pinned", "Pinned run", "", "", mode = ConversationModeUi.Chat,
            isPinned = true, isActiveRun = true),
        ConversationSummaryUi("first", "First chat", "", "", mode = ConversationModeUi.Chat,
            hasCompletionMarker = true, isToday = true),
        ConversationSummaryUi("second", "Second chat", "", "", mode = ConversationModeUi.Chat, isToday = true),
    )

    private fun show(scale: Float = 1f, dark: Boolean = false) {
        action = null
        val renderGeneration = ++generation
        mainThread {
            activity.setContent {
                key(renderGeneration) {
                    val platform = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platform.density, .9f)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = true) {
                            density.set(LocalDensity.current)
                            var visible by remember { mutableStateOf(true) }
                            var state by remember { mutableStateOf(ConversationPaneUiState(
                                conversations = conversations, selectedConversationId = "first", searchQuery = "",
                                folders = listOf(ConversationFolderUi("design", "设计")),
                                historyConversations = conversations,
                            )) }
                            ConversationSidePaneScaffold(
                                state = state, visible = visible, backHandlerEnabled = true,
                                onOpen = { visible = true }, onDismiss = { visible = false },
                                onSearchChange = { state = state.copy(searchQuery = it) },
                                onConversationSelected = { action = "conversation:$it" },
                                onConversationRename = { action = "rename:${it.id}" },
                                onConversationDelete = { action = "delete:${it.id}" },
                                onNewConversation = { action = "new" },
                                onSelectFolder = { action = "folder:$it" },
                                assistants = listOf(AssistantDisplay("assistant-a", "Drawer assistant")),
                                selectedAssistantId = "assistant-a",
                                onSelectAssistant = {}, onEditAssistant = {},
                                onOpenAssistants = { action = "assistants" },
                                onOpenSettings = { action = "settings" },
                                onOpenModelProviders = { action = "models" },
                                onOpenUsageStats = { action = "usage" },
                                onOpenSkills = { action = "skills" },
                                onOpenPermissions = { action = "permissions" },
                                modifier = Modifier.testTag("drawer-host"),
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun text(id: Int) = instrumentation.targetContext.getString(id)
    private fun bounds(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = px / density.get().density
    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun assertType(text: String, size: TextUnit, lineHeight: TextUnit) {
        val input = layout(text).layoutInput
        assertEquals(size, input.style.fontSize)
        assertEquals(lineHeight, input.style.lineHeight)
        assertEquals(.9f, input.density.fontScale, .001f)
    }

    fun testDrawerMatchesCssGeometryAcrossThemesAndInterfaceScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            val drawer = bounds("conversation-drawer")
            val search = bounds("drawer-search")
            val newChat = bounds("drawer-new")
            val manage = bounds("drawer-manage")
            val folders = bounds("drawer-folder-unfiled")
            val list = bounds("drawer-list")
            val pinned = bounds("drawer-conversation:pinned")
            val first = bounds("drawer-conversation:first")
            val second = bounds("drawer-conversation:second")
            val pinnedHeading = bounds("drawer-heading:section-Pinned")
            val todayHeading = bounds("drawer-heading:section-Today")
            assertEquals(minOf(dp(bounds("drawer-host").width) * .78f, 320f), dp(drawer.width), .7f)
            assertEquals(14f, dp(search.left - drawer.left), .6f)
            assertEquals(15f, dp(drawer.right - search.right), .6f)
            assertEquals(38f, dp(search.height), .6f)
            assertEquals(13f, dp(bounds("drawer-search-icon").left - search.left), .6f)
            assertEquals(18f, dp(bounds("drawer-search-icon").width), .6f)
            assertEquals(8f, dp(bounds("drawer-search-input").left - bounds("drawer-search-icon").right), .6f)
            assertEquals(12f, dp(newChat.top - search.bottom), .6f)
            assertEquals(40f, dp(newChat.height), .6f)
            assertEquals(40f, dp(manage.height), .6f)
            assertEquals(8f, dp(manage.top - newChat.bottom), .6f)
            assertEquals(13f, dp(bounds("drawer-new-icon").left - newChat.left), .6f)
            assertEquals(12f, dp(folders.top - manage.bottom), .6f)
            assertEquals(34f, dp(folders.height), .6f)
            assertEquals(6f, dp(bounds("drawer-folder:design").left - folders.right), .6f)
            assertEquals(12f, dp(bounds("drawer-folder:design-icon").left - bounds("drawer-folder:design").left), .6f)
            assertEquals(13f, dp(bounds("drawer-folder:design-icon").width), .6f)
            assertEquals(12f, dp(list.top - folders.bottom), .6f)
            assertEquals(5f, dp(pinnedHeading.top - list.top), .6f)
            assertEquals(6f, dp(pinned.top - pinnedHeading.bottom), .6f)
            assertEquals(12f, dp(todayHeading.top - pinned.bottom), .6f)
            assertEquals(6f, dp(first.top - todayHeading.bottom), .6f)
            assertEquals(44f, dp(first.height), .6f)
            assertEquals(2f, dp(second.top - first.bottom), .6f)
            assertEquals(13f, dp(bounds("drawer-title:first").left - first.left), .6f)
            assertEquals(8f, dp(bounds("drawer-status:pinned").left - bounds("drawer-title:pinned").right), .6f)
            assertEquals(8f, dp(bounds("drawer-pin:pinned").left - bounds("drawer-status:pinned").right), .6f)
            assertEquals(11f, dp(bounds("drawer-pin:pinned").width), .6f)

            val footer = bounds("drawer-footer-border")
            val assistant = bounds("drawer-assistant")
            val avatar = bounds("drawer-assistant-avatar")
            val dock = bounds("drawer-dock")
            assertEquals(1f, dp(footer.height), .6f)
            assertEquals(11f, dp(assistant.top - footer.bottom), .6f)
            assertEquals(48f, dp(assistant.height), .6f)
            assertEquals(32f, dp(avatar.width), .6f)
            assertEquals(32f, dp(avatar.height), .6f)
            assertEquals(8f, dp(assistant.right - avatar.right), .6f)
            assertEquals(8f, dp(dock.top - assistant.bottom), .6f)
            for (id in listOf("permissions", "models", "skills", "usage", "settings")) {
                val button = bounds("drawer-dock-$id")
                val icon = bounds("drawer-dock-$id-icon")
                assertEquals(dp(dock.width) * .15625f, dp(button.width), .6f)
                assertEquals(dp(button.width), dp(button.height), .6f)
                assertEquals(dp(button.width) * .5f, dp(icon.width), .6f)
            }
            assertEquals(dp(dock.width) * .0390625f,
                dp(bounds("drawer-dock-models").left - bounds("drawer-dock-permissions").right), .6f)
            assertEquals(0f, dp(dock.right - bounds("drawer-dock-settings").right), .6f)
            assertType(text(R.string.conversation_search_hint), 12.sp, 16.sp)
            assertType(text(R.string.drawer_new_conversation), 11.sp, 14.sp)
            assertType(text(R.string.drawer_chats_chip), 10.sp, 13.sp)
            assertType("First chat", 12.sp, 17.4.sp)
            assertType("Drawer assistant", 12.sp, 18.sp)
            assertEquals(FontWeight.SemiBold, layout("First chat").layoutInput.style.fontWeight)
            assertEquals(if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F), layout("First chat").layoutInput.style.color)
            assertEquals(if (dark) Color(0xFFC6B7D5) else Color(0xFF655A72),
                layout(text(R.string.drawer_new_conversation)).layoutInput.style.color)
        }
    }

    fun testManageAndSearchKeepExistingSelectionCallbacks() {
        show()
        node("drawer-manage").performClick()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        node("drawer-conversation:first").performClick().assertIsSelected()
        node("drawer-selection-count").assertTextEquals("已选择 1 项")
        assertNull(action)
        assertEquals(13f, dp(bounds("drawer-select:first").width), .6f)
        assertType("已选择 1 项", 9.sp, 12.sp)
        node("drawer-manage").performClick()
        node("drawer-selection-count").assertDoesNotExist()
        node("drawer-search").performClick()
        node("drawer-search-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Second")) }
        node("drawer-conversation:first").assertDoesNotExist()
        node("drawer-conversation:second").performClick()
        assertEquals("conversation:second", action)
    }

    fun testShortcutsStillDispatchTheirOriginalDestinations() {
        for ((tag, expected) in listOf("drawer-new" to "new", "drawer-assistant" to "assistants",
            "drawer-dock-permissions" to "permissions", "drawer-dock-models" to "models",
            "drawer-dock-skills" to "skills", "drawer-dock-usage" to "usage", "drawer-dock-settings" to "settings")) {
            show()
            node(tag).performClick()
            ui.waitForIdle()
            assertEquals(expected, action)
        }
        show()
        node("drawer-folder:design").performTouchInput { click() }
        assertEquals("folder:design", action)
    }
}
