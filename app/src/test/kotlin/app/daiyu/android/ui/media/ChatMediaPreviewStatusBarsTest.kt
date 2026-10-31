package app.daiyu.android.ui.media

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("DEPRECATION")
class ChatMediaPreviewStatusBarsTest {
    @Test
    fun hidingAndRestoringDialogDoesNotTouchActivity() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = owner.get()
            val dialog = Dialog(activity)
            dialog.show()
            val window = requireNotNull(dialog.window)
            val activityFlags = activity.window.decorView.systemUiVisibility
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val behavior = controller.systemBarsBehavior
            val lease = ChatMediaPreviewStatusBars(window)
            assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            assertEquals(activityFlags, activity.window.decorView.systemUiVisibility)
            lease.close()
            lease.close()
            assertFalse(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            assertEquals(behavior, controller.systemBarsBehavior)
            assertEquals(activityFlags, activity.window.decorView.systemUiVisibility)
            dialog.dismiss()
        } finally {
            owner.pause().stop().destroy()
        }
    }

    @Test
    fun fullscreenWindowWithoutInsetsStaysHiddenAfterDisposal() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // Do not show/attach the dialog: there are no root window insets yet.
            val dialog = Dialog(owner.get())
            val window = requireNotNull(dialog.window)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = 0
            ChatMediaPreviewStatusBars(window).close()
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
            assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        } finally {
            owner.pause().stop().destroy()
        }
    }

    @Test
    fun originallyHiddenStatusBarStaysHiddenAfterDisposal() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val dialog = Dialog(owner.get())
            dialog.show()
            val window = requireNotNull(dialog.window)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            ChatMediaPreviewStatusBars(window).close()
            assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            dialog.dismiss()
        } finally {
            owner.pause().stop().destroy()
        }
    }
}
