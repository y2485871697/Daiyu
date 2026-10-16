package app.daiyu.android

import android.content.ContextWrapper
import androidx.test.espresso.Espresso
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.skills.SkillBundle
import app.daiyu.android.skills.SkillStore
import app.daiyu.android.skills.SkillToolRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class IntegrationSettingsActivityTest : ComposeActivityTestCase() {
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
        Espresso.closeSoftKeyboard()
        ui.waitForIdle()
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
    fun testChatModelButtonOpensPickerAndSelectionPreservesConfigurationAndDraft() {
        val (_, _, connection) = catalogue()
        val before = fixture.settings.load()
        setDraft("切换模型时保留草稿")
        assertDraft("切换模型时保留草稿")
        val database = fixture.database.exportBackup()
        node("chat-model").performClick()
        node("chat-model-picker").assertExists()
        node("page:模型设置").assertDoesNotExist()
        finished(connection)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-option:second").fetchSemanticsNodes().isNotEmpty() }
        node("chat-model-search").performTextReplacement("second")
        node("chat-model-option:first").assertDoesNotExist()
        node("chat-model-option:second").performClick()
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(before.copy(model = "second"), fixture.settings.load())
        assertEquals(database, fixture.database.exportBackup())
        assertDraft("切换模型时保留草稿")
        assertEquals(0, fixture.opens.get())
    }
    fun testDismissedChatModelPickerCannotReviveFromPendingCatalog() {
        val (entered, release, connection) = catalogue(held = true)
        val before = fixture.settings.load()
        node("chat-model").performClick()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { ui.onNodeWithText("取消").performClick() } finally { release.countDown() }
        finished(connection)
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        val (retryEntered, _, retryConnection) = catalogue()
        node("chat-model").performClick()
        assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
        finished(retryConnection)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-option:second").fetchSemanticsNodes().isNotEmpty() }
        node("chat-model-option:second").assertExists()
        ui.onNodeWithText("取消").performClick()
        assertEquals(before, fixture.settings.load())
    }
    fun testChatModelPickerRejectsChangedSettingsAndRunningTask() {
        val (_, _, connection) = catalogue()
        val before = fixture.settings.load()
        node("chat-model").performClick(); finished(connection)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-option:second").fetchSemanticsNodes().isNotEmpty() }
        val changed = before.copy(model = "external-change")
        fixture.settings.save(changed)
        node("chat-model-option:second").performClick()
        assertEquals(changed, fixture.settings.load())
        ui.onNodeWithText("切换未保存，请关闭后重新选择").assertExists()
        ui.onNodeWithText("取消").performClick()
        val session = fixture.session(holdSeconds = 120)
        setDraft("运行时不切模型"); node("chat-send").performClick()
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        try {
            node("chat-model").performClick()
            node("chat-model-picker").assertExists()
            node("chat-model-option:external-change").assertIsNotEnabled()
            node("chat-model-refresh").assertIsNotEnabled()
            assertEquals(changed, fixture.settings.load())
            assertEquals(owner, fixture.controller.active)
            ui.onNodeWithText("取消").performClick()
        } finally { fixture.controller.stop(owner) }
    }
    fun testCatalogUsesCurrentEmptyKeyDraftAndOnlyExplicitSavePersistsSelection() {
        val (_, _, connection) = catalogue(); val before = fixture.settings.load()
        openModels(); editModel("Base URL", "https://catalog.invalid/v1/responses")
        editModel("API Key", ""); editModel("模型名", ""); fetch(); finished(connection)
        reveal("settings-row:second").performClick()
        assertEquals(before, fixture.settings.load())
        assertEquals("https://catalog.invalid/v1/models", connection.get().url.toString())
        assertNull(connection.get().getRequestProperty("Authorization"))
        modelField("模型名").assertEditableText("second"); editModel("API Key", "new-key"); saveModel()
        assertEquals("second", fixture.settings.load().model); assertEquals("new-key", fixture.settings.load().apiKey)
    }
    fun testCatalogChangedAndRestoredDraftRevokesLateResponseAndCanRetry() {
        val (entered, release, connection) = catalogue(held = true)
        val before = fixture.settings.load(); val old = before.model
        openModels(); modelField("模型名").assertEditableText(old)
        fetch(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            editModel("模型名", "changed"); modelField("模型名").assertEditableText("changed")
            editModel("模型名", old); modelField("模型名").assertEditableText(old)
        } finally { release.countDown() }
        finished(connection); fetchReady(); node("settings-row:second").assertDoesNotExist()
        val (retryEntered, _, retryConnection) = catalogue()
        fetch(); assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
        finished(retryConnection); fetchReady(); reveal("settings-row:second").assertExists()
        backToChat(activity); node("settings-row:second").assertDoesNotExist()
        assertEquals(old, fixture.settings.load().model); assertEquals(before, fixture.settings.load())
    }
    fun testCatalogSaveFailureAndDismissRevokePendingResponse() {
        for (save in listOf(true, false)) {
            val before = fixture.settings.load()
            val (entered, release, connection) = catalogue(held = true)
            openModels(); if (save) editModel("模型名", "")
            fetch(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { if (save) saveModel() else backToChat(activity) } finally { release.countDown() }
            finished(connection); node("settings-row:second").assertDoesNotExist()
            assertEquals(before, fixture.settings.load())
            if (save) backToChat(activity)
        }
    }
    fun testCatalogEmptyAndFailurePreserveSettingsAndHideResponseDetails() {
        val before = fixture.settings.load()
        for ((body, status) in listOf("""{"data":[]}""" to 200, "SECRET_RESPONSE_SENTINEL" to 401, "SECRET_RESPONSE_SENTINEL" to 200)) {
            val (_, _, connection) = catalogue(body, status); openModels(); fetch(); finished(connection)
            reveal("model-catalog-fetch").assertIsEnabled()
            ui.onNodeWithText(if (status == 200 && body.contains("data")) "模型目录为空" else "获取失败").assertExists()
            ui.onAllNodes(hasText("SECRET_RESPONSE_SENTINEL", substring = true)).assertCountEquals(0)
            node("settings-row:second").assertDoesNotExist()
            backToChat(activity); assertEquals(before, fixture.settings.load())
        }
    }
    fun testAdvancedRequestOptionsSaveReopenAndRejectInvalidBodyAndTier() {
        openModels(); editModel("自定义请求体", "{\"temperature\":0.25}")
        reveal("settings-radio:快速").performClick(); saveModel()
        val expected = ProviderRequestOptions("{\"temperature\":0.25}", GptSpeedMode.FAST, false)
        assertEquals(expected, fixture.settings.load().requestOptions)
        openModels(); modelField("自定义请求体").assertEditableText(expected.customBody)
        reveal("settings-radio:快速").assertIsSelected()
        editModel("自定义请求体", "{\"messages\":[]}"); saveModel()
        assertEquals(expected, fixture.settings.load().requestOptions); node("model-save").assertExists()
        editModel("自定义请求体", expected.customBody); reveal("settings-radio:超快速").performClick()
        reveal("settings-switch:Codex 档位").performClick(); saveModel()
        assertEquals(expected, fixture.settings.load().requestOptions); backToChat(activity)
        activity = recreateActivity(activity); openModels()
        modelField("自定义请求体").assertEditableText(expected.customBody); reveal("settings-radio:快速").assertIsSelected()
        backToChat(activity)
    }
    private fun openSkills() {
        openSettings(); reveal("settings-row:Skills", "设置").performClick()
        node("page:技能").assertIsDisplayed()
    }
    private fun skillSwitch(tag: String, on: Boolean): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.let { config ->
                config.getOrNull(SemanticsProperties.Disabled) == null &&
                    config.getOrNull(SemanticsProperties.ToggleableState) == (if (on) ToggleableState.On else ToggleableState.Off)
            } == true
        }
        return node(tag).assertIsEnabled().also { if (on) it.assertIsOn() else it.assertIsOff() }
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
        val blocked = File(directory, "agent-builtin-skills-v1/installed")
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
    }
    fun testUnavailableRequestPolicyRequiresExplicitReplacementWithoutLosingOriginalOnFailure() {
        val prefs = fixture.application.getSharedPreferences("model-settings", 0)
        check(prefs.edit().putString("request-options", "not-json").commit()); val before = HashMap(prefs.all)
        openModels(); reveal("settings-switch:授权替换策略").assertIsOff()
        editModel("API Key", "replacement-key"); editModel("自定义请求体", "{\"temperature\":0.5}"); saveModel()
        assertEquals(before, prefs.all)
        reveal("settings-switch:授权替换策略").performClick(); editModel("自定义请求体", "{\"tools\":[]}"); saveModel()
        assertEquals(before, prefs.all)
        editModel("自定义请求体", "{\"temperature\":0.5}"); saveModel()
        assertEquals(ProviderRequestOptions("{\"temperature\":0.5}"), fixture.settings.load().requestOptions)
        assertEquals("replacement-key", fixture.settings.load().apiKey)
        openModels(); reveal("model-save"); node("settings-switch:授权替换策略").assertDoesNotExist(); backToChat(activity)
    }
}
