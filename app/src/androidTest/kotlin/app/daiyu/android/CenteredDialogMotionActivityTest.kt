package app.daiyu.android

import android.app.Dialog
import android.content.Context
import android.util.Xml
import android.view.ContextThemeWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuAlertDialog
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.DaiyuDialog
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.prepareDaiyuCenterMotion
import app.daiyu.android.ui.haptics.material.TextButton
import org.xmlpull.v1.XmlPullParser

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CenteredDialogMotionActivityTest : WiredActivityTestCase() {
    private var dismissals = 0
    private var disposals = 0

    private fun show(content: @Composable (() -> Unit) -> Unit) {
        dismissals = 0
        disposals = 0
        mainThread {
            val open = mutableStateOf(true)
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    Box(Modifier.fillMaxSize()) {
                        if (open.value) {
                            DisposableEffect(Unit) { onDispose { disposals++ } }
                            content { dismissals++; open.value = false }
                        }
                    }
                }
            }
        }
    }

    private fun expectedDialogContext(): Context {
        val values = activity.obtainStyledAttributes(intArrayOf(android.R.attr.dialogTheme))
        val theme = try { values.getResourceId(0, 0) } finally { values.recycle() }
        assertTrue("Activity supplies a dialog overlay", theme != 0)
        return ContextThemeWrapper(activity, theme)
    }

    private fun themedAnimation(context: Context): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowAnimationStyle))
        return try { values.getResourceId(0, 0) } finally { values.recycle() }
    }

    private fun assertExitIsAlphaFade(context: Context, animation: Int) {
        val values = context.obtainStyledAttributes(animation, intArrayOf(android.R.attr.windowExitAnimation))
        val exit = try { values.getResourceId(0, 0) } finally { values.recycle() }
        assertTrue("Dialog style supplies an exit animation", exit != 0)
        val parser = context.resources.getXml(exit)
        var foundAlpha = false
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    assertFalse("Centered exit must not translate", parser.name == "translate")
                    assertFalse("Centered exit must not scale", parser.name == "scale")
                    if (parser.name == "alpha") {
                        val alpha = context.obtainStyledAttributes(Xml.asAttributeSet(parser),
                            intArrayOf(android.R.attr.fromAlpha, android.R.attr.toAlpha))
                        try {
                            assertEquals(1f, alpha.getFloat(0, Float.NaN), 0.0001f)
                            assertEquals(0f, alpha.getFloat(1, Float.NaN), 0.0001f)
                        } finally { alpha.recycle() }
                        foundAlpha = true
                    }
                }
                parser.next()
            }
        } finally { parser.close() }
        assertTrue("Exit contains alpha 1 to 0", foundAlpha)
    }

    private fun assertWindowAnimation(tag: String, platformFade: Boolean = true) {
        val root = node(tag).assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        mainThread {
            val window = (root.view.parent as DialogWindowProvider).window
            if (platformFade) {
                val context = expectedDialogContext()
                val expected = themedAnimation(context)
                assertTrue("Theme supplies a platform dialog animation", expected != 0)
                assertEquals(expected, window.attributes.windowAnimations)
                assertExitIsAlphaFade(context, expected)
            } else {
                assertEquals(0, window.attributes.windowAnimations)
            }
        }
    }

    private fun closeAndAssertDisposed(tag: String) {
        node(tag).performClick()
        node(tag).assertDoesNotExist()
        assertEquals(1, dismissals)
        assertEquals(1, disposals)
        assertEquals(0, fixture.opens.get())
    }

    fun testGenericAndMaterialCenterUsePlatformFadeAndDisposeOnDismiss() {
        show { dismiss ->
            DaiyuDialog(dismiss) {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("center-close")) { Text("关闭") }
            }
        }
        assertWindowAnimation("center-close")
        closeAndAssertDisposed("center-close")
        show { dismiss ->
            DaiyuAlertDialog(dismiss, confirmButton = {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("alert-close")) { Text("确定") }
            }, title = { Text("居中确认") })
        }
        assertWindowAnimation("alert-close")
        closeAndAssertDisposed("alert-close")
    }

    fun testCenteredPreviewAndCompactUsageUseTheSameWindowFade() {
        show { dismiss ->
            PreviewDialog(dismiss) {
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.Center).testTag("preview-close")) {
                    Text("关闭")
                }
            }
        }
        assertWindowAnimation("preview-close")
        closeAndAssertDisposed("preview-close")
        show { dismiss ->
            UsageGlassDialog("Token 用量", dismiss, compact = true) {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("usage-close")) { Text("关闭") }
            }
        }
        assertWindowAnimation("usage-close")
        closeAndAssertDisposed("usage-close")
    }

    fun testBottomPreviewAndChoiceKeepPlatformAnimationDisabled() {
        show { dismiss ->
            PreviewDialog(dismiss, bottomSheet = true) {
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.BottomCenter).size(160.dp)
                    .testTag("bottom-preview")) { Text("关闭") }
            }
        }
        assertWindowAnimation("bottom-preview", platformFade = false)
        closeAndAssertDisposed("bottom-preview")
        show { dismiss -> DaiyuChoiceSheet("选择", listOf("一"), {}, dismiss) }
        assertWindowAnimation("daiyu-choice-sheet", platformFade = false)
        closeAndAssertDisposed("choice-sheet-cancel")
    }

    fun testNativeCenterHasPlatformFadeBeforeShow() {
        mainThread {
            val dialog = Dialog(activity)
            dialog.prepareDaiyuCenterMotion()
            assertFalse(dialog.isShowing)
            val window = checkNotNull(dialog.window)
            val context = expectedDialogContext()
            val expected = themedAnimation(context)
            assertTrue(expected != 0)
            assertEquals(expected, window.attributes.windowAnimations)
            assertExitIsAlphaFade(context, expected)
        }
        assertEquals(0, fixture.opens.get())
    }
}
