package app.daiyu.android

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import java.io.File
import java.util.UUID

class ProviderPagePositionActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "provider-position-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = root
        })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }

    private data class Position(val scroll: Float, val top: Float)
    private fun position(tag: String): Position {
        ui.waitForIdle()
        val page = node("page:主模型服务商").fetchSemanticsNode()
        val range = page.config[SemanticsProperties.VerticalScrollAxisRange]
        return Position(range.value(), node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top)
    }
    private fun assertPositionWithoutScrolling(tag: String, before: Position) {
        // Neither reveal() nor awaitModelEditor(): they would scroll and hide a restore failure.
        awaitUniqueTag("page:主模型服务商")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 && node(tag).isDisplayed()
        }
        val after = position(tag)
        assertEquals("list scroll stays at its old offset", before.scroll, after.scroll, .001f)
        assertEquals("the visible control stays in place", before.top, after.top, 2f)
    }
    private fun backgroundAndResume() {
        mainThread { assertTrue(activity.moveTaskToBack(true)) }
        ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        mainThread {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
    }

    fun testConfigurationPositionSurvivesBothPauseAndFullBackgrounding() {
        val saved = fixture.settings.load()
        val registry = fixture.controller.providers.snapshot()
        openModels()
        reveal("provider-delete", lazyKey = "provider_delete")
        for (stop in listOf(false, true)) {
            val before = position("model-save")
            assertTrue("the form was scrolled away from its first item", before.scroll > 1f)
            if (stop) backgroundAndResume() else realPauseResume(activity)
            assertPositionWithoutScrolling("model-save", before)
        }
        assertEquals(saved, fixture.settings.load())
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testModelTabFilterAndPositionSurviveReloadAfterBackgrounding() {
        val catalog = fixture.settings.modelCatalogSnapshot()
        val records = (0 until 30).map {
            val id = "scroll-restore-${it.toString().padStart(2, '0')}"
            SavedCatalogModel(id, id, CatalogModelParameters())
        }
        assertEquals(CatalogCommitResult.COMMITTED,
            fixture.settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision, records) { false })
        val saved = fixture.settings.load()
        val savedCatalog = fixture.settings.localModelCatalogSnapshot()
        openModels()
        selectProviderTab("模型")
        reveal("local-model-search").performTextReplacement("scroll-restore")
        dismissIme()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("正在读取本地模型").fetchSemanticsNodes().isEmpty() }
        val tag = "local-model:scroll-restore-22"
        reveal(tag, lazyKey = tag)
        val before = position(tag)
        assertTrue("the model list was scrolled well past its header", before.scroll > 10f)
        backgroundAndResume()
        assertPositionWithoutScrolling(tag, before)
        // Inspect the restored tab/filter only after proving the position without scrolling.
        reveal("settings-radio:模型").assertIsSelected()
        node("local-model-search").assertEditableText("scroll-restore")
        assertEquals(saved, fixture.settings.load())
        assertEquals(savedCatalog, fixture.settings.localModelCatalogSnapshot())
        assertEquals(0, fixture.opens.get())
    }
}
