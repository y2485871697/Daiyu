package app.daiyu.android.integration.tools

import app.daiyu.android.CatalogFixturePort
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.rules.ExternalResource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Hold the issuing runner in next() while a test dispatches against its owner. */
class LiveRunnerOwners : ExternalResource() {
    private val release = CountDownLatch(1)
    private val workers = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<Message>>()

    fun issue(message: Message): RunExecutionOwner {
        val ready = CompletableFuture<RunExecutionOwner>()
        val done = CompletableFuture<Message>()
        workers += done
        lateinit var bound: RunExecutionOwner
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { bound = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
                error("fixture runner has no tools")
        }
        val runner = ChatRunner(CatalogFixturePort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                ready.complete(bound)
                release.await()
            }
            override fun cancel() { release.countDown() }
        } }, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("fixture runner has no tools") }, RunnerOptions(receiptBatchExecutor = receiver)) {}
        thread(isDaemon = true, name = "live-test-owner") {
            try {
                val result = runner.run(message, emptyList())
                check(result.status == RunStatus.COMPLETED) { "fixture runner ended: ${result.status}" }
                done.complete(result)
            } catch (failure: Throwable) {
                ready.completeExceptionally(failure)
                done.completeExceptionally(failure)
            }
        }
        return ready.get(10, TimeUnit.SECONDS)
    }

    override fun after() {
        release.countDown()
        workers.forEach { it.get(10, TimeUnit.SECONDS) }
    }
}
