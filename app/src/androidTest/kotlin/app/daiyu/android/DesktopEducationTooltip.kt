package app.daiyu.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed

/** Dismiss only the observed SystemUI education window, never application content or errors. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun dismissDesktopEducationTooltip(
    instrumentation: Instrumentation,
    ui: ComposeTestRule,
    blank: SemanticsNodeInteraction,
) {
    val automation = instrumentation.uiAutomation
    val originalFlags = automation.serviceInfo.flags
    try {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val titlePattern = Regex("Desktop Windowing Education Tooltip of Task=[0-9]+")
        fun educationWindows() = automation.windows.filter {
            it.title?.toString()?.matches(titlePattern) == true
        }
        var observed = false
        var ready: android.view.accessibility.AccessibilityWindowInfo? = null
        try {
            ui.waitUntil(5_000) {
                val windows = educationWindows()
                if (windows.isEmpty()) false else {
                    observed = true
                    check(windows.size == 1) { "Multiple desktop education windows: ${windows.map { it.title }}" }
                    val candidate = windows.single()
                    val root = candidate.root
                    if (root == null) false else {
                        check(root.packageName?.toString() == "com.android.systemui") { "Unexpected desktop education owner" }
                        val verified = root.findAccessibilityNodeInfosByText("Tap handle to access menu and display options").any {
                            it.text?.toString() == "Tap handle to access menu and display options"
                        }
                        if (verified) ready = candidate
                        verified
                    }
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            if (observed) throw AssertionError("Observed desktop education window did not publish its verified content", timeout)
            android.util.Log.i("DaiyuDesktopEducation", "No tooltip observed within discovery deadline")
            return
        }
        val window = checkNotNull(ready)
        val bounds = Rect().also(window::getBoundsInScreen)
        val node = blank.assertIsDisplayed().fetchSemanticsNode()
        val center = node.positionOnScreen + androidx.compose.ui.geometry.Offset(node.size.width / 2f, node.size.height / 2f)
        check(node.size.width > 0 && node.size.height > 0 && center.x.isFinite() && center.y.isFinite())
        check(center.x < bounds.left - 1f || center.x > bounds.right + 1f ||
            center.y < bounds.top - 1f || center.y > bounds.bottom + 1f) {
            "Fixture blank overlaps desktop education: $bounds"
        }
        val downTime = android.os.SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                action, center.x, center.y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) { "Desktop education touch was rejected" } }
            finally { event.recycle() }
        }
        try { inject(android.view.MotionEvent.ACTION_DOWN) }
        finally { inject(android.view.MotionEvent.ACTION_UP) }
        ui.waitUntil(5_000) { educationWindows().isEmpty() }
        android.util.Log.i("DaiyuDesktopEducation", "Observed SystemUI tooltip dismissed once; window removed")
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
    }
}
