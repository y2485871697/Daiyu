package app.daiyu.android.vd

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Internal temporary secondary-display cover; never appears in recents. */
class VirtualDisplayAnchorActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Context.display is API 30. The activity's WindowManager is display-bound on API 26 too.
        @Suppress("DEPRECATION")
        val displayId = windowManager.defaultDisplay.displayId
        if (displayId == 0 || intent?.data?.scheme != LAUNCH_SCHEME) {
            finishAndRemoveTask()
            return
        }
        setContentView(TextView(this).apply {
            text = "正在移交后台任务"
            setBackgroundColor(android.graphics.Color.BLACK)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        })
    }

    companion object {
        const val LAUNCH_SCHEME = "daiyu-vd-anchor"
        // Manifest contract, not the app's ordinary task affinity; see docs/integration/vd-owner-stage1.md.
        const val TASK_AFFINITY = "app.daiyu.android.vd.anchor"
    }
}
