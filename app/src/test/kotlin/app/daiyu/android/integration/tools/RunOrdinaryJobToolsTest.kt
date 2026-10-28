package app.daiyu.android.integration.tools

import android.content.ContextWrapper
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.TerminalConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RunOrdinaryJobToolsTest {
    private val owner = runnerOwner(Message(MessageId("m"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
    private val auth = TaskAuthorization("s", "r")
    private val capabilities = SessionCapabilities("s", setOf(TerminalJobSession.CAPABILITY))
    private var launches = 0
    private var pins = 0
    private var retained = 0
    private fun scope(configurations: Map<String, TerminalConfiguration>) = RunOrdinaryJobTools(ContextWrapper(null),
        owner, auth, capabilities, configurations, launch = { launches++; error("prepare launched backend") },
        pinWorkspace = { pins++; error("prepare pinned workspace") }, retainJob = { retained++; error("prepare retained job") })
    private fun arguments(environment: String, mode: String) = JSONObject().put("command", "id -u")
        .put("environment", environment).put("mode", mode).toString()
    @Test fun shizukuAsyncAndDaemonAreExplicitlyRefusedBeforeLedgerOrSideEffects() {
        for (mode in listOf("async", "daemon")) {
            val scope = scope(mapOf("android" to TerminalConfiguration(backend = "shizuku", workingDirectory = File("/fixed"))))
            val registry = ToolRegistry().also { r -> scope.tools.forEach { r.register(it) } }
            val dispatcher = ToolDispatcher(registry.snapshot(), capabilities, ToolLedger(InMemoryToolLedgerStore()))
            val result = dispatcher.dispatchOwned(auth, listOf(ToolInvocation(ToolCallId(mode), TerminalJobSession.START,
                arguments("android", mode))), owner).single().outcome as DispatchOutcome.NotExecuted
            assertEquals("android_shizuku_jobs_not_supported", result.code)
            assertEquals(0, launches); assertEquals(0, pins); assertEquals(0, retained)
        }
    }
    @Test fun explicitLinuxAndNormalRootAndroidAsyncStillPrepareWithoutOpeningBackends() {
        for (configuration in listOf(TerminalConfiguration(workingDirectory = File("/fixed")),
            TerminalConfiguration(root = true, workingDirectory = File("/fixed")),
            TerminalConfiguration(backend = "linux", workingDirectory = File("/fixed")))) {
            val environment = if (configuration.backend == "linux") "linux" else "android"
            val scope = scope(mapOf("android" to TerminalConfiguration(backend = "shizuku", workingDirectory = File("/fixed")),
                environment to configuration))
            val tool = scope.tools.single { it.definition.name == TerminalJobSession.START }
            assertNotNull(tool.prepare(tool.definition.schema.parse(arguments(environment, "async"))).writeEffect)
            assertEquals(0, launches); assertEquals(0, pins); assertEquals(0, retained)
        }
    }
}
