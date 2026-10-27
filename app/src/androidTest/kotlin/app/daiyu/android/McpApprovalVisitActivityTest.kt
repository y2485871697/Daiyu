package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import app.daiyu.android.mcp.McpRemoteTool
import app.daiyu.android.mcp.McpServer
import app.daiyu.android.mcp.McpSettingsOwner
import app.daiyu.android.mcp.McpSettingsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Main InputPrompt actions/preview handler, real Controller preparation, no HTTP/model calls.
 * Retained actions deliberately bypass PromptSurface's outer guard to test the MCP inner guard.
 * All settings envelopes live under a fresh fixture root, never the installed MCP private file.
 */
@Suppress("DEPRECATION")
class McpApprovalVisitActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private lateinit var root: File
    private val transports = AtomicInteger()
    private val entered = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)
    private val text = "  MCP visit draft remains exact  "
    private val raw = """{"server":"server","tool":"erase","arguments":{"target":"one"}}"""

    override fun createFixture(): RunControlFixture {
        root = File(instrumentation.targetContext.cacheDir, "mcp-visit-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        return RunControlFixture(isolated, mcpTransport = {
            transports.incrementAndGet(); error("MCP visit test must never open a transport")
        }, beforeModel = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
    }

    override fun setUp() {
        super.setUp()
        val store = McpSettingsStore(fixture.application)
        store.save(listOf(McpServer("server", "fixture", "https://fixture.invalid/mcp", "", enabled = true, catalog = listOf(McpRemoteTool("erase", "erase")), allowedTools = setOf("erase"))), McpSettingsOwner(), store.snapshot())
        setDraft(text)
    }

    override fun tearDown() {
        release.countDown()
        try { super.tearDown() } finally { if (::root.isInitialized) root.deleteRecursively() }
    }

    private fun host(target: MainActivity, name: String, vararg arguments: Any?): Any? {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        try { return method.apply { isAccessible = true }.invoke(target, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }
    private fun visit(target: MainActivity = activity): Any? =
        MainActivity::class.java.getDeclaredField("mcpVisit").apply { isAccessible = true }.get(target)
    private fun approval(target: MainActivity = activity) = host(target, "getMcpApproval") as ControllerMcpPreparation?
    private fun preparation(): ControllerMcpPreparation? = synchronized(fixture.controller) {
        val token = ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fixture.controller)
        token?.javaClass?.getDeclaredField("mcp")?.apply { isAccessible = true }?.get(token) as ControllerMcpPreparation?
    }
    private fun awaitTag(tag: String) {
        awaitUniqueTag(tag) {
            "promptPresent=${host(activity, "getPrompt") != null} visitPresent=${visit() != null} " +
                "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun openInput(): (String) -> Unit {
        lateinit var action: (String) -> Unit
        mainThread {
            host(activity, "openMcpApproval")
            val input = checkNotNull(host(activity, "getPrompt"))
            action = input.javaClass.getDeclaredMethod("getAction").apply { isAccessible = true }.invoke(input) as (String) -> Unit
            assertNotNull(visit())
        }
        awaitTag("prompt-input")
        awaitTag("dialog-confirm")
        return action
    }
    fun testIdenticalDraftCallbackPreservesOriginalInputVisitAndPrompt() {
        val action = openInput()
        mainThread {
            val originalVisit = checkNotNull(visit())
            val originalPrompt = checkNotNull(host(activity, "getPrompt"))
            host(activity, "updateDraft", text)
            assertSame(originalVisit, visit())
            assertSame(originalPrompt, host(activity, "getPrompt"))
            host(activity, "cancelMcpEditor")
            action(raw)
            assertNull(visit())
            assertNull(approval())
        }
        assertUnsent()
    }

    private fun assertUnsent() {
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, transports.get()); assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.textSendState())
    }
    private fun sidebarRoundTrip() {
        host(activity, "setSidebarVisible", true)
        host(activity, "setSidebarVisible", false)
    }
    private fun routeRoundTrip() {
        host(activity, "setRetainedPage", "settings")
        host(activity, "setRetainedPage", "chat")
    }

    fun testRetainedInputCannotResurrectAfterRouteSidebarOrPauseAndCannotReplaceANewVisit() {
        val transitions: List<(() -> Unit) -> Unit> = listOf(
            { check -> mainThread { routeRoundTrip(); check() } },
            { check -> mainThread { sidebarRoundTrip(); check() } },
            { check -> realPauseResume(activity, atPause = check) },
        )
        for (transition in transitions) {
            val stale = openInput()
            transition { assertNull(visit()); stale(raw); assertNull(approval()); assertNull(host(activity, "getPrompt")) }
            val fresh = openInput()
            mainThread {
                val newVisit = visit(); val newInput = host(activity, "getPrompt")
                stale(raw)
                assertSame(newVisit, visit()); assertSame(newInput, host(activity, "getPrompt"))
                host(activity, "cancelMcpEditor")
                fresh(raw)
                assertNull(approval()); assertNull(visit())
            }
            assertUnsent()
        }
    }

    fun testOriginalInputSubmitAutomaticallyGrantsExactCapabilityAndIsSingleUse() {
        val action = openInput()
        mainThread { host(activity, "setPrompt", null); action(raw) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lateinit var pending: ControllerMcpPreparation
        mainThread {
            pending = checkNotNull(preparation())
            assertTrue(pending.confirmed)
            assertEquals(1, pending.scope.authorization.allowedWrites.size)
            // Retaining the original input cannot enqueue a second run or reissue a token.
            action(raw)
            assertSame(pending, preparation())
        }
        ui.onNodeWithTag("mcp-approve").assertDoesNotExist()
        mainThread { fixture.controller.rejectMcp(pending); assertFalse(pending.scope.isCurrent()) }
        release.countDown()
        awaitControl("original MCP preparation drained") { preparation() == null }
        assertUnsent()
    }

    fun testDismissedInputRevokesItsInnerActionBeforeAnotherVisit() {
        val action = openInput()
        node("dialog-cancel").performClick()
        mainThread { action(raw); assertNull(visit()); assertNull(approval()) }
        assertUnsent()
    }

    fun testIOResultQueuedBeforeLeavingCannotPublishAfterReturningToSameDraft() {
        val action = openInput()
        lateinit var late: ControllerMcpPreparation
        mainThread {
            host(activity, "setPrompt", null); action(raw)
            // Keep the UI lane occupied only in this test. IO creates the actual preparation,
            // but its continuation cannot publish the preview until after the round trip.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var value = preparation()
            while (value == null && System.nanoTime() < deadline) { Thread.sleep(5); value = preparation() }
            late = checkNotNull(value)
            assertNull(approval())
            routeRoundTrip()
            assertNull(visit())
        }
        awaitControl("late original MCP preparation rejected") { !late.scope.isCurrent() }
        mainThread { assertNull(approval()); assertNull(visit()); action(raw); assertNull(approval()) }
        assertUnsent()
        // The old continuation also released Controller preparation, without creating a new run.
        val next = openInput()
        mainThread { host(activity, "cancelMcpEditor"); next(raw) }
        assertUnsent()
    }

    fun testDestroyedActivityCannotReuseOriginalSubmitCallback() {
        val oldActivity = activity
        val input = openInput()
        activity = recreateActivity(activity)
        mainThread { input(raw); assertNull(visit()); assertNull(approval()); assertNull(visit(oldActivity)) }
        assertUnsent()
    }
}
