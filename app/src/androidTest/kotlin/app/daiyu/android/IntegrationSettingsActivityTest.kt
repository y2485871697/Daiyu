package app.daiyu.android

import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.lifecycle.lifecycleScope
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.skills.SkillBundle
import app.daiyu.android.skills.SkillStore
import app.daiyu.android.skills.SkillToolRegistry
import app.daiyu.android.ui.screens.chat.conversation.LocalCatalogChatModelPickerSession
import kotlinx.coroutines.Job
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class IntegrationSettingsActivityTest : ComposeActivityTestCase() {
    override val engineeringUi: Boolean
        get() = name !in setOf(
            "testCustomRequestBodySaveReopenRejectsInvalidBodyAndHidesUnsupportedTiers",
            "testUnavailableRequestPolicyRequiresExplicitReplacementWithoutLosingOriginalOnFailure",
        )
    private lateinit var fixture: RunControlFixture
    private lateinit var original: ChatController
    private lateinit var directory: File
    private lateinit var activity: MainActivity
    override fun setUp() {
        super.setUp()
        val base = instrumentation.targetContext
        directory = File(base.cacheDir, "integration-settings-${UUID.randomUUID()}").apply { check(mkdirs()) }
        fixture = RunControlFixture(object : ContextWrapper(base) { override fun getFilesDir() = directory })
        awaitControl("ready") { fixture.controller.ready }
        mainThread { val app = base.applicationContext as ChatApplication; original = app.controller; app.controller = fixture.controller }
        activity = startMain()
    }
    override fun tearDown() {
        try {
            mainThread { activity.finish(); (instrumentation.targetContext.applicationContext as ChatApplication).controller = original }
            fixture.application.getSharedPreferences("runtime-skills-v1-settings", 0).edit().clear().commit()
            fixture.close(); directory.deleteRecursively()
        } finally { super.tearDown() }
    }
    private class CatalogConnection(url: java.net.URL, val entered: CountDownLatch, val release: CountDownLatch,
        val body: String, val status: Int) : java.net.HttpURLConnection(url) {
        @Volatile var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return status
        }
        override fun getInputStream() = body.byteInputStream()
    }
    private fun catalogue(body: String = """{"data":[{"id":"first"},{"id":"second"}]}""", status: Int = 200, held: Boolean = false):
        Triple<CountDownLatch, CountDownLatch, AtomicReference<CatalogConnection>> {
        fixture.settings.modelCatalogSnapshot()
        val entered = CountDownLatch(1); val release = CountDownLatch(if (held) 1 else 0)
        val connection = AtomicReference<CatalogConnection>()
        fixture.controller.modelCatalog = app.daiyu.android.model.catalog.ModelCatalogService(
            { error("draft must not load saved credentials") }, app.daiyu.android.model.catalog.RemoteModelFetcher { url ->
                CatalogConnection(url, entered, release, body, status).also { connection.set(it) }
            })
        return Triple(entered, release, connection)
    }
    private fun fetchReady(): SemanticsNodeInteraction {
        ui.waitForIdle()
        mainThread {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        ui.waitForIdle()
        selectProviderTab("模型")
        reveal("model-catalog-fetch")
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag("model-catalog-fetch").fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrNull(SemanticsProperties.Disabled) == null
            } == true
        }
        return node("model-catalog-fetch").assertIsEnabled()
    }
    private fun fetch() {
        fetchReady().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun finished(connection: AtomicReference<CatalogConnection>) {
        awaitControl("catalog finished") { connection.get()?.disconnected == true }; ui.waitForIdle()
    }
    // Explicit setup/migration only. Opening or refreshing the chat picker must never register
    // a provider or fetch a remote list. Match the active policy so only the model ID changes.
    private fun seedChatPickerCatalog() {
        val saved = fixture.settings.load()
        val snapshot = fixture.settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(
            snapshot.providerIdentity, snapshot.revision,
            listOf("first", "second").map { CatalogModelParameters.fromCurrent(saved.copy(model = it)) },
        ) { false })
    }
    private class PickerNetworkCalls {
        val savedCredentials = AtomicInteger()
        val connections = AtomicInteger()
    }
    private fun guardChatPickerNetwork(): PickerNetworkCalls {
        val calls = PickerNetworkCalls()
        fixture.controller.modelCatalog = app.daiyu.android.model.catalog.ModelCatalogService(
            { calls.savedCredentials.incrementAndGet(); error("local picker must not load remote credentials") },
            app.daiyu.android.model.catalog.RemoteModelFetcher {
                calls.connections.incrementAndGet(); error("local picker must not open a remote connection")
            })
        return calls
    }
    private fun assertChatPickerOffline(calls: PickerNetworkCalls) {
        assertEquals(0, calls.savedCredentials.get())
        assertEquals(0, calls.connections.get())
    }
    private fun awaitChatPickerLoaded() {
        awaitUniqueTag("chat-model-picker")
        awaitUniqueTag("chat-model-list")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrNull(SemanticsProperties.StateDescription) == "模型目录读取完成"
            } == true
        }
    }
    private fun closeChatPicker() {
        dismissIme()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().isEmpty() }
    }
    private fun reopenChatPicker() {
        closeChatPicker()
        node("chat-model").performClick()
        awaitChatPickerLoaded()
    }
    private fun awaitChatPickerReady() {
        awaitChatPickerLoaded()
        node("chat-model-list").performScrollToNode(hasTestTag("chat-model-option:second"))
        node("chat-model-option:second").assertIsEnabled()
    }
    // Retain the exact visit's capability, not an ID that could be resolved against a new visit.
    private fun retainedChatPickerSelection(): () -> Unit {
        val owner = checkNotNull(MainActivity::class.java.getDeclaredField("chatModelOwner")
            .apply { isAccessible = true }.get(activity))
        val session = MainActivity::class.java.getDeclaredField("chatModelSession")
            .apply { isAccessible = true }.get(activity) as LocalCatalogChatModelPickerSession
        val ticket = checkNotNull(session.selectionFor("second"))
        val select = MainActivity::class.java.declaredMethods.single {
            it.name == "selectChatModel" && it.parameterCount == 3
        }.apply { isAccessible = true }
        return { select.invoke(activity, ticket, owner, session); Unit }
    }
    fun testChatModelButtonOpensPickerAndSelectionPreservesConfigurationAndDraft() {
        seedChatPickerCatalog()
        val calls = guardChatPickerNetwork()
        val before = fixture.settings.load()
        setDraft("切换模型时保留草稿")
        assertDraft("切换模型时保留草稿")
        val database = fixture.database.exportBackup()
        val catalog = fixture.settings.localModelCatalogSnapshot()
        node("chat-model").performClick()
        awaitChatPickerReady()
        node("page:主模型服务商").assertDoesNotExist()
        assertChatPickerOffline(calls)
        reopenChatPicker()
        awaitChatPickerReady()
        assertEquals(catalog, fixture.settings.localModelCatalogSnapshot())
        assertEquals(before, fixture.settings.load())
        assertEquals(database, fixture.database.exportBackup())
        assertChatPickerOffline(calls)
        node("chat-model-search").performTextReplacement("second")
        node("chat-model-search").assertEditableText("second")
        dismissIme()
        awaitChatPickerReady()
        node("chat-model-option:first").assertDoesNotExist()
        node("chat-model-option:second").assertIsDisplayed().assertIsEnabled().performClick()
        awaitControl("chat model selected") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().isEmpty() }
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(before.copy(model = "second"), fixture.settings.load())
        assertEquals(database, fixture.database.exportBackup())
        assertDraft("切换模型时保留草稿")
        assertChatPickerOffline(calls)
        assertEquals(0, fixture.opens.get())
    }
    fun testOldPickerSelectionCannotBorrowReopenedPickerOwner() {
        seedChatPickerCatalog()
        val calls = guardChatPickerNetwork()
        val before = fixture.settings.load()
        setDraft("旧选择不得借用新页面")
        val database = fixture.database.exportBackup()
        node("chat-model").performClick(); awaitChatPickerReady()
        lateinit var stale: () -> Unit
        mainThread { stale = retainedChatPickerSelection() }
        closeChatPicker()
        mainThread { stale() }
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        node("chat-model").performClick(); awaitChatPickerReady()
        mainThread { stale() }
        assertFalse(fixture.controller.dataBusy)
        assertEquals(before, fixture.settings.load())
        node("chat-model-picker").assertExists()
        node("chat-model-option:second").performClick()
        awaitControl("new picker selection") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().isEmpty() }
        assertEquals(before.copy(model = "second"), fixture.settings.load())
        assertEquals(database, fixture.database.exportBackup())
        assertDraft("旧选择不得借用新页面")
        assertChatPickerOffline(calls)
        assertEquals(0, fixture.opens.get())
    }
    fun testDismissedChatModelPickerCannotReviveFromPendingCatalog() {
        seedChatPickerCatalog()
        val calls = guardChatPickerNetwork()
        val before = fixture.settings.load()
        setDraft("本地目录读取取消后保留草稿")
        val database = fixture.database.exportBackup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val completed = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        // Block the real local read with the same catalog lease used by Controller tests.
        val holder = Thread {
            try { fixture.settings.modelCatalog.configurationChange {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread {
                val parent = activity.lifecycleScope.coroutineContext[Job]!!
                val previousJobs = parent.children.toSet()
                MainActivity::class.java.getDeclaredMethod("openChatModels")
                    .apply { isAccessible = true }.invoke(activity)
                val pending = parent.children.single { it !in previousJobs }
                pending.invokeOnCompletion { completed.countDown() }
            }
            ui.onNodeWithText("正在读取模型目录…").assertExists()
            closeChatPicker()
            release.countDown()
            holder.join(5_000)
            assertFalse("local catalog lease released", holder.isAlive)
            failure.get()?.let { throw it }
            assertTrue("dismissed local read completed", completed.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            node("chat-model-picker").assertDoesNotExist()
            assertEquals(before, fixture.settings.load())
            node("chat-model").performClick(); awaitChatPickerReady()
            node("chat-model-option:second").assertExists()
            closeChatPicker()
            assertEquals(before, fixture.settings.load())
            assertEquals(database, fixture.database.exportBackup())
            assertDraft("本地目录读取取消后保留草稿")
            assertChatPickerOffline(calls)
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); holder.join(10_000) }
    }
    fun testChatModelPickerRejectsChangedSettingsAndRunningTask() {
        seedChatPickerCatalog()
        val calls = guardChatPickerNetwork()
        val before = fixture.settings.load()
        setDraft("拒绝过期选择时保留草稿")
        val database = fixture.database.exportBackup()
        node("chat-model").performClick(); awaitChatPickerReady()
        val changed = before.copy(model = "external-change")
        fixture.settings.save(changed)
        node("chat-model-option:second").performClick()
        awaitControl("chat model rejected") { !fixture.controller.dataBusy }
        val rejected = "切换未保存，请关闭后重新打开选择模型"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(rejected).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(changed, fixture.settings.load())
        ui.onNodeWithText(rejected).assertExists()
        node("chat-model-option:second").assertIsNotEnabled()
        reopenChatPicker(); awaitChatPickerReady()
        ui.onNodeWithText(rejected).assertDoesNotExist()
        node("chat-model-list").performScrollToNode(hasTestTag("chat-model-option:external-change"))
        node("chat-model-option:external-change").assertIsEnabled()
        closeChatPicker()
        assertEquals(changed, fixture.settings.load())
        assertEquals(database, fixture.database.exportBackup())
        assertDraft("拒绝过期选择时保留草稿")
        assertChatPickerOffline(calls)
        assertEquals(0, fixture.opens.get())
        val session = fixture.session(holdSeconds = 120)
        setDraft("运行时不切模型"); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        val runningDatabase = fixture.database.exportBackup()
        val runningOpens = fixture.opens.get()
        try {
            node("chat-model").performClick()
            awaitChatPickerLoaded()
            node("chat-model-picker").assertExists()
            node("chat-model-list").performScrollToNode(hasTestTag("chat-model-option:external-change"))
            node("chat-model-option:external-change").assertIsNotEnabled()
            val sessionField = MainActivity::class.java.getDeclaredField("chatModelSession")
                .apply { isAccessible = true }
            var previousSession: Any? = null
            mainThread { previousSession = sessionField.get(activity) }
            reopenChatPicker()
            ui.waitUntil(5_000) {
                var replaced = false
                mainThread { replaced = sessionField.get(activity)?.let { it !== previousSession } == true }
                replaced
            }
            node("chat-model-list").performScrollToNode(hasTestTag("chat-model-option:external-change"))
            node("chat-model-option:external-change").assertIsNotEnabled()
            node("chat-model-refresh").assertDoesNotExist()
            assertEquals(changed, fixture.settings.load())
            assertEquals(owner, fixture.controller.active)
            assertEquals(runningDatabase, fixture.database.exportBackup())
            assertEquals(runningOpens, fixture.opens.get())
            assertChatPickerOffline(calls)
            closeChatPicker()
        } finally { fixture.controller.stop(owner) }
    }
    fun testCatalogImportsCheckedModelsWithoutChangingCurrentDraftOrCredentials() {
        val (_, _, connection) = catalogue(); val before = fixture.settings.load()
        openModels(); editModel("Base URL", "https://catalog.invalid/v1/responses")
        editModel("API Key", ""); editModel("模型名", "")
        fetch(); finished(connection)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-option:second").fetchSemanticsNodes().isNotEmpty() }
        node("provider-model-option:second").performClick()
        node("dialog-confirm").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, fixture.settings.load())
        assertEquals("https://catalog.invalid/v1/models", connection.get().url.toString())
        assertNull(connection.get().getRequestProperty("Authorization"))
        val imported = fixture.settings.modelCatalogSnapshot().models
        assertTrue(imported.any { it.id == "second" }); assertFalse(imported.any { it.id == "first" })
        selectProviderTab("配置")
        modelField("模型名").assertEditableText("")
        editModel("模型名", "manual-model"); editModel("API Key", "new-key"); saveModel()
        awaitControl("model save") { !fixture.controller.dataBusy && fixture.settings.load().model == "manual-model" }
        assertEquals("manual-model", fixture.settings.load().model); assertEquals("new-key", fixture.settings.load().apiKey)
    }
    fun testCatalogChangedAndRestoredDraftRevokesLateResponseAndCanRetry() {
        val (entered, release, connection) = catalogue(held = true)
        val before = fixture.settings.load(); val old = before.model
        openModels(); modelField("模型名").assertEditableText(old)
        fetch(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            selectProviderTab("配置")
            editModel("模型名", "changed"); modelField("模型名").assertEditableText("changed")
            editModel("模型名", old); modelField("模型名").assertEditableText(old)
        } finally { release.countDown() }
        finished(connection); fetchReady(); node("provider-model-selection").assertDoesNotExist()
        val (retryEntered, _, retryConnection) = catalogue()
        fetch(); assertTrue(retryEntered.await(5, TimeUnit.SECONDS)); finished(retryConnection)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-option:second").fetchSemanticsNodes().isNotEmpty() }
        node("provider-model-option:second").assertExists()
        node("dialog-cancel").assertIsEnabled().performClick(); backToChat(activity)
        node("provider-model-selection").assertDoesNotExist()
        assertEquals(old, fixture.settings.load().model); assertEquals(before, fixture.settings.load())
    }
    fun testCatalogSaveFailureAndDismissRevokePendingResponse() {
        for (save in listOf(true, false)) {
            val before = fixture.settings.load()
            val (entered, release, connection) = catalogue(held = true)
            openModels(); if (save) editModel("模型名", "")
            fetch(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                if (save) {
                    selectProviderTab("配置")
                    reveal("model-save").performClick()
                } else backToChat(activity)
            } finally { release.countDown() }
            finished(connection)
            if (save) awaitControl("invalid model save") { !fixture.controller.dataBusy }
            node("provider-model-selection").assertDoesNotExist()
            assertEquals(before, fixture.settings.load())
            if (save) backToChat(activity)
        }
    }
    fun testCatalogEmptyAndFailurePreserveSettingsAndHideResponseDetails() {
        val before = fixture.settings.load()
        for ((body, status) in listOf("""{"data":[]}""" to 200, "SECRET_RESPONSE_SENTINEL" to 401, "SECRET_RESPONSE_SENTINEL" to 200)) {
            val (_, _, connection) = catalogue(body, status); openModels(); fetch(); finished(connection)
            reveal("model-catalog-fetch").assertIsEnabled()
            val expected = if (status == 200 && body.contains("data")) "模型目录为空" else "获取失败，请重试"
            ui.waitUntil(5_000) { ui.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(expected).assertExists()
            ui.onAllNodes(hasText("SECRET_RESPONSE_SENTINEL", substring = true)).assertCountEquals(0)
            node("provider-model-selection").assertDoesNotExist()
            backToChat(activity); assertEquals(before, fixture.settings.load())
        }
    }
    fun testCustomRequestBodySaveReopenRejectsInvalidBodyAndHidesUnsupportedTiers() {
        val before = fixture.settings.load().requestOptions
        val body = """{"temperature":0.25}"""
        openModels(); addInlineBodyField("temperature", "0.25"); saveModel()
        val expected = before.copy(customBody = body)
        assertEquals(expected, fixture.settings.load().requestOptions)
        val saved = fixture.settings.load()
        openModels(); openInlineBodyFields()
        inlineBodyControl("model-body-field-name:0").assertEditableText("temperature")
        inlineBodyControl("model-body-field-value:0").assertEditableText("0.25")
        inlineBodyControl("model-body-field-name:0").performTextReplacement("messages")
        inlineBodyControl("model-body-field-value:0").performTextReplacement("[]")
        attemptBlockedInlineBodySave()
        assertEquals(saved, fixture.settings.load())
        assertEquals(expected, fixture.settings.load().requestOptions); node("model-save").assertExists()
        inlineBodyControl("model-body-field-value:0").performTextReplacement("0.25")
        inlineBodyControl("model-body-field-name:0").performTextReplacement("temperature")
        saveModel()
        assertEquals(expected, fixture.settings.load().requestOptions)
        ui.onAllNodesWithText("超快速").assertCountEquals(0)
        ui.onAllNodesWithText("Codex 档位").assertCountEquals(0)
        backToChat(activity)
        activity = recreateActivity(activity); openModels(); openInlineBodyFields()
        inlineBodyControl("model-body-field-name:0").assertEditableText("temperature")
        inlineBodyControl("model-body-field-value:0").assertEditableText("0.25")
        assertEquals(expected, fixture.settings.load().requestOptions)
        backToChat(activity)
        assertEquals(0, fixture.opens.get())
    }
    private fun openSkills() {
        openSettings(); reveal("settings-row:Skills", "设置").performClick()
        node("page:技能").assertIsDisplayed()
    }
    private fun skillSwitch(tag: String, on: Boolean): SemanticsNodeInteraction {
        reveal(tag, "技能")
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.config?.let { config ->
                config.getOrNull(SemanticsProperties.Disabled) == null &&
                    config.getOrNull(SemanticsProperties.ToggleableState) == (if (on) ToggleableState.On else ToggleableState.Off)
            } == true
        }
        return ui.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().assertIsEnabled().also { if (on) it.assertIsOn() else it.assertIsOff() }
    }
    fun testSkillsEntryPersistsIndividualAndGlobalSwitchesAndClosesWithActivity() {
        val name = "integration-demo"
        SkillStore(fixture.application).install(listOf(SkillBundle(mapOf("SKILL.md" to "---\nname: $name\ndescription: UI fixture\n---\nFixture instructions".toByteArray()))))
        val id = "user:$name"
        openSkills()
        skillSwitch("skills-global", on = true)
        reveal("skill-row:$id", "技能").assertIsDisplayed()
        skillSwitch("skill-switch:$id", on = true).performClick()
        skillSwitch("skill-switch:$id", on = false)
        assertFalse(SkillStore(fixture.application).list().single().enabled)
        reveal("skills-global", "技能")
        skillSwitch("skills-global", on = true).performClick()
        skillSwitch("skills-global", on = false)
        assertFalse(SkillToolRegistry(fixture.application).enabled)
        activity = recreateActivity(activity)
        node("page:技能").assertDoesNotExist(); node("skills-global").assertDoesNotExist()
        node("chat-draft").assertExists()
        openSkills()
        skillSwitch("skills-global", on = false)
        reveal("skill-row:$id", "技能").assertIsDisplayed()
        skillSwitch("skill-switch:$id", on = false)
        assertTrue(SkillToolRegistry(fixture.application).snapshot().skills.isEmpty())
    }
    fun testDamagedSkillListStillAllowsGlobalOffAndDefaultNextCaptureDoesNotReadIt() {
        val blocked = File(fixture.application.filesDir, "agent-builtin-skills-v1/installed")
        check(blocked.parentFile!!.mkdirs()); blocked.writeText("not a directory"); openSkills()
        skillSwitch("skills-global", on = true)
        ui.onNodeWithText("技能列表暂不可用", substring = true).assertExists()
        node("skills-global").performClick()
        skillSwitch("skills-global", on = false)
        val reopened = SkillToolRegistry(fixture.application); assertFalse(reopened.enabled)
        val owner = app.daiyu.android.domain.tools.TaskAuthorization("disabled-skill-test", "next")
        val scope = reopened.captureForRun(app.daiyu.android.domain.tools.SessionCapabilities(owner.sessionId, setOf(app.daiyu.android.skills.SkillRunSession.READ)), owner)
        try { assertTrue(scope.availableTools.isEmpty()) } finally { scope.close() }
        assertTrue(blocked.isFile)
        assertEquals("not a directory", blocked.readText())
    }
    fun testUnavailableRequestPolicyRequiresExplicitReplacementWithoutLosingOriginalOnFailure() {
        val prefs = fixture.application.getSharedPreferences("model-settings", 0)
        check(prefs.edit().putString("request-options", "not-json").commit()); val before = HashMap(prefs.all)
        openModels(); reveal("settings-switch:授权替换策略").assertIsOff()
        editModel("API Key", "replacement-key"); addInlineBodyField("temperature", "0.5"); saveModel()
        assertEquals(before, prefs.all)
        reveal("settings-switch:授权替换策略").performClick(); openInlineBodyFields()
        inlineBodyControl("model-body-field-name:0").performTextReplacement("tools")
        inlineBodyControl("model-body-field-value:0").performTextReplacement("[]")
        attemptBlockedInlineBodySave()
        assertEquals(before, prefs.all)
        inlineBodyControl("model-body-field-value:0").performTextReplacement("0.5")
        inlineBodyControl("model-body-field-name:0").performTextReplacement("temperature")
        saveModel()
        assertEquals(ProviderRequestOptions("""{"temperature":0.5}"""), fixture.settings.load().requestOptions)
        assertEquals("replacement-key", fixture.settings.load().apiKey)
        openModels(); reveal("model-save"); node("settings-switch:授权替换策略").assertDoesNotExist(); backToChat(activity)
        assertEquals(0, fixture.opens.get())
    }
}
