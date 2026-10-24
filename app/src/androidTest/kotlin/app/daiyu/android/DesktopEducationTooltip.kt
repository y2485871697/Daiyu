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
        val educationText = "Tap handle to access menu and display options"
        var lastWindows = ""
        fun educationWindows(): List<android.view.accessibility.AccessibilityWindowInfo> {
            val diagnostics = mutableListOf<String>()
            val result = automation.windows.filter { window ->
                if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) false else {
                    val root = window.root
                    val owner = root?.packageName?.toString()
                    val matches = if (owner == "com.android.systemui" && root != null)
                        root.findAccessibilityNodeInfosByText(educationText).count {
                            it.text?.toString() == educationText && it.isVisibleToUser
                        } else 0
                    diagnostics += "id=${window.id} title=${window.title} owner=$owner matches=$matches"
                    check(matches <= 1) { "Ambiguous desktop education text: ${window.id}" }
                    matches == 1
                }
            }
            val summary = diagnostics.joinToString("; ")
            if (summary != lastWindows) {
                android.util.Log.i("DaiyuDesktopEducation", "System window discovery: $summary")
                lastWindows = summary
            }
            return result
        }
        var ready: android.view.accessibility.AccessibilityWindowInfo? = null
        try {
            ui.waitUntil(5_000) {
                val windows = educationWindows()
                if (windows.isEmpty()) false else {
                    check(windows.size == 1) { "Multiple desktop education windows: ${windows.map { it.title }}" }
                    ready = windows.single()
                    true
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            android.util.Log.i("DaiyuDesktopEducation", "No accessibility-verified education target within discovery deadline")
            return
        }
        val window = checkNotNull(ready)
        val windowId = window.id
        android.util.Log.i("DaiyuDesktopEducation", "Verified SystemUI education window id=$windowId title=${window.title}")
        val bounds = Rect().also(window::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0) { "Desktop education bounds missing" }
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
        ui.waitUntil(5_000) {
            automation.windows.none { it.id == windowId } && educationWindows().isEmpty()
        }
        android.util.Log.i("DaiyuDesktopEducation", "Observed SystemUI tooltip dismissed once; window removed")
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
    }
}
