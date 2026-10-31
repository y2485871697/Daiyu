package app.daiyu.android.browser

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.RunBrowserTools
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.Assert.*
import org.junit.Test

/** Production schema, admission, single-effect approval and central ledger; no WebView/network. */
class BrowserDefaultPermissionTest {
    private val assistant = AssistantProfile("profile", "Profile", "Frozen instructions")
    private val full = BrowserSettings(BrowserAccess.FULL)
    private val caps = setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)
    private val identity = BrowserRunIdentity(ConversationId("conversation"), RunId("parent"), assistant.id, MessageId("reply"))
    private val newTab = """{"action":"new_tab"}"""
    private val navigation = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/dynamic"}"""

    private fun owner(id: BrowserRunIdentity): RunExecutionOwner {
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
        runner.run(Message(id.assistantMessageId, id.conversationId, id.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(token)
    }

    private inner class Fixture(val preparation: BrowserRunPreparation,
        registry: BrowserSessionRegistry = BrowserSessionRegistry(),
        val scope: ChildRunScope? = null,
        sharedLedger: ToolLedger? = null) : AutoCloseable {
        var creates = 0
        var executions = 0
        var uncertain = false
        val leases = mutableListOf<BrowserTabLease>()
        val token = preparation.confirm(preparation.preview)
        val owner = owner(preparation.preview.identity)
        val session = BrowserToolSession(preparation.preview.grant, registry, object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { creates++; leases.add(lease) }
            override fun close(lease: BrowserTabLease) {}
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                executions++
                leases.add(lease)
                if (uncertain) error("lost reply after entry")
                return BrowserExecutionResult("ok")
            }
        })
        val bound = RunBrowserTools.createConfirmed(session, owner, assistant, token)
        val task = bound.taskAuthorization
        val ledger = sharedLedger ?: ToolLedger(InMemoryToolLedgerStore())
        val dispatcher = ToolDispatcher(ToolRegistry().apply { bound.tools.forEach { register(it) } }.snapshot(),
            scope?.session ?: SessionCapabilities(task.sessionId, caps), ledger)
        fun dispatch(raw: String, id: String, write: Boolean = true, task: TaskAuthorization = this.task,
            owner: RunExecutionOwner = this.owner, cancelled: () -> Boolean = { false }): DispatchOutcome =
            dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId(id),
                if (write) RunBrowserTools.WRITE_TOOL_NAME else RunBrowserTools.READ_TOOL_NAME, raw)), owner, cancelled).single().outcome
        override fun close() { token.revoke(); session.close() }
    }

    private fun main(access: BrowserAccess = BrowserAccess.FULL): BrowserRunPreparation {
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), access)
        return BrowserRunPreparation.prepare(identity, BrowserSettings(access), grant, setOf("list_tabs"),
            defaultPermission = BrowserDefaultPermission.main(grant))
    }

    private fun scope(child: String, parentCaps: Set<String> = caps): ChildRunScope {
        val childOwner = ChildRunOwner(identity.conversationId.value, identity.runId.value, "group", 1, child, "reply-$child")
        return ChildRunScope.capture(childOwner, SessionCapabilities(identity.runId.value, parentCaps),
            TaskAuthorization(identity.runId.value, identity.runId.value), caps)
    }

    private fun child(parent: BrowserRunPreparation, scope: ChildRunScope): BrowserRunPreparation {
        val grant = BrowserGrant(BrowserOwner(scope.owner.childRunId, BrowserOwnerKind.CHILD), BrowserAccess.FULL)
        return BrowserRunPreparation.prepareChild(scope, assistant.id, full, grant, setOf("list_tabs"),
            defaultPermission = BrowserDefaultPermission.child(grant, scope, checkNotNull(parent.preview.defaultPermission)))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) {}
    }

    @Test fun defaultDynamicallyApprovesExactEffectsWithoutBroadeningTheTask() {
        Fixture(main()).use { f ->
            assertTrue(f.preparation.preview.writes.isEmpty())
            assertTrue(f.task.allowedWrites.isEmpty())
            assertEquals(0, f.creates)
            assertEquals(0, f.executions)
            assertTrue(f.dispatch(newTab, "create") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(navigation, "navigate") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(navigation.replace("dynamic", "next"), "next") is DispatchOutcome.Confirmed)
            assertEquals(1, f.creates)
            assertEquals(2, f.executions)
            // Same exact effect under a fresh model call ID is a replay, not a second request.
            assertTrue(f.dispatch(navigation, "replay") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(newTab, "replay-create") is DispatchOutcome.Confirmed)
            assertEquals(1, f.creates)
            assertEquals(2, f.executions)
            assertTrue(f.task.allowedWrites.isEmpty())
            val exact = f.token.defaultWriteAuthorization(f.owner, assistant.id, f.session, f.task, navigation)!!
            assertEquals(setOf(RunBrowserTools.writeEffect(f.session, navigation)), exact.allowedWrites)
        }
    }

    @Test fun schemaUrlTabAndUnavailableActionsStillFailBeforeExecution() {
        Fixture(main()).use { f ->
            for ((index, raw) in listOf(navigation.replace("\"tab_id\":0,", ""),
                navigation.replace("\"tab_id\":0", "\"tab_id\":3"),
                navigation.replace("https://example.invalid/dynamic", "javascript:alert(1)"),
                """{"action":"fetch","tab_id":0,"url":"https://example.invalid/","headers":{"Cookie":"secret"}}""",
                """{"action":"get_cookies","tab_id":0}""",
                """{"action":"history","tab_id":0}""").withIndex()) {
                assertTrue(f.dispatch(raw, "bad-$index") is DispatchOutcome.NotExecuted)
            }
            // Permission doesn't invent a tab or borrow a moving current-tab target.
            assertTrue(f.dispatch(navigation, "missing-tab") is DispatchOutcome.NotExecuted)
            assertEquals(0, f.creates)
            assertEquals(0, f.executions)
        }
    }

    @Test fun cancellationCopiedTaskOldOwnerAndRevocationFenceFreshCallsAndReplay() {
        Fixture(main()).use { f ->
            assertTrue(f.dispatch(newTab, "cancelled", cancelled = { true }) is DispatchOutcome.NotExecuted)
            assertEquals(0, f.creates)
            assertTrue(f.dispatch(newTab, "create") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(navigation, "navigate") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(navigation, "copied-task", task = TaskAuthorization(f.task.sessionId, f.task.taskId)) is DispatchOutcome.NotExecuted)
            assertTrue(f.dispatch(navigation, "old-owner", owner = owner(identity)) is DispatchOutcome.NotExecuted)
            assertEquals(1, f.executions)
            f.token.revoke()
            assertTrue(f.dispatch(navigation, "revoked-replay") is DispatchOutcome.NotExecuted)
            assertTrue(f.dispatch(navigation.replace("dynamic", "fresh"), "revoked-fresh") is DispatchOutcome.NotExecuted)
            rejected { f.token.defaultWriteAuthorization(f.owner, assistant.id, f.session, f.task, navigation) }
            assertEquals(1, f.executions)
        }
    }

    @Test fun unknownIsNotRetriedUnderFreshCallId() {
        Fixture(main()).use { f ->
            assertTrue(f.dispatch(newTab, "create") is DispatchOutcome.Confirmed)
            f.uncertain = true
            assertTrue(f.dispatch(navigation, "uncertain") is DispatchOutcome.Unknown)
            assertTrue(f.dispatch(navigation, "uncertain-again") is DispatchOutcome.Unknown)
            assertEquals(1, f.executions)
        }
    }

    @Test fun childDefaultsPreserveCapturedTaskAndCannotBorrowParentOrSiblingSession() {
        val parent = main()
        val childScope = scope("child")
        val siblingScope = scope("sibling")
        val registry = BrowserSessionRegistry()
        val ledger = ToolLedger(InMemoryToolLedgerStore())
        Fixture(parent, registry, sharedLedger = ledger).use { p ->
            Fixture(child(parent, childScope), registry, childScope, ledger).use { c ->
                Fixture(child(parent, siblingScope), registry, siblingScope, ledger).use { s ->
                    assertSame(childScope.authorization, c.task)
                    assertTrue(c.task.allowedWrites.isEmpty())
                    assertTrue(p.dispatch(newTab, "parent-tab") is DispatchOutcome.Confirmed)
                    assertTrue(c.dispatch(navigation, "cannot-borrow-parent-tab") is DispatchOutcome.NotExecuted)
                    assertTrue(c.dispatch(newTab, "child-tab") is DispatchOutcome.Confirmed)
                    assertTrue(c.dispatch(navigation, "child-navigation") is DispatchOutcome.Confirmed)
                    assertEquals(BrowserOwner("child", BrowserOwnerKind.CHILD), c.leases.last().owner)
                    assertTrue(s.dispatch(navigation, "cannot-borrow-child-tab") is DispatchOutcome.NotExecuted)
                    assertTrue(c.dispatch(navigation, "sibling-owner", owner = s.owner) is DispatchOutcome.NotExecuted)
                    assertTrue(c.dispatch(navigation, "parent-task", task = p.task) is DispatchOutcome.NotExecuted)
                    rejected { c.token.checkInvocation(p.owner, assistant.id, p.session, RunBrowserTools.WRITE_TOOL_NAME, navigation) }
                    c.token.revoke()
                    assertTrue(c.dispatch(navigation, "revoked-child") is DispatchOutcome.NotExecuted)
                    assertTrue(p.dispatch(navigation, "parent-still-live") is DispatchOutcome.Confirmed)
                    assertTrue(s.dispatch(newTab, "sibling-still-live") is DispatchOutcome.Confirmed)
                    assertTrue(childScope.authorization.allowedWrites.isEmpty())
                }
            }
        }
    }

    @Test fun productionHostDefaultsUsePrivateRunOffloadsAndRevocableOwnerCallbacks() {
        val posixFixture = if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) {
            null
        } else {
            Jimfs.newFileSystem(Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix").build())
        }
        posixFixture.use { fs ->
            val base = (if (fs == null) {
                java.nio.file.Files.createTempDirectory("browser-host-defaults")
            } else {
                java.nio.file.Files.createTempDirectory(fs.getPath("/"), "browser-host-defaults")
            }).toRealPath()
            try {
                Fixture(main()).use { f ->
                    val policy = BrowserRunHostDefaults.prepare(f.token, base)
                    assertNotEquals(base, policy.root)
                    val lease = BrowserTabLease(f.session.grant.owner, 0, 1, 1)
                    val sibling = lease.copy(owner = BrowserOwner("sibling", BrowserOwnerKind.CHILD))
                    assertTrue(policy.allowHistory(lease, "https://example.invalid/page"))
                    assertTrue(policy.authorizeCookieName(lease, "https://example.invalid/", "session_name"))
                    assertFalse(policy.authorizeCookieName(lease, "https://example.invalid/", "bad;name"))
                    assertFalse(policy.allowHistory(lease, "javascript:alert(1)"))
                    assertFalse(policy.allowHistory(sibling, "https://example.invalid/"))
                    assertFalse(policy.authorizeCookieName(sibling, "https://example.invalid/", "session_name"))
                    val cookieRoot = java.nio.file.Files.createDirectory(policy.root.resolve("cookie-offloads"))
                    val env = java.nio.file.Files.createFile(cookieRoot.resolve("${java.util.UUID.randomUUID()}.env"),
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
                    assertEquals(env.toRealPath().toString(), policy.cookieEnvPath(lease, env))
                    rejected { policy.cookieEnvPath(sibling, env) }
                    val other = BrowserRunHostDefaults.prepare(f.token, base)
                    rejected { other.cookieEnvPath(lease, env) }
                    java.nio.file.Files.setPosixFilePermissions(env,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
                    rejected { policy.cookieEnvPath(lease, env) }
                    f.token.revoke()
                    assertFalse(policy.allowHistory(lease, "https://example.invalid/"))
                    assertFalse(policy.authorizeCookieName(lease, "https://example.invalid/", "session_name"))
                    rejected { policy.cookieEnvPath(lease, env) }
                }
            } finally {
                java.nio.file.Files.walk(base).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
            }
        }
    }

    @Test fun childCapabilityAndParentPermissionIntersectionsRemainEffective() {
        val parent = main()
        val readScope = scope("read-child", setOf(RunBrowserTools.READ_CAPABILITY))
        Fixture(child(parent, readScope), scope = readScope).use { f ->
            assertSame(readScope.authorization, f.task)
            assertFalse("navigate" in f.preparation.preview.allowedActions)
            assertFalse("new_tab" in f.preparation.preview.allowedActions)
            assertTrue(f.dispatch("""{"action":"list_tabs"}""", "read", write = false) is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(newTab, "write-denied") is DispatchOutcome.NotExecuted)
            assertEquals(0, f.creates)
        }
        val limitedParent = main(BrowserAccess.READ_ONLY)
        val childScope = scope("limited-child")
        val preparation = child(limitedParent, childScope)
        assertFalse("navigate" in preparation.preview.allowedActions)
        assertTrue("new_tab" in preparation.preview.allowedActions)
        val schema = RunBrowserTools.definition(true, preparation.preview.allowedActions).schema
        assertEquals(setOf("new_tab", "close_tab"),
            (schema.fields.single { it.name == "action" }.type as ArgumentType.Text).choices)
        schema.parse(newTab)
        rejected { schema.parse(navigation) }
        rejected { BrowserDefaultPermission.child(
            BrowserGrant(BrowserOwner("sibling", BrowserOwnerKind.CHILD), BrowserAccess.FULL), childScope,
            checkNotNull(parent.preview.defaultPermission)) }
    }
}
