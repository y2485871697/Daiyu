package app.daiyu.android

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

class ChatComposerImeActivityTest : WiredActivityTestCase() {
    private data class Frame(val decorBottom: Int, val imeBottom: Int,
        val composerBottom: Float, val headerBottom: Float)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun settledFrame(visible: Boolean, before: Frame? = null): Frame {
        val surface = node("chat-composer-surface").fetchSemanticsNode()
        val header = node("chat-top-bar").fetchSemanticsNode()
        assertSame(surface.root, header.root)
        val view = (surface.root as ViewRootForTest).view
        val decor = view.rootView
        val token = view.windowToken
        val density = activity.resources.displayMetrics.density
        return awaitStableWindowFrame(view) {
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets == null || view.windowToken != token || !surface.layoutInfo.isPlaced ||
                !header.layoutInfo.isPlaced || insets.isVisible(WindowInsetsCompat.Type.ime()) != visible) null
            else {
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                fun bottom(node: androidx.compose.ui.semantics.SemanticsNode) =
                    node.positionOnScreen.y + node.size.height
                val frame = Frame(location[1] + decor.height, imeBottom, bottom(surface), bottom(header))
                val valid = if (visible) {
                    val keyboardTop = checkNotNull(before).decorBottom - imeBottom
                    val gap = keyboardTop - frame.composerBottom
                    imeBottom > 0 && gap >= -2f && gap <= 32f * density
                } else imeBottom == 0 && (before == null ||
                    abs(frame.composerBottom - before.composerBottom) <= 2f)
                if (valid) frame else null
            }
        }
    }

    private fun assertRealApi26ImeBinding() {
        if (Build.VERSION.SDK_INT != 26) return
        val expected = "jp.co.omronsoft.openwnn/.OpenWnnJAJP"
        var state = ""
        try {
            ui.waitUntil(5_000) {
                state = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("dumpsys -t 2 input_method")
                ).bufferedReader().use { it.readText() }
                state.contains("mCurId=$expected mHaveConnect=true mBoundToMethod=true") &&
                    state.contains("mInputShown=true") && state.contains("packageName=app.daiyu.android ")
            }
        } finally {
            android.util.Log.i("DaiyuImeFixture", state.lineSequence().filter {
                it.contains("mCurId=") || it.contains("mCurMethodId=") ||
                    it.contains("mInputShown=") || it.contains("mServedView=") ||
                    it.contains("packageName=app.daiyu.android ")
            }.joinToString("\n"))
        }
    }

    fun testKeyboardKeepsComposerAboveImeAndDoesNotPanHeader() {
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        val emptyWindow = settledFrame(visible = false)
        setDraft("键盘位置回归草稿")
        // Finish the show initiated by text replacement before requesting the baseline hide.
        awaitImeFrame(node("chat-draft"), visible = true)
        dismissIme()
        val before = settledFrame(visible = false, before = emptyWindow)
        val database = fixture.database.exportBackup()
        repeat(2) {
            // Native IME frames do not advance the injected pointer clock past double-tap time.
            node("chat-draft").performTouchInput {
                advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
                click()
            }
            node("chat-draft").assertIsFocused()
            assertRealApi26ImeBinding()
            val shown = settledFrame(visible = true, before = before)
            val keyboardTop = before.decorBottom - shown.imeBottom
            val bottom = shown.composerBottom
            val density = activity.resources.displayMetrics.density
            val gap = keyboardTop - bottom
            android.util.Log.d("DaiyuImeGeometry", "round=$it before=$before shown=$shown")
            assertTrue("Composer must sit just above IME, gap=$gap", gap >= -2f && gap <= 32f * density)
            assertTrue("Keyboard must lift composer: before=$before shown=$shown", bottom < before.composerBottom - 48f * density)
            assertEquals("Header must not pan off-screen", before.headerBottom, shown.headerBottom, 2f)
            node("chat-draft").assertIsDisplayed()
            if (it == 0) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else dismissIme()
            val restored = settledFrame(visible = false, before = before)
            assertEquals("Composer returns after IME hides", before.composerBottom, restored.composerBottom, 2f)
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
            node("chat-top-bar").assertIsDisplayed()
            assertDraft("键盘位置回归草稿")
            assertEquals(database, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
    }
}
