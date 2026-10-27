package app.daiyu.android

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.mcp.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.platform.actions.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production submission gates; no network, fabricated runtime owner or permission UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ControllerCapabilitySubmissionTest {
    private class Backend : PlatformActionBackend {
        var unavailable: String? = null
        var executions = 0
        override fun unavailable(kind: PlatformActionKind) = unavailable
        override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
            executions++
            return ExecutionReceipt.Confirmed("fixture")
        }
    }
    private val backend = Backend()
    private fun platform(kind: PlatformActionKind = PlatformActionKind.VOLUME,
        arguments: String = """{"stream":"music","index":3}""") =
        ControllerPlatformActionPreparation(null, "explicit task", PlatformAction.parse(kind, arguments), backend)

    @Test fun ordinaryActionSubmissionIssuesOnlyOriginalExactTokenWithoutAnotherPermission() {
        for ((kind, json) in listOf(
            PlatformActionKind.VOLUME to """{"stream":"music","index":3}""",
            PlatformActionKind.MEDIA to """{"key":"pause"}""")) {
            val pending = platform(kind, json)
            val scope = pending.authorizeForSubmission()
            assertTrue(pending.submitted)
            assertSame(scope, pending.scope)
            assertEquals(setOf(pending.action.effect), scope.authorization.allowedWrites)
            assertEquals(pending.runId.value, scope.authorization.taskId)
            assertEquals(0, backend.executions) // Authorization is not an Android effect.
            assertThrows(IllegalStateException::class.java) { pending.authorizeForSubmission() }
            pending.cancel()
            assertThrows(IllegalStateException::class.java) { pending.authorizeForSubmission() }
        }
    }

    @Test fun realCapabilityLossAndMissingActionArgumentsNeverBecomeSuccess() {
        val pending = platform()
        backend.unavailable = "fixture capability lost"
        assertThrows(IllegalStateException::class.java) { pending.authorizeForSubmission() }
        assertFalse(pending.submitted); assertNull(pending.scope)
        assertThrows(IllegalArgumentException::class.java) { platform(PlatformActionKind.WIFI, """{"enabled":true}""") }
        assertTrue(runCatching { platform(PlatformActionKind.VOLUME, "{}") }.isFailure)
        assertEquals(0, backend.executions)
        pending.cancel()
    }

    @Test fun cancelledTaskAndClockWithoutLocationCannotAcquireActionToken() {
        val cancelled = platform(); cancelled.cancel()
        assertThrows(IllegalStateException::class.java) { cancelled.authorizeForSubmission() }
        val timer = platform(PlatformActionKind.TIMER, """{"seconds":15,"label":"fixture"}""")
        assertThrows(IllegalStateException::class.java) { timer.authorizeForSubmission() }
        assertFalse(timer.submitted); assertNull(timer.scope)
        assertEquals(0, backend.executions)
        timer.cancel()
    }

    private val editor = ChatController.TextSendOwner(null, 2, 7)
    private val current = AtomicBoolean(true)
    private var connections = 0
    private fun mcp(): ControllerMcpPreparation {
        val server = McpServer("server", "Fixture", "https://mcp.invalid/rpc", "", true,
            listOf(McpRemoteTool("selected_tool", "fixture")), setOf("selected_tool"))
        val selection = McpSelection("server", "selected_tool", """{"target":"one"}""")
        val scope = McpRunScope(McpSettings("revision", listOf(server)), listOf(selection),
            ConversationId("conversation"), RunId("run"), current::get,
            transport = { connections++; error("submission must not connect") })
        return ControllerMcpPreparation(null, editor, "explicit task", AssistantProfile("profile", "Profile", "instructions"),
            ProviderSnapshot.capture(ModelSettings("https://model.invalid", "fixture-model", "fixture-key", false)),
            selection, scope, MessageId("reply"))
    }

    @Test fun mcpOriginalSubmitUsesFrozenAuthorizationExactlyOnceWithoutOpeningConnection() {
        val pending = mcp()
        val original = pending.scope.authorization
        assertTrue(pending.authorizeForSubmission(editor, "explicit task"))
        assertTrue(pending.confirmed)
        assertSame(original, pending.scope.authorization)
        assertEquals(1, original.allowedWrites.size)
        assertEquals(pending.selection.arguments, original.allowedWrites.single().change)
        assertFalse(pending.authorizeForSubmission(editor, "explicit task"))
        assertEquals(0, connections)
        pending.cancel()
        assertFalse(pending.confirmed)
    }

    @Test fun mcpRejectsOwnerRevisionDraftConfigChangesAndCancellation() {
        val pending = mcp()
        assertFalse(pending.authorizeForSubmission(editor.copy(draftRevision = 8), "explicit task"))
        assertFalse(pending.authorizeForSubmission(editor.copy(editorGeneration = 3), "explicit task"))
        assertFalse(pending.authorizeForSubmission(editor, "changed"))
        current.set(false)
        assertFalse(pending.authorizeForSubmission(editor, "explicit task"))
        pending.cancel(); current.set(true)
        assertFalse(pending.authorizeForSubmission(editor, "explicit task"))
        assertEquals(0, connections)
    }

    @Test fun concurrentMcpSubmitsCannotIssueTwoGrants() {
        val pending = mcp()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { workers.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS))
                pending.authorizeForSubmission(editor, "explicit task")
            }) }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(0, connections)
        } finally { pending.cancel(); workers.shutdownNow() }
    }
}
