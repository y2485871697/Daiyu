package app.daiyu.android.browser

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.download.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.network.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Android files + SQLite reopen + production download/effect pipeline. HTTP fixture only. */
@Suppress("DEPRECATION")
class BrowserDownloadLedgerAndroidTest : InstrumentationTestCase() {
    fun testConfirmedDownloadReceiptSurvivesSQLiteReopenWithoutDownloadingAgain() = download(false)
    fun testUnknownDownloadSurvivesSQLiteReopenWithoutDownloadingAgain() = download(true)

    private fun download(loseReply: Boolean) {
        val context = instrumentation.targetContext
        val database = "browser-download-${UUID.randomUUID()}.db"
        val files = Files.createTempDirectory(context.cacheDir.toPath(), "browser-private-files-")
        val root = BrowserPrivateRoot.prepare(files)
        val downloadRoot = Files.createDirectory(root.resolve("downloads"))
        val grant = BrowserGrant(BrowserOwner("ledger-browser-run", BrowserOwnerKind.MAIN), BrowserAccess.FULL)
        val registry = BrowserSessionRegistry()
        val httpCalls = AtomicInteger()
        val body = "durable-private-browser-download".toByteArray()
        val http = BrowserHttpDownloader(registry,
            BrowserDownloadCoordinator(registry, BrowserPathMapper(downloadRoot)), connectionFactory = { uri ->
                httpCalls.incrementAndGet()
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getHeaderField(name: String): String? =
                        if (name.equals("Content-Length", true)) body.size.toString() else null
                    override fun getInputStream() = ByteArrayInputStream(body)
                }
            })
        val backend = BrowserFetchBackend(registry, http)
        val session = BrowserToolSession(grant, registry, object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) {}
            override fun close(lease: BrowserTabLease) {}
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                val receipt = backend.execute(command, lease).get(5, TimeUnit.SECONDS)
                if (loseReply) error("fixture lost reply after file publication")
                return receipt
            }
        })
        val identity = BrowserRunIdentity(ConversationId("ledger-browser-conversation"), RunId("ledger-browser-run"),
            "ledger-browser-profile", MessageId("ledger-browser-message"))
        val newTab = """{"action":"new_tab"}"""
        val fetch = """{"action":"fetch","tab_id":0,"url":"https://fixture.invalid/durable"}"""
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL), grant,
            setOf("new_tab", "fetch"), listOf(newTab, fetch), session)
        val confirmed = preparation.confirm(preparation.preview)
        val originalOwner = owner(identity)
        val bound = RunBrowserTools.createConfirmed(session, originalOwner,
            AssistantProfile(identity.assistantId, "Ledger", ""), confirmed)
        val capabilities = SessionCapabilities(bound.taskAuthorization.sessionId,
            setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY))
        fun dispatch(store: ToolLedgerStore, call: String, raw: String,
            task: TaskAuthorization = bound.taskAuthorization, executionOwner: RunExecutionOwner = originalOwner): DispatchOutcome = ToolDispatcher(
            ToolRegistry().apply { bound.tools.forEach { register(it) } }.snapshot(), capabilities, ToolLedger(store))
            .dispatchOwned(task, listOf(ToolInvocation(ToolCallId(call), RunBrowserTools.WRITE_TOOL_NAME, raw)),
                executionOwner) { false }.single().outcome
        try {
            var payload: String? = null
            SQLiteToolLedgerStore(context, database).use { store ->
                assertTrue(dispatch(store, "create-tab", newTab) is DispatchOutcome.Confirmed)
                val outcome = dispatch(store, "fetch-original", fetch)
                if (loseReply) assertTrue(outcome is DispatchOutcome.Unknown)
                else payload = (outcome as DispatchOutcome.Confirmed).payload
            }
            assertEquals(1, httpCalls.get())
            val paths = Files.list(downloadRoot).use { it.toArray().map { p -> p as java.nio.file.Path } }
            assertEquals(1, paths.size)
            val path = paths.single()
            assertTrue(path.toRealPath().startsWith(downloadRoot.toRealPath()))
            assertTrue(body.contentEquals(Files.readAllBytes(path)))
            payload?.let {
                val receipt = JSONObject(it)
                assertEquals(path.toString(), receipt.getString("filePath"))
                val summary = JSONObject(receipt.getString("text"))
                assertEquals(body.size.toLong(), summary.getLong("size"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { b -> "%02x".format(b) },
                    summary.getString("sha256"))
            }
            SQLiteToolLedgerStore(context, database).use { reopened ->
                // A durable cached/UNKNOWN receipt never substitutes for the original live
                // token, task and opaque owner. These probes must fail BEFORE cached replay.
                val replacement = TaskAuthorization(bound.taskAuthorization.sessionId,
                    bound.taskAuthorization.taskId, bound.taskAuthorization.allowedWrites)
                assertTrue(dispatch(reopened, "forged-task", fetch, task = replacement) is DispatchOutcome.NotExecuted)
                assertTrue(dispatch(reopened, "old-owner", fetch,
                    executionOwner = owner(identity)) is DispatchOutcome.NotExecuted)
                assertTrue(dispatch(reopened, "sibling-owner", fetch,
                    executionOwner = owner(identity.copy(runId = RunId("sibling-run"),
                        assistantMessageId = MessageId("sibling-message")))) is DispatchOutcome.NotExecuted)
                assertEquals(1, httpCalls.get())
                val replay = dispatch(reopened, "new-provider-call-id", fetch)
                if (loseReply) assertTrue(replay is DispatchOutcome.Unknown)
                else {
                    assertTrue((replay as DispatchOutcome.Confirmed).replayed)
                    assertEquals(payload, replay.payload)
                }
                confirmed.revoke()
                assertTrue(dispatch(reopened, "revoked-after-reopen", fetch) is DispatchOutcome.NotExecuted)
                assertEquals(1, httpCalls.get())
            }
            assertEquals(1, httpCalls.get())
        } finally {
            confirmed.revoke(); session.close(); backend.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!backend.closeStatus().drained && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(backend.closeStatus().successful)
            context.deleteDatabase(database)
            Files.walk(files).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun owner(identity: BrowserRunIdentity): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(identity.assistantMessageId, identity.conversationId, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
}
