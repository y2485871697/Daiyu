package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.content.res.Configuration
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.AppearanceSettingsScreen
import app.daiyu.android.ui.screens.settings.DaiyuSettingsScreen
import java.io.File

class SettingsChromeActivityTest : WiredActivityTestCase() {
    private fun showHome(events: MutableList<String>, dark: Boolean = false, scale: Float = 1f,
        fontScale: Float = 1f, pureBlack: Boolean = false) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(dark, scale, fontScale, pureBlack) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale, pureBlackEnabled = pureBlack), true) {
                            Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("settings-host")) {
                                DaiyuSettingsScreen(onBack = { events.add("back") }, onRoute = { events.add(it.name) },
                                    onOpenSubAgents = { events.add("subagents") }, onOpenAssistants = { events.add("assistants") },
                                    onOpenAppearance = { events.add("appearance") })
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    fun testHomeAt320DpKeepsPrimaryActionsReadableInBothThemes() {
        val events = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            showHome(events, dark)
            val host = node("settings-host").assertWidthIsEqualTo(320.dp).fetchSemanticsNode().boundsInRoot
            for (title in listOf("模型提供商", "外观与主题", "助手", "子代理")) {
                val row = reveal("settings-row:$title", "设置").assertHasClickAction().assertIsEnabled()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= host.left - 1 && bounds.right <= host.right + 1)
                assertTrue(bounds.height >= 48 * activity.resources.displayMetrics.density)
            }
            node("page:设置").performScrollToIndex(0)
            capture(if (dark) "settings-dark" else "settings-light")
        }
        val expected = mutableListOf<String>()
        for ((title, event) in listOf("模型提供商" to "MODEL_SETTINGS", "外观与主题" to "appearance",
                "助手" to "assistants", "子代理" to "subagents")) {
            reveal("settings-row:$title", "设置").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            expected += event
            ui.runOnIdle { assertEquals(expected, events) }
        }
    }

    fun testSettingsCardsHaveNoOutlineOrRowDividersInBothThemes() {
        for (dark in listOf(false, true)) {
            showHome(mutableListOf(), dark)
            val first = reveal("settings-row:外观与主题", "设置").fetchSemanticsNode().boundsInRoot
            val second = node("settings-row:助手").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(first.bottom, second.top, 0.1f)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val density = activity.resources.displayMetrics.density
            val x = (first.left + 18 * density).toInt()
            val boundary = first.bottom.toInt()
            val surface = pixels[x, boundary - 4]
            for (y in boundary - 3..boundary + 3) {
                val actual = pixels[x, y]
                assertEquals(surface.red, actual.red, .02f)
                assertEquals(surface.green, actual.green, .02f)
                assertEquals(surface.blue, actual.blue, .02f)
            }
            val middle = ((first.left + first.right) / 2).toInt()
            val top = first.top.toInt()
            val inside = pixels[middle, top + 4]
            val edge = pixels[middle, top]
            assertEquals(inside.red, edge.red, .02f)
            assertEquals(inside.green, edge.green, .02f)
            assertEquals(inside.blue, edge.blue, .02f)
        }
    }

    fun testHomeKeepsDaiyuCardsAndReadableDescriptionsAtSmallScaleAndLargeFont() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            showHome(mutableListOf(), dark, scale, fontScale = 1.3f)
            node("settings-section:general").assertTextEquals("通用设置")
            node("settings-row:外观与主题").assertTextContains("主题、图标与界面显示")
            node("settings-row:助手").assertTextContains("角色、提示词与个性化")
            node("settings-row:颜色模式").assertDoesNotExist()
            node("settings-row:偏好设置").assertDoesNotExist()
            node("settings-row:搜索服务").assertDoesNotExist()
            val host = node("settings-host").fetchSemanticsNode().boundsInRoot
            for ((group, title) in listOf("general" to "外观与主题", "models" to "模型提供商",
                    "extensions" to "Skills", "tools" to "Agent任务偏好", "data" to "数据备份", "about" to "APP 信息与更新")) {
                val row = reveal("settings-row:$title", "设置").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                val bounds = row.fetchSemanticsNode().boundsInRoot
                val card = node("settings-card:$group").fetchSemanticsNode().boundsInRoot
                assertTrue(card.left > host.left && card.right < host.right)
                assertTrue(bounds.left >= card.left && bounds.right <= card.right)
                row.assertHasClickAction()
            }
            for (title in listOf("检查更新", "Telegram 频道", "QQ 群", "源代码", "自用中转站", "赞助")) {
                node("settings-row:$title").assertDoesNotExist()
            }
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testPureBlackBackgroundRemainsBlackOutsideHomeCards() {
        showHome(mutableListOf(), dark = true, pureBlack = true)
        val card = node("settings-card:general").fetchSemanticsNode().boundsInRoot
        val page = node("page:设置").fetchSemanticsNode().boundsInRoot
        val pixels = ui.onRoot().captureToImage().toPixelMap()
        val x = ((page.left + card.left) / 2).toInt()
        val y = (card.top + 20).toInt()
        assertEquals(Color.Black, pixels[x, y])
    }

    fun testSettingsTopBarAndPageUseTheSameBackground() {
        for (dark in listOf(false, true)) {
            showHome(mutableListOf(), dark = dark)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val bar = node("page-topbar:设置").fetchSemanticsNode().boundsInRoot
            val page = node("page:设置").fetchSemanticsNode().boundsInRoot
            val x = (page.left + 2).toInt()
            val top = pixels[x, (bar.center.y).toInt()]
            val body = pixels[x, (bar.bottom + 4).toInt()]
            assertEquals(top.red, body.red, .015f)
            assertEquals(top.green, body.green, .015f)
            assertEquals(top.blue, body.blue, .015f)
        }
    }

    fun testGroupedHomeHasNoSearchAndKeepsRealIndependentRoutes() {
        val events = mutableListOf<String>()
        showHome(events)
        node("settings-search").assertDoesNotExist()
        node("settings-search-clear").assertDoesNotExist()
        reveal("settings-row:MCP 服务器", "设置").assertIsEnabled().assertHasClickAction().performClick()
        ui.runOnIdle { assertEquals(listOf("MCP_SETTINGS"), events) }
        ui.onNodeWithText("待接入").assertDoesNotExist()
        node("settings-row:发送消息").assertDoesNotExist()
        reveal("settings-row:Skills", "设置").performClick()
        ui.runOnIdle { assertEquals(listOf("MCP_SETTINGS", "SKILLS"), events) }
    }

    fun testOriginalGroupedHomeRemovesDefaultReasoningWithoutCategoryNavigation() {
        val events = mutableListOf<String>()
        showHome(events)
        node("settings-row:LLM 提供商").assertDoesNotExist()
        node("settings-row:默认启用推理").assertDoesNotExist()
        reveal("settings-row:模型提供商", "设置").assertIsDisplayed().assertHasClickAction()
        node("page:设置").assertExists()
        ui.runOnIdle { assertTrue(events.isEmpty()) }
    }

    fun testAppearanceKeepsThemeAndBlackAndRemovesRetiredOptions() {
        var state by mutableStateOf(AppearanceSettings())
        mainThread { activity.setContent {
            AgentAppTheme(state, false) {
                AppearanceSettingsScreen(state, onChange = { state = it }, onBack = {})
            }
        } }
        node("settings-radio:深色").performClick().assertIsSelected()
        reveal("settings-switch:纯黑背景", "外观与主题").performClick().assertIsOn()
        for (title in listOf("色彩风格", "强调色", "聊天气泡")) {
            node("settings-row:$title").assertDoesNotExist()
        }
        reveal("settings-switch:消息时间戳", "外观与主题").performClick().assertIsOn()
        node("settings-switch:圆形加载指示器").assertDoesNotExist()
        node("settings-switch:仅消息生成前").assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(AppearanceThemeMode.DARK, state.themeMode)
            assertTrue(state.pureBlackEnabled)
            assertTrue(state.messageTimestampsEnabled)
        }
        node("page:外观与主题").performScrollToIndex(0)
        capture("appearance-dark")
    }

    fun testTopBarBlurChoicesUseUnifiedSheetAndCommitOnlyTheSelectedValue() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val initial = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                interfaceScale = scale, topBarBlurStyle = AppearanceTopBarBlurStyle.PROGRESSIVE)
            var state by mutableStateOf(initial)
            val requests = mutableListOf<AppearanceSettings>()
            mainThread {
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        AgentAppTheme(state, true) {
                            AppearanceSettingsScreen(state, onChange = { requests.add(it); state = it },  onBack = {})
                        }
                    }
                }
            }
            fun open() {
                reveal("settings-row:顶栏模糊样式", "外观与主题").performTouchInput { click() }
                node("chat-selection-sheet").assertIsDisplayed()
                node("daiyu-choice-sheet").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                val sheet = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot
                var previousBottom = sheet.top
                for (tag in listOf("settings-radio:高斯模糊", "settings-radio:渐进模糊", "choice-sheet-cancel")) {
                    val bounds = node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= sheet.left && bounds.right <= sheet.right)
                    assertTrue(bounds.top >= previousBottom && bounds.bottom <= sheet.bottom)
                    previousBottom = bounds.bottom
                }
            }
            open()
            node("settings-radio:高斯模糊").assertIsNotSelected()
            node("settings-radio:渐进模糊").assertIsSelected()
            node("settings-radio:高斯模糊").performTouchInput { click() }
            node("chat-selection-sheet").assertDoesNotExist()
            ui.runOnIdle { assertEquals(listOf(initial.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN)), requests) }
            node("settings-row:顶栏模糊样式").assertTextContains("高斯模糊")
            open()
            node("settings-radio:高斯模糊").assertIsSelected()
            node("settings-radio:渐进模糊").assertIsNotSelected().performTouchInput { click() }
            node("chat-selection-sheet").assertDoesNotExist()
            ui.runOnIdle {
                assertEquals(listOf(initial.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN), initial), requests)
                assertEquals(initial, state)
            }
            node("settings-row:顶栏模糊样式").assertTextContains("渐进模糊")
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testTopBarBlurCancelOutsideAndBackDoNotChangeAppearance() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val initial = AppearanceSettings(topBarBlurStyle = AppearanceTopBarBlurStyle.PROGRESSIVE)
        var state by mutableStateOf(initial)
        val requests = mutableListOf<AppearanceSettings>()
        mainThread {
            activity.setContent {
                AgentAppTheme(state, false) {
                    AppearanceSettingsScreen(state, onChange = { requests.add(it); state = it },  onBack = {})
                }
            }
        }
        repeat(3) { action ->
            reveal("settings-row:顶栏模糊样式", "外观与主题").performTouchInput { click() }
            node("chat-selection-sheet").assertIsDisplayed()
            node("settings-radio:渐进模糊").assertIsSelected()
            when (action) {
                0 -> node("choice-sheet-cancel").performTouchInput { click() }
                1 -> node("preview-dialog-scrim").performTouchInput { click(Offset(width / 2f, height * .1f)) }
                else -> instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
            node("chat-selection-sheet").assertDoesNotExist()
            ui.runOnIdle { assertTrue(requests.isEmpty()); assertEquals(initial, state) }
            node("settings-row:顶栏模糊样式").assertTextContains("渐进模糊")
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAppearanceGroupsRetainControlsAtLargeFontAndBothScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var state by mutableStateOf(AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                interfaceScale = scale))
            mainThread {
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        key(dark, scale) {
                            AgentAppTheme(state, true) {
                                Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("appearance-host")) {
                                    AppearanceSettingsScreen(state, onChange = { state = it },  onBack = {})
                                }
                            }
                        }
                    }
                }
            }
            ui.waitForIdle()
            for (title in listOf("主题模式", "色彩与背景", "应用图标", "界面与效果", "导航与手势")) {
                node("page:外观与主题").scrollToDescendant(hasText(title))
                ui.onNodeWithText(title).assertIsDisplayed()
            }
            val host = node("appearance-host").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("settings-row:图标", "settings-switch:纯黑背景",
                "settings-switch:横移返回手势", "settings-switch:预测性返回手势")) {
                val bounds = reveal(tag, "外观与主题").assertHasClickAction().fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= host.left && bounds.right <= host.right)
            }
            val before = state
            ui.runOnIdle { assertEquals(before, state) }
            reveal("settings-switch:横移返回手势", "外观与主题").performClick()
            ui.runOnIdle { assertEquals(!before.swipeDismissEnabled, state.swipeDismissEnabled) }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testScaleGestureCommitsOnceAndRejectedSaveRestoresConfirmedValue() {
        val state = AppearanceSettings(interfaceScale = 1f)
        val requests = mutableListOf<AppearanceSettings>()
        mainThread {
            activity.setContent {
                AgentAppTheme(state, false) {
                    AppearanceSettingsScreen(state, onChange = { requests.add(it) },  onBack = {})
                }
            }
        }
        reveal("appearance-scale", "外观与主题").performTouchInput {
            swipe(Offset(width * (2f / 3f), centerY), Offset(width * 0.25f, centerY), durationMillis = 300)
        }
        ui.runOnIdle {
            assertEquals(1, requests.size)
            assertTrue(requests.single().interfaceScale < state.interfaceScale)
        }
        node("appearance-scale-value").assertTextEquals("100%")
        assertEquals(0, fixture.opens.get())
    }

    fun testToolsKeepOnlyRequestedEntriesAndSkillsLivesInExtensions() {
        val events = mutableListOf<String>()
        showHome(events)
        for (title in listOf("Linux 工具环境", "Agent任务偏好")) {
            reveal("settings-row:$title", "设置").assertHasClickAction().assertIsEnabled()
        }
        for (title in listOf("工作区与文件", "终端", "浏览器", "平台只读工具与私密出站", "Root 与系统增强", "悬浮窗权限")) {
            node("settings-row:$title").assertDoesNotExist()
        }
        reveal("settings-row:Linux 工具环境", "设置").assertTextContains("运行环境与可选工具")
        capture("tools-refined")
        reveal("settings-row:Skills", "设置").performClick()
        ui.runOnIdle { assertEquals(listOf("SKILLS"), events) }
    }

    fun testSystemBarIconsFollowThemeAndActivityRecreation() {
        val preferences = activity.getSharedPreferences("appearance-settings", android.content.Context.MODE_PRIVATE)
        val hadTheme = preferences.contains("theme")
        val oldTheme = preferences.getString("theme", null)
        fun assertBars(dark: Boolean) {
            ui.waitForIdle()
            mainThread {
                val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(!dark, bars.isAppearanceLightStatusBars)
                assertEquals(!dark, bars.isAppearanceLightNavigationBars)
            }
        }
        try {
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            for ((label, dark) in listOf("浅色" to false, "深色" to true)) {
                node("settings-radio:$label").performClick().assertIsSelected()
                assertBars(dark)
            }
            activity = recreateActivity(activity)
            assertBars(true)
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:跟随系统").performClick().assertIsSelected()
            val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertBars(systemDark)
            node("settings-radio:浅色").performClick()
            backToPage(activity, "page:设置"); backToChat(activity)
            assertBars(false)
        } finally {
            val editor = preferences.edit()
            if (hadTheme) editor.putString("theme", oldTheme) else editor.remove("theme")
            check(editor.commit())
        }
    }

    fun testAppearanceSurvivesActivityRecreation() {
        val preferences = activity.getSharedPreferences("appearance-settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        try {
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:深色").performClick()
            val black = reveal("settings-switch:纯黑背景", "外观与主题")
            if (preferences.getBoolean("pure_black", false)) black.performClick()
            black.performClick().assertIsOn()
            activity = recreateActivity(activity)
            assertEquals("dark", preferences.getString("theme", null))
            assertTrue(preferences.getBoolean("pure_black", false))
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:深色").assertIsSelected()
            reveal("settings-switch:纯黑背景", "外观与主题").assertIsOn()
        } finally {
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            check(editor.commit())
        }
    }

    fun testModelProvidersEntryShowsListAndPrimaryBackReturnsThroughSettings() {
        setDraft("provider navigation remains unsent")
        openProviderManagement()
        node("page:主模型服务商").assertDoesNotExist()
        reveal("provider-open-primary", "模型提供商").performClick()
        awaitModelEditor()
        reveal("settings-radio:配置").assertIsSelected()
        node("model-local-manage").assertDoesNotExist()
        backToPage(activity, "page:模型提供商")
        node("page:主模型服务商").assertDoesNotExist()
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("provider navigation remains unsent")
    }

    fun testSeparateFixturesNeverShareThePrimaryCatalog() {
        assertNotNull(fixture.application.filesDir)
        assertTrue(fixture.application.filesDir.isAbsolute)
        assertTrue(fixture.application.filesDir.isDirectory)
        val catalog = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        RunControlFixture(instrumentation.targetContext).use { other ->
            awaitControl("second isolated controller ready") { other.controller.ready }
            val otherCatalog = checkNotNull(other.settings.localModelCatalogSnapshot())
            assertFalse(catalog.providerIdentity == otherCatalog.providerIdentity)
            assertTrue(other.settings.modelCatalog.deleteProvider(otherCatalog.providerIdentity))
            assertNull(other.settings.localModelCatalogSnapshot())
            assertEquals(catalog, fixture.settings.localModelCatalogSnapshot())
            assertFalse(fixture.application.filesDir == other.application.filesDir)
        }
    }

    fun testMissingCatalogKeepsBothTabsWithoutRegisteringAProviderIdentity() {
        val editor = fixture.controller.providers.editor(app.daiyu.android.storage.ProviderRegistry.PRIMARY)
        fixture.controller.providers.withEditor(editor) { store ->
            val catalog = checkNotNull(store.localModelCatalogSnapshot())
            assertTrue(store.modelCatalog.deleteProvider(catalog.providerIdentity))
        }
        assertNull(fixture.controller.providers.withEditor(editor) { it.localModelCatalogSnapshot() })
        val before = fixture.settings.load()
        val registry = fixture.controller.providers.snapshot()
        openModels()
        selectProviderTab("模型")
        reveal("model-catalog-fetch").assertHasNoClickAction()
        ui.onNodeWithText("请先在配置页保存 Base URL 和 API Key，再添加或拉取模型。").assertExists()
        assertNull(fixture.controller.providers.withEditor(editor) { it.localModelCatalogSnapshot() })
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(before, fixture.settings.load())
        selectProviderTab("配置")
        node("settings-field:模型名").assertDoesNotExist()
        backToPage(activity, "page:模型提供商")
        assertNull(fixture.controller.providers.withEditor(editor) { it.localModelCatalogSnapshot() })
        assertEquals(0, fixture.opens.get())
    }

    fun testPrimaryProviderDirectorySavePreservesDetailAndSettingsPredecessor() {
        setDraft("primary save remains unsent")
        val before = fixture.settings.load()
        val endpoint = before.endpoint.trimEnd('/') + "/directory-save"
        openModels()
        reveal("settings-radio:配置").assertIsSelected()
        editModel("Base URL", endpoint)
        saveModel()
        awaitModelEditor()
        assertEquals(before.copy(endpoint = endpoint), fixture.settings.load())
        mainThread { assertEquals("providers", invokeHost(activity, "getRetainedPage")) }
        node("chat-draft").assertDoesNotExist()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("provider-management")
        node("page:主模型服务商").assertDoesNotExist()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("page:设置")
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("chat-draft")
        assertDraft("primary save remains unsent")
        assertEquals(0, fixture.opens.get())
    }
}
