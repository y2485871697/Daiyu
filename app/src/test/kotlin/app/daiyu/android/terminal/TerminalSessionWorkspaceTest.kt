package app.daiyu.android.terminal

import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class TerminalSessionWorkspaceTest {
    private class Resource(val rejectSetup: Boolean = false, val rejectCommand: Boolean = false) : TerminalOwnedResource {
        @Volatile var output = ""
        @Volatile var exited = false
        val writes = CopyOnWriteArrayList<String>()
        var resizes = 0
        override fun read(caller: TaskAuthorization, offset: Int, limit: Int): JSONObject {
            val bytes = output.toByteArray(Charsets.UTF_8)
            val end = minOf(bytes.size, offset + limit)
            return JSONObject().put("ok", true).put("stdout_base64", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
                .put("next_offset", end).put("has_more", end < bytes.size).put("log_truncated", false)
                .put("state", "running").put("wait_status", JSONObject.NULL).put("error", JSONObject.NULL)
        }
        override fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject {
            val text = bytes.toString(Charsets.UTF_8)
            writes += text
            val setup = Regex("daiyu-([^;]+);ready;").find(text)
            if ((setup != null && rejectSetup) || (text.startsWith("eval '") && rejectCommand))
                return JSONObject().put("ok", false).put("execution", "not_executed").put("error", "test_input_rejected")
            if (setup != null) output += "\u001b]daiyu-${setup.groupValues[1]};ready;/workspace\u0007"
            return JSONObject().put("ok", true).put("execution", "executed")
        }
        override fun resize(caller: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int): JSONObject {
            resizes++; return JSONObject().put("ok", true).put("execution", "executed")
        }
        override fun close() { exited = true }
        override fun awaitTermination(timeoutMillis: Long) = exited
    }
    private class Backend(val rejectSetup: Boolean = false, val rejectCommand: Boolean = false) : TerminalEnvironmentBackend {
        val resources = CopyOnWriteArrayList<Resource>()
        val requests = CopyOnWriteArrayList<TerminalOpenRequest>()
        override fun open(request: TerminalOpenRequest) = Resource(rejectSetup, rejectCommand).also {
            requests += request; resources += it
        }
        override fun linuxStatus() = TerminalLinuxState()
        override fun selectLinux(reference: LinuxGenerationRef?) = Unit
    }
    private class Fixture(val rejectSetup: Boolean = false, val rejectCommand: Boolean = false) : AutoCloseable {
        val backends = CopyOnWriteArrayList<Backend>()
        val anchor = TerminalUiOwner(TerminalEnvironmentHost(Backend()))
        val workspace = TerminalSessionWorkspace(anchor, {
            val backend = Backend(rejectSetup, rejectCommand).also { backends += it }
            TerminalUiOwner(TerminalEnvironmentHost(backend))
        }, { _, _ -> false })
        override fun close() { workspace.close() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("workspace did not converge", condition())
    }
    @Test fun restartAndSendKeepOriginalConversationWorkspaceDespiteNewCallerBinding() {
        Fixture().use { f ->
            f.workspace.setSimple(false)
            val original = TerminalWorkspaceDirectory(java.io.File("/approved/rootfs/first"))
            val changed = TerminalWorkspaceDirectory(java.io.File("/private/second"))
            val conversation = app.daiyu.android.domain.ConversationId("original")
            assertTrue(f.workspace.create(conversation, original))
            val id = f.workspace.state.value.activeSessionId!!
            await { f.workspace.state.value.active?.state?.acceptsInput == true }
            f.workspace.send(id, "input", app.daiyu.android.domain.ConversationId("changed"), changed)
            f.workspace.restart(id)
            await { f.backends.single().requests.size == 2 && f.workspace.state.value.active?.state?.acceptsInput == true }
            f.backends.single().requests.forEach { request ->
                assertSame(original, request.workspace)
                assertEquals(conversation, request.owner.conversationId)
            }
        }
    }

    @Test fun workspacePinSurvivesStopAndRestartUntilSessionRemoval() {
        Fixture().use { f ->
            f.workspace.setSimple(false)
            val closes = java.util.concurrent.atomic.AtomicInteger()
            val pin = AutoCloseable { closes.incrementAndGet() }
            assertTrue(f.workspace.create(workspace = TerminalWorkspaceDirectory(java.io.File("/approved/project")), workspacePin = pin))
            val id = f.workspace.state.value.activeSessionId!!
            await { f.workspace.state.value.active?.state?.acceptsInput == true }
            f.workspace.stop(id)
            await { f.workspace.state.value.active?.state?.ownsResources == false }
            assertEquals(0, closes.get())
            f.workspace.restart(id)
            await { f.workspace.state.value.activeSessionId != id && f.workspace.state.value.active?.state?.acceptsInput == true }
            assertEquals(0, closes.get())
            f.workspace.closeSession(f.workspace.state.value.activeSessionId!!)
            await { closes.get() == 1 }
        }
    }

    @Test fun entryObservationAndModeSelectionNeverOpenAShell() {
        Fixture().use { f ->
            f.workspace.refresh(); f.workspace.setSimple(false); f.workspace.setSimple(true)
            assertTrue(f.backends.isEmpty()); assertTrue(f.workspace.state.value.sessions.isEmpty())
        }
    }
    @Test fun twoSessionsHaveIndependentOwnersAndOldInputCannotFollowASelection() {
        Fixture().use { f ->
            f.workspace.setSimple(false)
            assertTrue(f.workspace.create()); val first = f.workspace.state.value.activeSessionId!!
            await { f.workspace.state.value.active?.state?.acceptsInput == true }
            assertTrue(f.workspace.create()); val second = f.workspace.state.value.activeSessionId!!
            await { f.workspace.state.value.active?.state?.acceptsInput == true }
            f.workspace.send(first, "must not send")
            f.workspace.send(second, "second")
            await { f.backends[1].resources.single().writes.isNotEmpty() }
            assertTrue(f.backends[0].resources.single().writes.isEmpty())
            f.workspace.select(first); f.workspace.send(first, "first")
            await { f.backends[0].resources.single().writes == listOf("first") }
            assertEquals(2, f.backends.size)
            f.workspace.closeSession(first)
            assertEquals(second, f.workspace.state.value.activeSessionId)
            f.workspace.closeSession(second)
            assertTrue(f.workspace.state.value.sessions.isEmpty()); assertNull(f.workspace.state.value.activeSessionId)
            assertEquals(2, f.backends.size)
        }
    }
    @Test fun restartChangesInputIdentityAndDoesNotRestartAnotherSession() {
        Fixture().use { f ->
            f.workspace.setSimple(false); assertTrue(f.workspace.create())
            val first = f.workspace.state.value.activeSessionId!!
            await { f.workspace.state.value.active?.state?.acceptsInput == true }
            f.workspace.restart(first)
            await { f.workspace.state.value.activeSessionId != first && f.workspace.state.value.active?.state?.acceptsInput == true }
            f.workspace.send(first, "old callback")
            assertTrue(f.backends.single().resources.last().writes.isEmpty())
            assertEquals(2, f.backends.single().resources.size)
            val next = f.workspace.state.value.activeSessionId!!
            f.workspace.resize(next, 30, 100)
            await { f.backends.single().resources.last().resizes == 1 }
        }
    }
    @Test fun simpleAndConsoleSessionsAreSeparateAndClosingBackgroundDoesNotStealSelection() {
        Fixture().use { f ->
            assertTrue(f.workspace.create()); val simple = f.workspace.state.value.activeSessionId!!
            f.workspace.setSimple(false); assertTrue(f.workspace.create()); val console = f.workspace.state.value.activeSessionId!!
            f.workspace.closeSession(simple)
            assertEquals(console, f.workspace.state.value.activeSessionId)
            f.workspace.setSimple(true); assertNull(f.workspace.state.value.active)
            assertEquals(1, f.workspace.state.value.sessions.size)
        }
    }
    @Test fun rejectedSetupClearsPendingInputAndClosesInsteadOfWaitingForever() {
        Fixture(rejectSetup = true).use { f ->
            f.workspace.send(null, "must not execute")
            await { f.workspace.state.value.active?.state?.ownsResources == false }
            assertTrue(f.workspace.state.value.active?.blocks.isNullOrEmpty())
            assertEquals(1, f.backends.single().resources.single().writes.size)
        }
    }
    @Test fun rejectedCommandStopsItsBlockAndOversizedCommandCreatesNoRunningBlock() {
        Fixture(rejectCommand = true).use { f ->
            f.workspace.send(null, "echo rejected")
            await { f.workspace.state.value.active?.blocks?.singleOrNull()?.running == false }
            val id = checkNotNull(f.workspace.state.value.activeSessionId)
            assertEquals("test_input_rejected", f.workspace.state.value.error)
            f.workspace.send(id, ": #" + "a".repeat(65_536))
            assertEquals(1, f.workspace.state.value.active?.blocks?.size)
            assertFalse(checkNotNull(f.workspace.state.value.active).running)
            assertEquals(2, f.backends.single().resources.single().writes.size)
        }
    }
    @Test fun simpleSessionLimitMatchesOriginalWithoutLimitingConsoleSessions() {
        Fixture().use { f ->
            repeat(6) { assertTrue(f.workspace.create()) }
            assertFalse(f.workspace.create())
            assertEquals(6, f.workspace.state.value.sessions.size)
            f.workspace.setSimple(false)
            assertTrue(f.workspace.create())
        }
    }

}
