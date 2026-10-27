package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.AssistantDisplay
import app.daiyu.android.ui.components.ConversationSidePaneScaffold
import app.daiyu.android.ui.components.ConversationPanePreviewPalette
import app.daiyu.android.ui.model.ConversationFolderUi
import app.daiyu.android.ui.model.ConversationModeUi
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    private fun show(scale: Float = 1f, dark: Boolean = false, fontScale: Float = .9f, compact: Boolean = false,
        loaded: Boolean = true, rows: List<ConversationSummaryUi> = conversations, query: String = "",
        blurEnabled: Boolean = true, initiallyVisible: Boolean = true,
        content: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) }) {
        action = null
        val renderGeneration = ++generation
        mainThread {
            activity.setContent {
                key(renderGeneration) {
                    val platform = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale, blurEnabled = blurEnabled,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = true) {
                            density.set(LocalDensity.current)
                            var visible by remember { mutableStateOf(initiallyVisible) }
                            var state by remember { mutableStateOf(ConversationPaneUiState(
                                conversations = rows, selectedConversationId = "first", searchQuery = query,
                                folders = listOf(ConversationFolderUi("design", "设计")),
                                historyConversations = rows, conversationsLoaded = loaded,
                            )) }
                            ConversationSidePaneScaffold(
                                state = state, visible = visible, backHandlerEnabled = true,
                                onOpen = { visible = true }, onDismiss = { visible = false },
                                onSearchChange = { state = state.copy(searchQuery = it) },
                                onConversationSelected = { action = "conversation:$it" },
                                onConversationRename = { action = "rename:${it.id}" },
                                onConversationDelete = { action = "delete:${it.id}" },
                                onNewConversation = { action = "new" },
                                onSelectFolder = { action = "folder:$it"; state = state.copy(selectedFolderId = it) },
                                onCreateFolder = { action = "create-folder:$it" },
                                assistants = listOf(AssistantDisplay("assistant-a", "Drawer assistant")),
                                selectedAssistantId = "assistant-a",
                                onSelectAssistant = {}, onEditAssistant = {},
                                onOpenAssistants = { action = "assistants" },
                                onOpenSettings = { action = "settings" },
                                onOpenModelProviders = { action = "models" },
                                onOpenUsageStats = { action = "usage" },
                                onOpenSkills = { action = "skills" },
                                onOpenPermissions = { action = "permissions" },
                                modifier = Modifier.testTag("drawer-host").then(if (compact) Modifier.widthIn(max = 320.dp) else Modifier),
                            ) { content() }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    fun testDrawerOnlyDimsExposedContentWithoutBlurringInBothThemes() {
        for (dark in listOf(false, true)) for (blurEnabled in listOf(false, true)) {
            for (open in listOf(false, true)) {
                show(dark = dark, compact = true, blurEnabled = blurEnabled, initiallyVisible = open) {
                    Canvas(Modifier.fillMaxSize()) {
                        val boundary = (size.height / 2).toInt().toFloat()
                        drawRect(Color.White)
                        drawRect(Color.Black, topLeft = Offset(0f, boundary),
                            size = Size(size.width, size.height - boundary))
                    }
                }
                val pixels = node("drawer-host").captureToImage().toPixelMap()
                val x = pixels.width - (12 * density.get().density).toInt()
                val boundary = pixels.height / 2
                for ((base, y) in listOf(Color.White to boundary - 1, Color.Black to boundary)) {
                    val expected = if (open) ConversationPanePreviewPalette(dark).scrim.compositeOver(base) else base
                    val actual = pixels[x, y]
                    val label = "dark=$dark blur=$blurEnabled open=$open y=$y"
                    assertEquals(label, expected.red, actual.red, .015f)
                    assertEquals(label, expected.green, actual.green, .015f)
                    assertEquals(label, expected.blue, actual.blue, .015f)
                }
                assertNull(action)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testFolderNameUsesInsetRoundedFieldAndPlaceholderWithoutChangingSaveOrCancel() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            node("drawer-folder-new").scrollToVisible().performClick()
            val field = node("folder-name-input").assertHeightIsEqualTo(48.dp)
            val bounds = field.fetchSemanticsNode().boundsInRoot
            val cancel = node("folder-name-cancel").fetchSemanticsNode().boundsInRoot
            val save = node("folder-name-save").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
            assertEquals(9f, dp(bounds.left - cancel.left), dp(1f) + .01f)
            assertEquals(9f, dp(save.right - bounds.right), dp(1f) + .01f)
            ui.onNode(hasText(text(R.string.drawer_folder_name)) and
                hasAnyAncestor(hasTestTag("folder-name-input")), useUnmergedTree = true).assertIsDisplayed()
            field.performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString("项目资料"))) }
                .assertEditableText("项目资料")
            ui.onNodeWithText(text(R.string.drawer_folder_name), useUnmergedTree = true).assertDoesNotExist()
            node("folder-name-save").assertIsEnabled().performClick()
            assertEquals("create-folder:项目资料", action)
            node("folder-name-input").assertDoesNotExist()
            action = null
            node("drawer-folder-new").scrollToVisible().performClick()
            node("folder-name-input").assertEditableText("")
            node("folder-name-cancel").performClick()
            assertNull(action)
        }
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

    fun testDrawerGroupsDifferentTimesUnderOneLocalDateAndFiltersEmptyDays() {
        val date = LocalDate.of(2026, 10, 26)
        val previous = date.minusDays(1)
        val zone = ZoneId.systemDefault()
        val rows = listOf(
            ConversationSummaryUi("day-new", "match new", "", "2026/10/26 20:34",
                updatedAtMillis = date.atTime(20, 34).atZone(zone).toInstant().toEpochMilli(), mode = ConversationModeUi.Chat),
            ConversationSummaryUi("day-old", "match old", "", "2026/10/26 08:14",
                updatedAtMillis = date.atTime(8, 14).atZone(zone).toInstant().toEpochMilli(), mode = ConversationModeUi.Chat),
            ConversationSummaryUi("previous-day", "other", "", "2026/10/25 22:10",
                updatedAtMillis = previous.atTime(22, 10).atZone(zone).toInstant().toEpochMilli(), mode = ConversationModeUi.Chat),
        )
        val heading = "drawer-heading:section-Dated(date=$date)"
        val olderHeading = "drawer-heading:section-Dated(date=$previous)"
        for (dark in listOf(false, true)) {
            show(dark = dark, rows = rows)
            ui.onAllNodesWithTag(heading, useUnmergedTree = true).assertCountEquals(1)
            node(heading).assertIsDisplayed().assertTextEquals(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
            assertTrue(bounds(heading).bottom <= bounds("drawer-conversation:day-new").top)
            assertTrue(bounds("drawer-conversation:day-new").bottom <= bounds("drawer-conversation:day-old").top)
            assertTrue(bounds("drawer-conversation:day-old").bottom <= bounds(olderHeading).top)
            node("drawer-conversation:previous-day").assertIsDisplayed()
            rows.forEach { ui.onNodeWithText(it.timeLabel).assertDoesNotExist() }
            node("drawer-conversation:day-old").performClick()
            assertEquals("conversation:day-old", action)
            show(dark = dark, rows = rows, query = "match")
            ui.onAllNodesWithTag(heading, useUnmergedTree = true).assertCountEquals(1)
            node("drawer-conversation:day-new").assertIsDisplayed()
            node("drawer-conversation:day-old").assertIsDisplayed()
            node(olderHeading).assertDoesNotExist()
            node("drawer-conversation:previous-day").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptyAndSearchMessagesRequireACompletedSnapshot() {
        for (dark in listOf(false, true)) {
            show(dark = dark, loaded = false, rows = emptyList())
            ui.onNodeWithText(text(R.string.conversation_empty)).assertDoesNotExist()
            ui.onNodeWithText(text(R.string.conversation_no_results)).assertDoesNotExist()
            show(dark = dark, loaded = false, rows = emptyList(), query = "not-loaded")
            ui.onNodeWithText(text(R.string.conversation_empty)).assertDoesNotExist()
            ui.onNodeWithText(text(R.string.conversation_no_results)).assertDoesNotExist()
            show(dark = dark, rows = emptyList())
            ui.onNodeWithText(text(R.string.conversation_empty)).assertIsDisplayed()
            show(dark = dark, query = "no matching conversation")
            ui.onNodeWithText(text(R.string.conversation_no_results)).assertIsDisplayed()
            show(dark = dark)
            node("drawer-conversation:first").assertIsDisplayed()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testDrawerKeepsReadableGeometryAcrossThemesAndInterfaceScales() {
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
            assertEquals(44f, dp(search.height), .6f)
            assertEquals(13f, dp(bounds("drawer-search-icon").left - search.left), dp(1f) + .01f)
            assertEquals(20f, dp(bounds("drawer-search-icon").width), .6f)
            assertEquals(8f, dp(bounds("drawer-search-input").left - bounds("drawer-search-icon").right), .6f)
            assertEquals(12f, dp(newChat.top - search.bottom), .6f)
            assertEquals(44f, dp(newChat.height), .6f)
            assertEquals(44f, dp(manage.height), .6f)
            assertEquals(8f, dp(manage.top - newChat.bottom), .6f)
            assertEquals(13f, dp(bounds("drawer-new-icon").left - newChat.left), dp(1f) + .01f)
            assertEquals(12f, dp(folders.top - manage.bottom), .6f)
            assertEquals(36f, dp(folders.height), .6f)
            assertEquals(6f, dp(bounds("drawer-folder:design").left - folders.right), .6f)
            assertEquals(12f, dp(bounds("drawer-folder:design-icon").left - bounds("drawer-folder:design").left), .6f)
            assertEquals(15f, dp(bounds("drawer-folder:design-icon").width), .6f)
            assertEquals(12f, dp(list.top - folders.bottom), .6f)
            assertEquals(5f, dp(pinnedHeading.top - list.top), .6f)
            assertEquals(6f, dp(pinned.top - pinnedHeading.bottom), .6f)
            assertEquals(12f, dp(todayHeading.top - pinned.bottom), .6f)
            assertEquals(6f, dp(first.top - todayHeading.bottom), .6f)
            assertEquals(44f, dp(first.height), .6f)
            assertEquals(2f, dp(second.top - first.bottom), .6f)
            assertEquals(13f, dp(bounds("drawer-title:first").left - first.left), dp(1f) + .01f)
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
                assertEquals(dp(button.width) * .5f, dp(icon.width), maxOf(.6f, dp(1f) + .01f))
            }
            assertEquals(dp(dock.width) * .0390625f,
                dp(bounds("drawer-dock-models").left - bounds("drawer-dock-permissions").right), .6f)
            assertEquals(0f, dp(dock.right - bounds("drawer-dock-settings").right), .6f)
            assertType(text(R.string.conversation_search_hint), 14.sp, 20.sp)
            assertType(text(R.string.drawer_new_conversation), 14.sp, 20.sp)
            assertType(text(R.string.drawer_chats_chip), 12.sp, 16.sp)
            assertType("First chat", 14.sp, 20.sp)
            assertType("Drawer assistant", 14.sp, 20.sp)
            assertEquals(FontWeight.SemiBold, layout("First chat").layoutInput.style.fontWeight)
            assertEquals(if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F), layout("First chat").layoutInput.style.color)
            assertEquals(if (dark) Color(0xFFC6B7D5) else Color(0xFF655A72),
                layout(text(R.string.drawer_new_conversation)).layoutInput.style.color)
        }
    }

    private fun assertFolderFill(tag: String, fill: Color) {
        node(tag).scrollToVisible().assertIsDisplayed()
        val drawer = bounds("conversation-drawer")
        val chip = bounds(tag)
        val pixels = node("conversation-drawer").captureToImage().toPixelMap()
        val x = (chip.center.x - drawer.left).toInt()
        val top = chip.top - drawer.top
        val background = pixels[x, (top - 3 * density.get().density).toInt()]
        val actual = pixels[x, (top + 4 * density.get().density).toInt()]
        val expected = fill.compositeOver(background)
        assertEquals(expected.red, actual.red, .015f)
        assertEquals(expected.green, actual.green, .015f)
        assertEquals(expected.blue, actual.blue, .015f)
    }

    fun testSearchUsesTheSameGrayPurpleFillAsActionsBeforeAndAfterEditing() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale = scale, dark = dark)
            val fill = if (dark) Color(0x17AC93C2) else Color(0x1A81738E)
            fun assertFill(tag: String) {
                node(tag).assertIsDisplayed()
                val drawer = bounds("conversation-drawer")
                val control = bounds(tag)
                val pixels = node("conversation-drawer").captureToImage().toPixelMap()
                val d = density.get().density
                val x = (control.center.x - drawer.left).toInt()
                val y = (control.top - drawer.top + 4f * d).toInt()
                val leftX = (control.left - drawer.left - 3f * d).toInt()
                val rightX = (control.right - drawer.left + 3f * d).toInt()
                assertTrue("$tag background samples stay inside drawer",
                    leftX > 0 && rightX < pixels.width - 1 && leftX < x && x < rightX)
                assertTrue("$tag sample row stays inside control",
                    y >= 0 && y < pixels.height && drawer.top + y > control.top && drawer.top + y < control.bottom)
                val left = pixels[leftX, y]
                val right = pixels[rightX, y]
                val t = (x - leftX).toFloat() / (rightX - leftX).toFloat()
                // Compare the translucent fill against the same row of the drawer's diagonal gradient.
                val background = Color(left.red + (right.red - left.red) * t,
                    left.green + (right.green - left.green) * t,
                    left.blue + (right.blue - left.blue) * t,
                    left.alpha + (right.alpha - left.alpha) * t)
                val expected = fill.compositeOver(background)
                val actual = pixels[x, y]
                val label = "dark=$dark scale=$scale tag=$tag sample=($x,$y) backgroundX=[$leftX,$rightX]"
                assertEquals("$label red", expected.red, actual.red, .015f)
                assertEquals("$label green", expected.green, actual.green, .015f)
                assertEquals("$label blue", expected.blue, actual.blue, .015f)
            }
            node("drawer-search").assertHeightIsAtLeast(44.dp)
            assertEquals(bounds("drawer-new").width, bounds("drawer-search").width, 1f)
            assertFill("drawer-search")
            assertFill("drawer-new")
            assertFill("drawer-manage")
            node("drawer-search").performClick()
            node("drawer-search-input").performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString("Second")))
            }.assertEditableText("Second")
            assertFill("drawer-search")
            dismissIme()
            assertFill("drawer-search")
            node("drawer-list").scrollToDescendant(hasTestTag("drawer-conversation:second"))
            node("drawer-conversation:second").assertIsDisplayed()
            node("drawer-conversation:first").assertDoesNotExist()
            assertNull(action)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testFolderAndCreateChipsKeepIdleBackgroundWhenSelectionChanges() {
        for (dark in listOf(false, true)) {
            show(dark = dark)
            val idle = if (dark) Color(0x24C0ACD7) else Color(0x2281738E)
            val selected = if (dark) Color(0xFF786A89) else Color(0xFF786C83)
            assertFolderFill("drawer-folder-unfiled", selected)
            assertFolderFill("drawer-folder:design", idle)
            assertFolderFill("drawer-folder-new", idle)
            node("drawer-folder:design").scrollToVisible().performTouchInput { click() }
            assertEquals("folder:design", action)
            assertFolderFill("drawer-folder:design", selected)
            assertFolderFill("drawer-folder-unfiled", idle)
            assertFolderFill("drawer-folder-new", idle)
        }
    }

    fun testNarrowDrawerAndLargerSystemFontKeepControlsReadableAndReachable() {
        for (dark in listOf(false, true)) {
            show(dark = dark, fontScale = 1.3f, compact = true)
            node("drawer-search").assertHeightIsAtLeast(44.dp)
            node("drawer-new").assertHeightIsAtLeast(44.dp)
            node("drawer-manage").assertHeightIsAtLeast(44.dp)
            val search = layout(text(R.string.conversation_search_hint))
            assertEquals(14.sp, search.layoutInput.style.fontSize)
            assertEquals(1.3f, search.layoutInput.density.fontScale, .001f)
            assertFalse(search.hasVisualOverflow)
            val newChat = layout(text(R.string.drawer_new_conversation))
            assertFalse("New chat overflows: width=${newChat.didOverflowWidth} height=${newChat.didOverflowHeight} " +
                "constraints=${newChat.layoutInput.constraints} size=${newChat.size} lines=${newChat.lineCount}",
                newChat.hasVisualOverflow)
            assertTrue(bounds("drawer-manage").bottom < bounds("drawer-folder-unfiled").top)
            assertTrue(bounds("drawer-list").height > 0)
            assertTrue(bounds("drawer-list").bottom <= bounds("drawer-footer-border").top)
            node("drawer-folder-new").scrollToVisible().assertIsDisplayed()
                .performTouchInput { click() }
            node("folder-name-input").assertIsDisplayed()
            node("folder-name-cancel").performClick()
            node("drawer-dock-settings").assertIsDisplayed().performClick()
            ui.waitForIdle()
            assertEquals("settings", action)
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
        assertType("已选择 1 项", 12.sp, 17.sp)
        node("drawer-manage").performClick()
        node("drawer-selection-count").assertDoesNotExist()
        node("drawer-search").performClick()
        node("drawer-search-input").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString("Second"))) }
        node("drawer-search-input").assertEditableText("Second")
        dismissIme()
        node("drawer-list").scrollToDescendant(hasTestTag("drawer-conversation:second"))
        node("drawer-conversation:first").assertDoesNotExist()
        node("drawer-conversation:second").assertIsDisplayed().performClick()
        assertEquals("conversation:second", action)
    }

    fun testLongPressMenuWorksNormallyAndInManageModeWithoutChangingSelectionOrOpeningConversation() {
        for (dark in listOf(false, true)) {
            show(dark = dark)
            val normalBounds = bounds("drawer-conversation:second")
            node("drawer-conversation:second").performTouchInput { longClick() }
            ui.onNodeWithText(text(R.string.action_rename)).assertIsDisplayed()
            assertEquals(normalBounds, bounds("drawer-conversation:second"))
            assertNull(action)
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            node("drawer-manage").performClick()
            node("drawer-conversation:first").performClick().assertIsSelected()
            node("drawer-selection-count").assertTextEquals("已选择 1 项")
            val before = bounds("drawer-conversation:second")
            node("drawer-conversation:second").performTouchInput { longClick() }
            ui.onNodeWithText(text(R.string.action_rename)).assertIsDisplayed()
            ui.onNodeWithText(text(R.string.conversation_pin)).assertIsDisplayed()
            ui.onNodeWithText(text(R.string.conversation_export)).assertIsDisplayed()
            ui.onNodeWithText(text(R.string.drawer_move_to_folder)).assertIsDisplayed()
            ui.onNodeWithText(text(R.string.action_delete)).assertIsDisplayed()
            node("drawer-conversation:second").assertIsNotSelected()
            node("drawer-conversation:first").assertIsSelected()
            node("drawer-selection-count").assertTextEquals("已选择 1 项")
            assertEquals(before, bounds("drawer-conversation:second"))
            assertNull(action)
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText(text(R.string.action_rename)).assertDoesNotExist()
            node("drawer-selection-count").assertTextEquals("已选择 1 项")
            node("drawer-conversation:first").performTouchInput { longClick() }
            ui.onNodeWithText(text(R.string.action_rename)).performClick()
            assertEquals("rename:first", action)
            node("drawer-selection-count").assertTextEquals("已选择 1 项")
        }
        assertEquals(0, fixture.opens.get())
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
