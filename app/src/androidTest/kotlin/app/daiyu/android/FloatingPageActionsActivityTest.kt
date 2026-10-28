package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.haptics.miuix.TextButton
import app.daiyu.android.ui.layout.FloatingPageActionsLayout

class FloatingPageActionsActivityTest : WiredActivityTestCase() {
    fun testTransparentGapsPassThroughAndTrackActualFooterHeight() {
        for (fontScale in listOf(.9f, 1.5f)) {
            val tall = mutableStateOf(false)
            val visible = mutableStateOf(true)
            var clearance = 0.dp
            var backgroundClicks = 0
            var buttonClicks = 0
            mainThread { activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        FloatingPageActionsLayout(Modifier.fillMaxSize(), content = { bottomSpacerMinHeight ->
                            clearance = bottomSpacerMinHeight
                            Box(Modifier.fillMaxSize().background(Color.Magenta)
                                .clickable { backgroundClicks++ }.testTag("floating-body"))
                        }) {
                            if (visible.value) Row(Modifier.fillMaxWidth().testTag("floating-footer")
                                .navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton("取消", { buttonClicks++ }, modifier = Modifier.weight(1f)
                                    .heightIn(min = if (tall.value) 88.dp else 48.dp).testTag("floating-left"))
                                TextButton("删除", { buttonClicks++ }, modifier = Modifier.weight(1f)
                                    .heightIn(min = if (tall.value) 88.dp else 48.dp).testTag("floating-right"))
                            }
                        }
                    }
                }
            } }
            for (expanded in listOf(false, true)) {
                ui.runOnIdle { tall.value = expanded }
                awaitVisualIdle()
                val body = node("floating-body").fetchSemanticsNode().boundsInRoot
                val footer = node("floating-footer").fetchSemanticsNode()
                val left = node("floating-left").fetchSemanticsNode().boundsInRoot
                val right = node("floating-right").fetchSemanticsNode().boundsInRoot
                val gap = Offset((left.right + right.left) / 2f, left.center.y) - body.topLeft
                val pixels = captureScreenNode(node("floating-body")).toPixelMap()
                val pixel = pixels[gap.x.toInt(), gap.y.toInt()]
                val referenceY = ((footer.boundsInRoot.top - body.top) / 2f).toInt()
                val reference = pixels[gap.x.toInt(), referenceY]
                val buttonCenter = left.center - body.topLeft
                val buttonPixel = pixels[buttonCenter.x.toInt(), buttonCenter.y.toInt()]
                fun channelDifference(a: Color, b: Color): Float = maxOf(
                    kotlin.math.abs(a.red - b.red),
                    kotlin.math.abs(a.green - b.green),
                    kotlin.math.abs(a.blue - b.blue),
                )
                val context = "fontScale=$fontScale expanded=$expanded gap=$gap " +
                    "pixel=$pixel reference=$reference button=$buttonPixel"
                assertTrue("uncovered reference contains the magenta body: $context",
                    reference.red - reference.green > .5f && reference.blue - reference.green > .5f)
                assertTrue("gap exposes the underlying content: $context", channelDifference(pixel, reference) < .03f)
                assertTrue("screen capture includes the overlaid button: $context", channelDifference(buttonPixel, reference) > .1f)
                ui.runOnIdle {
                    assertEquals(footer.size.height.toFloat(), with(footer.layoutInfo.density) { clearance.toPx() }, 1f)
                }
                val previous = backgroundClicks
                node("floating-body").performTouchInput { click(gap) }
                ui.runOnIdle { assertEquals(previous + 1, backgroundClicks) }
                node("floating-left").performTouchInput { click() }
                ui.runOnIdle { assertEquals(previous + 1, backgroundClicks) }
            }
            ui.runOnIdle { assertEquals(2, buttonClicks); visible.value = false }
            awaitVisualIdle()
            ui.runOnIdle { assertEquals(0.dp, clearance) }
        }
    }
}
