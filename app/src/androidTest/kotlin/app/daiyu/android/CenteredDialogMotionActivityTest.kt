package app.daiyu.android

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.ScaleAnimation
import android.view.animation.Transformation
import android.view.animation.TranslateAnimation
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
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.prepareDaiyuCenterMotion
import app.daiyu.android.ui.haptics.material.TextButton

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

    private fun assertOneShot(animation: Animation, diagnostic: String) {
        assertEquals("Fade must not repeat: $diagnostic", 0, animation.repeatCount)
        assertEquals("Fade starts immediately: $diagnostic", 0L, animation.startOffset)
        if (animation is AnimationSet) animation.animations.forEach { assertOneShot(it, diagnostic) }
    }

    private fun assertStationaryFade(diagnostic: String, entering: Boolean = false,
        expectedDuration: Long? = null, create: () -> Animation) {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (size in listOf(intArrayOf(320, 640, 1080, 1920), intArrayOf(1080, 1920, 1080, 1920))) {
            val prototype = create().apply { initialize(size[0], size[1], size[2], size[3]) }
            assertOneShot(prototype, diagnostic)
            val duration = prototype.computeDurationHint()
            assertTrue("Finite positive fade duration: $diagnostic", duration in 1L..10_000L)
            expectedDuration?.let { assertEquals("Short dialog fade: $diagnostic", it, duration) }
            for (step in 0..64) {
                val loaded = create().apply {
                    initialize(size[0], size[1], size[2], size[3])
                    startTime = 0L
                }
                val transformation = Transformation()
                loaded.getTransformation(duration * step / 64L, transformation)
                val matrix = FloatArray(9)
                transformation.matrix.getValues(matrix)
                val sample = "$diagnostic type=${loaded.javaClass.simpleName} " +
                    "size=${size.contentToString()} step=$step alpha=${transformation.alpha} matrix=${matrix.contentToString()}"
                for (index in identity.indices) {
                    assertEquals("Centered fade must have identity matrix: $sample", identity[index], matrix[index], .0001f)
                }
                assertTrue("Finite fade alpha in [0,1]: $sample",
                    transformation.alpha.isFinite() && transformation.alpha in 0f..1f)
                if (step == 0) assertEquals("Fade start: $sample", if (entering) 0f else 1f, transformation.alpha, .0001f)
                if (step == 64) assertEquals("Fade end: $sample", if (entering) 1f else 0f, transformation.alpha, .0001f)
            }
        }
    }

    private fun assertWindowFades(context: Context, animation: Int) {
        for ((attribute, duration, entering) in listOf(
            Triple(android.R.attr.windowEnterAnimation, 220L, true),
            Triple(android.R.attr.windowExitAnimation, 150L, false),
        )) {
            val values = context.obtainStyledAttributes(animation, intArrayOf(attribute))
            val resource = try { values.getResourceId(0, 0) } finally { values.recycle() }
            assertTrue("Dialog style supplies an animation", resource != 0)
            val diagnostic = "style=${context.resources.getResourceName(animation)} resource=${context.resources.getResourceName(resource)}"
            android.util.Log.i("CenteredMotionDiag", diagnostic)
            assertStationaryFade(diagnostic, entering, expectedDuration = duration) {
                AnimationUtils.loadAnimation(context, resource)
            }
        }
    }

    fun testFadeVerifierAcceptsIdentityTranslateAndScale() {
        assertStationaryFade("identity translation and scale") {
            AnimationSet(false).apply {
                addAnimation(AlphaAnimation(1f, 0f).apply { duration = 160L })
                addAnimation(TranslateAnimation(0f, 0f, 0f, 0f).apply { duration = 160L })
                addAnimation(ScaleAnimation(1f, 1f, 1f, 1f).apply { duration = 160L })
            }
        }
    }

    fun testFadeVerifierRejectsRealMotionAndWrongAlphaEndpoints() {
        val invalid = listOf<() -> Animation>(
            { TranslateAnimation(0f, 12f, 0f, 0f).apply { duration = 160L } },
            { ScaleAnimation(1f, .9f, 1f, .9f).apply { duration = 160L } },
            { AlphaAnimation(1f, 1f).apply { duration = 160L } },
            { AlphaAnimation(.5f, 0f).apply { duration = 160L } },
            { AlphaAnimation(1f, 0f).apply { duration = 160L; repeatCount = 1; repeatMode = Animation.REVERSE } },
            { AnimationSet(false).apply {
                addAnimation(AlphaAnimation(1f, 0f).apply { duration = 160L; repeatCount = 1; repeatMode = Animation.REVERSE })
            } })
        for ((index, create) in invalid.withIndex()) {
            var rejected = false
            try { assertStationaryFade("negative-$index", create = create) }
            catch (_: junit.framework.AssertionFailedError) { rejected = true }
            assertTrue("Non-fade must be rejected: $index", rejected)
        }
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
                assertWindowFades(context, expected)
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

    fun testCollaborationSheetCanUseCenterFadeAndDisposeOnDismiss() {
        show { dismiss ->
            ConversationAgentSheet(dismiss, bottomSheetMotion = false) {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("collaboration-close")) { Text("完成") }
            }
        }
        assertWindowAnimation("collaboration-close")
        closeAndAssertDisposed("collaboration-close")
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
            assertWindowFades(context, expected)
        }
        assertEquals(0, fixture.opens.get())
    }
}
