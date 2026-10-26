package app.daiyu.android

import androidx.compose.ui.test.*

class RootExitConfirmationActivityTest : WiredActivityTestCase() {
    private fun hasExitPrompt(): Boolean = MainActivity::class.java.getDeclaredField("rootExitToast")
        .apply { isAccessible = true }.get(activity) != null

    private fun pressBack() {
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    fun testRootFirstBackPromptsAndSecondBackFinishesWithoutSendingDraft() {
        setDraft("退出保护保留的草稿")
        dismissIme()
        pressBack()
        mainThread {
            assertFalse(activity.isFinishing)
            assertTrue(hasExitPrompt())
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
        }
        assertEquals("退出保护保留的草稿", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsReturnDoesNotCountAsFirstExitPress() {
        openSettings()
        pressBack()
        node("chat-draft").assertIsDisplayed()
        mainThread { assertFalse(activity.isFinishing); assertFalse(hasExitPrompt()) }
        pressBack()
        mainThread { assertFalse(activity.isFinishing); assertTrue(hasExitPrompt()) }
        assertEquals(0, fixture.opens.get())
    }

    fun testSidebarClosesBeforeRootExitConfirmation() {
        dismissIme()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        pressBack()
        mainThread { assertFalse(activity.isFinishing); assertFalse(hasExitPrompt()) }
        pressBack()
        mainThread { assertFalse(activity.isFinishing); assertTrue(hasExitPrompt()) }
        assertEquals(0, fixture.opens.get())
    }

    fun testKeyboardBackOnlyHidesImeBeforeExitConfirmation() {
        setDraft("键盘返回不退出")
        node("chat-draft").performClick()
        awaitImeFrame(node("chat-draft"), visible = true)
        pressBack()
        awaitImeFrame(node("chat-draft"), visible = false)
        mainThread { assertFalse(activity.isFinishing); assertFalse(hasExitPrompt()) }
        pressBack()
        mainThread { assertFalse(activity.isFinishing); assertTrue(hasExitPrompt()) }
        assertDraft("键盘返回不退出")
        assertEquals(0, fixture.opens.get())
    }
}
