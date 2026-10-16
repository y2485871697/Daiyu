package app.daiyu.android.integration.tools

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real facade + catalog/ledger, fake lifecycle and port. No WebView, device or network. */
class RunBrowserToolsTest {
    private class Resources : BrowserTabLifecycle {
        val created = mutableListOf<BrowserTabLease>()
        val closed = mutableListOf<BrowserTabLease>()
        var failCreate = false
        override fun create(lease: BrowserTabLease) {
            created.add(lease)
            if (failCreate) error("allocation outcome uncertain")
        }
        override fun close(lease: BrowserTabLease) { closed.add(lease) }
    }

    private class Port : BrowserExecutionPort {
        val calls = mutableListOf<Pair<BrowserCommand, BrowserTabLease>>()
        var result = BrowserExecutionResult("browser result")
        var fail = false
        override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
            calls.add(command to lease)
            if (fail) error("lost reply after entry")
            return result
        }
    }

    // Obtain the opaque token from the real runner rather than reflecting/constructing an owner.
    private fun owner(): RunExecutionOwner {
        var token: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { token = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(token)
    }

    private inner class Fixture(
        access: BrowserAccess = BrowserAccess.FULL,
        approved: List<String> = emptyList(),
        enabled: Set<String> = setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY),
        val token: RunExecutionOwner = owner()
    ) : AutoCloseable {
        val resources = Resources()
        val port = Port()
        val session = BrowserToolSession(BrowserGrant(BrowserOwner("session", BrowserOwnerKind.MAIN), access),
            BrowserSessionRegistry(), resources, port)
        val task = TaskAuthorization("session", "run", approved.map { RunBrowserTools.writeEffect(session, it) }.toSet())
        val tools = RunBrowserTools.create(session, token, task)
        val store = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(ToolRegistry().apply { tools.forEach { register(it) } }.snapshot(),
            SessionCapabilities("session", enabled), ToolLedger(store))
        fun call(json: String, write: Boolean = false, id: String = "call") = ToolInvocation(ToolCallId(id),
            if (write) RunBrowserTools.WRITE_TOOL_NAME else RunBrowserTools.READ_TOOL_NAME, json)
        fun dispatch(json: String, write: Boolean = false, id: String = "call", auth: TaskAuthorization = task,
            runOwner: RunExecutionOwner = token, cancelled: () -> Boolean = { false }): DispatchOutcome =
            dispatcher.dispatchOwned(auth, listOf(call(json, write, id)), runOwner, cancelled).single().outcome
        fun hostTab() {
            assertTrue(session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed)
        }
        override fun close() = session.close()
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected validation rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun creationAndPreparationArePureAndExecutionRequiresDispatcherContext() {
        val json = """{"action":"new_tab"}"""
        Fixture(approved = listOf(json)).use { f ->
            val write = f.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
            val plan = write.prepare(write.definition.schema.parse(json))
            val read = f.tools.single { it.definition.name == RunBrowserTools.READ_TOOL_NAME }
            read.prepare(read.definition.schema.parse("""{"action":"list_tabs"}"""))
            assertEquals(RunBrowserTools.writeEffect(f.session, json), plan.writeEffect)
            assertNull(f.session.currentTab)
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.resources.closed.isEmpty())
            assertTrue(f.port.calls.isEmpty())
            try { plan.execute(); fail("context-free execution") } catch (_: IllegalStateException) { }
            assertTrue(f.resources.created.isEmpty())
            val result = f.dispatch(json, write = true) as DispatchOutcome.Confirmed
            assertFalse(result.isError)
            assertTrue(JSONObject(result.payload).getString("text").contains("\"tabId\":0"))
            assertEquals(1, f.resources.created.size)
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun existingSessionDefaultReadAndResultIncludingFilePathArePreserved() {
        Fixture().use { f ->
            f.hostTab()
            f.port.result = BrowserExecutionResult("quoted \"text\"\n原文", "/private/result.png")
            val result = f.dispatch("""{"action":"screenshot","full_page":true}""") as DispatchOutcome.Confirmed
            val payload = JSONObject(result.payload)
            assertEquals(f.port.result.text, payload.getString("text"))
            assertEquals(f.port.result.filePath, payload.getString("filePath"))
            val (command, lease) = f.port.calls.single()
            assertEquals("screenshot", command.action)
            assertEquals(true, command.arguments["full_page"])
            assertEquals(f.session.currentTab, lease)
            assertEquals(1, f.resources.created.size) // No second session/host/tab.
            assertTrue((f.dispatch("""{"action":"screenshot","full_page":true}""") as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.port.calls.size)
        }
    }

    @Test fun absentFilePathIsExplicitNullAndReadNeverCreatesTab() {
        Fixture().use { f ->
            val result = f.dispatch("""{"action":"list_tabs"}""") as DispatchOutcome.Confirmed
            assertEquals("{\"tabs\":[],\"currentTabId\":null}", JSONObject(result.payload).getString("text"))
            assertTrue(JSONObject(result.payload).isNull("filePath"))
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun notExecutedReasonIsNotConvertedToConfirmedOrUnknown() {
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("BROWSER_CURRENT_TAB_MISSING"), f.dispatch("""{"action":"get_text"}"""))
            f.session.close()
            assertEquals(DispatchOutcome.NotExecuted("BROWSER_SESSION_CLOSED"),
                f.dispatch("""{"action":"list_tabs"}""", id = "closed"))
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun exactlyApprovedNavigationExecutesOnceAndRetainsNormalizedArguments() {
        val json = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":500}"""
        Fixture(approved = listOf(json)).use { f ->
            f.hostTab()
            val result = f.dispatch(json, write = true) as DispatchOutcome.Confirmed
            assertFalse(result.isError)
            assertEquals("browser result", JSONObject(result.payload).getString("text"))
            val (command, lease) = f.port.calls.single()
            assertEquals("navigate", command.action)
            assertEquals(0, command.tabId)
            assertEquals(mapOf("url" to "https://example.invalid/", "timeout_ms" to 500), command.arguments)
            assertEquals(f.session.currentTab, lease)
            assertTrue((f.dispatch(json, write = true, id = "equivalent") as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.port.calls.size)
        }
    }

    @Test fun uncertainWriteIsNotRetriedBySameOrNewCallId() {
        val json = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/"}"""
        Fixture(approved = listOf(json)).use { f ->
            f.hostTab()
            f.port.fail = true
            assertEquals(DispatchOutcome.Unknown("browser_execution_unconfirmed"), f.dispatch(json, write = true))
            assertTrue(f.dispatch(json, write = true) is DispatchOutcome.Unknown)
            assertTrue(f.dispatch(json, write = true, id = "fresh-id") is DispatchOutcome.Unknown)
            assertEquals(1, f.port.calls.size)
            assertEquals(1, f.resources.created.size)
            assertTrue(f.resources.closed.isEmpty()) // Adapter never retries/reopens/cleans up.
        }
    }

    @Test fun uncertainAllocationIsAlsoUnknownWithoutReopening() {
        val json = """{"action":"new_tab"}"""
        Fixture(approved = listOf(json)).use { f ->
            f.resources.failCreate = true
            assertEquals(DispatchOutcome.Unknown("browser_execution_unconfirmed"), f.dispatch(json, write = true))
            assertTrue(f.dispatch(json, write = true, id = "again") is DispatchOutcome.Unknown)
            assertEquals(1, f.resources.created.size)
            assertEquals(1, f.resources.closed.size) // Existing facade owns partial-allocation cleanup.
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun readsMapExplicitTabAndBoundedIntegersToExistingFacade() {
        Fixture().use { f ->
            f.hostTab()
            f.dispatch("""{"action":"get_text","tab_id":0,"offset":3,"max_chars":9}""") as DispatchOutcome.Confirmed
            val command = f.port.calls.single().first
            assertEquals(0, command.tabId)
            assertEquals(mapOf("offset" to 3, "max_chars" to 9), command.arguments)
        }
    }

    @Test fun noDefaultWriteNavigationOrFetchAuthorization() {
        Fixture().use { f ->
            for ((index, json) in listOf(
                """{"action":"new_tab"}""",
                """{"action":"close_tab","tab_id":0}""",
                """{"action":"navigate","tab_id":0,"url":"https://example.invalid/"}""",
                """{"action":"fetch","tab_id":0,"url":"https://example.invalid/file"}""",
                """{"action":"execute_js","tab_id":0,"script":"1"}"""
            ).withIndex()) {
                assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), f.dispatch(json, write = true, id = "write-$index"))
            }
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun readOnlyGrantCannotBeUpgradedEvenWithAnExactTaskEffect() {
        val json = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/"}"""
        val effect = Fixture().use { RunBrowserTools.writeEffect(it.session, json) }
        Fixture(access = BrowserAccess.READ_ONLY).use { f ->
            f.hostTab()
            val task = TaskAuthorization("session", "run", setOf(effect))
            val tools = RunBrowserTools.create(f.session, f.token, task)
            val dispatcher = ToolDispatcher(ToolRegistry().apply { tools.forEach { register(it) } }.snapshot(),
                SessionCapabilities("session", setOf(RunBrowserTools.WRITE_CAPABILITY)), ToolLedger(f.store))
            assertEquals(DispatchOutcome.NotExecuted("preparation_failed"),
                dispatcher.dispatchOwned(task, listOf(f.call(json, write = true)), f.token).single().outcome)
            assertTrue(f.port.calls.isEmpty())
            assertEquals(1, f.resources.created.size)
        }
    }

    @Test fun ownerAndTaskMustBeTheExactInjectedObjects() {
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"),
                f.dispatch("""{"action":"list_tabs"}""", runOwner = owner()))
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
                f.dispatch("""{"action":"list_tabs"}""", id = "copy", auth = TaskAuthorization("session", "run")))
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun confirmedReadAndWriteReplayRequireExactOwnerAndAuthorizationBeforeLedgerAccess() {
        for (write in listOf(false, true)) {
            val json = if (write) """{"action":"navigate","tab_id":0,"url":"https://example.invalid/"}"""
                else """{"action":"screenshot","tab_id":0}"""
            Fixture(approved = if (write) listOf(json) else emptyList()).use { f ->
                f.hostTab()
                f.port.result = BrowserExecutionResult("private browser payload", "/private/result.png")
                val first = f.dispatch(json, write = write) as DispatchOutcome.Confirmed
                assertFalse(first.replayed)
                assertEquals(f.port.result.text, JSONObject(first.payload).getString("text"))
                assertEquals(f.port.result.filePath, JSONObject(first.payload).getString("filePath"))
                val rows = f.store.transaction { it.toList() }
                val otherOwner = owner()
                assertNotSame(f.token, otherOwner)
                assertEquals(f.token.conversationId, otherOwner.conversationId)
                assertEquals(f.token.runId, otherOwner.runId)
                assertEquals(f.token.assistantMessageId, otherOwner.assistantMessageId)
                val otherTask = TaskAuthorization(f.task.sessionId, f.task.taskId, f.task.allowedWrites)
                assertNotSame(f.task, otherTask)
                // Same call hits the receipt; a fresh write call hits the same effect's receipt.
                val replayIds = if (write) listOf("call", "same-effect") else listOf("call")
                for (id in replayIds) {
                    assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"),
                        f.dispatch(json, write = write, id = id, runOwner = otherOwner))
                    assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
                        f.dispatch(json, write = write, id = id, auth = otherTask))
                    assertEquals(rows, f.store.transaction { it.toList() })
                    assertEquals(1, f.port.calls.size)
                }
                for (id in replayIds) {
                    val replay = f.dispatch(json, write = write, id = id) as DispatchOutcome.Confirmed
                    assertTrue(replay.replayed)
                    assertEquals(first.payload, replay.payload)
                    assertEquals(first.isError, replay.isError)
                }
                assertEquals(1, f.port.calls.size)
                assertEquals(1, f.resources.created.size)
                assertTrue(f.resources.closed.isEmpty())
            }
        }
    }

    @Test fun creationRejectsMismatchedTaskOrBrowserSessionScope() {
        Fixture().use { f ->
            rejected { RunBrowserTools.create(f.session, f.token, TaskAuthorization("other", "run")) }
            rejected { RunBrowserTools.create(f.session, f.token, TaskAuthorization("session", "other")) }
            assertTrue(f.resources.created.isEmpty())
        }
    }

    @Test fun forgedIdentityUnknownActionsAndInvalidShapesAreRejectedBeforeExecution() {
        Fixture().use { f ->
            val read = f.tools.single { it.definition.effect == ToolEffect.READ_ONLY }
            for (json in listOf(
                """{"action":"list_tabs","owner":"other"}""",
                """{"action":"list_tabs","grant":"FULL"}""",
                """{"action":"list_tabs","authorization":{}}""",
                """{"action":"list_tabs","session":"other"}""",
                """{"action":"navigate","url":"https://example.invalid/"}""",
                """{"action":"download","url":"https://example.invalid/"}""",
                """{"action":"get_text","tab_id":null}""",
                """{"action":"get_text","tab_id":"0"}""",
                """{"action":"get_text","tab_id":3}""",
                """{"action":"get_text","offset":-1}""",
                """{"action":"get_text","max_chars":64001}""",
                """{"action":"get_text","tabId":0}"""
            )) rejected { read.prepare(read.definition.schema.parse(json)) }
            // Cross-action options must not be silently ignored by the union schema.
            rejected { read.prepare(read.definition.schema.parse("""{"action":"list_tabs","offset":0}""")) }
            val longSelector = JSONObject().put("action", "get_text").put("selector", "😀".repeat(32001)).toString()
            rejected { read.prepare(read.definition.schema.parse(longSelector)) }
            val longKeyword = """{"action":"get_cookies","tab_id":0,"keywords":["${"😀".repeat(65)}"]}"""
            rejected { RunBrowserTools.writeEffect(f.session, longKeyword) }
            assertTrue(f.port.calls.isEmpty())
            assertTrue(f.resources.created.isEmpty())
        }
    }

    @Test fun entireBatchValidatesBeforeFirstTabAllocation() {
        val json = """{"action":"new_tab"}"""
        Fixture(approved = listOf(json)).use { f ->
            val bad = f.call("""{"action":"get_text","selector":"   "}""", id = "bad")
            val result = f.dispatcher.dispatchOwned(f.task, listOf(f.call(json, write = true), bad), f.token)
            assertTrue(result.all { it.outcome == DispatchOutcome.NotExecuted("preparation_failed") })
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun exactEffectIncludesActionTabUrlOptionsAndHostOwner() {
        val json = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":1000}"""
        Fixture(approved = listOf(json)).use { f ->
            val effect = RunBrowserTools.writeEffect(f.session, json)
            assertEquals(effect, RunBrowserTools.writeEffect(f.session,
                """{ "timeout_ms":1000,"url":"https://example.invalid/","tab_id":0,"action":"navigate" }"""))
            for (other in listOf(
                json.replace("tab_id\":0", "tab_id\":1"),
                json.replace("example.invalid/", "example.invalid/other"),
                json.replace("1000", "1001")
            )) {
                assertNotEquals(effect, RunBrowserTools.writeEffect(f.session, other))
                assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), f.dispatch(other, write = true, id = other))
            }
            val child = BrowserToolSession(BrowserGrant(BrowserOwner("session", BrowserOwnerKind.CHILD), BrowserAccess.FULL),
                BrowserSessionRegistry(), Resources(), Port())
            child.use { assertNotEquals(effect, RunBrowserTools.writeEffect(it, json)) }
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun writesRejectMovingTargetsUnsafeUrlsAndInvalidModesDuringPrepare() {
        Fixture().use { f ->
            for (json in listOf(
                """{"action":"navigate","url":"https://example.invalid/"}""",
                """{"action":"new_tab","tab_id":0}""",
                """{"action":"new_tab","url":"https://example.invalid/"}""",
                """{"action":"navigate","tab_id":0,"url":"file:///private/file"}""",
                """{"action":"fetch","tab_id":0,"url":"https://user@example.invalid/"}""",
                """{"action":"click","tab_id":0,"selector":"a","coordinate_x":0,"coordinate_y":0}""",
                """{"action":"type","tab_id":0,"coordinate_x":0,"text":"x"}""",
                """{"action":"set_viewport","tab_id":0,"viewport_width":800}""",
                """{"action":"set_viewport","tab_id":0,"reset":true,"viewport_width":800,"viewport_height":600}"""
            )) rejected { RunBrowserTools.writeEffect(f.session, json) }
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
        }
    }

    @Test fun disabledCapabilityUnownedDispatchAndCancellationNeverEnterFacade() {
        Fixture(enabled = emptySet()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("capability_disabled"), f.dispatch("""{"action":"list_tabs"}"""))
            assertTrue(f.store.transaction { it.isEmpty() })
        }
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"),
                f.dispatcher.dispatch(f.task, listOf(f.call("""{"action":"list_tabs"}"""))).single().outcome)
            assertEquals(DispatchOutcome.NotExecuted("cancelled_before_execution"),
                f.dispatch("""{"action":"list_tabs"}""", cancelled = { true }))
            assertTrue(f.store.transaction { it.isEmpty() })
            assertTrue(f.resources.created.isEmpty())
            assertTrue(f.port.calls.isEmpty())
        }
    }
}
