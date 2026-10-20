package app.daiyu.android

import androidx.compose.ui.test.*

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
                reveal("settings-search", "设置").performTextReplacement(title)
                reveal("settings-row:$title", "设置").performClick()
                awaitUniqueTag(page)
                backOnce(system)
                node("page:设置").assertIsDisplayed()
                node("settings-search").assertEditableText(title)
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
}
