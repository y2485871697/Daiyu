package app.daiyu.android.ui.screens.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.context.CompressionSettingsEditor
import app.daiyu.android.context.ProductionCompressionSettingsStore
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.storage.ProviderRepository
import app.daiyu.android.testContext
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsSwitchBusyAppearanceTest : WiredActivityTestCase() {
    private fun differentPixels(before: ImageBitmap, after: ImageBitmap, diagnostic: String = "busy-appearance"): Int {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val left = before.toPixelMap()
        val right = after.toPixelMap()
        var different = 0
        var minX = left.width
        var minY = left.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until left.height) for (x in 0 until left.width) {
            val a = left[x, y]
            val b = right[x, y]
            if (maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) > .01f) {
                different++
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        if (different != 0) {
            android.util.Log.e("DaiyuSwitchPixels", "$diagnostic different=$different bbox=$minX,$minY,$maxX,$maxY image=${left.width}x${left.height}")
            val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
            check(directory.isDirectory || directory.mkdirs())
            listOf("before" to before, "pending" to after).forEach { (phase, image) ->
                File(directory, "$diagnostic-$phase.png").outputStream().use {
                    check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
        return different
    }

    fun testBusyLocksInteractionWithoutChangingEitherSwitchStateInBothThemes() {
        for (dark in listOf(false, true)) for (checked in listOf(false, true)) {
            val busy = mutableStateOf(false)
            var calls = 0
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        Box(Modifier.requiredWidth(320.dp).background(MiuixTheme.colorScheme.background)) {
                            SettingsCard {
                                SettingsSwitchRow("保存中的设置", checked, busy = busy.value, tag = "busy-switch") { calls++ }
                            }
                        }
                    }
                }
            }
            val before = captureScreenNode(node("busy-switch").assertIsEnabled())
            mainThread { busy.value = true }
            val pending = node("busy-switch").assertIsNotEnabled()
            assertEquals(0, differentPixels(before, captureScreenNode(pending), "busy-dark-$dark-checked-$checked"))
            pending.performTouchInput { click() }
            ui.runOnIdle { assertEquals(0, calls) }
            mainThread { busy.value = false }
            node("busy-switch").assertIsEnabled().performClick()
            ui.runOnIdle { assertEquals(1, calls) }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testGenuinelyUnavailableSwitchStaysDisabledAndDimEvenWhenBusy() {
        for (dark in listOf(false, true)) for (checked in listOf(false, true)) {
            val enabled = mutableStateOf(true)
            val busy = mutableStateOf(false)
            var calls = 0
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        Box(Modifier.requiredWidth(320.dp).background(MiuixTheme.colorScheme.background)) {
                            SettingsCard {
                                SettingsSwitchRow("不可用的设置", checked, enabled = enabled.value,
                                    busy = busy.value, tag = "unavailable-switch") { calls++ }
                            }
                        }
                    }
                }
            }
            val available = captureScreenNode(node("unavailable-switch"))
            mainThread { enabled.value = false }
            val disabled = captureScreenNode(node("unavailable-switch").assertIsNotEnabled())
            assertTrue(differentPixels(available, disabled) > 0)
            mainThread { busy.value = true }
            val pending = node("unavailable-switch").assertIsNotEnabled()
            assertEquals(0, differentPixels(disabled, captureScreenNode(pending)))
            pending.performTouchInput { click() }
            ui.runOnIdle { assertEquals(0, calls) }
        }
    }

    private class StoreGate(val fail: Boolean = false, openingsToSkip: Int = 0) {
        private val remaining = AtomicInteger(openingsToSkip)
        fun shouldBlock() = remaining.getAndDecrement() <= 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRelease() {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "test store gate was not released" }
            check(!fail) { "synthetic save failure" }
        }
    }

    private fun compressionScreen(initialGate: StoreGate? = null, test: (
        CompressionSettingsEditor, ProductionCompressionSettingsStore, AtomicReference<StoreGate?>, AtomicInteger,
    ) -> Unit) {
        val prefix = "switch-busy-${UUID.randomUUID()}-"
        val context = testContext(instrumentation.targetContext, prefix)
        val preferences = context.getSharedPreferences("compression", Context.MODE_PRIVATE)
        val store = ProductionCompressionSettingsStore(preferences)
        val gate = AtomicReference(initialGate)
        val calls = AtomicInteger()
        val editor = CompressionSettingsEditor(compressionStore = {
            calls.incrementAndGet()
            val pending = gate.get()
            if (pending != null && pending.shouldBlock() && gate.compareAndSet(pending, null)) pending.awaitRelease()
            store
        }, profileStore = { error("no summary profile is selected") })
        val providers = ProviderRepository(ProviderRegistry(File(context.cacheDir, "${prefix}providers.bin"))) {
            error("provider credentials must not be opened")
        }
        val source = SettingsModelSource(context, providers)
        var failure: Throwable? = null
        try {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), false) {
                        CompressionSettingsScreen(editor, onBack = {}, ownerCurrent = { true }, source = source)
                    }
                }
            }
            test(editor, store, gate, calls)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            fun clean(action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    val original = failure
                    if (original == null) failure = caught else original.addSuppressed(caught)
                }
            }
            clean {
                initialGate?.release?.countDown()
                gate.getAndSet(null)?.release?.countDown()
                awaitEnabled()
            }
            clean { mainThread { activity.setContent {} } }
            clean { check(preferences.edit().clear().commit()) }
        }
        failure?.let { throw it }
    }

    private fun awaitEnabled() {
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodes(hasTestTag("settings-switch:自动压缩") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun testCompressionInitialLoadStillDisablesInteraction() {
        val loading = StoreGate()
        compressionScreen(loading) { _, _, _, _ ->
            try {
                assertTrue(loading.entered.await(5, TimeUnit.SECONDS))
                node("settings-switch:自动压缩").assertIsNotEnabled()
                node("settings-switch:使用独立摘要配置").assertIsNotEnabled()
            } finally { loading.release.countDown() }
            awaitEnabled()
            node("settings-switch:自动压缩").assertIsOff()
        }
    }

    fun testCompressionSaveKeepsPixelsAndBusyLockUntilReadbackConfirmsValue() {
        compressionScreen { editor, store, gate, calls ->
            awaitEnabled()
            val before = captureScreenNode(node("settings-switch:使用独立摘要配置"))
            val saving = StoreGate(openingsToSkip = 1)
            val previousCalls = calls.get()
            gate.set(saving)
            try {
                node("settings-switch:自动压缩").assertIsOff().performClick()
                assertTrue(saving.entered.await(5, TimeUnit.SECONDS))
                node("settings-switch:自动压缩").assertIsNotEnabled().assertIsOff()
                assertTrue(store.snapshot().automatic)
                val untouched = node("settings-switch:使用独立摘要配置").assertIsNotEnabled()
                assertEquals(0, differentPixels(before, captureScreenNode(untouched)))
                untouched.performTouchInput { click() }
                assertNull(editor.prepareLoad())
                assertEquals(previousCalls + 2, calls.get())
                node("settings-model-picker").assertDoesNotExist()
            } finally { saving.release.countDown() }
            awaitEnabled()
            node("settings-switch:自动压缩").assertIsOn()
            assertTrue(store.snapshot().automatic)
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testCompressionFailedSaveUnlocksOldValueWithoutAutomaticRetry() {
        compressionScreen { _, store, gate, calls ->
            awaitEnabled()
            val saving = StoreGate(fail = true)
            val previousCalls = calls.get()
            gate.set(saving)
            try {
                node("settings-switch:自动压缩").performClick()
                assertTrue(saving.entered.await(5, TimeUnit.SECONDS))
                node("settings-switch:自动压缩").assertIsNotEnabled().assertIsOff()
            } finally { saving.release.countDown() }
            awaitEnabled()
            node("settings-switch:自动压缩").assertIsOff()
            ui.onNodeWithText("保存或回读未确认。请核对后明确重新保存；不会自动重试。").assertExists()
            assertEquals(previousCalls + 1, calls.get())
            assertFalse(store.snapshot().automatic)
            node("settings-switch:自动压缩").performClick()
            awaitEnabled()
            node("settings-switch:自动压缩").assertIsOn()
            assertTrue(store.snapshot().automatic)
            assertEquals(0, fixture.opens.get())
        }
    }
}
