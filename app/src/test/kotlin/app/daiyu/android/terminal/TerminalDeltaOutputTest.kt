package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class TerminalDeltaOutputTest {
    private class Resource : TerminalOwnedResource {
        val chunks = ConcurrentLinkedQueue<Pair<ByteArray, Boolean>>()
        @Volatile var exited = false
        override fun read(caller: TaskAuthorization, offset: Int, limit: Int): JSONObject = error("snapshot must not be used")
        override fun readAvailable(caller: TaskAuthorization): JSONObject {
            val chunk = chunks.poll()
            return JSONObject().put("ok", true)
                .put("stdout_base64", Base64.getEncoder().encodeToString(chunk?.first ?: ByteArray(0)))
                .put("ui_dropped", chunk?.second ?: false).put("log_truncated", false)
                .put("state", if (exited) "exited" else "running")
                .put("wait_status", if (exited) 0 else JSONObject.NULL).put("error", JSONObject.NULL)
        }
        override fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray) =
            JSONObject().put("ok", true).put("execution", "executed")
        override fun close() { exited = true }
        override fun awaitTermination(timeoutMillis: Long) = exited
        fun add(text: String) { chunks.add(text.toByteArray(Charsets.UTF_8) to false) }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("delta output did not converge", condition())
    }
    @Test fun utf8AndOutputPastSnapshotLimitAreConsumedOnceAndFinalTailSurvivesExit() {
        val resource = Resource()
        val backend = object : TerminalEnvironmentBackend {
            override fun open(request: TerminalOpenRequest) = resource
            override fun linuxStatus() = TerminalLinuxState()
            override fun selectLinux(reference: LinuxGenerationRef?) = Unit
        }
        val host = TerminalEnvironmentHost(backend)
        val caller = TaskAuthorization("delta", "run")
        try {
            assertTrue(host.open(TerminalOpenRequest(PtyRunOwner(ConversationId("delta"), caller,
                TerminalEnvironmentHost.ANDROID_ENVIRONMENT, TerminalEnvironmentHost.ANDROID_GENERATION), "scope")))
            await { host.state.value.acceptsInput }
            val utf8 = "中".toByteArray(Charsets.UTF_8)
            resource.chunks.add(utf8.copyOfRange(0, 1) to false)
            resource.chunks.add(utf8.copyOfRange(1, utf8.size) to false)
            await { host.state.value.output == "中" }
            resource.add("a".repeat(262_144))
            await { host.state.value.outputStart == 1L }
            resource.add("tail")
            await { host.state.value.output.endsWith("tail") }
            assertEquals(5L, host.state.value.outputStart)
            assertEquals(262_144, host.state.value.output.length)
            resource.chunks.add(byteArrayOf(0xe4.toByte()) to false)
            await { resource.chunks.isEmpty() }
            resource.chunks.add("recovered".toByteArray() to true)
            await { host.state.value.output.endsWith("recovered") }
            assertFalse(host.state.value.output.contains('\ufffd'))
            resource.add("final")
            resource.chunks.add(byteArrayOf(0xe4.toByte(), 0xb8.toByte()) to false)
            resource.exited = true
            await { !host.state.value.ownsResources }
            assertTrue(host.state.value.output.endsWith("recoveredfinal\ufffd"))
            assertEquals("EXITED", host.state.value.phase)
        } finally { host.close() }
    }
}
