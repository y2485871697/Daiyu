package app.daiyu.android

import android.os.Build
import android.content.Context
import app.daiyu.android.data.model.AppearanceSettingsStore
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.test.platform.graphics.HardwareRendererCompat

class SidebarShortcutNavigationTest : WiredActivityTestCase() {
    private fun openDock(id: String, page: String) {
        dismissIme()
        ui.onNode(hasContentDescription("打开侧边栏") and hasClickAction()).performClick()
        node("drawer-dock-$id").performClick()
        awaitUniqueTag(page)
    }

    private fun backOnce(system: Boolean) {
        dismissIme()
        if (system) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        else ui.onNode(hasContentDescription(activity.getString(R.string.action_back)) and
            hasClickAction()).assertIsDisplayed().performClick()
        ui.waitForIdle()
    }

    private fun assertChat() {
        mainThread {
            assertEquals("chat", invokeHost(activity, "getRetainedPage"))
            assertEquals(false, invokeHost(activity, "getSidebarVisible"))
            assertFalse(activity.isFinishing)
        }
        awaitUniqueTag("chat-draft")
        node("page:设置").assertDoesNotExist()
    }

    fun testAllFiveDockPagesReturnToConversationWithOneBack() {
        setDraft("侧栏快捷入口草稿")
        val before = fixture.database.exportBackup()
        for (system in listOf(false, true)) {
            for ((id, page) in listOf(
                "permissions" to "page:权限检查",
                "models" to "page:模型提供商",
                "skills" to "page:技能",
                "usage" to "usage-stats-screen",
                "settings" to "page:设置",
            )) {
                openDock(id, page)
                backOnce(system)
                assertChat()
                assertDraft("侧栏快捷入口草稿")
            }
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testSharedPagesRememberLatestEntryWithoutChangingSettingsReturnState() {
        setDraft("按来源返回草稿")
        for (system in listOf(false, true)) {
            for ((id, title, page) in listOf(
                Triple("models", "模型提供商", "page:模型提供商"),
                Triple("skills", "Skills", "page:技能"),
            )) {
                openSettings()

                val row = reveal("settings-row:$title", "设置")
                val before = row.fetchSemanticsNode().boundsInRoot
                val scroll = node("page:设置").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
                row.performClick()
                awaitUniqueTag(page)
                backOnce(system)
                node("page:设置").assertIsDisplayed()
                node("settings-search").assertDoesNotExist()
                val restored = node("settings-row:$title").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(before.top, restored.top, 2f)
                assertEquals(scroll, node("page:设置").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .00002f)
                backOnce(system)
                assertChat()
                // Re-entry from the dock must overwrite the prior settings parent.
                openDock(id, page)
                backOnce(system)
                assertChat()
                assertDraft("按来源返回草稿")
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testProviderDetailKeepsItsParentBeforeReturningToConversation() {
        setDraft("提供商详情返回草稿")
        for (system in listOf(false, true)) {
            openDock("models", "page:模型提供商")
            reveal("provider-open-primary", "模型提供商").performClick()
            awaitModelEditor()
            backOnce(system)
            node("page:模型提供商").assertIsDisplayed()
            backOnce(system)
            assertChat()
            assertDraft("提供商详情返回草稿")
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsDockSwipeCancelAndCommitReturnToClosedConversation() {
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            setDraft("侧栏手势保持的会话草稿")
            val before = fixture.database.exportBackup()
            captureScreenNode(node("page-back-surface"))
            openDock("settings", "page:设置")
            ui.mainClock.autoAdvance = false
            val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
            node("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .12f, height * .6f), 16)
                moveTo(Offset(width * .18f, height * .6f), 16)
                moveTo(Offset(width * .40f, height * .6f), 80)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            node("page-back-target").assertExists()
            node("page-back-scrim").assertExists()
            assertTrue(node("page-back-surface").fetchSemanticsNode().boundsInRoot.left > initial.left + .25f * initial.width)
            mainThread { assertEquals("settings", invokeHost(activity, "getRetainedPage")) }
            node("page-back-host").performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(1000)
            node("page:设置").assertIsDisplayed()
            node("page-back-target").assertDoesNotExist()
            node("page-back-host").performTouchInput {
                swipe(Offset(width * .05f, height * .6f), Offset(width * .90f, height * .6f), 500)
            }
            ui.mainClock.advanceTimeBy(1000)
            ui.mainClock.autoAdvance = true
            assertChat()
            assertDraft("侧栏手势保持的会话草稿")
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testAgentTaskPreferenceUsesItsSettingsPredecessorAndNeverSkipsToChat() {
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        val appearancePreferences = activity.getSharedPreferences(AppearanceSettingsStore.FILE_NAME, Context.MODE_PRIVATE)
        val originalPreferences = appearancePreferences.all.toMap()
        val appearanceStore = AppearanceSettingsStore(appearancePreferences)
        val originalAppearance = appearanceStore.load()
        try {
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            mainThread { invokeHost(activity, "saveAppearance", originalAppearance.copy(predictiveBackEnabled = true)) }
            assertTrue(appearanceStore.load().predictiveBackEnabled)
            openSettings()
            val row = reveal("settings-row:Agent任务偏好", "设置")
            val before = row.fetchSemanticsNode().boundsInRoot
            captureScreenNode(node("page-back-surface"))
            row.performClick()
            awaitUniqueTag("page:Agent任务偏好")
            ui.mainClock.autoAdvance = false
            if (Build.VERSION.SDK_INT >= 34) {
                val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
                for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .5f, edge)) }
                    ui.mainClock.advanceTimeByFrame()
                    node("page-back-target").assertExists()
                    assertEquals(initial.left + .5f * initial.width,
                        node("page-back-surface").fetchSemanticsNode().boundsInRoot.left, 2f)
                    mainThread { assertEquals("settings", invokeHost(activity, "getRetainedPage")) }
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    ui.mainClock.advanceTimeBy(1000)
                    node("page:Agent任务偏好").assertIsDisplayed()
                }
            }
            node("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .12f, height * .6f), 16)
                moveTo(Offset(width * .18f, height * .6f), 16)
                moveTo(Offset(width * .70f, height * .6f), 80)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            node("page-back-target").assertExists()
            node("page-back-host").performTouchInput { up() }
            ui.mainClock.advanceTimeBy(1000)
            ui.mainClock.autoAdvance = true
            node("page:Agent任务偏好").assertDoesNotExist()
            node("page:设置").assertIsDisplayed()
            assertEquals(before.top, node("settings-row:Agent任务偏好").fetchSemanticsNode().boundsInRoot.top, 2f)
            mainThread { assertEquals("settings", invokeHost(activity, "getRetainedPage")) }
            backOnce(system = true)
            assertChat()
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = true
            try {
                mainThread { invokeHost(activity, "saveAppearance", originalAppearance) }
            } finally {
                try {
                    val editor = appearancePreferences.edit().clear()
                    originalPreferences.forEach { (key, value) -> when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                        null -> editor.remove(key)
                        else -> error("Unsupported appearance preference type: $key")
                    } }
                    check(editor.commit())
                } finally {
                    if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
                }
            }
        }
    }
}
