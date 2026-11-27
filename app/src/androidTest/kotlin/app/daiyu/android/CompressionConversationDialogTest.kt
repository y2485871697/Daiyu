package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.context.ContextManagementOperation
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import app.daiyu.android.ui.screens.chat.context.CompressionConversationDialog
import app.daiyu.android.ui.screens.chat.context.CompressionDialogDraft

class CompressionConversationDialogTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(initial())
    private var confirms = 0
    private var cancels = 0
    private var picks = 0
    private fun initial() = CompressionDialogDraft(true, "deepseek-v4-1-flash", "fixture/model",
        ProviderProtocol.CHAT_COMPLETIONS)
    private fun api(protocol: ProviderProtocol) = node("compression-dialog-api:${protocol.wireName}")
    private fun show(dark: Boolean, scale: Float = 1f, fontScale: Float = 1f,
        operation: ContextManagementOperation? = null) {
        mainThread {
            draft = initial()
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        CompressionConversationDialog(draft, onChange = { draft = it },
                            onChooseModel = { picks++ }, onConfirm = { confirms++ }, onDismiss = { cancels++ },
                            enabled = operation == null, operation = operation)
                    }
                }
            }
        }
        awaitUniqueTag("context-management")
    }

    fun testCompressionDimsBackgroundLikeCollaborationAndOutsideTapDismisses() {
        var showReference by mutableStateOf(true)
        var showCompression by mutableStateOf(true)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (showReference) {
                            ConversationAgentSheet(onDismiss = {}, useDefaultHeight = true) {}
                        } else if (showCompression) {
                            CompressionConversationDialog(draft, onChange = { draft = it },
                                onChooseModel = { picks++ }, onConfirm = { confirms++ }, onDismiss = {
                                    cancels++
                                    showCompression = false
                                })
                        }
                    }
                }
            }
        }
        val scrim = node("preview-dialog-scrim")
        fun sample(sheetTag: String): Color {
            awaitUniqueTag(sheetTag)
            val sheet = node(sheetTag)
            awaitDialogEnter(sheet)
            val viewport = scrim.fetchSemanticsNode().boundsInRoot
            val outsideHeight = sheet.fetchSemanticsNode().boundsInRoot.top - viewport.top
            assertTrue("The sheet must leave room for the outside sample", outsideHeight >= viewport.height * .16f)
            val pixels = captureScreenNode(scrim).toPixelMap()
            return pixels[pixels.width / 2, (pixels.height * .14f).toInt()]
        }
        val expected = sample("conversation-agent-sheet")
        assertTrue("The reference sheet must visibly shade the white background: $expected", expected.red < .8f)
        mainThread { showReference = false }
        val actual = sample("usage-glass-dialog")
        assertEquals("Compression backdrop red", expected.red, actual.red, .025f)
        assertEquals("Compression backdrop green", expected.green, actual.green, .025f)
        assertEquals("Compression backdrop blue", expected.blue, actual.blue, .025f)
        scrim.performTouchInput { click(Offset(center.x, height * .14f)) }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("usage-glass-dialog").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        assertEquals(0, picks)
        assertEquals(0, fixture.opens.get())
    }

    fun testOriginalControlsUseNewThemeAndOnlyDispatchExplicitActions() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale)
            ui.onNodeWithText("压缩会话").assertIsDisplayed()
            node("compression-dialog-custom").assertIsOn()
            node("compression-dialog-model").assertTextContains("deepseek-v4-1-flash")
            api(ProviderProtocol.CHAT_COMPLETIONS).assertIsSelected()
            api(ProviderProtocol.RESPONSES).performClick().assertIsSelected()
            api(ProviderProtocol.CHAT_COMPLETIONS).assertIsNotSelected()
            assertEquals(ProviderProtocol.RESPONSES, draft.protocol)
            node("compression-dialog-model").performClick()
            node("compression-dialog-custom").performClick().assertIsOff()
            node("compression-dialog-model").assertDoesNotExist()
            node("compression-dialog-custom").performClick().assertIsOn()
            node("compression-dialog-model").assertTextContains("deepseek-v4-1-flash")
            node("compression-dialog-help-text").assertDoesNotExist()
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
            node("compression-dialog-help").performClick()
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help-text"))
            node("compression-dialog-help-text").assertTextContains("Anthropic / Codex", substring = true)
            node("context-close").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            node("context-compress").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            val pixels = captureScreenNode(node("context-compress")).toPixelMap()
            val actual = pixels[(pixels.width * .1f).toInt(), pixels.height / 2]
            val expected = AssistantPreviewPalette(dark).primary
            assertEquals(expected.red, actual.red, .025f)
            assertEquals(expected.green, actual.green, .025f)
            assertEquals(expected.blue, actual.blue, .025f)
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
            node("compression-dialog-help").performClick()
            node("compression-dialog-help-text").assertDoesNotExist()
            node("context-compress").performClick()
            node("context-close").performClick()
            node("context-refresh").assertDoesNotExist()
            node("context-advanced").assertDoesNotExist()
            ui.onNodeWithText("未接入", substring = true).assertDoesNotExist()
        }
        assertEquals(4, confirms)
        assertEquals(4, cancels)
        assertEquals(4, picks)
        assertEquals(0, fixture.opens.get())
    }

    fun testPreparationStatesKeepOriginalControlsWithoutExtraProgressOrCancelAction() {
        for (operation in listOf(null, ContextManagementOperation.REFRESH,
            ContextManagementOperation.SELECT, ContextManagementOperation.ARCHIVE)) {
            show(dark = false, operation = operation)
            node("context-management").performScrollToIndex(0)
            node("compression-dialog-operation").assertDoesNotExist()
            node("context-cancel").assertDoesNotExist()
            ui.onNodeWithText("正在准备会话信息…").assertDoesNotExist()
            ui.onNodeWithText("正在核对压缩目标…").assertDoesNotExist()
            ui.onNodeWithText("取消并等待退出").assertDoesNotExist()
            node("compression-dialog-custom").assertExists()
            node("context-compress").assertIsDisplayed()
            if (operation == null) node("context-compress").assertIsEnabled()
            else node("context-compress").assertIsNotEnabled()
            node("context-close").assertIsEnabled().performClick()
        }
        assertEquals(4, cancels)
        assertEquals(0, confirms)
        assertEquals(0, fixture.opens.get())
    }

    fun testCompressionProgressIsNotRenderedInsideTheConfigurationDialog() {
        show(dark = true, operation = ContextManagementOperation.COMPRESS)
        node("context-management").performScrollToIndex(0)
        node("compression-dialog-operation").assertDoesNotExist()
        node("context-compression-confirmation").assertDoesNotExist()
        node("context-cancel").assertDoesNotExist()
        node("context-compress").assertIsNotEnabled()
        node("context-close").assertIsEnabled().performClick()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        assertEquals(0, fixture.opens.get())
    }

    fun testExpandedHelpAtLargerFontKeepsFooterReachableAndBackCancels() {
        show(dark = true, scale = .8f, fontScale = 1.3f)
        node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
        node("compression-dialog-help").performClick()
        node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help-text"))
        val help = node("compression-dialog-help-text").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = node("context-compress").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(help.bottom <= footer.top)
        node("context-close").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        assertEquals(0, fixture.opens.get())
    }
}
