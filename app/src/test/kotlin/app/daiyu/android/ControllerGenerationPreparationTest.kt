package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.tools.ToolDefinition
import app.daiyu.android.integration.tools.*
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetPublicationGate
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import app.daiyu.android.ui.GenerationApprovalForm
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference

/** Real Runner-issued owners, production effect encoder and dispatcher. No HTTP, files or DB. */
class ControllerGenerationPreparationTest {
    private val identity = GenerationPreparationIdentity("account", ConversationId("conversation"),
        RunId("run"), MessageId("reply"))
    private val editor = GenerationEditorSnapshot(7, " exact editor\n", listOf("immutable-attachment"))
    private val hosts = GenerationDownloadHosts(setOf("cdn.example"))
    private fun profile(protocol: GenerationProtocol = GenerationProtocol.OPENAI_IMAGES_JSON) =
        GenerationModelProfile("generation-only", "Explicit model", protocol, "https://api.example/v1/" + when (protocol) {
            GenerationProtocol.OPENAI_IMAGES_JSON -> "images/generations"
            GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "images/edits"
            GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "videos"
        }, "never-echo-this-key", "chosen-model")
    private fun prepare(parameters: Map<String, String> = emptyMap(), maxPolls: Int = 3) =
        ControllerGenerationPreparation.prepare(identity, editor, listOf(profile()), listOf(
            GenerationBatchSelection("batch", "generation-only", listOf(
                GenerationRunConfig.Item("item", " original prompt\n", parameters = parameters)))), maxPolls, hosts)
    private fun confirmed() = prepare().let { it.confirm(it.preview, editor, identity) }

