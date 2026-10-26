package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owner contracts use the real host worker, but never Android/native processes or files. */
class TerminalUiOwnerTest {
    private class Resource : TerminalOwnedResource {
        @Volatile var exited = false
        @Volatile var throwWrite = false
        val closes = AtomicInteger()
        val writers = CopyOnWriteArrayList<TaskAuthorization>()
        override fun read(caller: TaskAuthorization, offset: Int, limit: Int): JSONObject = JSONObject()
            .put("ok", true).put("stdout_base64", "").put("next_offset", 0)
            .put("has_more", false).put("log_truncated", false)
            .put("state", "running").put("wait_status", JSONObject.NULL).put("error", JSONObject.NULL)
        override fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject {
            writers += caller
            if (throwWrite) error("delivery unknown")
            return JSONObject().put("ok", true).put("execution", "executed")
        }
        override fun close() { closes.incrementAndGet() }
        override fun awaitTermination(timeoutMillis: Long): Boolean = exited
    }

    private class Backend : TerminalEnvironmentBackend {
        val requests = CopyOnWriteArrayList<TerminalOpenRequest>()
        val resources = CopyOnWriteArrayList<Resource>()
        val inspections = AtomicInteger()
        val selections = AtomicInteger()
        var linux = TerminalLinuxState()
        var blockOpen: CountDownLatch? = null
        var failOpen = false
        override fun open(request: TerminalOpenRequest): TerminalOwnedResource {
            requests += request
            check(blockOpen?.await(5, TimeUnit.SECONDS) != false)
            if (failOpen) error("not launched")
            return Resource().also { resources += it }
        }
        override fun linuxStatus(): TerminalLinuxState {
            inspections.incrementAndGet()
            return linux
        }
        override fun selectLinux(reference: LinuxGenerationRef?) { selections.incrementAndGet() }
    }

    private class Fixture(trusted: () -> Set<LinuxGenerationRef> = { emptySet() }) {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        val ids = AtomicInteger()
        val owner = TerminalUiOwner(host, trusted) { "id-${ids.incrementAndGet()}" }
        fun dispose() {
            backend.blockOpen?.countDown()
            owner.close()
            await {
                backend.resources.forEach { it.exited = true }
                !host.state.value.ownsResources
            }
        }
    }

