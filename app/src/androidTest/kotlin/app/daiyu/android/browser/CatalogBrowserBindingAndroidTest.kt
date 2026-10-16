package app.daiyu.android.browser

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.openminis.AndroidBrowserRunHost
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real adapter/Android lifecycle wiring. Not a JVM substitute and not executed by this change. */
@Suppress("DEPRECATION")
class CatalogBrowserBindingAndroidTest : InstrumentationTestCase() {
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    fun testOriginalAndroidHostAndSessionSurviveBindingWithoutImplicitTab() {
        val identity = BrowserRunIdentity(ConversationId("binding-conversation"), RunId("binding-run"),
            "binding-profile", MessageId("binding-message"))
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
            grant, setOf("list_tabs"))
        val token = preparation.confirm(preparation.preview)
        val context = instrumentation.targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "binding-android-")
        val host = main {
            AndroidBrowserRunHost(context, grant, root,
                cookieEnvPath = { _, _ -> error("offload disabled") },
                cookieAuthorizeName = { _, _, _ -> false })
        }
        val binding = CatalogBrowserBinding.attach(host, AssistantProfile(identity.assistantId, "Test", ""), token)
        try {
            assertSame(host.session, binding.session)
            assertNull(host.currentTab)
            val bound = binding.bindOwner(owner(identity))
            assertTrue(bound.taskAuthorization.allowedWrites.isEmpty())
            assertSame(host.session, binding.session)
            assertNull(host.currentTab)
            assertFalse(binding.closeStatus().closeRequested)
        } finally {
            binding.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!binding.closeStatus().successful && System.nanoTime() < deadline) Thread.sleep(10)
            val status = binding.closeStatus()
            assertTrue("must observe the real successful close receipt: $status", status.successful)
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun owner(identity: BrowserRunIdentity): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(identity.assistantMessageId, identity.conversationId, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
}
