package app.daiyu.android.browser

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.openminis.AndroidBrowserRunHost
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real adapter and Android lifecycle wiring through CatalogBrowserBinding. */
@Suppress("DEPRECATION")
class CatalogBrowserBindingAndroidTest : InstrumentationTestCase() {
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    fun testOriginalAndroidHostAndSessionSurviveBindingWithoutImplicitTab() {
        val identity = BrowserRunIdentity(ConversationId("binding-conversation"), RunId("binding-run"),
            "binding-profile", MessageId("binding-message"))
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
            grant, setOf("list_tabs"))
        val token = preparation.confirm(preparation.preview)
        val context = instrumentation.targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "binding-android-")
        val host = main {
            AndroidBrowserRunHost(context, grant, root,
                cookieEnvPath = { _, _ -> error("offload disabled") },
                cookieAuthorizeName = { _, _, _ -> false })
        }
        val binding = CatalogBrowserBinding.attach(host, AssistantProfile(identity.assistantId, "Test", ""), token)
        try {
            assertSame(host.session, binding.session)
            assertNull(host.currentTab)
            val bound = binding.bindOwner(owner(identity))
            assertTrue(bound.taskAuthorization.allowedWrites.isEmpty())
            assertSame(host.session, binding.session)
            assertNull(host.currentTab)
            assertFalse(binding.closeStatus().closeRequested)
        } finally {
            binding.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!binding.closeStatus().successful && System.nanoTime() < deadline) Thread.sleep(10)
            val status = binding.closeStatus()
            assertTrue("must observe the real successful close receipt: $status", status.successful)
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    fun testFreshFilesRootWorkerMkdirThenActualAndroidHostSucceedsWithoutPreviewIO() {
        val base = instrumentation.targetContext
        val freshFiles = Files.createTempDirectory(base.cacheDir.toPath(), "fresh-browser-files-").toFile()
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = freshFiles
        }
        val identity = BrowserRunIdentity(ConversationId("fresh-conversation"), RunId("fresh-run"),
            "fresh-profile", MessageId("fresh-message"))
        val grant = BrowserGrant(BrowserOwner("fresh-run", BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        var binding: CatalogBrowserBinding? = null
        try {
            val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
                grant, setOf("list_tabs"))
            val token = preparation.confirm(preparation.preview)
            assertFalse(java.io.File(freshFiles, "browser-runs").exists())
            // On instrumentation's worker, not main.
            val root = app.daiyu.android.ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
            assertEquals(freshFiles.toPath().toRealPath().resolve("browser-runs"), root)
            val opened = main { CatalogBrowserBinding.open(context, root,
                AssistantProfile(identity.assistantId, "Fresh", ""), token) }
            binding = opened
            assertNull(opened.session.currentTab)
            val bound = opened.bindOwner(owner(identity))
            assertTrue(bound.taskAuthorization.allowedWrites.isEmpty())
            assertEquals("fresh-run", opened.sessionId)
            assertTrue(Files.isDirectory(root.resolve("downloads")))
            assertTrue(Files.isDirectory(root.resolve("cookie-offloads")))
            opened.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!opened.closeStatus().successful && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(opened.closeStatus().successful)
        } finally {
            binding?.close()
            Files.walk(freshFiles.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    fun testPrivateRootHelperRejectsMainThreadBeforeCreatingAnyDirectory() {
        val base = instrumentation.targetContext
        val files = Files.createTempDirectory(base.cacheDir.toPath(), "browser-no-main-io-").toFile()
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = files
        }
        try {
            main {
                try {
                    app.daiyu.android.ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
                    fail("main must not create root")
                } catch (expected: IllegalStateException) {
                    assertEquals("browser_worker_required", expected.message)
                }
            }
            assertFalse(java.io.File(files, "browser-runs").exists())
        } finally { Files.delete(files.toPath()) }
    }

    fun testProductionScreenshotReceiptSurvivesHostCloseAndSourceRemoval() = verifyScreenshotReceipt()

    private fun verifyScreenshotReceipt() {
        val base = instrumentation.targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "browser-receipt-").toFile().canonicalFile
        val files = java.io.File(root, "files").apply { mkdirs() }
        val cache = java.io.File(root, "cache").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = files
            override fun getCacheDir(): java.io.File = cache
        }
        val identity = BrowserRunIdentity(ConversationId("receipt-conversation"), RunId("receipt-run"),
            "receipt-profile", MessageId("receipt-message"))
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), BrowserAccess.FULL)
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL),
            grant, setOf("screenshot"))
        val token = preparation.confirm(preparation.preview)
        var binding: CatalogBrowserBinding? = null
        var constructionCleanup: (() -> app.daiyu.android.browser.network.BrowserCloseResult)? = null
        var testFailure: Throwable? = null
        try {
            val privateRoot = app.daiyu.android.ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
            val opened = main {
                CatalogBrowserBinding.create(AssistantProfile(identity.assistantId, "Screenshot", ""), token) {
                    val host = try { app.daiyu.android.browser.openminis.AndroidBrowserRunHost(context, grant, privateRoot,
                        cookieEnvPath = { _, _ -> error("unused") }, cookieAuthorizeName = { _, _, _ -> false },
                        historyApproval = app.daiyu.android.browser.openminis.OpenMinisHistoryApproval { _, url, _ ->
                            url == "https://browser-fixture.invalid/receipt"
                        }, requestOverride = { request ->
                            check(request.url.host == "browser-fixture.invalid")
                            if (request.url.path == "/receipt") android.webkit.WebResourceResponse("text/html", "UTF-8",
                                java.io.ByteArrayInputStream("<html><body>Receipt screenshot fixture<img src='/missing.png'></body></html>".toByteArray()))
                            else android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(),
                                java.io.ByteArrayInputStream(byteArrayOf()))
                        }) } catch (failure: app.daiyu.android.browser.openminis.BrowserRunConstructionException) {
                            constructionCleanup = { failure.closeWithResult() }
                            throw failure
                        }
                    object : CatalogBrowserBinding.Host {
                        override val session get() = host.session
                        override val screenshotStore = app.daiyu.android.media.BrowserScreenshotStore(files, cache)
                        override fun closeWithResult() = host.closeWithResult()
                        override fun closeStatus() = host.closeStatus()
                    }
                }
            }
            binding = opened
            assertTrue(opened.session.execute(mapOf("action" to "new_tab")) is
                app.daiyu.android.browser.bridge.BrowserOutcome.Confirmed)
            val navigation = opened.session.execute(mapOf("action" to "navigate", "url" to "https://browser-fixture.invalid/receipt"))
            assertTrue("fixture navigation must confirm before screenshot: $navigation",
                navigation is app.daiyu.android.browser.bridge.BrowserOutcome.Confirmed)
            val executionOwner = owner(identity)
            val catalog = opened.bindOwner(executionOwner)
            val ledger = app.daiyu.android.domain.tools.InMemoryToolLedgerStore()
            val dispatcher = app.daiyu.android.domain.tools.ToolDispatcher(
                app.daiyu.android.domain.tools.ToolRegistry().apply { catalog.tools.forEach { register(it) } }.snapshot(),
                app.daiyu.android.domain.tools.SessionCapabilities(identity.runId.value,
                    setOf("browser.read", "browser.write")), app.daiyu.android.domain.tools.ToolLedger(ledger))
            val invocation = ToolInvocation(ToolCallId("receipt-call"), "browser_read", "{\"action\":\"screenshot\"}")
            val outcome = dispatcher.dispatchOwned(catalog.taskAuthorization, listOf(invocation), executionOwner).single().outcome
            assertTrue("real screenshot must freeze before Confirmed: $outcome",
                outcome is app.daiyu.android.domain.tools.DispatchOutcome.Confirmed)
            val payload = org.json.JSONObject((outcome as app.daiyu.android.domain.tools.DispatchOutcome.Confirmed).payload)
            assertTrue(payload.isNull("filePath"))
            val receipt = payload.getJSONObject("screenshot_receipt")
            val assetBinding = app.daiyu.android.media.BrowserScreenshotStore.Binding.from(executionOwner, invocation)
            val store = app.daiyu.android.media.BrowserScreenshotStore(files, cache)
            val original = store.read(assetBinding, receipt)
            opened.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!opened.closeStatus().successful && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(opened.closeStatus().successful)
            val sourceDirectory = java.io.File(cache, "browser_screenshots")
            assertTrue(sourceDirectory.deleteRecursively())
            assertFalse(sourceDirectory.exists())
            val reopened = app.daiyu.android.media.BrowserScreenshotStore(files, cache)
            assertTrue(original.contentEquals(reopened.read(assetBinding, receipt)))
            assertTrue(runCatching { reopened.read(assetBinding.copy(call = "other-call"), receipt) }.isFailure)
            val thumbnail = reopened.thumbnail(assetBinding, receipt)
            assertNotNull(thumbnail)
            thumbnail!!.recycle()
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            try {
                val close: (() -> app.daiyu.android.browser.network.BrowserCloseResult)? =
                    binding?.let { opened -> { opened.close(); opened.closeStatus() } } ?: constructionCleanup
                if (close != null) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var status = close()
                    while (!status.successful && System.nanoTime() < deadline) {
                        Thread.sleep(10)
                        status = close()
                    }
                    assertTrue("fixture host must drain before deleting its root: $status", status.successful)
                }
                assertTrue(root.deleteRecursively())
            } catch (cleanup: Throwable) {
                if (testFailure != null) testFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun testOrdinaryEmptyInspectionCloseThenFreshRunNavigatesWithoutOpeningPanel() {
        val base = instrumentation.targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "ordinary-browser-stop-").toFile()
        val files = java.io.File(root, "files").apply { mkdirs() }
        val cache = java.io.File(root, "cache").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        val leaves = mutableListOf<app.daiyu.android.integration.tools.RunOrdinaryBrowserTools>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val bindings = mutableListOf<CatalogBrowserBinding>()
        fun closeLeaf(leaf: app.daiyu.android.integration.tools.RunOrdinaryBrowserTools) {
            val done = java.util.concurrent.CountDownLatch(1)
            var result: app.daiyu.android.integration.tools.RunCatalogCloseResult? = null
            leaf.observeClose { result = it; done.countDown() }
            assertTrue("original ordinary browser must drain", done.await(10, TimeUnit.SECONDS))
            assertEquals(app.daiyu.android.integration.tools.RunCatalogCloseResult.Drained, result)
        }
        fun run(suffix: String): Pair<app.daiyu.android.integration.tools.RunOrdinaryBrowserTools,
            (String, String) -> app.daiyu.android.domain.tools.DispatchOutcome> {
            val id = "ordinary-${java.util.UUID.randomUUID()}-$suffix"
            val identity = BrowserRunIdentity(ConversationId("ordinary-conversation"), RunId(id),
                "ordinary-profile", MessageId("message-$id"))
            val executionOwner = owner(identity)
            val authorization = app.daiyu.android.domain.tools.TaskAuthorization(id, id)
            val leaf = app.daiyu.android.integration.tools.RunOrdinaryBrowserTools(context, executionOwner,
                authorization, AssistantProfile(identity.assistantId, "Ordinary", ""), identity.assistantMessageId,
                BrowserSettings(BrowserAccess.FULL), opened = { binding ->
                    if (binding !in bindings) bindings.add(binding)
                }, openHost = { hostContext, privateRoot, assistant, confirmed ->
                    val host = AndroidBrowserRunHost(hostContext, confirmed.preview.grant, privateRoot,
                        cookieEnvPath = { _, _ -> error("unused") }, cookieAuthorizeName = { _, _, _ -> false },
                        requestOverride = { request ->
                            check(request.url.host == "ordinary-fixture.invalid")
                            if (request.isForMainFrame) requests.incrementAndGet()
                            android.webkit.WebResourceResponse("text/html", "UTF-8",
                                java.io.ByteArrayInputStream("<html><body>Fresh ordinary browser</body></html>".toByteArray()))
                        })
                    CatalogBrowserBinding.attach(host, assistant, confirmed)
                })
            leaves.add(leaf)
            val dispatcher = app.daiyu.android.domain.tools.ToolDispatcher(
                app.daiyu.android.domain.tools.ToolRegistry().apply { leaf.tools.forEach { register(it) } }.snapshot(),
                app.daiyu.android.domain.tools.SessionCapabilities(id, setOf("browser.read", "browser.write")),
                app.daiyu.android.domain.tools.ToolLedger(app.daiyu.android.domain.tools.InMemoryToolLedgerStore()))
            var calls = 0
            return leaf to { name, arguments ->
                dispatcher.dispatchOwned(authorization,
                    listOf(ToolInvocation(ToolCallId("$id-${calls++}"), name, arguments)), executionOwner).single().outcome
            }
        }
        try {
            val (old, inspect) = run("old")
            val listed = inspect("browser_read", """{"action":"list_tabs"}""")
            assertTrue(listed is app.daiyu.android.domain.tools.DispatchOutcome.Confirmed)
            val listing = org.json.JSONObject((listed as app.daiyu.android.domain.tools.DispatchOutcome.Confirmed).payload)
            assertEquals(0, org.json.JSONObject(listing.getString("text")).getJSONArray("tabs").length())
            val binding = bindings.single()
            var pauses = 0
            val visual = main {
                app.daiyu.android.ControllerBrowserVisual(Message(MessageId("visual-old"),
                    ConversationId("ordinary-conversation"), RunId(binding.runId), Message.ASSISTANT_ROLE,
                    emptyList(), RunStatus.RUNNING), binding, { true }, { pauses++; true }, { false },
                    { error("opening must not resume") }, { error("opening must not stop") })
            }
            main {
                val visit = requireNotNull(visual.openVisit())
                val pool = app.daiyu.android.browser.ui.BrowserUiPool(context, visit)
                repeat(3) { pool.ensureTabForUI(); pool.refresh() }
                visit.close()
                assertEquals(0, pauses)
                assertTrue(binding.session.visibleTabs.isEmpty())
                assertFalse(visual.automationHeld)
                visual.revoke()
            }
            closeLeaf(old)
            val (fresh, execute) = run("fresh")
            assertEquals(0, requests.get())
            val navigated = execute("browser_write",
                """{"action":"navigate","url":"https://ordinary-fixture.invalid/fresh"}""")
            assertTrue("fresh ordinary navigation must confirm: $navigated",
                navigated is app.daiyu.android.domain.tools.DispatchOutcome.Confirmed)
            assertEquals(1, requests.get())
            assertEquals(2, bindings.size)
            assertFalse(binding.browserOwnerId == bindings.last().browserOwnerId)
            assertEquals(1, bindings.last().session.visibleTabs.size)
            val body = execute("browser_read", """{"action":"get_text","selector":"body"}""")
            assertTrue(body is app.daiyu.android.domain.tools.DispatchOutcome.Confirmed)
            assertTrue((body as app.daiyu.android.domain.tools.DispatchOutcome.Confirmed).payload.contains("Fresh ordinary browser"))
            assertEquals(1, requests.get())
            closeLeaf(fresh)
        } finally {
            leaves.forEach(::closeLeaf)
            assertTrue(root.deleteRecursively())
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
