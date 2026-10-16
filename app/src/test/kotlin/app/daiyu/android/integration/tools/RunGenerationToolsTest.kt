package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.media.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real dispatcher/context/clients/assets; only transport, codecs and persistent-attempt seam
 * are substituted. SQLite registration is covered by the device adapter test, not this fake.
 */
class RunGenerationToolsTest {
    private fun owner(conversation: String = "conversation", run: String = "run",
        assistant: String = "assistant"): RunExecutionOwner {
        var token: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { token = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(
            Message(MessageId(assistant), ConversationId(conversation), RunId(run),
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(token)
    }

    private class Attempts : GenerationAttemptStore {
        val rows = linkedMapOf<GenerationIdentity, Pair<GenerationAttempt, Submission?>>()
        override fun reserve(attempt: GenerationAttempt): GenerationReservation {
            val old = rows[attempt.identity]
            if (old != null) return GenerationReservation.Existing(old.first.fingerprint, old.second)
            rows[attempt.identity] = attempt to null
            return GenerationReservation.Started
        }
        override fun finish(attempt: GenerationAttempt, submission: Submission) { rows[attempt.identity] = attempt to submission }
    }

    private inner class Fixture(
        val config: GenerationRunConfig?,
        allowed: Boolean = true,
        val http: GenerationHttp = GenerationHttp { error("unexpected HTTP") },
        review: (GenerationRunReview) -> GenerationRunDecision = { GenerationRunDecision.APPROVED },
        val token: RunExecutionOwner = owner(),
        val accountScope: String = "account",
        ledger: ToolLedger = ToolLedger(InMemoryToolLedgerStore()),
        validate: (GenerationAssetOwner) -> Boolean = { true },
    ) : AutoCloseable {
        val attempts = Attempts()
        val root = Files.createTempDirectory("generation-adapter").toFile()
        var binding: GenerationAttemptBinding? = null
        var assets: MessageGenerationAssets? = null
        var reviews = 0
        val task = TaskAuthorization("session", token.runId.value, if (allowed && config != null)
            config.batches.map { RunGenerationTools.writeEffect(accountScope, token, config, it) }.toSet() else emptySet())
        val leaf = RunGenerationTools(config, token, task, accountScope, { error("no SQLite in this fixture") },
            { fence ->
                binding = fence.owner.binding
                MessageGenerationAssets(root, fence, ImageInspector { ImageDimensions("image/png", 2, 2) },
                    VideoInspector { _, _, _ -> VideoMetadata("video/mp4", 1000, 2, 2) },
                    ImageAttachmentLimits(), VideoAttachmentLimits()).also { assets = it }
            },
            { it.accountScope == accountScope && it.binding.conversationId == token.conversationId.value &&
                it.binding.runId == token.runId.value && it.binding.assistantMessageId == token.assistantMessageId.value && validate(it) },
            GenerationRunApproval { reviews++; review(it) }, GenerationDownloadAuthorization { _, _ -> false },
            http, { attempts })
        val dispatcher = ToolDispatcher(ToolRegistry().apply { leaf.registeredTools.forEach { register(it) } }.snapshot(),
            SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY)), ledger)
        fun dispatch(id: String = "original-call", otherOwner: RunExecutionOwner = token): DispatchOutcome =
            dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId(id),
                leaf.registeredTools.single().definition.name, """{"batch_id":"batch"}""")), otherOwner).single().outcome
        override fun close() { leaf.close(); root.deleteRecursively() }
    }

    private fun config(video: Boolean = false, invalidLast: Boolean = false, polls: Int = 2): GenerationRunConfig =
        GenerationRunConfig(listOf(GenerationRunConfig.Batch("batch", if (video)
            GenerationProtocol.OPENAI_VIDEO_MULTIPART else GenerationProtocol.OPENAI_IMAGES_JSON,
            if (video) "https://api.example/v1/videos" else "https://api.example/v1/images/generations",
            "secret", "model", if (invalidLast) listOf(GenerationRunConfig.Item("first", "ok"),
                GenerationRunConfig.Item("last", "bad", parameters = mapOf("n" to "2")))
            else listOf(GenerationRunConfig.Item("first", "draw")))), polls)

    @Test fun paidEffectUsesPersistentAccountOwnerAndConfigScopeNotRuntimeIdentity() {
        val frozen = config()
        val token = owner()
        fun effect(account: String = "account", realOwner: RunExecutionOwner = token,
            settings: GenerationRunConfig = frozen) =
            RunGenerationTools.writeEffect(account, realOwner, settings, settings.batches.single())
        val original = effect()
        // Independently captured Runner owners and restored config have the same persisted scope.
        assertNotSame(token, owner())
        assertEquals(original, effect(realOwner = owner(), settings = config()))
        assertNotEquals(original, effect(account = "other-account"))
        assertNotEquals(original, effect(realOwner = owner(conversation = "other-conversation")))
        assertNotEquals(original, effect(realOwner = owner(run = "other-run")))
        assertNotEquals(original, effect(realOwner = owner(assistant = "other-assistant")))
        assertNotEquals(original, effect(settings = config(polls = 0)))
        val otherBatch = GenerationRunConfig.Batch("other", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://api.example/v1/images/generations", "secret", "model",
            listOf(GenerationRunConfig.Item("first", "draw")))
        val expanded = GenerationRunConfig(frozen.batches + otherBatch, frozen.maxPolls)
        assertNotEquals(original, RunGenerationTools.writeEffect("account", token, expanded, expanded.batches.first()))
        try {
            RunGenerationTools.writeEffect("account", token, frozen, config().batches.single()); fail()
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sharedCentralLedgerUnknownIsIsolatedBetweenRealOwnersWithExactAuthorization() {
        val ledger = ToolLedger(InMemoryToolLedgerStore())
        val frozen = config(video = true, polls = 0)
        var postsA = 0
        var postsB = 0
        Fixture(frozen, http = GenerationHttp { postsA++; error("lost A response") }, ledger = ledger).use { a ->
            Fixture(frozen, http = GenerationHttp { postsB++; error("lost B response") }, ledger = ledger,
                token = owner(assistant = "assistant-b")).use { b ->
                assertNotSame(a.token, b.token)
                assertNotEquals(a.task.allowedWrites, b.task.allowedWrites)
                assertTrue(a.dispatch("a-call") is DispatchOutcome.Unknown)
                // A's UNKNOWN must not reserve B's identically configured paid generation.
                assertTrue(b.dispatch("b-call") is DispatchOutcome.Unknown)
                assertEquals(1, postsA); assertEquals(1, postsB)
                assertEquals(1, a.reviews); assertEquals(1, b.reviews)
                assertEquals("assistant-b", b.binding!!.assistantMessageId)
                assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), a.dispatch("a-new-call"))
                assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), b.dispatch("b-new-call"))
                assertEquals(1, postsA); assertEquals(1, postsB)
                val tool = b.leaf.registeredTools.single()
                val copy = TaskAuthorization(b.task.sessionId, b.task.taskId, b.task.allowedWrites)
                val denied = b.dispatcher.dispatchOwned(copy, listOf(ToolInvocation(ToolCallId("b-copy"),
                    tool.definition.name, """{"batch_id":"batch"}""")), b.token).single().outcome
                assertEquals(DispatchOutcome.NotExecuted("generation_authorization_mismatch"), denied)
                assertEquals(1, postsB)
            }
        }
    }

    @Test fun unconfiguredDoesNotAdvertiseAndNoWritePermissionMakesZeroPosts() {
        Fixture(null).use { assertTrue(it.leaf.registeredTools.isEmpty()) }
        Fixture(config(), allowed = false).use {
            assertTrue(it.dispatch() is DispatchOutcome.NotExecuted)
            assertEquals(0, it.reviews); assertTrue(it.attempts.rows.isEmpty())
        }
    }

    @Test fun wholeBatchPreflightPrecedesApprovalAndPost() {
        Fixture(config(invalidLast = true)).use {
            assertTrue(it.dispatch() is DispatchOutcome.NotExecuted)
            assertEquals(0, it.reviews); assertTrue(it.attempts.rows.isEmpty())
        }
    }

    @Test fun denialAndForeignContextNeverPost() {
        Fixture(config(), review = { GenerationRunDecision.DENIED }).use {
            assertTrue(it.dispatch() is DispatchOutcome.NotExecuted)
            assertEquals(1, it.reviews); assertTrue(it.attempts.rows.isEmpty())
        }
        Fixture(config()).use {
            assertTrue(it.dispatch(otherOwner = owner()) is DispatchOutcome.NotExecuted)
            assertEquals(0, it.reviews)
            val tool = it.leaf.registeredTools.single()
            assertEquals(ToolEffect.WRITE, tool.definition.effect)
            val copiedAuthorization = TaskAuthorization("session", "run", it.task.allowedWrites)
            val swapped = it.dispatcher.dispatchOwned(copiedAuthorization, listOf(ToolInvocation(ToolCallId("swapped"),
                tool.definition.name, """{"batch_id":"batch"}""")), it.token).single().outcome
            assertTrue(swapped is DispatchOutcome.NotExecuted)
            try { tool.prepare(tool.definition.schema.parse("""{"batch_id":"batch"}""")).execute(); fail() }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun realContextBindsStableIdentityAndPendingUsesOnlyBoundedSameHandleGets() {
        val calls = mutableListOf<GenerationHttpRequest>()
        val frozen = config(video = true)
        Fixture(frozen, http = GenerationHttp { request ->
            calls.add(request)
            GenerationHttpResponse(200, """{"id":"task_1","status":"queued"}""".toByteArray())
        }, review = { review ->
            assertSame(frozen, review.config)
            assertEquals("original-call", review.context.invocation.id.value)
            assertEquals("""{"batch_id":"batch"}""", review.context.invocation.arguments)
            GenerationRunDecision.APPROVED
        }).use { f ->
            assertTrue(f.dispatch() is DispatchOutcome.Unknown)
            val expected = GenerationAttemptBinding("conversation", "assistant", "run", "original-call", RunGenerationTools.VIDEO_TOOL)
            assertEquals(expected.identity("first"), f.attempts.rows.keys.single())
            assertEquals(expected.toolName, f.binding!!.toolName)
            assertEquals("Bearer secret", calls.first().headers["Authorization"])
            assertTrue(calls.first().body!!.toString(Charsets.UTF_8).contains("draw"))
            assertEquals(listOf("POST", "GET", "GET"), calls.map { it.method })
            assertTrue(calls.drop(1).all { it.url == "https://api.example/v1/videos/task_1" })
            assertTrue(f.leaf.outcomes().single().items.single().submission is Submission.Pending)
            f.dispatch("invented-retry-id")
            assertEquals(3, calls.size) // central semantic ledger forbids a new-ID retry
        }
    }

    @Test fun unknownPostNeverPollsOrPostsAgain() {
        var posts = 0
        Fixture(config(video = true), http = GenerationHttp { posts++; error("lost response") }).use {
            assertTrue(it.dispatch() is DispatchOutcome.Unknown)
            assertTrue(it.leaf.outcomes().single().items.single().submission is Submission.Unknown)
            it.dispatch(); it.dispatch("new-call")
            assertEquals(1, posts)
        }
    }

    @Test fun deniedDownloadKeepsCompletedOutputsWithoutPublishingAnAssetReference() {
        var requests = 0
        Fixture(config(), http = GenerationHttp {
            requests++
            GenerationHttpResponse(200, """{"data":[{"url":"https://cdn.example/image.png"},{"bad":true}]}""".toByteArray())
        }).use { f ->
            assertTrue(f.dispatch() is DispatchOutcome.Unknown)
            val item = f.leaf.outcomes().single().items.single()
            assertTrue(item.submission is Submission.Completed)
            assertEquals(2, (item.submission as Submission.Completed).outputs.size)
            assertEquals(GenerationFailure.DOWNLOAD_DENIED, item.failures[0])
            assertEquals(GenerationFailure.PROTOCOL, item.failures[1])
            assertTrue(item.references.isEmpty()); assertEquals(1, requests)
        }
    }

    @Test fun admissionRevocationAloneIsNotDrainAndDoesNotSkipLaterCleanup() {
        Fixture(config()).use { f ->
            f.leaf.revokeAdmission(); f.leaf.revokeAdmission()
            assertEquals(DispatchOutcome.NotExecuted("generation_closed"), f.dispatch())
            assertEquals(0, f.reviews)
            assertEquals(GenerationToolsCloseResult.Unknown("generation_cleanup_not_started"), f.leaf.awaitClose(0))
            f.leaf.close(); f.leaf.close(); f.leaf.revokeAdmission()
            assertSame(GenerationToolsCloseResult.Drained, f.leaf.awaitClose(0))
        }
    }

    @Test fun admissionRevocationNeverWaitsForPublicationValidatorOrDisconnectsHttp() {
        val httpEntered = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val validatorEntered = CountDownLatch(1)
        val releaseValidator = CountDownLatch(1)
        val admissionReturned = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val disconnects = java.util.concurrent.atomic.AtomicInteger()
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val transport = object : GenerationHttp {
            override fun execute(request: GenerationHttpRequest): GenerationHttpResponse = error("wrong overload")
            override fun execute(request: GenerationHttpRequest, cancellation: GenerationCancellation): GenerationHttpResponse {
                val execution = cancellation.begin()
                execution.register(object : HttpURLConnection(URL(request.url)) {
                    override fun connect() {}
                    override fun usingProxy() = false
                    override fun disconnect() { disconnects.incrementAndGet() }
                })
                try {
                    httpEntered.countDown(); check(releaseHttp.await(10, TimeUnit.SECONDS))
                    return GenerationHttpResponse(200, """{"data":[{"b64_json":"AQ=="}]}""".toByteArray())
                } finally { execution.finish() }
            }
        }
        Fixture(config(), http = transport, validate = {
            if (Thread.currentThread().name == "blocked-generation-validator") {
                validatorEntered.countDown(); check(releaseValidator.await(10, TimeUnit.SECONDS))
            }
            true
        }).use { f ->
            val worker = Thread { f.dispatch() }.apply { start() }
            var publisher: Thread? = null
            var revoker: Thread? = null
            var closer: Thread? = null
            try {
                assertTrue(httpEntered.await(5, TimeUnit.SECONDS))
                publisher = Thread({
                    try {
                        f.assets!!.commit(f.binding!!.identity("first"), 0, GenerationKind.IMAGE,
                            byteArrayOf(1), GenerationMediaInfo("image/png", 2, 2))
                    } catch (failure: GenerationAssetException) {
                        if (failure.failure != GenerationAssetFailure.CANCELLED) errors.set(failure)
                    } catch (failure: Throwable) { errors.set(failure) }
                }, "blocked-generation-validator").apply { start() }
                assertTrue(validatorEntered.await(5, TimeUnit.SECONDS))
                revoker = Thread { f.leaf.revokeAdmission(); admissionReturned.countDown() }.apply { start() }
                assertTrue("admission barrier must not acquire the publication fence", admissionReturned.await(1, TimeUnit.SECONDS))
                assertEquals(0, disconnects.get())
                assertEquals(DispatchOutcome.NotExecuted("generation_closed"), f.dispatch("closed-call"))
                assertTrue(f.leaf.awaitClose(0) is GenerationToolsCloseResult.Unknown)
                closer = Thread { f.leaf.close(); closeReturned.countDown() }.apply { start() }
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
                assertTrue(f.leaf.awaitClose(0) is GenerationToolsCloseResult.Unknown)
                releaseValidator.countDown()
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
                assertEquals(1, disconnects.get())
                assertTrue("HTTP execution has not physically exited", f.leaf.awaitClose(0) is GenerationToolsCloseResult.Unknown)
            } finally {
                releaseValidator.countDown(); releaseHttp.countDown()
                publisher?.join(5000); revoker?.join(5000); closer?.join(5000); worker.join(5000)
            }
            assertNull(errors.get())
            assertFalse(worker.isAlive)
            assertSame(GenerationToolsCloseResult.Drained, f.leaf.awaitClose(1000))
        }
    }

    @Test fun closeRevokesBeforeDisconnectAndWaitsForPhysicalWorkerExit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fixture: Fixture? = null
        var revokedAtDisconnect = false
        val transport = object : GenerationHttp {
            override fun execute(request: GenerationHttpRequest): GenerationHttpResponse = error("wrong overload")
            override fun execute(request: GenerationHttpRequest, cancellation: GenerationCancellation): GenerationHttpResponse {
                val execution = cancellation.begin()
                val connection = object : HttpURLConnection(URL(request.url)) {
                    override fun connect() {}
                    override fun usingProxy() = false
                    override fun disconnect() {
                        val f = checkNotNull(fixture)
                        try {
                            f.assets!!.commit(f.binding!!.identity("first"), 0, GenerationKind.IMAGE,
                                byteArrayOf(1), GenerationMediaInfo("image/png", 2, 2))
                        } catch (failure: GenerationAssetException) {
                            revokedAtDisconnect = failure.failure == GenerationAssetFailure.CANCELLED
                        }
                    }
                }
                execution.register(connection)
                try {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return GenerationHttpResponse(200, """{"data":[{"b64_json":"AQ=="}]}""".toByteArray())
                } finally { execution.finish() }
            }
        }
        Fixture(config(), http = transport).use { f ->
            fixture = f
            val worker = Thread { f.dispatch() }.apply { start() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.leaf.close()
                assertTrue(revokedAtDisconnect)
                assertTrue(f.leaf.awaitClose(0) is GenerationToolsCloseResult.Unknown)
            } finally { release.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive)
            assertSame(GenerationToolsCloseResult.Drained, f.leaf.awaitClose(1000))
        }
    }
}
