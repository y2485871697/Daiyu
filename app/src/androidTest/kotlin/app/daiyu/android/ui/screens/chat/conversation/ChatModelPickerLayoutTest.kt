package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daiyu.android.balance.BalanceFailure
import app.daiyu.android.balance.BalanceMoney
import app.daiyu.android.balance.BalanceResult
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.balance.formatBalanceDisplay
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi

class ChatModelPickerLayoutTest : WiredActivityTestCase() {
    private var contentDensity = 1f
    private val selected = mutableListOf<Pair<String, String>>()
    private val models = listOf(ModelCatalogEntryUi("same", "Display name"), ModelCatalogEntryUi("next"))
    private val state = ChatModelPickerState("same", models, providerId = "a", providers = listOf(
        ChatModelProviderUi("a", "Alpha", models, balance = BalanceState(result = money("1.93"))),
        ChatModelProviderUi("b", "Beta", listOf(ModelCatalogEntryUi("same", "Display name"))),
    ))

    private fun show(value: ChatModelPickerState = state, dark: Boolean = false, scale: Float = 1f, font: Float = 1f, width: Dp? = null,
        updates: State<ChatModelPickerState>? = null) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                    key(value, dark, scale, font) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale), applyInterfaceScale = true) {
                            val actualDensity = LocalDensity.current.density
                            SideEffect { contentDensity = actualDensity }
                            ChatModelPicker(updates?.value ?: value, { selected += "a" to it }, {},
                                onSelectOtherProvider = { provider, model -> selected += provider to model },
                                modifier = if (width == null) Modifier else Modifier.widthIn(max = width))
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun search(text: String) {
        node("chat-model-search").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(text))) }
        awaitVisualIdle()
    }

    private fun listItem(key: String, tag: String): SemanticsNodeInteraction {
        node("chat-model-list").performScrollToKey(key)
        awaitVisualIdle()
        return node(tag).assertIsDisplayed()
    }

    fun testHtmlStructureAndAccordionAcrossThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark = dark, scale = scale)
            ui.onNodeWithText("当前模型").assertDoesNotExist()
            ui.onNodeWithText("搜索模型 / 供应商", useUnmergedTree = true).assertExists()
            node("chat-model-provider:a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            node("chat-model-option:same").assertIsSelected()
            node("chat-model-option:b:same").assertDoesNotExist()
            node("chat-model-list").scrollToDescendant(hasTestTag("chat-model-provider:b"))
            node("chat-model-provider:b").performClick()
            node("chat-model-option:b:same").assertIsNotSelected().performClick()
            node("chat-model-option:same").assertDoesNotExist()
            assertEquals("b" to "same", selected.last())
            node("chat-model-provider:b").performClick()
            node("chat-model-option:b:same").assertDoesNotExist()
        }
    }

    fun testSearchMatchesProviderNameAndModelIdAndKeepsCollapseSeparate() {
        show()
        search("beta")
        node("chat-model-provider:a").assertDoesNotExist()
        node("chat-model-option:b:same").assertIsDisplayed().assertIsNotSelected()
        node("chat-model-provider:b").performClick()
        node("chat-model-option:b:same").assertDoesNotExist()
        search("same")
        listItem("model:a:same", "chat-model-option:same").assertIsSelected()
        listItem("model:b:same", "chat-model-option:b:same").assertIsNotSelected()
        search("")
        node("chat-model-provider:a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        node("chat-model-option:b:same").assertDoesNotExist()
        search("no-match")
        ui.onNodeWithText("没有找到匹配的模型").assertIsDisplayed()
        search("next")
        node("chat-model-option:next").performClick()
        assertEquals("a" to "next", selected.last())
    }

    fun testLongCatalogRemainsLazyAndRunningStateDisablesSelection() {
        val many = (0..999).map { ModelCatalogEntryUi("model-$it", "长模型名称 $it") }
        show(ChatModelPickerState("model-999", many, enabled = false), font = 1.3f)
        awaitVisualIdle()
        listItem("model:current:model-999", "chat-model-option:model-999")
            .assertIsSelected().assertIsNotEnabled().performClick()
        node("chat-model-option:model-0").assertDoesNotExist()
        assertTrue(selected.isEmpty())
    }

    private fun money(amount: String) = BalanceResult.Available(BalanceMoney(amount.toBigDecimal(), "USD"), 1L)

    private fun amountNode(provider: String) =
        ui.onNodeWithTag("chat-model-provider-balance:$provider", useUnmergedTree = true)

    private fun assertAmount(provider: String, raw: String) {
        listItem("heading:$provider", "chat-model-provider:$provider")
        amountNode(provider).assertIsDisplayed().assertHasNoClickAction()
        ui.onNode(hasText(formatBalanceDisplay(raw)) and
            hasAnyAncestor(hasTestTag("chat-model-provider-balance:$provider")), useUnmergedTree = true).assertIsDisplayed()
    }

    fun testProviderBalancesKeepTheirOwnAmountsWhenGroupsCollapseOrSearch() {
        val providers = listOf(
            ChatModelProviderUi("a", "Alpha", models, BalanceState(result = money("17.177208"))),
            ChatModelProviderUi("b", "Beta", models, BalanceState(result = money("2500.5"))),
        )
        val updates = mutableStateOf(state.copy(providers = providers))
        show(updates.value, updates = updates)
        assertAmount("a", "17.177208")
        assertAmount("b", "2500.5")
        ui.onNodeWithText("USD", substring = true).assertDoesNotExist()
        ui.onNodeWithText("17.177208", substring = true).assertDoesNotExist()
        node("chat-model-provider:b").performClick()
        assertAmount("a", "17.177208")
        assertAmount("b", "2500.5")
        listItem("model:b:same", "chat-model-option:b:same").assertIsNotSelected().performClick()
        assertEquals("b" to "same", selected.last())
        search("Beta")
        amountNode("a").assertDoesNotExist()
        assertAmount("b", "2500.5")
        mainThread { updates.value = updates.value.copy(providers = providers.reversed()) }
        ui.waitForIdle()
        amountNode("a").assertDoesNotExist() // Search survives reorder in the same composition.
        assertAmount("b", "2500.5")
        search("")
        node("chat-model-list").performScrollToIndex(0)
        awaitVisualIdle()
        node("chat-model-provider:b").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        node("chat-model-provider:b").performClick()
        node("chat-model-list").performScrollToIndex(0)
        awaitVisualIdle()
        val beta = node("chat-model-provider:b").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val alpha = node("chat-model-provider:a").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(beta.top < alpha.top)
        node("chat-model-provider:b").performClick()
        mainThread {
            updates.value = updates.value.copy(providers = updates.value.providers.map {
                if (it.id == "b") it.copy(balance = BalanceState(result = money("0"))) else it
            })
        }
        ui.waitForIdle()
        assertAmount("b", "0")
        assertAmount("a", "17.177208")
        listItem("heading:b", "chat-model-provider:b").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        assertEquals("USD 17.177208", providers.first().balance?.project()?.balanceText)
    }

    fun testFailedAndRefreshingBalancesKeepOnlyTheirLastSuccess() {
        for (refreshing in listOf(false, true)) {
            show(state.copy(providers = listOf(
                ChatModelProviderUi("a", "Alpha", models, BalanceState(refreshing = refreshing,
                    result = BalanceResult.Failed(BalanceFailure.AUTHORIZATION), lastSuccessful = money("17.177208"))),
                ChatModelProviderUi("b", "Beta", models, BalanceState(refreshing = refreshing,
                    result = BalanceResult.Failed(BalanceFailure.NETWORK))),
                ChatModelProviderUi("c", "Gamma", models),
            )))
            assertAmount("a", "17.177208")
            amountNode("b").assertDoesNotExist()
            amountNode("c").assertDoesNotExist()
            ui.onNodeWithText("未授权", substring = true).assertDoesNotExist()
            ui.onNodeWithText("正在查询余额", substring = true).assertDoesNotExist()
            ui.onNodeWithText("网络失败", substring = true).assertDoesNotExist()
        }
        for (balance in listOf(BalanceState(), BalanceState(refreshing = true))) {
            show(state.copy(providers = listOf(ChatModelProviderUi("a", "Alpha", models, balance))))
            amountNode("a").assertDoesNotExist()
            ui.onNodeWithTag("chat-model-count:a", useUnmergedTree = true).assertExists()
        }
    }

    fun testLongProviderHeaderKeepsBalanceCountAndExpandAlignedAtLargeFont() {
        val longName = "这是一个很长的供应商名称，用于检查余额不会挤占模型数量与展开按钮"
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(state.copy(providers = listOf(
                ChatModelProviderUi("a", longName, models, BalanceState(result = money("17.177208"))),
            )), dark = dark, scale = scale, font = 1.3f, width = 320.dp)
            assertAmount("a", "17.177208")
            fun bounds(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val picker = bounds("chat-model-picker")
            assertTrue("picker content must be at most 320dp", picker.width / contentDensity <= 321f)
            val header = bounds("chat-model-provider:a")
            val name = bounds("chat-model-provider-name:a")
            val count = bounds("chat-model-count:a")
            val amount = bounds("chat-model-provider-balance:a")
            val expand = bounds("chat-model-provider-expand:a")
            assertTrue(name.width > 0f)
            assertTrue(name.bottom <= count.top + 1f)
            assertEquals(name.left, count.left, 1f)
            assertTrue(count.right <= amount.left + 1f)
            assertTrue(amount.right <= expand.left + 1f)
            assertTrue(name.right <= expand.left + 1f)
            assertTrue(name.left >= header.left && expand.right <= header.right)
            assertTrue(amount.top >= header.top && amount.bottom <= header.bottom)
            assertEquals(count.center.y, amount.center.y, 1f)
            assertEquals(header.center.y, expand.center.y, 1f)
            assertTrue("provider touch target must be at least 56dp", header.height / contentDensity >= 55.5f)
            assertTrue("search touch target must be at least 48dp", bounds("chat-model-search").height / contentDensity >= 47.5f)
            listItem("model:a:same", "chat-model-option:same").scrollToVisible()
            val model = bounds("chat-model-option:same")
            val viewport = bounds("chat-model-list")
            assertTrue("model top must be inside list", model.top >= viewport.top - 1f)
            assertTrue("model bottom must be inside list", model.bottom <= viewport.bottom + 1f)
            assertTrue("model touch target must be at least 56dp", model.height / contentDensity >= 55.5f)
            for (tag in listOf("chat-model-brand:a:same", "chat-model-indicator:a:same")) {
                val icon = bounds(tag)
                assertEquals("$tag width", 22f, icon.width / contentDensity, .5f)
                assertEquals("$tag height", 22f, icon.height / contentDensity, .5f)
            }
            assertTrue(bounds("chat-model-name:a:same").bottom <= bounds("chat-model-id:a:same").top + 1f)
            node("chat-model-option:same").assertIsSelected()
            assertAmount("a", "17.177208")
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNode(hasText(formatBalanceDisplay("17.177208")) and
                hasAnyAncestor(hasTestTag("chat-model-provider-balance:a")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            val input = layout.layoutInput
            assertFalse("ordinary amount must not be ellipsized: dark=$dark scale=$scale " +
                "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} " +
                "constraints=${input.constraints} size=${layout.size} " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "lines=${layout.lineCount} font=${input.style.fontSize} lineHeight=${input.style.lineHeight} " +
                "density=${input.density.density} fontScale=${input.density.fontScale}", layout.hasVisualOverflow)
            node("chat-model-provider:a").performClick()
            assertAmount("a", "17.177208")
            node("chat-model-option:same").assertDoesNotExist()
        }
    }

    fun testPickerAccentMatchesConversationCollaborationInBothThemes() {
        for (dark in listOf(false, true)) {
            assertEquals(AssistantPreviewPalette(dark).accent, UsagePalette(dark).accent)
        }
    }
}
