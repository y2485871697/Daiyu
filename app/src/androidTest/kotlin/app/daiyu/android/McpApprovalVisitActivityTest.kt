package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
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
    private lateinit var root: File
    private val transports = AtomicInteger()
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
        })
    }

    override fun setUp() {
        super.setUp()
        val store = McpSettingsStore(fixture.application)
        store.save(listOf(McpServer("server", "fixture", "https://fixture.invalid/mcp", "")), McpSettingsOwner(), store.snapshot())
        setDraft(text)
    }

    override fun tearDown() {
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
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        node(tag).assertExists()
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
    private fun preview(): Pair<ControllerMcpPreparation, Any> {
        val action = openInput()
        mainThread { host(activity, "setPrompt", null); action(raw) }
        ui.waitUntil(timeoutMillis = 5_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = approval() != null }
            ready
        }
        awaitTag("mcp-approve")
        lateinit var result: Pair<ControllerMcpPreparation, Any>
        mainThread { result = checkNotNull(approval()) to checkNotNull(visit()) }
        return result
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
    private fun pauseResume() {
        instrumentation.callActivityOnPause(activity)
        instrumentation.callActivityOnResume(activity)
    }

    fun testRetainedInputCannotResurrectAfterRouteSidebarOrPauseAndCannotReplaceANewVisit() {
        val transitions: List<() -> Unit> = listOf(::routeRoundTrip, ::sidebarRoundTrip, ::pauseResume)
        for (transition in transitions) {
            val stale = openInput()
            mainThread { transition(); assertNull(visit()); stale(raw); assertNull(approval()); assertNull(host(activity, "getPrompt")) }
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

    fun testPreviewApproveUsesOriginalVisitAndForegroundNotTheCurrentVisit() {
        for (transition in listOf<() -> Unit>(::routeRoundTrip, ::sidebarRoundTrip)) {
            val (old, oldVisit) = preview()
            mainThread { transition() }
            assertFalse(old.scope.isCurrent())
            val (fresh, freshVisit) = preview()
            mainThread {
                // The same production host method is captured by McpApprovalHost's approve callback.
                host(activity, "approveMcp", old, oldVisit)
                assertSame(fresh, approval()); assertSame(freshVisit, visit())
                host(activity, "cancelMcpEditor")
            }
            assertUnsent()
        }
        val (fresh, freshVisit) = preview()
        mainThread {
            instrumentation.callActivityOnPause(activity)
            host(activity, "approveMcp", fresh, freshVisit)
            host(activity, "openMcpApproval")
            assertNull(approval()); assertNull(visit()); assertNull(host(activity, "getPrompt"))
            instrumentation.callActivityOnResume(activity)
            host(activity, "approveMcp", fresh, freshVisit)
            assertNull(approval()); assertFalse(fresh.scope.isCurrent())
        }
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
        val (next, _) = preview()
        mainThread { host(activity, "cancelMcpEditor") }
        assertFalse(next.scope.isCurrent()); assertUnsent()
    }

    fun testDestroyedActivityCannotReuseOriginalInputOrPreviewCallback() {
        val oldActivity = activity
        val input = openInput()
        activity = recreateActivity(activity)
        mainThread { input(raw); assertNull(visit()); assertNull(approval()); assertNull(visit(oldActivity)) }
        val (pending, token) = preview()
        val previewActivity = activity
        activity = recreateActivity(activity)
        mainThread {
            host(previewActivity, "approveMcp", pending, token)
            assertNull(approval()); assertNull(visit()); assertFalse(pending.scope.isCurrent())
        }
        assertUnsent()
    }
}
