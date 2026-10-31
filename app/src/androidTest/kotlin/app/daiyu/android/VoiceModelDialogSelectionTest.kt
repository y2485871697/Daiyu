package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.SettingsModelSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real catalog leases and selection tickets; no speech synthesis or transport. */
class VoiceModelDialogSelectionTest : WiredActivityTestCase() {
    private val saved = mutableListOf<VoiceConfiguration>()
    private fun seed(): SettingsModelSource {
        fixture.settings.save(fixture.settings.load().copy(model = "tts-1"))
        val catalog = fixture.settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(
            catalog.providerIdentity, catalog.revision,
            listOf(SavedCatalogModel("tts-1-hd", "tts-1-hd", CatalogModelParameters()))) { false })
        return SettingsModelSource(fixture.application, fixture.controller.providers)
    }
    private fun show(source: SettingsModelSource) {
        val configuration = mutableStateOf(VoiceConfiguration())
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                VoiceModelDialog(configuration.value, source, { true }, onSave = {
                    saved += it
                    configuration.value = it.copy(revision = "saved-${saved.size}")
                }, onDismiss = {})
            }
        } }
    }
    private fun awaitLoaded() {
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodes(hasTestTag("chat-model-picker") and SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "模型目录读取完成")).fetchSemanticsNodes().size == 1
        }
        awaitNodeWindowFocus(node("voice-model-dialog"))
    }
    private fun withReadHeld(action: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val holder = Thread {
            try { fixture.settings.modelCatalog.readConfiguration {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            } } catch (t: Throwable) { failure.set(t) }
        }
        holder.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            action()
        } finally { release.countDown(); holder.join(5_000) }
        assertFalse(holder.isAlive)
        failure.get()?.let { throw AssertionError("Catalog read lease failed", it) }
    }
    fun testFirstDirectoryReadStillShowsLoading() {
        val source = seed()
        withReadHeld {
            show(source)
            ui.onNodeWithText("正在读取模型目录…").assertIsDisplayed()
            assertTrue(saved.isEmpty())
        }
        awaitLoaded()
        ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testSelectionRetainsPixelsAndSearchWhileRealConnectionValidationWaits() {
        val source = seed()
        val choices = source.speechChoices()
        val first = choices.single { it.model == "tts-1" }
        val second = choices.single { it.model == "tts-1-hd" }
        val firstTag = "chat-model-option:${first.speechPickerId}"
        val secondTag = "chat-model-option:${second.speechPickerId}"
        val beforeCatalog = fixture.settings.localModelCatalogSnapshot()
        show(source); awaitLoaded()
        node("chat-model-search").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("tts-1")))
        }
        val sheetRoot = node("chat-selection-sheet").fetchSemanticsNode().root
        val before = captureScreenNode(node(firstTag)).toPixelMap()
        withReadHeld {
            node(secondTag).performClick()
            node(firstTag).assertIsNotEnabled().performTouchInput { click(center) }
            node(secondTag).assertIsNotEnabled().performTouchInput { click(center) }
            ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
            ui.onNodeWithText("当前任务结束后可切换模型").assertDoesNotExist()
            node("chat-model-search").assertTextContains("tts-1")
            assertSame(sheetRoot, node("chat-selection-sheet").fetchSemanticsNode().root)
            val pending = captureScreenNode(node(firstTag)).toPixelMap()
            assertEquals(before.width, pending.width); assertEquals(before.height, pending.height)
            var changed = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                if (before[x, y] != pending[x, y]) changed++
            }
            assertEquals(0, changed)
            assertTrue(saved.isEmpty())
        }
        ui.waitUntil(timeoutMillis = 5000) { saved.size == 1 }
        node(secondTag).assertIsSelected().assertIsEnabled()
        assertSame(sheetRoot, node("chat-selection-sheet").fetchSemanticsNode().root)
        node("chat-model-search").assertTextContains("tts-1")
        ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
        node(firstTag).performClick()
        ui.waitUntil(timeoutMillis = 5000) { saved.size == 2 }
        node(firstTag).assertIsSelected()
        assertEquals(listOf(second.identity, first.identity), saved.map { it.modelIdentity })
        assertEquals(beforeCatalog, fixture.settings.localModelCatalogSnapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testStaleConnectionStillShowsFailureWithoutSaving() {
        val source = seed()
        val choice = source.speechChoices().single { it.model == "tts-1-hd" }
        show(source); awaitLoaded()
        fixture.settings.save(fixture.settings.load())
        node("chat-model-option:${choice.speechPickerId}").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodesWithText("模型连接已变化或不可读取，请重新选择。").fetchSemanticsNodes().size == 1
        }
        ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
        assertTrue(saved.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
}
