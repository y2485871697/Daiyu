package app.daiyu.android

import android.app.AlertDialog
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private fun fetch() { reveal("model-catalog-fetch").performClick() }
    private fun finished(connection: AtomicReference<CatalogConnection>) {
        awaitControl("catalog finished") { connection.get()?.disconnected == true }; ui.waitForIdle()
    }
    fun testCatalogUsesCurrentEmptyKeyDraftAndOnlyExplicitSavePersistsSelection() {
        val (_, _, connection) = catalogue(); val before = fixture.settings.load()
        openModels(); editModel("Base URL", "https://catalog.invalid/v1/responses")
        editModel("API Key", ""); editModel("模型名", ""); fetch(); finished(connection)
        reveal("settings-row:second").performClick()
        assertEquals(before, fixture.settings.load())
        assertEquals("https://catalog.invalid/v1/models", connection.get().url.toString())
        assertNull(connection.get().getRequestProperty("Authorization"))
        modelField("模型名").assertTextEquals("second"); editModel("API Key", "new-key"); saveModel()
        assertEquals("second", fixture.settings.load().model); assertEquals("new-key", fixture.settings.load().apiKey)
    }
    fun testCatalogChangedAndRestoredDraftRevokesLateResponseAndCanRetry() {
        val (entered, release, connection) = catalogue(held = true)
        openModels(); fetch(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val old = fixture.settings.load().model
        try { editModel("模型名", "changed"); editModel("模型名", old) } finally { release.countDown() }
        finished(connection); node("settings-row:second").assertDoesNotExist()
        fetch(); finished(connection); reveal("settings-row:second").assertExists()
        backToChat(activity); node("settings-row:second").assertDoesNotExist()
        assertEquals(old, fixture.settings.load().model)
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
        openModels(); modelField("自定义请求体").assertTextEquals(expected.customBody)
        reveal("settings-radio:快速").assertIsSelected()
        editModel("自定义请求体", "{\"messages\":[]}"); saveModel()
        assertEquals(expected, fixture.settings.load().requestOptions); node("model-save").assertExists()
        editModel("自定义请求体", expected.customBody); reveal("settings-radio:超快速").performClick()
        reveal("settings-switch:Codex 档位").performClick(); saveModel()
        assertEquals(expected, fixture.settings.load().requestOptions); backToChat(activity)
        activity = recreateActivity(activity); openModels()
        modelField("自定义请求体").assertTextEquals(expected.customBody); reveal("settings-radio:快速").assertIsSelected()
        backToChat(activity)
    }
    // SkillManagementDialog has not changed in this migration: assert its actual native controls.
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun skillDialog(): AlertDialog {
        val host = MainActivity::class.java.getDeclaredField("skillManagementDialog").apply { isAccessible = true }.get(activity)
        return host.javaClass.getDeclaredField("window").apply { isAccessible = true }.get(host) as AlertDialog
    }
    private fun openSkills() { menu("设置"); reveal("settings-row:技能管理", "设置").performClick() }
    fun testSkillsEntryPersistsIndividualAndGlobalSwitchesAndClosesWithActivity() {
        val name = "integration-demo"
        SkillStore(fixture.application).install(listOf(SkillBundle(mapOf("SKILL.md" to "---\nname: $name\ndescription: UI fixture\n---\nFixture instructions".toByteArray()))))
        openSkills()
        awaitControl("skill loaded") { views(skillDialog().window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "关闭 $name" } }
        mainThread { views(skillDialog().window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == "关闭 $name" }.performClick() }
        awaitControl("individual switch") { views(skillDialog().window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "开启 $name" } }
        assertFalse(SkillStore(fixture.application).list().single().enabled)
        mainThread { views(skillDialog().window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == "技能总开关：已开启（点击关闭）" }.performClick() }
        awaitControl("global switch") { views(skillDialog().window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "技能总开关：已关闭（点击开启）" } }
        assertFalse(SkillToolRegistry(fixture.application).enabled)
        lateinit var old: AlertDialog
        mainThread { old = skillDialog() }; activity = recreateActivity(activity); assertFalse(old.isShowing)
        openSkills()
        awaitControl("reopened switches") {
            val labels = views(skillDialog().window!!.decorView).filterIsInstance<Button>().map { it.text.toString() }
            "开启 $name" in labels && "技能总开关：已关闭（点击开启）" in labels
        }
        assertTrue(SkillToolRegistry(fixture.application).snapshot().skills.isEmpty())
    }
    fun testDamagedSkillListStillAllowsGlobalOffAndDefaultNextCaptureDoesNotReadIt() {
        val blocked = File(directory, "agent-builtin-skills-v1/installed")
        check(blocked.parentFile!!.mkdirs()); blocked.writeText("not a directory"); openSkills()
        awaitControl("global available") { views(skillDialog().window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "技能总开关：已开启（点击关闭）" } }
        mainThread {
            val nodes = views(skillDialog().window!!.decorView)
            assertTrue(nodes.filterIsInstance<android.widget.TextView>().any { it.text.toString().startsWith("技能列表暂不可用") })
            nodes.filterIsInstance<Button>().single { it.text.toString() == "技能总开关：已开启（点击关闭）" }.performClick()
        }
        awaitControl("off despite directory") { views(skillDialog().window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "技能总开关：已关闭（点击开启）" } }
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
