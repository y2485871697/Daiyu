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

    fun testFreshFilesRootWorkerMkdirThenActualAndroidHostSucceedsWithoutPreviewIO() {
        val base = instrumentation.targetContext
        val freshFiles = Files.createTempDirectory(base.cacheDir.toPath(), "fresh-browser-files-").toFile()
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = freshFiles
        }
        val identity = BrowserRunIdentity(ConversationId("fresh-conversation"), RunId("fresh-run"),
            "fresh-profile", MessageId("fresh-message"))
        val grant = BrowserGrant(BrowserOwner("fresh-run", BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        var binding: CatalogBrowserBinding? = null
        try {
            val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
                grant, setOf("list_tabs"))
            val token = preparation.confirm(preparation.preview)
            assertFalse(java.io.File(freshFiles, "browser-runs").exists())
            // On instrumentation's worker, not main. This is the production helper parent must use.
            val root = app.daiyu.android.ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
            assertEquals(freshFiles.toPath().toRealPath().resolve("browser-runs"), root)
            val opened = main { CatalogBrowserBinding.open(context, root,
                AssistantProfile(identity.assistantId, "Fresh", ""), token) }
            binding = opened
            assertNull(opened.session.currentTab)
            val bound = opened.bindOwner(owner(identity))
            assertTrue(bound.taskAuthorization.allowedWrites.isEmpty())
            assertEquals("fresh-run", opened.sessionId)
            assertTrue(Files.isDirectory(root.resolve("downloads")))
            assertTrue(Files.isDirectory(root.resolve("cookie-offloads")))
            opened.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!opened.closeStatus().successful && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(opened.closeStatus().successful)
        } finally {
            binding?.close()
            Files.walk(freshFiles.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    fun testPrivateRootHelperRejectsMainThreadBeforeCreatingAnyDirectory() {
        val base = instrumentation.targetContext
        val files = Files.createTempDirectory(base.cacheDir.toPath(), "browser-no-main-io-").toFile()
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = files
        }
        try {
            main {
                try {
                    app.daiyu.android.ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
                    fail("main must not create root")
                } catch (expected: IllegalStateException) {
                    assertEquals("browser_worker_required", expected.message)
                }
            }
            assertFalse(java.io.File(files, "browser-runs").exists())
        } finally { Files.delete(files.toPath()) }
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