    private fun owner(id: GenerationPreparationIdentity = identity): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(
            Message(id.assistantMessageId, id.conversationId, id.runId, Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(result)
    }
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    private fun dispatchReview(token: ConfirmedGenerationPreparation, owner: RunExecutionOwner,
        task: TaskAuthorization, approval: GenerationRunApproval,
        cancelled: () -> Boolean = { false }): GenerationRunDecision? {
        var decision: GenerationRunDecision? = null
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition(RunGenerationTools.IMAGE_TOOL, 1, "test", "test",
                ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                val binding = GenerationAttemptBinding("conversation", "reply", "run",
                    context.invocation.id.value, context.invocation.name)
                val batch = token.preview.config.batches.single()
                val assets = object : GenerationAssets {
                    override fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo = error("unused")
                    override fun commit(identity: GenerationIdentity, outputIndex: Int, kind: GenerationKind,
                        bytes: ByteArray, info: GenerationMediaInfo): GenerationAssetReceipt = error("unused")
                }
                val plans = batch.requests(binding::identity).map { GenerationRequestPlan.prepare(it, assets) }
                decision = approval.review(GenerationRunReview(context, token.preview.config, batch, plans,
                    task.allowedWrites.single()))
                ExecutionReceipt.Confirmed("reviewed")
            }
        }
        ToolDispatcher(ToolRegistry().apply { register(tool) }.snapshot(), SessionCapabilities("session", setOf("test")),
            ToolLedger(InMemoryToolLedgerStore())).dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId("review-call"), tool.definition.name, "{}")), owner, cancelled)
        return decision
    }

    private fun dispatchLeaf(leaf: RunGenerationTools, owner: RunExecutionOwner, task: TaskAuthorization) {
        val tool = leaf.registeredTools.single()
        ToolDispatcher(ToolRegistry().apply { register(tool) }.snapshot(),
            SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY)),
            ToolLedger(InMemoryToolLedgerStore())).dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId("original-call"), tool.definition.name, "{\"batch_id\":\"batch\"}")), owner)
    }

    @Test fun explicitIndependentProfileRequiredNoModelUrlArgumentOrChatFallback() {
        rejects { ControllerGenerationPreparation.prepare(identity, editor, emptyList(), listOf(
            GenerationBatchSelection("batch", "chat-default", listOf(GenerationRunConfig.Item("item", "p")))), 1, hosts) }
        rejects { GenerationModelProfile("bad", "label", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://api.example/v1/videos", "key", "model") }
        rejects { GenerationModelProfile("bad", "label", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://user:secret@api.example/images/generations", "key", "model") }
        rejects { GenerationModelProfile("bad", "label", GenerationProtocol.OPENAI_IMAGES_JSON,
            "http://api.example/images/generations", "key", "model") }
        val p = prepare()
        assertEquals("chosen-model", p.preview.config.batches.single().model)
        assertEquals(" original prompt\n", p.preview.config.batches.single().items.single().prompt)
        assertEquals(3, p.preview.config.maxPolls)
        assertFalse(p.preview.toString().contains("never-echo"))
        assertFalse(profile().toString().contains("never-echo"))
        rejects { prepare(maxPolls = 33) }
    }

    @Test fun referencesMaskParametersAndPollsAreFrozenWithoutSilentSizeDowngrade() {
        val bytes = byteArrayOf(1, 2, 3)
        val input = GenerationInput(bytes, "image/png")
        val references = mutableListOf(input)
        val parameters = linkedMapOf("size" to " 1024x1024 ", "seed" to "0007")
        val items = mutableListOf(GenerationRunConfig.Item("item", "unchanged", references, input, parameters))
        val selections = mutableListOf(GenerationBatchSelection("edit", "generation-only", items))
        val p = ControllerGenerationPreparation.prepare(identity, editor,
            listOf(profile(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART)), selections, 0, hosts)
        bytes[0] = 99; input.bytes[1] = 98; references.clear(); parameters.clear(); items.clear(); selections.clear()
        val frozen = p.preview.config.batches.single().items.single()
        assertArrayEquals(byteArrayOf(1, 2, 3), frozen.references.single().bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), frozen.mask!!.bytes)
        assertEquals(" 1024x1024 ", frozen.parameters["size"])
        assertEquals("0007", frozen.parameters["seed"])
        assertEquals(0, p.preview.config.maxPolls)
        assertTrue(p.preview.blockers.isNotEmpty())
        rejects { p.confirm(p.preview, editor, identity) }
    }

    @Test fun formPreservesImageAndVideoOriginalParametersAndNeverChoosesProfile() {
        rejects { GenerationApprovalForm(prompt = "p").selection() }
        val form = GenerationApprovalForm("generation-only", " exact\nprompt ", " 640x480 ", "09", "2")
        val item = form.selection().items.single()
        assertEquals(form.prompt, item.prompt)
        assertEquals(mapOf("size" to " 640x480 ", "duration" to "09"), item.parameters)
        assertFalse(form.toString().contains(form.prompt))
        val video = ControllerGenerationPreparation.prepare(identity, editor,
            listOf(profile(GenerationProtocol.OPENAI_VIDEO_MULTIPART)), listOf(
                GenerationApprovalForm("generation-only", "video").selection()), 2, hosts)
        assertEquals(GenerationProtocol.OPENAI_VIDEO_MULTIPART, video.preview.config.batches.single().protocol)
        assertTrue(video.preview.blockers.isEmpty())
    }

    @Test fun confirmationIsSingleUseExactPreviewAndRevisionEvenAfterEditRevert() {
        val p = prepare()
        rejects { p.confirm(prepare().preview, editor, identity) }
        rejects { p.confirm(p.preview, editor, identity) }
        val edited = prepare()
        rejects { edited.confirm(edited.preview, GenerationEditorSnapshot(8, editor.text, editor.attachmentIds), identity) }
        val changedAttachments = prepare()
        rejects { changedAttachments.confirm(changedAttachments.preview, GenerationEditorSnapshot(7, editor.text), identity) }
        val changedAccount = prepare()
        rejects { changedAccount.confirm(changedAccount.preview, editor, identity.copy(accountScope = "other")) }
        val cancelled = prepare(); cancelled.cancel()
        rejects { cancelled.confirm(cancelled.preview, editor, identity) }
        val ok = prepare(); ok.confirm(ok.preview, editor, identity)
        rejects { ok.confirm(ok.preview, editor, identity) }
    }

    @Test fun exactPaidWritesUseOnlyRealRunnerOwnerAndCannotRebindReplacementTask() {
        val token = confirmed()
        val realOwner = owner()
        val writes = token.paidWrites(realOwner, editor, identity)
        assertEquals(token.preview.config.batches.map {
            RunGenerationTools.writeEffect(identity.accountScope, realOwner, token.preview.config, it)
        }.toSet(), writes)
        rejects { token.paidWrites(owner(), editor, identity) } // equal IDs are not the same runtime owner
        rejects { token.paidWrites(realOwner, GenerationEditorSnapshot(8, editor.text), identity) }
        rejects { token.approval(realOwner, TaskAuthorization("parent-session", identity.runId.value)) { true } }
        rejects { token.approval(realOwner, TaskAuthorization("parent-session", "another-run", writes)) { true } }
        val otherWrite = WriteEffect("other", "write", "exact", "effect")
        val task = TaskAuthorization("parent-session", identity.runId.value, writes + otherWrite)
        token.approval(realOwner, task) { true }
        rejects { token.approval(realOwner, TaskAuthorization("parent-session", identity.runId.value, writes)) { true } }
        token.close()
        rejects { token.paidWrites(realOwner, editor, identity) }
        assertEquals(GenerationToolsCloseResult.Drained, token.awaitClose(0))
        for (wrong in listOf(identity.copy(accountScope = "other"), identity.copy(conversationId = ConversationId("other")),
            identity.copy(runId = RunId("other")), identity.copy(assistantMessageId = MessageId("other")))) {
            val t = confirmed()
            rejects { t.paidWrites(owner(wrong), editor, wrong) }
        }
    }

    @Test fun everyImageAndVideoBatchUsesTheProductionEffectAndSameFrozenConfig() {
        val image = profile()
        val video = GenerationModelProfile("video-only", "Video model", GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            "https://video.example/api/videos", "separate-video-key", "video-model")
        val preparation = ControllerGenerationPreparation.prepare(identity, editor, listOf(image, video), listOf(
            GenerationBatchSelection("images", image.id, listOf(GenerationRunConfig.Item("image-item", "draw"))),
            GenerationBatchSelection("videos", video.id, listOf(GenerationRunConfig.Item("video-item", "animate")))), 0, hosts)
        val token = preparation.confirm(preparation.preview, editor, identity)
        val realOwner = owner()
        val effects = token.paidWrites(realOwner, editor, identity)
        assertEquals(2, effects.size)
        assertEquals(token.preview.config.batches.map {
            RunGenerationTools.writeEffect("account", realOwner, token.preview.config, it)
        }.toSet(), effects)
        assertEquals(setOf(token.preview.config.fingerprint), effects.map { it.change }.toSet())
        token.close()
    }

    @Test fun workerApprovalUsesSameConfigTaskAndRevocableCurrentOwnerGate() {
        val token = confirmed(); val realOwner = owner()
        val writes = token.paidWrites(realOwner, editor, identity)
        val task = TaskAuthorization("session", identity.runId.value, writes)
        var current = true
        val approval = token.approval(realOwner, task) { current }
        var config = token.preview.config
        var decision: GenerationRunDecision? = null
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition(RunGenerationTools.IMAGE_TOOL, 1, "test", "test", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                val binding = GenerationAttemptBinding("conversation", "reply", "run", context.invocation.id.value, context.invocation.name)
                val batch = config.batches.single()
                val unusedAssets = object : GenerationAssets {
                    override fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo = error("no input media")
                    override fun commit(identity: GenerationIdentity, outputIndex: Int, kind: GenerationKind, bytes: ByteArray,
                        info: GenerationMediaInfo): GenerationAssetReceipt = error("no output media")
                }
                val plans = batch.requests(binding::identity).map { GenerationRequestPlan.prepare(it, unusedAssets) }
                decision = approval.review(GenerationRunReview(context, config, batch, plans, writes.single()))
                ExecutionReceipt.Confirmed("ok")
            }
        }
        val dispatcher = ToolDispatcher(ToolRegistry().apply { register(tool) }.snapshot(), SessionCapabilities("session", setOf("test")),
            ToolLedger(InMemoryToolLedgerStore()))
        var call = 0
        fun review() { dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId("call-${call++}"), RunGenerationTools.IMAGE_TOOL, "{}")), realOwner) }
        review(); assertEquals(GenerationRunDecision.APPROVED, decision)
        config = prepare().preview.config
        review(); assertEquals(GenerationRunDecision.DENIED, decision)
        config = token.preview.config; current = false
        review(); assertEquals(GenerationRunDecision.DENIED, decision)
        current = true; token.close()
        review(); assertEquals(GenerationRunDecision.DENIED, decision)
    }

    @Test fun downloadsRequireSeparatelyReviewedHttpsHostOwnerAndParentNetworkPolicy() {
        val token = confirmed(); val realOwner = owner()
        token.paidWrites(realOwner, editor, identity)
        val assetOwner = GenerationAssetOwner("account", GenerationAttemptBinding("conversation", "reply", "run", "call", "generate_images"))
        var checks = 0; var networkAllowed = true; var current = true
        val auth = token.downloadAuthorization(realOwner, GenerationDownloadAuthorization { _, _ -> checks++; networkAllowed }) { current }
        assertTrue(auth.allows(assetOwner, "https://cdn.example/image.png?signature=exact"))
        for (url in listOf("http://cdn.example/image.png", "https://cdn.example.evil/image", "https://evil.example/image",
            "https://user@cdn.example/image", "https://cdn.example:444/image", "https://cdn.example/image#fragment",
            "https://127.0.0.1/image", "https://cdn.example/../image")) assertFalse(url, auth.allows(assetOwner, url))
        assertEquals(1, checks)
        networkAllowed = false; assertFalse(auth.allows(assetOwner, "https://cdn.example/image"))
        networkAllowed = true; current = false; assertFalse(auth.allows(assetOwner, "https://cdn.example/image"))
        current = true
        assertFalse(auth.allows(GenerationAssetOwner("other-account", assetOwner.binding), "https://cdn.example/image"))
        token.close(); assertFalse(auth.allows(assetOwner, "https://cdn.example/image"))
        assertFalse(GenerationDownloadHosts(emptySet()).allows("https://cdn.example/image"))
        for (host in listOf("*", "*.example", "127.0.0.1", "localhost", "host.local", "a..example", "CDN.example"))
            rejects { GenerationDownloadHosts(setOf(host)) }
    }

    @Test fun approvalNeverTakesParentLockUnderTokenAndRechecksRevocationAfterCallback() {
        val token = confirmed(); val realOwner = owner()
        val task = TaskAuthorization("session", "run", token.paidWrites(realOwner, editor, identity))
        val parentLock = Any()
        val parentHeld = CountDownLatch(1); val callbackEntered = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val decision = AtomicReference<GenerationRunDecision?>()
        val failure = AtomicReference<Throwable?>()
        val approval = token.approval(realOwner, task) {
            if (Thread.holdsLock(token)) failure.set(AssertionError("parent callback under token lock"))
            callbackEntered.countDown()
            synchronized(parentLock) { true } // deliberately returns stale true after revocation
        }
        val parent = Thread {
            try {
                synchronized(parentLock) {
                    parentHeld.countDown()
                    check(callbackEntered.await(5, TimeUnit.SECONDS))
                    token.revokeAdmission()
                    revoked.countDown()
                }
            } catch (t: Throwable) { failure.set(t) }
        }.apply { isDaemon = true }
        val review = Thread {
            try {
                check(parentHeld.await(5, TimeUnit.SECONDS))
                decision.set(dispatchReview(token, realOwner, task, approval))
            } catch (t: Throwable) { failure.set(t) }
        }.apply { isDaemon = true }
        parent.start(); review.start()
        parent.join(5_000); review.join(5_000)
        assertFalse("parent -> token must not deadlock token -> parent", parent.isAlive)
        assertFalse("approval must finish", review.isAlive)
        assertNull(failure.get())
        assertEquals(0L, revoked.count)
        assertEquals(GenerationRunDecision.DENIED, decision.get())
        token.close()
    }

    @Test fun cancellationCallbackRunsOutsideTokenLockAndIsRecheckedAfterOwnerCallback() {
        val token = confirmed(); val realOwner = owner()
        val task = TaskAuthorization("session", "run", token.paidWrites(realOwner, editor, identity))
        var cancelled = false
        var callbackUnderLock = false
        val approval = token.approval(realOwner, task) { cancelled = true; true }
        val decision = dispatchReview(token, realOwner, task, approval) {
            if (Thread.holdsLock(token)) callbackUnderLock = true
            cancelled
        }
        assertFalse(callbackUnderLock)
        assertEquals(GenerationRunDecision.DENIED, decision)
        token.close()
    }

    @Test fun rejectedOrThrowingUiCallbackRetainsTokenAndNeverWaitsForBlockingLeafClose() {
        for (throwsAfterStarting in listOf(false, true)) {
            val realOwner = owner()
            val fenceEntered = CountDownLatch(1); val releaseFence = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1); val cleanupDone = CountDownLatch(1)
            val uiReturned = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val result = AtomicReference<GenerationToolsCloseResult?>()
            val execution = AtomicReference<Thread?>()
            // One bounded worker, owned here by the test parent, not by a token or composable.
            val cleanup = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
                { runnable -> Thread(runnable).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            val handoff = GenerationConfirmationHandoff { slot ->
                cleanup.execute {
                    try {
                        cleanupEntered.countDown()
                        val token = checkNotNull(slot.token)
                        token.close() // blocks at the real publication fence until releaseFence
                        execution.get()?.join(5_000)
                        result.set(token.awaitClose(1_000))
                    } catch (t: Throwable) { failure.set(t) }
                    finally { cleanupDone.countDown() }
                }
            }
            val ui = Thread {
                try {
                    val accepted = handoff.confirm(prepare(), editor, identity) { token ->
                        val task = TaskAuthorization("session", "run", token.paidWrites(realOwner, editor, identity))
                        val leaf = token.openLeaf(realOwner, task, { error("no database") }, { fence ->
                            fence.admit {
                                fenceEntered.countDown()
                                check(releaseFence.await(5, TimeUnit.SECONDS))
                                error("stop before assets/database/network")
                            }
                        }, { true }, GenerationDownloadAuthorization { _, _ -> false }, { true },
                            GenerationHttp { error("no HTTP") })
                        val worker = Thread { dispatchLeaf(leaf, realOwner, task) }.apply { isDaemon = true }
                        execution.set(worker); worker.start()
                        check(fenceEntered.await(5, TimeUnit.SECONDS))
                        if (throwsAfterStarting) error("callback failed after starting its leaf")
                        false
                    }
                    check(!accepted)
                } catch (t: Throwable) { failure.set(t) }
                finally { uiReturned.countDown() }
            }.apply { isDaemon = true }
            try {
                ui.start()
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                assertTrue("UI must return while leaf.close is blocked", uiReturned.await(1, TimeUnit.SECONDS))
                assertEquals("the real leaf reached its publication fence", 0L, fenceEntered.count)
                assertEquals(1L, cleanupDone.count)
                assertTrue(handoff.cleanupRequested)
                val token = checkNotNull(handoff.token)
                rejects { token.paidWrites(realOwner, editor, identity) }
                releaseFence.countDown()
                assertTrue(cleanupDone.await(5, TimeUnit.SECONDS))
                assertEquals(GenerationToolsCloseResult.Drained, result.get())
                assertSame("only the parent may release the drained slot", token, handoff.token)
                assertNull(failure.get())
            } finally {
                releaseFence.countDown(); ui.join(5_000); execution.get()?.join(5_000)
                cleanup.shutdownNow(); cleanup.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun failedCleanupNotificationKeepsHandoffAndCheapRevokeIsNotDrain() {
        val handoff = GenerationConfirmationHandoff { error("bounded cleanup queue unavailable") }
        assertFalse(handoff.confirm(prepare(), editor, identity) { false })
        val token = checkNotNull(handoff.token)
        assertTrue(handoff.cleanupRequested)
        val unknown = token.awaitClose(0)
        assertTrue(unknown is GenerationToolsCloseResult.Unknown)
        token.close() // a later cleanup retry still runs, but cannot upgrade UNKNOWN
        assertSame(unknown, token.awaitClose(1_000))
        assertSame(token, handoff.token)
        rejects { handoff.confirm(prepare(), editor, identity) { true } }
    }

    @Test fun interruptedAwaitRestoresInterruptAndRetainsUnknownAfterPhysicalDrain() {
        val token = confirmed(); val realOwner = owner()
        val task = TaskAuthorization("session", "run", token.paidWrites(realOwner, editor, identity))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val leaf = token.openLeaf(realOwner, task, { error("no database") }, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("no assets")
        }, { true }, GenerationDownloadAuthorization { _, _ -> false }, { true }, GenerationHttp { error("no HTTP") })
        val worker = Thread { dispatchLeaf(leaf, realOwner, task) }.apply { isDaemon = true }
        val result = AtomicReference<GenerationToolsCloseResult?>()
        val interruptPreserved = AtomicReference(false)
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            token.close()
            val waiter = Thread {
                Thread.currentThread().interrupt()
                result.set(token.awaitClose(5_000))
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            }
            waiter.start(); waiter.join(5_000)
            assertFalse(waiter.isAlive)
            assertTrue(interruptPreserved.get())
            val unknown = result.get()
            assertEquals("generation_await_interrupted", (unknown as GenerationToolsCloseResult.Unknown).reason)
            release.countDown(); worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals(GenerationToolsCloseResult.Drained, leaf.awaitClose(1_000))
            assertSame(unknown, token.awaitClose(1_000))
        } finally { release.countDown(); worker.join(5_000); token.close() }
    }

    @Test fun tokenPassesParentFenceFactoryWithoutGivingHistoryItsExecutionCancellation() {
        val token = confirmed()
        val realOwner = owner()
        val task = TaskAuthorization("session", "run", token.paidWrites(realOwner, editor, identity))
        val gate = GenerationAssetPublicationGate()
        var captured: GenerationAssetFence? = null
        val leaf = token.openLeaf(realOwner, task, { error("no database") }, { fence ->
            assertSame(gate, fence.publicationGate)
            error("stop before files")
        }, { true }, GenerationDownloadAuthorization { _, _ -> false }, { true },
            fenceFactory = { assetOwner, validator ->
                GenerationAssetFence(assetOwner, gate, validator).also { captured = it }
            })
        dispatchLeaf(leaf, realOwner, task)
        val writer = checkNotNull(captured)
        val history = GenerationAssetFence(writer.owner, gate) { true }
        token.revokeAdmission()
        token.close()
        assertEquals(GenerationToolsCloseResult.Drained, token.awaitClose(1000))
        assertTrue(history.admit { true })
    }

    @Test fun purePrepareConfirmBindAndToolPrepareDoZeroIoAndUnknownCleanupCannotReopen() {
        val token = confirmed(); val realOwner = owner()
        val writes = token.paidWrites(realOwner, editor, identity)
        val task = TaskAuthorization("session", "run", writes)
        var dbCalls = 0; var httpCalls = 0
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val leaf = token.openLeaf(realOwner, task,
            database = { dbCalls++; error("No DB in preparation test") },
            assetsFactory = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("No assets in preparation test") },
            validateOwner = { true }, networkPolicy = GenerationDownloadAuthorization { _, _ -> false }, isCurrentOwner = { true },
            http = GenerationHttp { httpCalls++; error("No HTTP in preparation test") })
        val tool = leaf.registeredTools.single()
        tool.prepare(tool.definition.schema.parse("{\"batch_id\":\"batch\"}"))
        assertEquals(0, dbCalls); assertEquals(0, httpCalls)
        rejects { token.openLeaf(realOwner, task, { error("unused") }, { error("unused") }, { true },
            GenerationDownloadAuthorization { _, _ -> false }, { true }) }
        val dispatcher = ToolDispatcher(ToolRegistry().apply { register(tool) }.snapshot(),
            SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY)), ToolLedger(InMemoryToolLedgerStore()))
        val worker = Thread {
            dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId("original-call"), tool.definition.name,
                "{\"batch_id\":\"batch\"}")), realOwner)
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            token.close()
            val unknown = token.awaitClose(0)
            assertTrue(unknown is GenerationToolsCloseResult.Unknown)
            release.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
            assertSame(unknown, token.awaitClose(1000))
            rejects { token.paidWrites(owner(), editor, identity) }
            assertEquals(0, dbCalls); assertEquals(0, httpCalls)
        } finally { release.countDown(); worker.join(5_000); token.close() }
    }
}