    companion object {
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("owner/host state did not converge", condition())
        }
    }

    @Test fun workspaceIsFrozenPerAndroidRequestWithoutChangingBackendOrSelection() {
        val first = Fixture()
        val second = Fixture()
        val a = TerminalWorkspaceDirectory(java.io.File("/approved/rootfs/a").absoluteFile,
            LinuxWorkspaceDirectory(java.io.File("/approved/rootfs/a").absoluteFile, "/a"))
        val b = TerminalWorkspaceDirectory(java.io.File("/private/workspace").absoluteFile)
        try {
            assertTrue(first.owner.openAndroid(ConversationId("conversation-a"), a))
            assertTrue(second.owner.openAndroid(ConversationId("conversation-b"), b))
            await { first.backend.requests.size == 1 && second.backend.requests.size == 1 }
            assertSame(a, first.backend.requests.single().workspace)
            assertSame(b, second.backend.requests.single().workspace)
            assertNull(first.backend.requests.single().linux)
            assertEquals(0, first.backend.selections.get())
            assertEquals(0, second.backend.selections.get())
            assertEquals(java.io.File("/approved/rootfs/a").absoluteFile, first.backend.requests.single().workspace!!.hostDirectory)
        } finally { first.dispose(); second.dispose() }
    }

    @Test fun constructionAndPageReobservationHaveStableIdleCallerAndNoIo() {
        val f = Fixture()
        try {
            val idle = f.owner.caller.value
            repeat(3) {
                // Re-entering UI observes the same owner, not a new host or a new authorization.
                val pageOwner = f.owner
                assertSame(f.host, pageOwner.host)
                assertSame(idle, runBlocking { pageOwner.caller.first() })
            }
            assertEquals(f.owner.uiSessionId, idle.sessionId)
            assertTrue(idle.allowedWrites.isEmpty())
            assertEquals(1, f.ids.get())
            assertTrue(f.backend.requests.isEmpty())
            assertEquals(0, f.backend.inspections.get())
            assertEquals(0, f.backend.selections.get())
            assertEquals("IDLE", f.host.state.value.phase)
        } finally { f.dispose() }
    }

    @Test fun acceptedAndroidRunBindsConversationConstantsAndExactPublishedAuthorization() {
        val f = Fixture()
        try {
            val idle = f.owner.caller.value
            val conversation = ConversationId("persisted-conversation")
            assertTrue(f.owner.openAndroid(conversation))
            val caller = f.owner.caller.value
            assertNotSame(idle, caller)
            await { f.host.state.value.acceptsInput }
            val request = f.backend.requests.single()
            assertSame(caller, request.owner.authorization)
            assertEquals(conversation, request.owner.conversationId)
            assertEquals(TerminalEnvironmentHost.ANDROID_ENVIRONMENT, request.owner.environmentId)
            assertEquals(TerminalEnvironmentHost.ANDROID_GENERATION, request.owner.generation)
            assertNull(request.linux)
            assertTrue(request.sessionScope.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            assertTrue(caller.allowedWrites.isEmpty())
            val copied = TaskAuthorization(caller.sessionId, caller.taskId)
            assertNull(f.host.prepareInput(copied, "must not send"))
            assertFalse(f.host.requestClose(copied))
            assertTrue(f.host.send(caller, "explicit UI input\n").get(2, TimeUnit.SECONDS).ok)
            assertSame(caller, f.backend.resources.single().writers.single())
        } finally { f.dispose() }
    }

    @Test fun blankChatHasIndependentUiBindingAndEachExplicitNewRunHasFreshScopeAndGrant() {
        val f = Fixture()
        try {
            assertTrue(f.owner.openAndroid(null))
            await { f.host.state.value.acceptsInput }
            val first = f.backend.requests.single()
            val caller = f.owner.caller.value
            assertEquals(ConversationId(f.owner.uiSessionId), first.owner.conversationId)
            assertEquals(f.owner.uiSessionId, caller.sessionId)
            assertTrue(f.host.requestClose(caller))
            f.backend.resources.single().exited = true
            await { f.host.state.value.canOpen }
            assertSame(caller, f.owner.caller.value) // Termination itself does not manufacture a grant.
            assertEquals(1, f.backend.requests.size)
            assertTrue(f.owner.openAndroid(null))
            await { f.backend.requests.size == 2 && f.host.state.value.acceptsInput }
            val second = f.backend.requests.last()
            assertEquals(first.owner.conversationId, second.owner.conversationId)
            assertNotEquals(first.sessionScope, second.sessionScope)
            assertNotEquals(caller.taskId, f.owner.caller.value.taskId)
            assertNotSame(caller, f.owner.caller.value)
            assertSame(second.owner.authorization, f.owner.caller.value)
        } finally { f.dispose() }
    }

    @Test fun openingAndCleanupRefusalsKeepCurrentCallerAndNeverReplay() {
        val f = Fixture()
        f.backend.blockOpen = CountDownLatch(1)
        try {
            assertTrue(f.owner.openAndroid(ConversationId("first")))
            await { f.backend.requests.size == 1 }
            val accepted = f.owner.caller.value
            assertEquals("OPENING", f.host.state.value.phase)
            assertFalse(f.owner.openAndroid(ConversationId("second")))
            assertSame(accepted, f.owner.caller.value)
            assertTrue(f.host.requestClose(accepted))
            assertFalse(f.owner.openAndroid(null))
            f.backend.blockOpen!!.countDown()
            await { f.backend.resources.firstOrNull()?.closes?.get()?.let { it > 0 } == true }
            repeat(3) { assertFalse(f.owner.openAndroid(null)) }
            assertSame(accepted, f.owner.caller.value)
            assertEquals(1, f.backend.requests.size)
            assertEquals(2, f.ids.get()) // Refusals did not even allocate replacement run IDs.
            assertTrue(f.host.state.value.ownsResources)
        } finally { f.dispose() }
    }

    @Test fun unknownInputNeverTriggersResendOrReplacementRun() {
        val f = Fixture()
        try {
            assertTrue(f.owner.openAndroid(null))
            await { f.host.state.value.acceptsInput }
            val caller = f.owner.caller.value
            val resource = f.backend.resources.single().apply { throwWrite = true }
            val result = f.host.send(caller, "possibly delivered\n").get(2, TimeUnit.SECONDS)
            assertEquals("unknown", result.execution)
            await { resource.closes.get() > 0 }
            repeat(3) { assertFalse(f.owner.openAndroid(null)) }
            assertSame(caller, f.owner.caller.value)
            assertEquals(1, resource.writers.size)
            assertEquals(1, f.backend.requests.size)
            assertTrue(f.host.state.value.ownsResources)
            assertFalse(f.host.state.value.acceptsInput)
            resource.exited = true
            await { f.host.state.value.canOpen }
            assertSame(caller, f.owner.caller.value)
            assertEquals(1, f.backend.requests.size) // Even after join, a new click is required.
        } finally { f.dispose() }
    }

    @Test fun linuxRequiresCurrentParentGateEvenWithCachedFilesReadySelection() {
        val ref = LinuxGenerationRef("trusted-linux", "generation-1")
        var trusted = emptySet<LinuxGenerationRef>()
        val f = Fixture { trusted }
        f.backend.linux = TerminalLinuxState("FILES_READY", ref, "Linux",
            listOf(TerminalLinuxChoice(ref, "Linux")))
        try {
            f.host.refreshLinux()
            await { f.host.state.value.linux.selected == ref }
            val idle = f.owner.caller.value
            assertFalse(f.owner.openLinux(ref, null))
            assertSame(idle, f.owner.caller.value)
            assertTrue(f.backend.requests.isEmpty())
            trusted = setOf(ref)
            assertTrue(f.owner.openLinux(ref, ConversationId("linux-chat")))
            await { f.host.state.value.acceptsInput }
            val request = f.backend.requests.single()
            val caller = f.owner.caller.value
            assertEquals(ref, request.linux)
            assertEquals(ref.environmentId, request.owner.environmentId)
            assertEquals(ref.generation, request.owner.generation)
            assertEquals(ConversationId("linux-chat"), request.owner.conversationId)
            assertSame(caller, request.owner.authorization)
            assertTrue(caller.allowedWrites.isEmpty())
            assertTrue(f.host.requestClose(caller))
            f.backend.resources.single().exited = true
            await { f.host.state.value.canOpen }
            trusted = emptySet() // No refresh: cached FILES_READY is deliberately still present.
            assertEquals(ref, f.host.state.value.linux.selected)
            assertFalse(f.owner.openLinux(ref, null))
            assertSame(caller, f.owner.caller.value)
            assertEquals(1, f.backend.requests.size)
            assertEquals(0, f.backend.selections.get()) // Owner never installs or selects implicitly.
        } finally { f.dispose() }
    }

    @Test fun defaultGateDeniesLinuxAndDisposedHostDoesNotReplaceIdleIdentity() {
        val f = Fixture()
        try {
            val idle = f.owner.caller.value
            assertFalse(f.owner.openLinux(LinuxGenerationRef("linux", "v1"), null))
            f.owner.close()
            assertFalse(f.owner.openAndroid(null))
            assertSame(idle, f.owner.caller.value)
            assertTrue(f.backend.requests.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun hostAdmissionRefusalAfterStatePrecheckDoesNotPublishSpeculativeCaller() {
        val f = Fixture()
        val external = TaskAuthorization("external-session", "external-run")
        val competingRequest = TerminalOpenRequest(PtyRunOwner(ConversationId("external-chat"),
            external, TerminalEnvironmentHost.ANDROID_ENVIRONMENT,
            TerminalEnvironmentHost.ANDROID_GENERATION), "external-scope")
        val ids = AtomicInteger()
        // Deliberately inject an external admission between canOpen and host.open. Normal UI
        // must not bypass the owner, but even a raced host refusal must preserve its caller.
        val owner = TerminalUiOwner(f.host, newId = {
            val next = ids.incrementAndGet()
            if (next == 2) assertTrue(f.host.open(competingRequest))
            "candidate-$next"
        })
        try {
            val idle = owner.caller.value
            assertFalse(owner.openAndroid(null))
            assertSame(idle, owner.caller.value)
            await { f.host.state.value.acceptsInput }
            assertSame(external, f.backend.requests.single().owner.authorization)
        } finally { f.dispose() }
    }

    @Test fun asynchronousStartupFailureIsStillAnAcceptedRunNotAnAutomaticRetry() {
        val f = Fixture()
        f.backend.failOpen = true
        try {
            val idle = f.owner.caller.value
            assertTrue(f.owner.openAndroid(null))
            val accepted = f.owner.caller.value
            assertNotSame(idle, accepted)
            await { f.host.state.value.phase == "FAILED" }
            assertSame(accepted, f.owner.caller.value)
            assertSame(accepted, f.backend.requests.single().owner.authorization)
            assertEquals(1, f.backend.requests.size)
            assertTrue(f.host.state.value.canOpen)
        } finally { f.dispose() }
    }

    @Test fun realOwnerTeardownIsIdempotentAndKeepsHostResponsibleUntilJoin() {
        val f = Fixture()
        try {
            assertTrue(f.owner.openAndroid(null))
            await { f.host.state.value.acceptsInput }
            val caller = f.owner.caller.value
            f.owner.close()
            f.owner.close()
            await { f.backend.resources.single().closes.get() > 0 }
            assertSame(f.host, f.owner.host)
            assertSame(caller, f.owner.caller.value)
            assertTrue(f.host.state.value.disposed)
            assertTrue(f.host.state.value.ownsResources)
            assertFalse(f.owner.openAndroid(null))
            f.backend.resources.single().exited = true
            await { !f.host.state.value.ownsResources }
            assertTrue(f.host.state.value.terminationConfirmed)
        } finally { f.dispose() }
    }
}
