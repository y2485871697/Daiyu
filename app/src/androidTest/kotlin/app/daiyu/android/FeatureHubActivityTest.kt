package app.daiyu.android

import androidx.compose.ui.semantics.getOrNull
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.FeatureCatalog
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.TopBarBackdrop
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import app.daiyu.android.ui.components.topBarContainerColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The old FeatureHub entry contract now belongs to the retained Compose settings home. */
class FeatureHubActivityTest : WiredActivityTestCase() {
    private val entries get() = FeatureCatalog.groups.flatMap { it.entries }
    private fun openHub() { menu("设置") }
    private fun entry(id: String): SemanticsNodeInteraction {
        val item = entries.single { it.id == id }
        reveal("settings-search", "设置").performTextReplacement(item.title)
        return reveal("settings-row:${item.title}", "设置")
    }
    fun testCatalogSlotsAppearOutsideExistingChatTreeWithoutStateLabels() {
        openHub()
        entries.forEach { item -> entry(item.id).assertIsEnabled().assertTextContains(item.title) }
        ui.onAllNodes(SemanticsMatcher("forbidden state marker") { semantics ->
            val texts = semantics.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
            texts.any { text -> listOf("尚未接入", "未实现", "占位", "开发中").any { it in text.text } }
        }, useUnmergedTree = true).assertCountEquals(0)
        backToChat(activity); node("chat-draft").assertExists(); node("chat-messages").assertExists()
    }
    fun testUnwiredButtonsDoNothingAndKeepDraftDatabaseSettingsAndWindow() {
        setDraft("Gemini UI 草稿"); val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        openHub()
        val implemented = setOf("P01", "P15", "P16", "P24", "P29", "P30", "P31", "P35", "P36", "P40")
        entries.filter { it.route == null && it.id !in implemented }.forEach { item ->
            entry(item.id).performClick(); node("page:设置").assertExists()
            assertEquals(before, fixture.database.exportBackup()); assertEquals(settings, fixture.settings.loadForEditing().settings)
            assertNull(fixture.controller.active); assertEquals(0, fixture.opens.get())
        }
        backToChat(activity); assertDraft("Gemini UI 草稿")
    }
    fun testConnectedModelEntryOpensTheExistingSettingsWithoutSaving() {
        setDraft("原草稿"); val before = fixture.settings.loadForEditing().settings
        openHub(); entry("P02").performClick()
        modelField("Base URL").assertEditableText(before.endpoint)
        modelField("模型名").assertEditableText(before.model)
        backToChat(activity); assertDraft("原草稿")
        assertEquals(0, fixture.opens.get()); assertEquals(before, fixture.settings.loadForEditing().settings)
    }
    fun testRecreationClosesHubAndStaleButtonsCannotOpenTheNewActivity() {
        setDraft("重建保留草稿"); openHub(); entry("R03")
        val oldOpen = retainedClick(activity, "settings-row:模型设置")
        val old = activity; activity = recreateActivity(activity)
        mainThread { oldOpen() }
        node("page:模型设置").assertDoesNotExist(); node("page:设置").assertDoesNotExist()
        assertDraft("重建保留草稿"); assertTrue(old.isDestroyed)
        openHub(); node("page:设置").assertExists(); backToChat(activity)
        assertEquals(0, fixture.opens.get())
    }
    fun testChatControlNavigationDoesNotSendPauseOrStopAnActiveRun() {
        val session = fixture.session(holdSeconds = 120)
        setDraft("运行正文"); node("chat-send").performClick(); setDraft("保留追加草稿")
        assertTrue(session.entered.await(5, TimeUnit.SECONDS)); val owner = checkNotNull(fixture.controller.active)
        try {
            openHub()
            listOf("P11", "P12", "P13", "P23").forEach { id ->
                entry(id).performClick(); node("page:设置").assertExists()
                assertEquals(owner.id, fixture.controller.active!!.id)
                assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
                assertEquals(1L, session.cancelled.count); assertEquals(1, fixture.opens.get())
            }
            backToChat(activity); assertDraft("保留追加草稿")
        } finally { session.release.countDown() }
        awaitControl("original finishes") { fixture.controller.active == null }; assertEquals(1, fixture.opens.get())
    }
    fun testButtonsFromPreviousDetailPageAreInert() {
        openHub(); entry("R03"); val oldOpen = retainedClick(activity, "settings-row:模型设置")
        backToChat(activity); menu("设置"); entry("R12").performClick()
        mainThread { oldOpen() }
        node("page:备份与导入").assertExists(); node("page:模型设置").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testOriginalManageAndUsagePagesOpenAndBackRestoresRealChatDraft() {
        setDraft("原聊天草稿")
        listOf("P22" to R.string.history_page_title, "P21" to R.string.stats_page_title).forEach { (id, title) ->
            openHub(); entry(id).performClick()
            ui.onAllNodesWithText(instrumentation.targetContext.getString(title)).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
            backToChat(activity); assertDraft("原聊天草稿")
        }
        assertEquals(0, fixture.opens.get()); assertTopBarBlurCompatibility()
    }
    private fun assertTopBarBlurCompatibility() {
        AppearanceTopBarBlurStyle.entries.forEach { style -> listOf(true, false).forEach { enabled ->
            val drawn = CountDownLatch(1); val observation = AtomicReference<Triple<Boolean, Boolean, Boolean>>()
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(blurEnabled = enabled, topBarBlurStyle = style), applyInterfaceScale = true) {
                        val backdrop = rememberTopBarBackdrop(); val capture = Modifier.captureForTopBar(backdrop)
                        val solid = topBarContainerColor(backdrop) == MiuixTheme.colorScheme.surface
                        SideEffect { observation.set(Triple(backdrop != null, solid, capture === Modifier)) }
                        Box(Modifier.fillMaxSize().drawWithContent { drawContent(); drawn.countDown() }) {
                            Box(Modifier.fillMaxSize().then(capture))
                            TopBarBackdrop(backdrop) { Box(Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
            ui.waitForIdle()
            assertTrue("Top bar draw: $style enabled=$enabled", drawn.await(5, TimeUnit.SECONDS))
            val expected = enabled && android.os.Build.VERSION.SDK_INT >= 33; val actual = checkNotNull(observation.get())
            assertEquals(expected, actual.first); assertEquals(!expected, actual.second); assertEquals(!expected, actual.third)
        } }
    }
    fun testOriginalSidebarOpensOverChatAndBackDoesNotLoseOrSendDraft() {
        setDraft("抽屉草稿"); ui.onNodeWithContentDescription("打开侧边栏").performClick()
        ui.waitForIdle()
        mainThread {
            val sidebar = MainActivity::class.java.getDeclaredField("sidebarVisible\$delegate").apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
            assertEquals(true, sidebar.value)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertDraft("抽屉草稿"); node("chat-draft").assertIsEnabled()
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
