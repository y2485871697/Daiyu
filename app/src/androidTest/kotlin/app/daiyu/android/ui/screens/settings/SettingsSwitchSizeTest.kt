package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.dismissDesktopEducationTooltip
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

class SettingsSwitchSizeTest : WiredActivityTestCase() {
    private var pixelsPerDp = 1f

    private val reference = Color(0xFF82728F)

    private fun Modifier.switchDiagnosticCanvas() = testTag("switch-diagnostic-canvas").drawBehind {
        drawRect(reference, Offset(size.width - 24f, size.height - 24f), Size(16f, 16f))
    }

    private fun Modifier.educationDismissalBlank() = testTag("switch-education-blank").pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assertTrackWithControl(target: SemanticsNodeInteraction, diagnostic: String, bounds: String) {
        dismissDesktopEducationTooltip(instrumentation, ui, node("switch-education-blank"))
        val canvas = node("switch-diagnostic-canvas").assertIsDisplayed()
        fun screenRect(part: SemanticsNodeInteraction): Rect {
            val node = part.fetchSemanticsNode()
            return node.boundsInRoot.translate(node.positionOnScreen - node.positionInRoot)
        }
        val before = listOf(screenRect(canvas), screenRect(target))
        val control = Rect(before[0].right - 24f, before[0].bottom - 24f,
            before[0].right - 8f, before[0].bottom - 8f)
        check(!before[1].overlaps(control))
        val frame = captureScreenNode(canvas)
        check(before == listOf(screenRect(canvas), screenRect(target))) { "diagnostic geometry changed" }
        val bitmap = frame.asAndroidBitmap()
        val originX = floor(before[0].left.toDouble()).toInt()
        val originY = floor(before[0].top.toDouble()).toInt()
        fun crop(rect: Rect): ImageBitmap {
            val left = floor(rect.left.toDouble()).toInt() - originX
            val top = floor(rect.top.toDouble()).toInt() - originY
            val right = ceil(rect.right.toDouble()).toInt() - originX
            val bottom = ceil(rect.bottom.toDouble()).toInt() - originY
            check(left >= 0 && top >= 0 && right <= frame.width && bottom <= frame.height && right > left && bottom > top)
            return android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top).asImageBitmap()
        }
        val solid = crop(control).toPixelMap()
        val center = solid[solid.width / 2, solid.height / 2]
        val distance = maxOf(abs(center.red - reference.red), abs(center.green - reference.green), abs(center.blue - reference.blue))
        val extra = "solidCenter=$center solidDistance=$distance bitmapConfig=${bitmap.config} " +
            "bitmapColorSpace=${bitmap.colorSpace} canvas=${frame.width}x${frame.height} regions=$before control=$control"
        android.util.Log.i("DaiyuSwitchSize", "$diagnostic $extra")
        assertCompactTrack(crop(before[1]), diagnostic, "$bounds $extra")
    }

    private fun assertCompactTrack(image: ImageBitmap, diagnostic: String, bounds: String) {
        val pixels = image.toPixelMap()
        val expected = Color(0xFF82728F)
        var left = pixels.width
        var right = -1
        var top = pixels.height
        var bottom = -1
        var closestDistance = Float.POSITIVE_INFINITY
        var closest = Color.Unspecified
        var closestX = -1
        var closestY = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val actual = pixels[x, y]
            val distance = maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green),
                abs(actual.blue - expected.blue))
            if (distance < closestDistance) {
                closestDistance = distance
                closest = actual
                closestX = x
                closestY = y
            }
            if (distance < .015f) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        val message = "$diagnostic density=$pixelsPerDp image=${pixels.width}x${pixels.height} " +
            "$bounds track=$left,$top,$right,$bottom closest=$closest distance=$closestDistance at=$closestX,$closestY"
        android.util.Log.i("DaiyuSwitchSize", message)
        if (right < left || bottom < top) {
            val directory = java.io.File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
            check(directory.isDirectory || directory.mkdirs())
            java.io.File(directory, "switch-size-$diagnostic.png").outputStream().use {
                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        assertTrue("checked track pixels must be present: $message", right >= left && bottom >= top)
        assertEquals("visible track width", 36.4f * pixelsPerDp, (right - left + 1).toFloat(), 2f)
        assertEquals("visible track height", 22.4f * pixelsPerDp, (bottom - top + 1).toFloat(), 2f)
    }

    fun testIndependentSwitchKeeps48DpTargetAndSingleCallbackOutsideCompactTrack() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val checked = mutableStateOf(true)
                val enabled = mutableStateOf(true)
                var calls = 0
                mainThread {
                    activity.setContent {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().switchDiagnosticCanvas()) {
                            val platform = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(platform.density, 1.3f),
                                LocalLayoutDirection provides direction) {
                                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                                    pixelsPerDp = LocalDensity.current.density
                                    SettingsSwitch(checked.value, onCheckedChange = { checked.value = it; calls++ },
                                        enabled = enabled.value, modifier = Modifier.testTag("compact-interactive-switch"),
                                        colors = SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                                            checkedBorderColor = DaiyuPalette.switchTrack, checkedThumbColor = Color.White))
                                }
                            }
                            Box(Modifier.align(Alignment.BottomStart).size(32.dp).educationDismissalBlank())
                        }
                    }
                }
                val target = node("compact-interactive-switch").assertIsOn().assertIsEnabled()
                val bounds = target.fetchSemanticsNode().boundsInRoot
                assertEquals(48f * pixelsPerDp, bounds.width, 1f)
                assertEquals(48f * pixelsPerDp, bounds.height, 1f)
                assertTrackWithControl(target, "interactive-dark-$dark-scale-$scale-$direction",
                    "root=$bounds window=${target.fetchSemanticsNode().boundsInWindow}")
                ui.onAllNodes(hasClickAction() and (hasTestTag("compact-interactive-switch") or
                    hasAnyAncestor(hasTestTag("compact-interactive-switch"))), useUnmergedTree = true).assertCountEquals(1)
                // Above the painted track, but within the unscaled switch target.
                target.performTouchInput { click(Offset(centerX, 2f * pixelsPerDp)) }
                target.assertIsOff()
                ui.runOnIdle { assertEquals(1, calls) }
                mainThread { enabled.value = false }
                target.assertIsNotEnabled().performTouchInput { click(Offset(centerX, 2f * pixelsPerDp)) }
                ui.runOnIdle { assertEquals(1, calls); assertFalse(checked.value) }
                mainThread { enabled.value = true }
                target.performClick().assertIsOn()
                ui.runOnIdle { assertEquals(2, calls) }
            }
        assertEquals(0, fixture.opens.get())
    }

    fun testRowUsesCompactOccupancyWithoutGrowingAndPassiveSwitchHasNoAction() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var calls = 0
            mainThread {
                activity.setContent {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().switchDiagnosticCanvas()) {
                        val platform = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(platform.density, 1.3f)) {
                            AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                                pixelsPerDp = LocalDensity.current.density
                                Column(Modifier.requiredWidth(280.dp).background(MiuixTheme.colorScheme.background)) {
                                    SettingsCard {
                                        SettingsSwitchRow("开关", true, tag = "compact-row") { calls++ }
                                    }
                                    SettingsSwitch(true, onCheckedChange = null,
                                        modifier = Modifier.testTag("compact-passive-switch"))
                                }
                            }
                        }
                        Box(Modifier.align(Alignment.BottomStart).size(32.dp).educationDismissalBlank())
                    }
                }
            }
            val row = node("compact-row").assertIsEnabled().assertIsOn()
            assertEquals(64f * pixelsPerDp, row.fetchSemanticsNode().boundsInRoot.height, 1f)
            assertTrackWithControl(row, "row-dark-$dark-scale-$scale",
                "root=${row.fetchSemanticsNode().boundsInRoot} window=${row.fetchSemanticsNode().boundsInWindow}")
            row.performClick()
            ui.runOnIdle { assertEquals(1, calls) }
            val passive = node("compact-passive-switch").assertHasNoClickAction()
            val bounds = passive.fetchSemanticsNode().boundsInRoot
            assertEquals(36.4f * pixelsPerDp, bounds.width, 1f)
            assertEquals(22.4f * pixelsPerDp, bounds.height, 1f)
            ui.onAllNodes(hasAnyAncestor(hasTestTag("compact-passive-switch")) and hasClickAction(),
                useUnmergedTree = true).assertCountEquals(0)
            passive.performTouchInput { click() }
            ui.runOnIdle { assertEquals(1, calls) }
        }
        assertEquals(0, fixture.opens.get())
    }
}
