package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.model.generation.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real catalog/leaf/Runner owner, no SQL, assets, network or paid submission. */
class RunCatalogGenerationBindingTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val capabilities = SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY, "audit"))
    private val config = GenerationRunConfig(listOf(GenerationRunConfig.Batch("batch",
        GenerationProtocol.OPENAI_IMAGES_JSON, "https://provider.example/v1/images/generations",
        "test-key", "model", listOf(GenerationRunConfig.Item("item", "draw")))))
    private val call = ToolInvocation(ToolCallId("original-call"), RunGenerationTools.IMAGE_TOOL,
        """{"batch_id":"batch"}""")

    private fun leaf(owner: RunExecutionOwner, authorization: TaskAuthorization,
        validate: (app.daiyu.android.media.GenerationAssetOwner) -> Boolean = { error("unapproved execution") }) =
        RunGenerationTools(config, owner, authorization, "account", { error("unexpected SQL") },
            { error("unexpected assets") }, validate,
            GenerationRunApproval { error("unexpected approval") },
            GenerationDownloadAuthorization { _, _ -> false }, GenerationHttp { error("unexpected HTTP") })

    private fun capture(caps: SessionCapabilities, auth: TaskAuthorization): SkillRunSession {
        assertSame(capabilities, caps)
        return SkillRunSession.create(auth, emptySet(), emptyList())
    }
    private fun closeAndAwait(tools: RunCatalogTools): RunCatalogCloseResult {
        val result = AtomicReference<RunCatalogCloseResult?>()
        val done = CountDownLatch(1)
        tools.closeWithResult { result.set(it); done.countDown() }
        assertTrue("catalog did not drain", done.await(5, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("unexpected acceptance") }
        catch (_: IllegalStateException) {}
        catch (_: IllegalArgumentException) {}
    }

    @Test fun realRunnerBindsFactoriesBeforeFirstModelSchemasWithoutGrantingPaidWrites() {
        val authCalls = AtomicInteger()
        val leafCalls = AtomicInteger()
        var actualOwner: RunExecutionOwner? = null
        var actualAuth: TaskAuthorization? = null
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { owner ->
                authCalls.incrementAndGet(); actualOwner = owner
                TaskAuthorization(capabilities.sessionId, owner.runId.value).also { actualAuth = it }
            }, generationFactory = { owner, caps, auth ->
                assertSame(actualOwner, owner); assertSame(actualAuth, auth); assertSame(capabilities, caps)
                leafCalls.incrementAndGet(); leaf(owner, auth)
            }, capture = { caps, auth -> assertSame(actualAuth, auth); capture(caps, auth) })
        rejected { tools.definitions }
        assertEquals(0, authCalls.get()); assertEquals(0, leafCalls.get())
        var opened = false
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("catalog route required")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                opened = true
                assertTrue(checkNotNull(actualOwner).owns(message))
                assertTrue(checkNotNull(actualAuth).allowedWrites.isEmpty())
                assertTrue(definitions.any { it.name == RunGenerationTools.IMAGE_TOOL })
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
                    override fun cancel() {}
                }
            }
        }
        try {
            val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                tools, RunnerOptions(receiptBatchExecutor = tools)) {}
            assertEquals(RunStatus.COMPLETED, runner.run(message, emptyList()).status)
            assertTrue(opened)
            tools.bindOwner(checkNotNull(actualOwner))
            assertEquals(1, authCalls.get()); assertEquals(1, leafCalls.get())
            val denied = tools.execute(message, listOf(call)) { false }.single().outcome
            assertTrue(denied is DispatchOutcome.NotExecuted)
            assertTrue(checkNotNull(actualAuth).allowedWrites.isEmpty())
        } finally { assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools)) }
    }

    @Test fun explicitlyGrantedLeafUsesTheSameCentralOwnerAuthorizationAndOriginalCall() {
        val token = runnerOwner(message)
        val grant = RunGenerationTools.writeEffect("account", token, config, config.batches.single())
        val auth = TaskAuthorization(capabilities.sessionId, message.runId.value, setOf(grant))
        val validations = AtomicInteger()
        var context: ToolExecutionContext? = null
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "audit", 1, "read", "audit", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                context = it; ExecutionReceipt.Confirmed("read")
            }
        })
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { assertSame(token, it); auth },
            generationFactory = { owner, _, authorization ->
                assertSame(token, owner); assertSame(auth, authorization)
                leaf(owner, authorization) { assetOwner ->
                    validations.incrementAndGet()
                    assertEquals(message.id.value, assetOwner.binding.assistantMessageId)
                    assertEquals(call.id.value, assetOwner.binding.toolCallId)
                    false // Stop before assets/SQL/approval/HTTP; admission must already have succeeded.
                }
            }, capture = ::capture)
        try {
            tools.bindOwner(token); tools.definitions
            val audit = ToolInvocation(ToolCallId("audit-call"), "audit", "{}")
            assertTrue(tools.execute(message, listOf(audit)) { false }.single().outcome is DispatchOutcome.Confirmed)
            assertSame(token, context!!.owner); assertSame(auth, context!!.authorization); assertSame(audit, context!!.invocation)
            assertEquals(DispatchOutcome.NotExecuted("generation_owner_rejected"),
                tools.execute(message, listOf(call)) { false }.single().outcome)
            assertEquals(1, validations.get())
            assertEquals(setOf(grant), auth.allowedWrites)
        } finally { assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools)) }
    }

    @Test fun closeDuringFactoryDoesNotWaitAndLateLeafIsRetainedUntilPhysicalDrain() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val result = AtomicReference<RunCatalogCloseResult?>()
        val failure = AtomicReference<Throwable?>()
        val lateLeaf = AtomicReference<RunGenerationTools?>()
        val factoryCalls = AtomicInteger()
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { TaskAuthorization(capabilities.sessionId, it.runId.value) },
            generationFactory = { owner, _, auth ->
                factoryCalls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                leaf(owner, auth).also { lateLeaf.set(it) }
            }, capture = ::capture)
        val token = runnerOwner(message)
        val worker = Thread {
            try { tools.bindOwner(token); failure.set(AssertionError("late binding published")) }
            catch (expected: IllegalStateException) { if (expected.message != "run_closed") failure.set(expected) }
            catch (other: Throwable) { failure.set(other) }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            rejected { tools.definitions }
            rejected { tools.bindOwner(token) }
            tools.closeWithResult { result.set(it); closed.countDown() }
            assertNull(result.get()) // Factory has not returned a cleanup handle yet.
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive); failure.get()?.let { throw it }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(RunCatalogCloseResult.Drained, result.get())
        assertEquals(GenerationToolsCloseResult.Drained, checkNotNull(lateLeaf.get()).awaitClose(0))
        assertEquals(1, factoryCalls.get())
        rejected { tools.definitions }; rejected { tools.bindOwner(token) }
    }

    @Test fun invalidAuthorizationAndClosedCatalogNeverOpenGeneration() {
        for (wrong in listOf(TaskAuthorization("foreign-session", "run"), TaskAuthorization("session", "foreign-run"))) {
            val opens = AtomicInteger()
            val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
                message.conversationId, message.runId, authorizationFactory = { wrong },
                generationFactory = { owner, _, auth -> opens.incrementAndGet(); leaf(owner, auth) }, capture = ::capture)
            rejected { tools.bindOwner(runnerOwner(message)) }
            assertEquals(0, opens.get())
            assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools))
        }
        val authCalls = AtomicInteger()
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { authCalls.incrementAndGet(); error("must not run") },
            generationFactory = { _, _, _ -> error("must not run") }, capture = ::capture)
        assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools))
        rejected { tools.bindOwner(runnerOwner(message)) }
        assertEquals(0, authCalls.get())
    }

    @Test fun explicitAuthorizationCannotBeReplacedByFactoryCopy() {
        val fixed = TaskAuthorization("session", "run")
        val opens = AtomicInteger()
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, authorization = fixed,
            authorizationFactory = { TaskAuthorization(fixed.sessionId, fixed.taskId) },
            generationFactory = { owner, _, auth -> opens.incrementAndGet(); leaf(owner, auth) }, capture = ::capture)
        rejected { tools.bindOwner(runnerOwner(message)) }
        assertEquals(0, opens.get())
        assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools))
        rejected {
            RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
                message.conversationId, message.runId,
                generationFactory = { owner, _, auth -> leaf(owner, auth) }, capture = ::capture)
        }
    }

    @Test fun parentCloseCannotReportDrainBeforeActiveDispatchReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<RunCatalogCloseResult?>()
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "audit", 1, "read", "audit", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ExecutionReceipt.Confirmed("done")
            }
        })
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { TaskAuthorization("session", "run") },
            generationFactory = { owner, _, auth -> leaf(owner, auth) }, capture = ::capture)
        tools.bindOwner(runnerOwner(message)); tools.definitions
        val worker = Thread {
            try { tools.execute(message, listOf(ToolInvocation(ToolCallId("audit"), "audit", "{}"))) { false } }
            catch (caught: Throwable) { failure.set(caught) }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            tools.closeWithResult { result.set(it); stopped.countDown() }
            assertNull(result.get())
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive); failure.get()?.let { throw it }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertEquals(RunCatalogCloseResult.Drained, result.get())
    }

    @Test fun browserAndGenerationShareFinalFactoryAuthorization() {
        val identity = app.daiyu.android.browser.BrowserRunIdentity(message.conversationId, message.runId,
            "assistant-profile", message.id)
        val profile = app.daiyu.android.assistants.AssistantProfile(identity.assistantId, "Test", "")
        val grant = app.daiyu.android.browser.session.BrowserGrant(
            app.daiyu.android.browser.session.BrowserOwner("run", app.daiyu.android.browser.session.BrowserOwnerKind.MAIN),
            app.daiyu.android.browser.session.BrowserAccess.FULL)
        val newTab = """{"action":"new_tab"}"""
        val preparation = app.daiyu.android.browser.BrowserRunPreparation.prepare(identity,
            app.daiyu.android.browser.BrowserSettings(app.daiyu.android.browser.session.BrowserAccess.FULL),
            grant, setOf("new_tab", "list_tabs"), listOf(newTab))
        val host = object : CatalogBrowserBinding.Host {
            override val session = app.daiyu.android.browser.host.BrowserToolSession(grant,
                app.daiyu.android.browser.session.BrowserSessionRegistry(),
                object : app.daiyu.android.browser.host.BrowserTabLifecycle {
                    override fun create(lease: app.daiyu.android.browser.session.BrowserTabLease) {}
                    override fun close(lease: app.daiyu.android.browser.session.BrowserTabLease) {}
                }, object : app.daiyu.android.browser.bridge.BrowserExecutionPort {
                    override fun execute(command: app.daiyu.android.browser.bridge.BrowserCommand,
                        lease: app.daiyu.android.browser.session.BrowserTabLease) =
                        app.daiyu.android.browser.bridge.BrowserExecutionResult("test")
                })
            override fun closeWithResult(): app.daiyu.android.browser.network.BrowserCloseResult {
                session.close()
                return app.daiyu.android.browser.network.BrowserCloseResult(true)
            }
            override fun closeStatus() = app.daiyu.android.browser.network.BrowserCloseResult(true)
        }
        val browser = CatalogBrowserBinding.create(profile, preparation.confirm(preparation.preview)) { host }
        val token = runnerOwner(message)
        val paid = RunGenerationTools.writeEffect("account", token, config, config.batches.single())
        val auth = TaskAuthorization("run", "run", browser.requiredWrites + paid)
        val caps = SessionCapabilities("run", capabilities.enabled +
            setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY))
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, browser = browser,
            observeBrowserClose = { binding, done -> done(checkNotNull(binding.closeResult())) },
            authorizationFactory = { assertSame(token, it); auth },
            generationFactory = { owner, _, actual ->
                assertSame(token, owner); assertSame(auth, actual)
                assertSame(actual, browser.bindOwner(owner, actual).taskAuthorization)
                leaf(owner, actual) { false }
            }, capture = { _, actual ->
                assertSame(auth, actual); SkillRunSession.create(actual, emptySet(), emptyList())
            })
        try {
            tools.bindOwner(token)
            assertTrue(tools.definitions.any { it.name == RunGenerationTools.IMAGE_TOOL })
            assertTrue(tools.definitions.any { it.name == RunBrowserTools.WRITE_TOOL_NAME })
            val browserCall = ToolInvocation(ToolCallId("browser-call"), RunBrowserTools.WRITE_TOOL_NAME, newTab)
            assertTrue(tools.execute(message, listOf(browserCall)) { false }.single().outcome is DispatchOutcome.Confirmed)
            assertEquals(DispatchOutcome.NotExecuted("generation_owner_rejected"),
                tools.execute(message, listOf(call)) { false }.single().outcome)
        } finally { assertEquals(RunCatalogCloseResult.Drained, closeAndAwait(tools)) }
    }

    @Test fun throwingGenerationFactoryIsUnknownAndCannotBeRetried() {
        val opens = AtomicInteger()
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId,
            authorizationFactory = { TaskAuthorization(capabilities.sessionId, it.runId.value) },
            generationFactory = { _, _, _ -> opens.incrementAndGet(); error("factory failed") }, capture = ::capture)
        val token = runnerOwner(message)
        rejected { tools.bindOwner(token) }; rejected { tools.bindOwner(token) }
        assertEquals(1, opens.get())
        assertTrue(closeAndAwait(tools) is RunCatalogCloseResult.Unknown)
    }
}
