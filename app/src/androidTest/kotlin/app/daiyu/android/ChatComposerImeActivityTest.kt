package app.daiyu.android

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.test.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

class ChatComposerImeActivityTest : WiredActivityTestCase() {
    private fun composeHost(view: View): View? {
        if (view is AbstractComposeView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            composeHost(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun screenBottom(tag: String): Float {
        val bounds = node(tag).fetchSemanticsNode().boundsInRoot
        val location = IntArray(2)
        mainThread { checkNotNull(composeHost(activity.window.decorView)).getLocationOnScreen(location) }
        return bounds.bottom + location[1]
    }

    fun testKeyboardKeepsComposerAboveImeAndDoesNotPanHeader() {
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        setDraft("键盘位置回归草稿")
        dismissIme()
        val before = screenBottom("chat-composer-surface")
        val headerBefore = screenBottom("chat-top-bar")
        val database = fixture.database.exportBackup()
        repeat(2) {
            node("chat-draft").performClick()
            mainThread {
                val root = activity.window.decorView
                val focus = checkNotNull(root.findFocus())
                activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
            }
            ui.waitUntil(5_000) {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            ui.waitForIdle()
            var keyboardTop = 0
            mainThread {
                val decor = activity.window.decorView
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                keyboardTop = location[1] + decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
            val bottom = screenBottom("chat-composer-surface")
            val density = activity.resources.displayMetrics.density
            val gap = keyboardTop - bottom
            assertTrue("Composer must sit just above IME, gap=$gap", gap >= -2f && gap <= 32f * density)
            assertTrue("Keyboard must lift composer", bottom < before - 48f * density)
            assertEquals("Header must not pan off-screen", headerBefore, screenBottom("chat-top-bar"), 2f)
            node("chat-draft").assertIsDisplayed()
            dismissIme()
            ui.waitUntil(5_000) { abs(screenBottom("chat-composer-surface") - before) <= 2f }
            assertDraft("键盘位置回归草稿")
            assertEquals(database, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
    }
}
