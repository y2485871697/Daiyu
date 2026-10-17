package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import app.daiyu.android.delegation.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SubAgentCatalogActivityTest : WiredActivityTestCase() {
    private var testRoot: File? = null
    private val conversationOwners = mutableListOf<Pair<ConversationSubAgentPreferences, SubAgentConfigKey.Conversation>>()
    override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "catalog-activity-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        testRoot = root
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root }
        return RunControlFixture(context).also { it.settings.modelCatalogSnapshot() }
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() }
        finally {
            conversationOwners.forEach { (repo, owner) -> repo.delete(owner) }
            conversationOwners.clear()
            testRoot?.deleteRecursively(); testRoot = null
        }
    }

    private fun awaitDirectory(description: String, condition: () -> Boolean) {
        ui.waitUntil(5_000) { ui.runOnIdle(condition) }
        assertTrue(description, ui.runOnIdle(condition))
    }
    private fun source() = MainActivity::class.java.getDeclaredField("subAgentModelSource")
        .apply { isAccessible = true }.get(activity) as SavedCatalogSubAgentModelSource
    private fun repository() = ConversationSubAgentPreferences(activity.getSharedPreferences("subagent_configuration", 0))
    private fun openSubAgents() {
        openSettings()
        node("settings-search").performTextReplacement("子代理")
        reveal("settings-row:子代理", "设置").performClick()
        awaitDirectory("saved directory published") { source().models.any { it.modelId == "run-control" } }
    }
    private fun assertPickerContainsSavedModel() {
        ui.onNodeWithContentDescription("草稿模型").assertIsEnabled().performClick()
        ui.onNodeWithText("主模型服务商").assertExists()
        ui.onNode(hasText("run-control") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertExists().assertIsEnabled().performClick()
        ui.onNodeWithContentDescription("草稿模型").assertTextContains("run-control", substring = true)
    }

    fun testPresetPickerUsesSavedDirectoryWithoutDecryptingKeyAndRefreshesAfterResume() {
        setDraft("catalog preset unsent")
        val before = fixture.database.exportBackup()
        fixture.application.getSharedPreferences("model-settings", 0).edit().putString("key", "malformed-ciphertext").commit()
        val repo = repository()
        val preset = repo.addPreset("Catalog ${UUID.randomUUID()}")
        try {
            openSubAgents()
            realPauseResume(activity, atPause = { assertTrue(source().models.isEmpty()) })
            awaitDirectory("directory restored on resume") { source().models.any { it.modelId == "run-control" } }
            ui.onNodeWithContentDescription("编辑子代理组${preset.name}").assertIsEnabled().performClick()
            ui.onNode(hasText("添加子代理") and hasClickAction()).assertIsEnabled().performClick()
            assertPickerContainsSavedModel()
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            ui.waitForIdle()
            ui.onNodeWithText("添加子代理组").assertExists()
            backToChat(activity)
            awaitDirectory("leaving clears directory") { source().models.isEmpty() }
            assertDraft("catalog preset unsent")
        } finally { repo.removePreset(preset.id) }
    }

    fun testConversationPickerReceivesTheSameSavedDirectoryWithoutOpeningModel() {
        val conversation = fixture.database.create("default")
        mainThread { activity.select(conversation) }
        setDraft("catalog conversation unsent")
        val repo = repository()
        val owner = SubAgentConfigKey.Conversation(conversation.id.value)
        repo.createConversation(owner)
        conversationOwners.add(repo to owner)
        val before = fixture.database.exportBackup()
        menu("子代理状态")
        node("subagent-collaboration-card").assertExists()
        awaitDirectory("conversation directory published") { source().models.any { it.modelId == "run-control" } }
        ui.onNodeWithText("使用当前配置").assertIsEnabled().performClick()
        ui.onNodeWithContentDescription("添加子代理").assertIsEnabled().performClick()
        assertPickerContainsSavedModel()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        ui.onNodeWithText("完成").assertIsEnabled().performClick()
        awaitDirectory("closing conversation clears directory") { source().models.isEmpty() }
        assertDraft("catalog conversation unsent")
    }

    fun testQueuedModelSaveInvalidatesVisibleDirectoryAndRefreshesWithoutAnotherResume() {
        openSubAgents()
        val before = fixture.database.exportBackup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val saved = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val result = AtomicReference<Boolean>()
        val old = fixture.settings.load()
        val holder = Thread {
            try { fixture.settings.modelCatalog.configurationChange {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread { fixture.controller.saveModelSettings(old.copy(model = "changed-local-model"), false, false) {
                result.set(it); saved.countDown()
            } }
            awaitDirectory("busy save invalidates directory") { source().models.isEmpty() }
            release.countDown()
            assertTrue(saved.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            assertEquals(true, result.get())
            awaitDirectory("same route refreshes saved directory") { source().models.any { it.modelId == "changed-local-model" } }
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); holder.join(10_000) }
    }
}
