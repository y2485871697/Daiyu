package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.SettingsConnectionTestUi
import app.daiyu.android.ui.screens.settings.rememberSettingsConnectionTest
import app.daiyu.android.model.catalog.ModelConnectionResult
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.model.catalog.ModelCatalogService
import app.daiyu.android.model.catalog.RemoteModelFetcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelConnectionActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    private val requests = AtomicInteger()
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "connection-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private class Connection(url: URL, private val status: Int, val entered: CountDownLatch,
        val release: CountDownLatch) : HttpURLConnection(url) {
        @Volatile var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return status
        }
        override fun getInputStream() = """{"data":[{"id":"connection-test-model"}]}""".byteInputStream()
    }
    private fun connect(status: Int = 200, held: Boolean = false): Triple<CountDownLatch, CountDownLatch, AtomicReference<Connection>> {
        val entered = CountDownLatch(1); val release = CountDownLatch(if (held) 1 else 0)
        val last = AtomicReference<Connection>()
        fixture.controller.modelCatalog = ModelCatalogService({ error("connection test must not read saved credentials") },
            RemoteModelFetcher { url -> requests.incrementAndGet(); Connection(url, status, entered, release).also { last.set(it) } })
        return Triple(entered, release, last)
    }
    private fun testConnection() = reveal("model-connection-test").performClick()
    private fun awaitStatus(text: String) {
        ui.waitUntil(5_000) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    fun testUnsavedConnectionDoesNotSaveImportSelectOrSend() {
        setDraft("connection test unsent")
        val before = fixture.database.exportBackup(); val saved = fixture.settings.load()
        openModels(); val catalog = fixture.settings.localModelCatalogSnapshot()
        val (entered, _, last) = connect()
        editModel("Base URL", "https://unsaved.invalid/v1/responses")
        editModel("API Key", "unsaved-test-key"); editModel("模型名", "")
        testConnection(); assertTrue(entered.await(5, TimeUnit.SECONDS)); awaitStatus("目录连接成功，返回 1 个模型")
        assertEquals("https://unsaved.invalid/v1/models", last.get().url.toString())
        assertEquals("Bearer unsaved-test-key", last.get().getRequestProperty("Authorization"))
        assertEquals(saved, fixture.settings.load()); assertEquals(catalog, fixture.settings.localModelCatalogSnapshot())
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("connection test unsent")
    }
    fun testAuthenticationAndRateLimitAreSeparateNonSensitiveResults() {
        openModels(); connect(status = 401); testConnection(); awaitStatus("鉴权失败，请检查密钥")
        editModel("API Key", "different-unsaved-key")
        ui.onNodeWithText("鉴权失败，请检查密钥").assertDoesNotExist()
        connect(status = 429); testConnection(); awaitStatus("服务限流，请稍后重试")
        assertEquals(0, fixture.opens.get())
    }
    fun testEditingAwayAndBackRejectsTheOldConnectionResult() {
        openModels(); val endpoint = fixture.settings.load().endpoint
        val (entered, release, last) = connect(held = true)
        testConnection(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            editModel("Base URL", "https://temporary.invalid"); editModel("Base URL", endpoint)
        } finally { release.countDown() }
        awaitControl("cancelled connection drained") { last.get().closed }
        ui.waitForIdle(); ui.onAllNodesWithText("目录连接成功", substring = true).assertCountEquals(0)
        testConnection(); awaitStatus("目录连接成功，返回 1 个模型")
        assertEquals(0, fixture.opens.get())
    }
    fun testPauseCancelsThePendingResultAndAllowsAnExplicitNewTest() {
        openModels(); val (entered, release, last) = connect(held = true)
        testConnection(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { realPauseResume(activity) } finally { release.countDown() }
        awaitControl("paused connection drained") { last.get().closed }
        ui.waitForIdle(); ui.onAllNodesWithText("目录连接成功", substring = true).assertCountEquals(0)
        testConnection(); awaitStatus("目录连接成功，返回 1 个模型")
        assertEquals(0, fixture.opens.get())
    }
    fun testOldModelVisitCannotBorrowTheNewEqualDraftOwner() {
        fun read(name: String): Any = checkNotNull(MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == 0
        }.apply { isAccessible = true }.invoke(activity))
        openModels()
        lateinit var owner: Any; lateinit var oldDraft: ModelSettingsDraftUi
        mainThread { owner = read("getModelEditingOwner"); oldDraft = read("getModelDraft") as ModelSettingsDraftUi }
        backToChat(activity); openModels(); connect()
        val callback: (ModelConnectionResult) -> Unit = { error("old visit must not receive a result") }
        var accepted = true
        mainThread {
            assertEquals(oldDraft, read("getModelDraft"))
            val method = MainActivity::class.java.declaredMethods.single { it.name == "requestModelConnectionTest" }
            accepted = method.apply { isAccessible = true }.invoke(activity, owner, oldDraft, { false }, callback) as Boolean
        }
        assertFalse(accepted); assertEquals(0, requests.get())
        testConnection(); awaitStatus("目录连接成功，返回 1 个模型"); assertEquals(1, requests.get())
    }
    fun testDisposedConnectionUiCannotStartARequestAfterRemount() {
        val first = AtomicReference<SettingsConnectionTestUi>()
        val latest = AtomicReference<SettingsConnectionTestUi>()
        var mounted by mutableStateOf(true)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    if (mounted) {
                        val state = rememberSettingsConnectionTest(ModelSettingsDraftUi(), true) { _, _, _ ->
                            requests.incrementAndGet(); true
                        }
                        SideEffect { first.compareAndSet(null, state); latest.set(state) }
                        top.yukonga.miuix.kmp.basic.Text(if (state.running) "testing" else "idle")
                    }
                }
            }
        }
        ui.waitForIdle(); val stale = checkNotNull(first.get())
        mainThread { mounted = false }; ui.waitForIdle()
        mainThread { mounted = true }; ui.waitForIdle()
        mainThread { stale.start() }; assertEquals(0, requests.get())
        mainThread { checkNotNull(latest.get()).start() }; assertEquals(1, requests.get())
    }

}
