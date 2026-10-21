package app.daiyu.android

import android.test.InstrumentationTestCase
import android.net.Uri
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.domain.*
import app.daiyu.android.media.GenerationAssetException
import app.daiyu.android.media.GenerationAssetFailure
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.GenerationToolsCloseResult
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.model.generation.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller, durable two-row admission and Runner-issued identity. No paid HTTP/tool call. */
@Suppress("DEPRECATION")
class ControllerGenerationIntegrationTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private val controller get() = fixture.controller
    private val editor = GenerationEditorSnapshot(23, "  exact generation draft\n ")
    private lateinit var store: GenerationSettingsStore
    private var configured: GenerationSettingsSnapshot? = null
    private var forbidGenerationCredentials = false
    private val credentialOpens = AtomicInteger()

    override fun setUp() {
        super.setUp()
        forbidGenerationCredentials = false
        credentialOpens.set(0)
        val guarded = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getCacheDir(): File = super.getCacheDir().canonicalFile
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                if (name.contains("generation-credential-")) {
                    credentialOpens.incrementAndGet()
                    check(!forbidGenerationCredentials) { "credential vault access forbidden during backup/history" }
                }
                return super.getSharedPreferences(name, mode)
            }
        }
        fixture = RunControlFixture(guarded, filesRoot = File(guarded.cacheDir, "generation-control-${UUID.randomUUID()}").canonicalFile)
        awaitControl("controller ready") { controller.ready }
        store = GenerationSettingsStore(fixture.application)
        configured = null
        fixture.database.draft(editor.text)
    }

    override fun tearDown() {
        try {
            if (::fixture.isInitialized) {
                // A failed assertion must not strand an Accepted-but-unconsumed reservation.
                controlMain {
                    controller.currentGenerationEntry()?.let { entry ->
                        if (entry.submitted) entry.session?.let { session ->
                            controller.pendingGenerationReceiptNotice()?.let(controller::acknowledgeGenerationReceipt)
                        } else controller.abandonGeneration(entry)
                    }
                }
                fixture.close()
            }
        } finally { super.tearDown() }
    }

    private fun saveSettings(downloadsEnabled: Boolean = false): GenerationSettingsSnapshot = configured ?: store.save(GenerationSettingsValues(
        listOf(GenerationProfileSettings(label = "explicit image profile", kind = GenerationKind.IMAGE,
            protocol = GenerationProtocol.OPENAI_IMAGES_JSON,
            endpoint = "https://generation.invalid/images/generations", model = "explicit-image-model",
            apiKey = "synthetic-generation-test-key")), maxPolls = 0,
        downloadsEnabled = downloadsEnabled,
        downloadHosts = if (downloadsEnabled) setOf("cdn.example") else emptySet()), null).also { configured = it }

    private fun prepare(): ChatController.GenerationEntry {
        saveSettings()
        return checkNotNull(controller.prepareGeneration(null, 17, editor))
    }

    private fun confirm(entry: ChatController.GenerationEntry, submittedEditor: GenerationEditorSnapshot = editor) {
        val preparation = ControllerGenerationPreparation.prepare(entry.identity, editor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id,
                listOf(GenerationRunConfig.Item("item", "draw this exact prompt")))),
            entry.settings.maxPolls, entry.settings.downloads)
        controlMain {
            assertTrue(entry.handoff.confirm(preparation, editor, entry.identity) {
                controller.sendGeneration(entry, submittedEditor)
            })
        }
    }

    fun testMissingIndependentConfigAndCancelledPreviewNeverEnterModelOrDatabaseSend() {
        assertNull(controller.prepareGeneration(null, 17, editor))
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
        val entry = prepare()
        assertSame(entry, controller.currentGenerationEntry())
        assertNull(entry.handoff.token)
        assertNull(controller.send(null, "must not bypass reserved generation form"))
        controlMain { controller.abandonGeneration(entry) }
        assertNull(controller.currentGenerationEntry())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testParentRejectsDifferentRevisionEvenWhenTextWasEditedBack() {
        val entry = prepare()
        val preview = ControllerGenerationPreparation.prepare(entry.identity, editor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id,
                listOf(GenerationRunConfig.Item("item", "draw")))), 0, entry.settings.downloads)
        controlMain {
            assertFalse(entry.handoff.confirm(preview, editor, entry.identity) {
                controller.sendGeneration(entry, GenerationEditorSnapshot(editor.revision + 1, editor.text))
            })
        }
        awaitControl("rejected handoff releases only after real cleanup") { controller.currentGenerationEntry() == null }
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testRealAdmissionBindsOneOwnerAndOneTaskBeforeModelAndConsumesOnlyExactReceipt() {
        val model = fixture.session(held = true)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        val session = checkNotNull(entry.session)
        val accepted = session.persistenceReceipt as GenerationPersistenceReceipt.Accepted
        val rows = fixture.database.messages(accepted.conversation.id)
        assertEquals(2, rows.size)
        assertEquals(entry.identity.assistantMessageId, rows.last().second.id)
        assertEquals(entry.identity.runId, rows.last().second.runId)
        assertEquals(entry.identity.conversationId, rows.last().second.conversationId)
        assertEquals(RunStatus.RUNNING, rows.last().second.status)
        assertEquals("", fixture.database.draft())
        assertEquals(1, fixture.opens.get())

        val catalog = checkNotNull(entry.catalog)
        val actualOwner = field(catalog, "owner") as RunExecutionOwner
        val actualTask = field(catalog, "boundAuthorization") as TaskAuthorization
        assertTrue(actualOwner.owns(rows.last().second))
        assertSame(actualOwner, field(session, "owner"))
        assertSame(actualTask, field(session, "authorization"))
        assertSame(field(controller, "generationResources"), field(session, "resources"))
        assertEquals(1, actualTask.allowedWrites.size)
        assertEquals(entry.identity.runId.value, actualTask.taskId)
        assertTrue(actualTask.allowedWrites.containsAll(session.token.paidWrites(actualOwner, editor, entry.identity)))
        assertNotNull(field(catalog, "generation"))

        val removals = AtomicInteger()
        controlMain {
            assertFalse(controller.consumeGenerationReceipt(entry, 18, editor) { removals.incrementAndGet() })
            assertFalse(controller.consumeGenerationReceipt(entry, 17,
                GenerationEditorSnapshot(24, editor.text)) { removals.incrementAndGet() })
            assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
            assertFalse(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
        }
        assertEquals(1, removals.get())
        assertSame(entry, controller.currentGenerationEntry())
        model.release.countDown()
        awaitControl("real runner and generation cleanup retire") { controller.currentGenerationEntry() == null }
        assertEquals(GenerationToolsCloseResult.Drained, session.closeResult)
    }

    fun testGenerationInputsAreAcceptedButOnlyOriginalChatDraftIsConsumedOnceAfterRealAdmission() {
        configured = store.save(GenerationSettingsValues(listOf(GenerationProfileSettings(
            label = "explicit edit profile", kind = GenerationKind.IMAGE,
            protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            endpoint = "https://generation.invalid/images/edits", model = "explicit-edit-model",
            apiKey = "synthetic-generation-test-key")), maxPolls = 0,
            downloadsEnabled = false, downloadHosts = emptySet()), null)
        val bytes = app.daiyu.android.validDeviceTestPng()
        val input = GenerationInputImport.read("content://synthetic/reference", { bytes.inputStream() },
            app.daiyu.android.media.AndroidImageInspector(), GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            GenerationInputTarget.REFERENCE, null) { false }
        assertTrue(bytes.contentEquals(input.bytes))
        val model = fixture.session(held = true)
        val entry = prepare()
        val formEditor = GenerationEditorSnapshot(48, editor.text, GenerationFormInputs.ids(listOf(input), input))
        val preparation = ControllerGenerationPreparation.prepare(entry.identity, formEditor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id, listOf(
                GenerationRunConfig.Item("item", "exact edit prompt", listOf(input), input)))),
            0, entry.settings.downloads)
        controlMain {
            assertTrue(entry.handoff.confirm(preparation, formEditor, entry.identity) {
                controller.sendGeneration(entry, formEditor)
            })
        }
        try {
            assertTrue(model.entered.await(10, TimeUnit.SECONDS))
            val session = checkNotNull(entry.session)
            assertTrue(session.persistenceReceipt is GenerationPersistenceReceipt.Accepted)
            assertEquals(1, fixture.opens.get())
            val removals = AtomicInteger()
            controlMain {
                assertFalse(controller.consumeGenerationReceipt(entry, 17, formEditor) { removals.incrementAndGet() })
                assertFalse(controller.consumeGenerationReceipt(entry, 17,
                    GenerationEditorSnapshot(editor.revision + 1, editor.text)) { removals.incrementAndGet() })
                assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
                assertFalse(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
            }
            assertEquals(1, removals.get())
            assertFalse(session.canRetire)
            assertSame(entry, controller.currentGenerationEntry())
            assertSame(input, session.token.preview.config.batches.single().items.single().mask)
            assertEquals("", fixture.database.draft())
        } finally { model.release.countDown() }
        awaitControl("input generation physical drain") { controller.currentGenerationEntry() == null }
    }

    fun testGenerationFormCannotSmuggleUnrelatedChatAttachmentIds() {
        val entry = prepare()
        val formEditor = GenerationEditorSnapshot(48, editor.text, listOf("ordinary-chat-attachment"))
        val preparation = ControllerGenerationPreparation.prepare(entry.identity, formEditor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id,
                listOf(GenerationRunConfig.Item("item", "draw")))), 0, entry.settings.downloads)
        controlMain {
            assertFalse(entry.handoff.confirm(preparation, formEditor, entry.identity) {
                controller.sendGeneration(entry, formEditor)
            })
        }
        awaitControl("bad input identity cleanup") { controller.currentGenerationEntry() == null }
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
    }

    /** Real production constructor -> Runner catalog -> Session -> token -> RunGenerationTools.
     * The model is held without tool events: inspect the real pair, never execute a network call.
     * This fails both if production forgets injection and if it still passes DENY_DOWNLOADS.
     */
    fun testProductionRetainedResourcePairsSamePublicTransportAndKeepsReviewedHostOwnerGate() {
        saveSettings(downloadsEnabled = true)
        val model = fixture.session(held = true)
        val entry = prepare()
        confirm(entry)
        try {
            assertTrue(model.entered.await(10, TimeUnit.SECONDS))
            val session = checkNotNull(entry.session)
            val catalog = checkNotNull(entry.catalog)
            val retained = field(controller, "generationResources") as ControllerGenerationResources
            val http = field(retained, "downloadTransport") as GenerationPublicNetworkHttp
            val catalogLeaf = checkNotNull(field(catalog, "generation"))
            val leaf = field(catalogLeaf, "tools") as RunGenerationTools
            assertSame(retained, field(session, "resources"))
            assertSame(leaf, field(session, "leaf"))
            assertSame(leaf, field(session.token, "leaf"))
            assertSame(http, field(leaf, "http"))
            assertSame(http, retained.generationNetwork().http)
            assertSame(field(catalog, "owner"), field(leaf, "owner"))
            assertSame(field(catalog, "boundAuthorization"), field(leaf, "authorization"))
            val authorization = field(leaf, "downloadAuthorization") as
                app.daiyu.android.integration.tools.GenerationDownloadAuthorization
            val binding = GenerationAttemptBinding(entry.identity.conversationId.value,
                entry.identity.assistantMessageId.value, entry.identity.runId.value,
                "not-yet-persisted-call", RunGenerationTools.IMAGE_TOOL)
            val assetOwner = GenerationAssetOwner(entry.identity.accountScope, binding)
            val url = "https://cdn.example/image.png?signature=synthetic-only"
            assertTrue(authorization.allows(assetOwner, url))
            assertFalse(authorization.allows(assetOwner, "https://sub.cdn.example/image.png"))
            assertFalse(authorization.allows(assetOwner, "https://other.example/image.png"))
            assertFalse(authorization.allows(assetOwner, "https://127.0.0.1/image.png"))
            assertFalse(authorization.allows(GenerationAssetOwner("foreign-account", binding), url))
            // Host admission is NOT persisted tool ownership: no tool event was emitted.
            assertFalse(retained.validateOwner(assetOwner))
            assertTrue(leaf.outcomes().isEmpty())
            controlMain {
                assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) {})
                controller.stop()
            }
            assertFalse(authorization.allows(assetOwner, url))
            assertTrue(model.cancelled.await(5, TimeUnit.SECONDS))
            awaitControl("production generation cleanup") { controller.currentGenerationEntry() == null }
            assertSame(retained, field(controller, "generationResources"))
            assertSame(http, retained.generationNetwork().http)
            assertEquals(1, fixture.opens.get())
        } finally { model.release.countDown() }
    }

    fun testExplicitReceiptRecoveryPreservesReplacementDraftAndWaitsForRunningWorker() {
        val model = fixture.session(held = true)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        val notice = checkNotNull(controller.pendingGenerationReceiptNotice())
        fixture.database.draft("replacement editor draft")
        controlMain {
            assertFalse(controller.acknowledgeGenerationReceipt(notice.copy(
                identity = notice.identity.copy(accountScope = "different-account"))))
            assertFalse(controller.acknowledgeGenerationReceipt(notice.copy(
                receipt = GenerationPersistenceReceipt.Accepted(notice.receipt.conversation))))
            assertTrue(controller.acknowledgeGenerationReceipt(notice))
            assertFalse(controller.acknowledgeGenerationReceipt(notice))
            assertNull(controller.pendingGenerationReceiptNotice())
        }
        assertEquals("replacement editor draft", fixture.database.draft())
        assertSame(entry, controller.currentGenerationEntry())
        assertFalse(checkNotNull(entry.session).canRetire)
        assertEquals(1, fixture.opens.get())
        model.release.countDown()
        awaitControl("acknowledged receipt still waits for physical worker") {
            controller.currentGenerationEntry() == null
        }
        assertEquals("replacement editor draft", fixture.database.draft())
    }

    fun testStopAcknowledgementDoesNotPretendNonCooperativeRunnerHasExited() {
        val model = fixture.session(held = true, holdSeconds = 30, releaseOnCancel = false)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        controlMain {
            assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) {})
            controller.stop()
        }
        assertTrue(model.cancelled.await(5, TimeUnit.SECONDS))
        assertSame(entry, controller.currentGenerationEntry())
        assertNull(checkNotNull(entry.session).closeResult)
        assertFalse(checkNotNull(entry.session).canRetire)
        model.release.countDown()
        awaitControl("must wait for actual runner exit") { controller.currentGenerationEntry() == null }
        assertEquals(GenerationToolsCloseResult.Drained, checkNotNull(entry.session).closeResult)
    }

    fun testPersistedDraftCasFailureNeverFabricatesAcceptedOrClearsReplacement() {
        val entry = prepare()
        fixture.database.draft("newer persisted draft")
        confirm(entry)
        awaitControl("rejected preparation drains") { controller.currentGenerationEntry() == null }
        assertSame(GenerationPersistenceReceipt.Rejected, checkNotNull(entry.session).persistenceReceipt)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals("newer persisted draft", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testChangedGenerationConfigRequiresFreshPreviewAndNeverFallsBackToChatProfile() {
        val entry = prepare()
        store.save(entry.settings.values, entry.settings.revision)
        confirm(entry)
        awaitControl("stale config rejected") { controller.currentGenerationEntry() == null }
        assertSame(GenerationPersistenceReceipt.Rejected, checkNotNull(entry.session).persistenceReceipt)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsSaveAloneCannotCreateApprovalOrGenerationAssets() {
        val saved = saveSettings()
        assertEquals("explicit-image-model", saved.profiles.single().model)
        assertNull(controller.currentGenerationEntry())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertTrue(ControllerGenerationResources.DOWNLOAD_STATUS.contains("显式接入"))
        // Capability alone and a configured host are still not download permission.
        assertFalse(ControllerGenerationResources.DENY_DOWNLOADS.allows(
            app.daiyu.android.media.GenerationAssetOwner(saved.accountScope,
                app.daiyu.android.storage.GenerationAttemptBinding("conversation", "assistant", "run", "call",
                    RunGenerationTools.IMAGE_TOOL)), "https://cdn.example/video.mp4"))
    }

    fun testAssetReadUsesWorkerAndSideTableWithoutStartingGeneration() {
        saveSettings()
        val conversation = fixture.database.create("default")
        val done = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.atomic.AtomicReference<List<ChatController.GenerationAssetDisplay>?>()
        controlMain {
            assertTrue(controller.loadGenerationOutputs(conversation.id) {
                assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                returned.set(it); done.countDown()
            })
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNotNull(returned.get())
        assertTrue(checkNotNull(returned.get()).isEmpty())
        assertEquals(0, fixture.opens.get())
        assertNull(controller.currentGenerationEntry())
        assertTrue(fixture.database.messages(conversation.id).isEmpty())
    }

    private data class ExistingOutput(val conversation: Conversation, val owner: GenerationAssetOwner,
        val attempt: GenerationAttempt, val resources: ControllerGenerationResources,
        val assets: MessageGenerationAssets, val png: ByteArray)

    /** Offline fixture only: persist a real two-row owner with its original tool invocation, then
     * publish a static PNG and register it using the production filesystem/SQLite stores. No fake
     * Tool.result URL or fake output reader can make these tests pass. The central attempt is
     * durably UNKNOWN, not Confirmed; readback must not reserve a new paid attempt.
     */
    private fun existingOutput(kind: GenerationKind = GenerationKind.IMAGE): ExistingOutput {
        val settings = saveSettings()
        val conversation = fixture.database.create("default")
        val run = RunId(UUID.randomUUID().toString())
        val assistantId = MessageId(UUID.randomUUID().toString())
        val call = ToolInvocation(ToolCallId(UUID.randomUUID().toString()),
            if (kind == GenerationKind.IMAGE) RunGenerationTools.IMAGE_TOOL else RunGenerationTools.VIDEO_TOOL, "{}")
        val tool = MessageBlock.Tool(call)
        val assistant = Message(assistantId, conversation.id, run, Message.ASSISTANT_ROLE,
            listOf(tool), RunStatus.RUNNING, createdAt = 1_700_000_000_123L, historySegments = listOf(
                MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, listOf(tool))))
        fixture.database.send(Message(MessageId(UUID.randomUUID().toString()), conversation.id, run,
            Message.USER_ROLE, listOf(MessageBlock.Text("prompt", "offline fixture")), RunStatus.COMPLETED,
            createdAt = 1_700_000_000_123L), assistant)
        assertTrue(checkNotNull(loadOutputs(conversation.id)).isEmpty()) // constructs parent's resource
        val resources = field(controller, "generationResources") as ControllerGenerationResources
        val owner = GenerationAssetOwner(settings.accountScope, GenerationAttemptBinding(conversation.id.value,
            assistantId.value, run.value, call.id.value, call.name))
        val attempt = GenerationAttempt(owner.binding.identity("item"), "a".repeat(64),
            if (kind == GenerationKind.IMAGE) GenerationProtocol.OPENAI_IMAGES_JSON else GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        val attempts = SQLiteGenerationAttemptStore(resources::database, owner.binding)
        assertEquals(GenerationReservation.Started, attempts.reserve(attempt))
        val png = if (kind == GenerationKind.IMAGE) app.daiyu.android.validDeviceTestPng()
        else {
            val video = File(fixture.application.cacheDir, "generation-fixture-${UUID.randomUUID()}.mp4")
            try { encodeOfflineBackupMp4(video); video.readBytes() } finally { video.delete() }
        }
        val assets = resources.assets(resources.createFence(owner))
        val info = assets.inspect(kind, png, if (kind == GenerationKind.IMAGE) "image/png" else "video/mp4")
        val receipt = assets.commit(attempt.identity, 0, kind, png, info)
        SQLiteGenerationOutputStore(resources::database, owner, assets).register(attempt, receipt)
        attempts.finish(attempt, Submission.Unknown(GenerationFailure.TRANSPORT))
        val endedTool = tool.copy(result = "{\"status\":\"UNKNOWN\"}")
        fixture.database.save(assistant.copy(status = RunStatus.COMPLETED, blocks = listOf(endedTool),
            historySegments = listOf(MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, listOf(endedTool)))))
        assertTrue((attempts.reserve(attempt) as GenerationReservation.Existing).submission is Submission.Unknown)
        return ExistingOutput(conversation, owner, attempt, resources, assets, png)
    }

    fun testCardOriginalReaderRequiresExactLiveCallAndAsset() {
        verifyCardOriginalReader()
    }

    private fun verifyCardOriginalReader() {
        val output = existingOutput()
        val binding = output.owner.binding
        val active = AtomicBoolean(true)
        val original = checkNotNull(loadCardOriginal(controller, binding, "live:item:0", active))
        original.use {
            assertEquals(output.png.size.toLong(), it.metadata.bytes)
            assertTrue(output.png.contentEquals(it.stream.readBytes()))
        }
        val wrong = GenerationAttemptBinding(binding.conversationId, binding.assistantMessageId,
            binding.runId, "other-call", binding.toolName)
        assertNull(loadCardOriginal(controller, wrong, "live:item:0", active))
        assertNull(loadCardOriginal(controller, binding, "live:other:0", active))
        assertNull(loadCardOriginal(controller, binding, "history:item:0", active))
        assertNull(loadCardOriginal(controller, binding, "static:item:0", active))
        controlMain {
            assertFalse(controller.loadGenerationMediaOriginal(binding, "live:item:32", active) { fail("invalid key callback") })
            active.set(false)
            assertFalse(controller.loadGenerationMediaOriginal(binding, "live:item:0", active) { fail("revoked visit callback") })
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testCardStaticOriginalReaderUsesDestinationCallWithoutRevivingLiveAuthority() {
        verifyCardStaticOriginalReader()
    }

    private fun verifyCardStaticOriginalReader() {
        val output = existingOutput()
        val branch = branchThroughProduction(controller, output.conversation.id)
        val item = staticThroughProduction(controller, branch.id).output
        val target = item.destination
        val binding = GenerationAttemptBinding(target.conversationId, target.assistantMessageId,
            target.runId, target.toolCallId, target.toolName)
        val active = AtomicBoolean(true)
        checkNotNull(loadCardOriginal(controller, binding, "static:item:0", active)).use {
            assertTrue(output.png.contentEquals(it.stream.readBytes()))
        }
        assertNull(loadCardOriginal(controller, binding, "live:item:0", active))
        assertNull(loadCardOriginal(controller, output.owner.binding, "static:item:0", active))
        assertEquals(0, fixture.opens.get())
    }

    private class CardDocuments(private val destination: File) : android.content.ContentProvider() {
        val opens = AtomicInteger()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
            opens.incrementAndGet()
            return android.os.ParcelFileDescriptor.open(destination, android.os.ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = error("unused")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("unused")
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = error("unused")
    }

    fun testCardOriginalSaveRevalidatesCallAndRevokedVisitBeforeOpeningTarget() {
        verifyCardOriginalSave()
    }

    private fun verifyCardOriginalSave() {
        val output = existingOutput()
        val saved = File(fixture.application.cacheDir, "card-save-${UUID.randomUUID()}.png")
        val documents = CardDocuments(saved).apply {
            attachInfo(fixture.application, android.content.pm.ProviderInfo().apply { authority = "card-documents" })
        }
        val resolver = android.test.mock.MockContentResolver(fixture.application).apply { addProvider("card-documents", documents) }
        val uri = Uri.parse("content://card-documents/output")
        try {
            assertEquals("生成原件已保存", dataOperation {
                controller.saveGenerationMediaOriginal(output.owner.binding, "live:item:0", uri, AtomicBoolean(true), resolver)
            })
            assertTrue(output.png.contentEquals(saved.readBytes()))
            assertEquals(1, documents.opens.get())
            val wrong = output.owner.binding.let { GenerationAttemptBinding(it.conversationId, it.assistantMessageId,
                it.runId, "wrong-call", it.toolName) }
            assertTrue(dataOperation {
                controller.saveGenerationMediaOriginal(wrong, "live:item:0", uri, AtomicBoolean(true), resolver)
            }.startsWith("原件保存未确认"))
            assertTrue(dataOperation {
                controller.saveGenerationMediaOriginal(output.owner.binding, "live:item:0", uri, AtomicBoolean(false), resolver)
            }.startsWith("原件保存未确认"))
            assertEquals(1, documents.opens.get())
            assertTrue(output.png.contentEquals(saved.readBytes()))
            assertEquals(0, fixture.opens.get())
        } finally { saved.delete() }
    }

    fun testScreenshotCleanupDoesNotRunWhenDeleteMutationLeavesConversation() = verifyDeleteReadback()

    private fun verifyDeleteReadback() {
        val conversation = fixture.database.create("default")
        val source = File(fixture.application.cacheDir, "browser_screenshots/screenshot_${System.nanoTime()}.jpg")
        check(source.parentFile!!.mkdirs() || source.parentFile!!.isDirectory)
        val bitmap = android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { bitmap.recycle() }
        val store = app.daiyu.android.media.BrowserScreenshotStore(fixture.application.filesDir, fixture.application.cacheDir)
        val binding = app.daiyu.android.media.BrowserScreenshotStore.Binding(conversation.id.value,
            "message", "run", "call", "browser_read", "{\"action\":\"screenshot\"}")
        val receipt = store.freeze(binding, source.path)
        val bytes = source.readBytes()
        try {
            val success = AtomicBoolean(true)
            assertTrue(dataOperation {
                controller.mutateGenerationOwners(setOf(conversation.id.value), completed = { success.set(it) }) {}
            }.startsWith("删除未确认"))
            assertFalse(success.get())
            assertTrue(fixture.database.conversations().any { it.id == conversation.id })
            assertTrue(bytes.contentEquals(store.read(binding, receipt)))
        } finally { source.delete() }
    }

    fun testBrowserCardOriginalUsesCommittedReceiptAndSavesExactJpeg() = verifyBrowserCardOriginal()

    private fun verifyBrowserCardOriginal() {
        val conversation = fixture.database.create("default")
        val messageId = MessageId(UUID.randomUUID().toString())
        val run = RunId(UUID.randomUUID().toString())
        val call = ToolInvocation(ToolCallId(UUID.randomUUID().toString()), "browser_read", "{\"action\":\"screenshot\"}")
        val binding = GenerationAttemptBinding(conversation.id.value, messageId.value, run.value, call.id.value, call.name)
        val source = File(fixture.application.cacheDir, "browser_screenshots/screenshot_${System.nanoTime()}.jpg")
        check(source.parentFile!!.mkdirs() || source.parentFile!!.isDirectory)
        val bitmap = android.graphics.Bitmap.createBitmap(1600, 2, android.graphics.Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { bitmap.recycle() }
        val bytes = source.readBytes()
        val receipt = app.daiyu.android.media.BrowserScreenshotStore(fixture.application.filesDir, fixture.application.cacheDir)
            .freeze(app.daiyu.android.media.BrowserScreenshotStore.Binding(binding.conversationId, binding.assistantMessageId,
                binding.runId, binding.toolCallId, binding.toolName, call.arguments), source.path)
        source.delete()
        val result = org.json.JSONObject().put("screenshot_receipt", receipt).put("filePath", org.json.JSONObject.NULL).toString()
        val tool = MessageBlock.Tool(call, result)
        val assistant = Message(messageId, conversation.id, run, Message.ASSISTANT_ROLE, listOf(tool), RunStatus.COMPLETED,
            createdAt = 1_700_000_000_123L)
        fixture.database.send(Message(MessageId(UUID.randomUUID().toString()), conversation.id, run,
            Message.USER_ROLE, listOf(MessageBlock.Text("prompt", "offline browser screenshot fixture")),
            RunStatus.COMPLETED, createdAt = assistant.createdAt), assistant.copy(status = RunStatus.RUNNING))
        fixture.database.save(assistant)
        val snapshot = app.daiyu.android.domain.runtime.RunCheckpoint(assistant, emptyList(),
            app.daiyu.android.domain.runtime.ExecutionPhase.TERMINAL,
            tools = listOf(app.daiyu.android.domain.runtime.ToolRecord(call,
                app.daiyu.android.domain.runtime.ToolDisposition.SUCCEEDED, result)), owner = "browser-fixture", revision = 1)
        fixture.database.writableDatabase.insertOrThrow("runtime_checkpoints", null, android.content.ContentValues().apply {
            put("message_id", messageId.value); put("conversation_id", conversation.id.value); put("run_id", run.value)
            put("owner", snapshot.owner); put("revision", snapshot.revision); put("phase", snapshot.phase.name)
            put("process_owner", "browser-fixture"); put("payload", RunCheckpointCodec.encode(snapshot))
        })
        val key = app.daiyu.android.media.BrowserScreenshotStore.MEDIA_KEY
        checkNotNull(loadCardOriginal(controller, binding, key, AtomicBoolean(true))).use {
            assertEquals("image/jpeg", it.metadata.mimeType); assertEquals(1600, it.metadata.width)
            assertTrue(bytes.contentEquals(it.stream.readBytes()))
        }
        val saved = File(fixture.application.cacheDir, "browser-card-save-${UUID.randomUUID()}.jpg")
        val documents = CardDocuments(saved).apply {
            attachInfo(fixture.application, android.content.pm.ProviderInfo().apply { authority = "browser-card-documents" })
        }
        val resolver = android.test.mock.MockContentResolver(fixture.application).apply { addProvider("browser-card-documents", documents) }
        val uri = Uri.parse("content://browser-card-documents/output")
        try {
            assertEquals("原件已保存", dataOperation { controller.saveGenerationMediaOriginal(binding, key, uri, AtomicBoolean(true), resolver) })
            assertTrue(bytes.contentEquals(saved.readBytes()))
            val wrong = GenerationAttemptBinding(binding.conversationId, binding.assistantMessageId, binding.runId, "other", binding.toolName)
            assertNull(loadCardOriginal(controller, wrong, key, AtomicBoolean(true)))
            assertTrue(dataOperation { controller.saveGenerationMediaOriginal(binding, key, uri, AtomicBoolean(false), resolver) }
                .startsWith("原件保存未确认"))
            assertEquals(1, documents.opens.get())
            // Fixture-only corruption: a checkpoint-owned terminal receipt cannot be rewritten via save().
            val storedBlocks = fixture.database.readableDatabase.rawQuery(
                "SELECT blocks FROM messages WHERE id=? AND conversation_id=? AND run_id=?",
                arrayOf(messageId.value, conversation.id.value, run.value)).use {
                    check(it.moveToFirst()); org.json.JSONArray(it.getString(0))
                }
            storedBlocks.getJSONObject(0).put("arguments", "{\"action\":\"get_text\"}")
            assertEquals(1, fixture.database.writableDatabase.update("messages", android.content.ContentValues().apply {
                put("blocks", storedBlocks.toString())
            }, "id=? AND conversation_id=? AND run_id=?", arrayOf(messageId.value, conversation.id.value, run.value)))
            assertNull(loadCardOriginal(controller, binding, key, AtomicBoolean(true)))
            assertTrue(dataOperation { controller.saveGenerationMediaOriginal(binding, key, uri, AtomicBoolean(true), resolver) }
                .startsWith("原件保存未确认"))
            assertEquals(1, documents.opens.get())
            assertEquals(0, fixture.opens.get())
            assertTrue(dataOperation {
                controller.mutateGenerationOwners(setOf(conversation.id.value), completed = {}) {
                    fixture.database.delete(conversation.id)
                }
            }.startsWith("删除已完成"))
            val screenshotBinding = app.daiyu.android.media.BrowserScreenshotStore.Binding(binding.conversationId,
                binding.assistantMessageId, binding.runId, binding.toolCallId, binding.toolName, call.arguments)
            assertTrue(runCatching {
                app.daiyu.android.media.BrowserScreenshotStore(fixture.application.filesDir, fixture.application.cacheDir)
                    .read(screenshotBinding, receipt)
            }.isFailure)
        } finally { saved.delete() }
    }

    private fun loadCardOriginal(target: ChatController, binding: GenerationAttemptBinding, key: String,
        active: AtomicBoolean): GenerationMediaOriginal? {
        val done = CountDownLatch(1)
        val result = AtomicReference<GenerationMediaOriginal?>()
        controlMain {
            assertTrue(target.loadGenerationMediaOriginal(binding, key, active) {
                result.set(it); done.countDown()
            })
        }
        assertTrue("original read completion", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun loadOutputs(id: ConversationId): List<ChatController.GenerationAssetDisplay>? {
        val done = CountDownLatch(1)
        val result = AtomicReference<List<ChatController.GenerationAssetDisplay>?>()
        controlMain {
            assertTrue(controller.loadGenerationOutputs(id) {
                assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                result.set(it); done.countDown()
            })
        }
        assertTrue("history reader completion", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun assertReadable(output: ExistingOutput) {
        val display = checkNotNull(loadOutputs(output.conversation.id)).single()
        assertEquals(output.png.size.toLong(), display.metadata.bytes)
        assertEquals(GenerationKind.IMAGE, display.metadata.kind)
        assertNotNull(display.thumbnail)
        assertSame(output.resources, field(controller, "generationResources"))
        output.resources.openOutputs(output.owner).use { reader ->
            assertTrue(output.png.contentEquals(checkNotNull(reader.openOriginal(output.attempt, 0)).use { it.readBytes() }))
        } // Closing a sibling reader must not seal the cached reader or a writer.
    }

    private fun dataOperation(target: ChatController = controller, start: () -> Unit): String {
        controlMain { target.consumeDataNotice(); start() }
        awaitControl("data operation completed") { !target.dataBusy && target.dataNotice != null }
        return checkNotNull(target.dataNotice)
    }

    private fun historicalOutputs(target: ChatController, id: ConversationId): List<ChatController.HistoricalGenerationAssetDisplay> {
        val done = CountDownLatch(1)
        val result = AtomicReference<List<ChatController.HistoricalGenerationAssetDisplay>?>()
        controlMain { assertTrue(target.loadHistoricalGenerationOutputs(id) { result.set(it); done.countDown() }) }
        assertTrue("historical original read completion", done.await(10, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }

    private fun addHistoricalChildren(parent: ExistingOutput): app.daiyu.android.domain.delegation.ChildTaskCommand {
        val b = parent.owner.binding
        val token = UUID.randomUUID().toString()
        val source = app.daiyu.android.domain.delegation.ChildTaskRecord(
            app.daiyu.android.domain.delegation.ChildRunOwner(b.conversationId, b.runId, "group-$token", 1,
                "source-$token", "assistant-source-$token"), b.assistantMessageId, "original-parent-owner", 0,
            "historical-profile", "source prompt", app.daiyu.android.domain.delegation.ChildTaskStatus.COMPLETED, "source result")
        val command = app.daiyu.android.domain.delegation.ChildTaskCommand("command-$token", source,
            app.daiyu.android.domain.delegation.ChildTaskCommandKind.APPEND, "historical-profile", "follow-up")
        val successor = app.daiyu.android.domain.delegation.ChildTaskRecord(
            app.daiyu.android.domain.delegation.ChildRunOwner(b.conversationId, b.runId, "successor-group-$token", 2,
                "successor-$token", "assistant-successor-$token"), b.assistantMessageId, source.parentOwnerId, 0,
            command.profileId, command.instruction, app.daiyu.android.domain.delegation.ChildTaskStatus.RUNNING,
            sourceTaskId = source.owner.childRunId, commandKind = command.kind)
        fun row(t: app.daiyu.android.domain.delegation.ChildTaskRecord) = org.json.JSONObject()
            .put("task_id", t.owner.childRunId).put("conversation_id", t.owner.conversationId)
            .put("parent_run_id", t.owner.parentRunId).put("parent_message_id", t.parentMessageId).put("parent_owner_id", t.parentOwnerId)
            .put("task_group_id", t.owner.taskGroupId).put("generation", t.owner.generation).put("task_index", t.index)
            .put("assistant_message_id", t.owner.assistantMessageId).put("profile_id", t.profileId).put("prompt", t.prompt)
            .put("status", t.status.name).put("text", t.text).put("detail", t.detail ?: org.json.JSONObject.NULL)
            .put("control_revision", t.revision).put("source_task_id", t.sourceTaskId ?: org.json.JSONObject.NULL)
            .put("command_kind", t.commandKind?.name ?: org.json.JSONObject.NULL)
        val db = fixture.database.writableDatabase
        app.daiyu.android.backup.BackupHistoryRows.insert(db, "child_tasks", row(source.copy(revision = 1)))
        app.daiyu.android.backup.BackupHistoryRows.insert(db, "child_tasks", row(successor))
        app.daiyu.android.backup.BackupHistoryRows.insert(db, "child_task_commands", org.json.JSONObject()
            .put("call_id", command.callId).put("fingerprint", command.fingerprint()).put("source_id", source.owner.childRunId)
            .put("successor_id", successor.owner.childRunId).put("expected_revision", 0).put("acknowledged", 0))
        app.daiyu.android.backup.BackupHistoryRows.insert(db, "child_request_usage", org.json.JSONObject()
            .put("task_id", successor.owner.childRunId).put("request_id", "request-$token").put("request_owner", "historical-request-owner")
            .put("attempt_id", "attempt-$token").put("operation", "OPEN").put("checkpoint_revision", 1)
            .put("input_tokens", 7).put("output_tokens", 3).put("cached_tokens", org.json.JSONObject.NULL))
        return command
    }

    /** Real Controller construction/acceptance worker -> complete service -> owner-fenced
     * generated originals -> Controller import -> independent backup-only display/save paths.
     * Both image and encoded synthetic video traverse production inspectors and byte stores.
     */
    fun testProductionCompleteExportImportOriginalsChildrenTimeDuplicateAndBadBlobRollbackWithoutKeyReads() {
        val image = existingOutput()
        val video = existingOutput(GenerationKind.VIDEO)
        val command = addHistoricalChildren(image)
        val sourceText = fixture.database.exportBackup()
        val originalRows = listOf(image, video).associate { it.conversation.id to fixture.database.messages(it.conversation.id) }
        val retained = field(controller, "generationResources")
        val opensBefore = credentialOpens.get()
        forbidGenerationCredentials = true
        val file = File(fixture.application.cacheDir, "production-complete-${UUID.randomUUID()}.json")
        val saved = File(fixture.application.cacheDir, "historical-original-${UUID.randomUUID()}")
        val targetFiles = File(fixture.application.cacheDir, "backup-target-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = targetFiles
        }
        val target = RunControlFixture(isolated)
        fun count(table: String) = target.database.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        fun payload(text: String) = org.json.JSONObject(org.json.JSONObject(text).getString("payload"))
        try {
            awaitControl("target ready") { target.controller.ready }
            assertTrue(dataOperation { controller.exportUserBackup(Uri.fromFile(file), true) }.startsWith("完整中立备份已导出"))
            val text = file.readText()
            val data = payload(text)
            assertEquals(2, data.getJSONObject("generation_history").getJSONArray("outputs").length())
            assertEquals(2, data.getJSONObject("child_task_history").getJSONArray("tasks").length())
            assertEquals(1, data.getJSONObject("child_task_history").getJSONArray("commands").length())
            assertEquals(1, data.getJSONObject("child_task_history").getJSONArray("usage").length())
            assertEquals(sourceText, fixture.database.exportBackup())
            assertSame(retained, field(controller, "generationResources"))
            val readers = field(controller, "generationOutputReaders") as Map<*, *>
            val owners = field(controller, "generationOutputConversations") as Map<*, *>
            assertEquals(2, readers.size); assertEquals(readers.keys, owners.keys)
            assertNotNull(checkNotNull(loadOutputs(image.conversation.id)).single().thumbnail)
            assertEquals(GenerationKind.VIDEO, checkNotNull(loadOutputs(video.conversation.id)).single().metadata.kind)
            assertEquals(opensBefore, credentialOpens.get())

            // A bad input original fails before visible import; then a final-receipt trigger
            // demonstrates real SQL rollback after text, history and commands were inserted.
            val broken = payload(text)
            broken.getJSONArray("blobs").getJSONObject(0).put("data", "AAAA")
            val raw = broken.toString()
            file.writeText(org.json.JSONObject(text).put("payload", raw)
                .put("sha256", app.daiyu.android.context.PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString())
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("导入失败"))
            assertEquals(0, count("messages")); assertEquals(0, count("neutral_imports"))
            file.writeText(text)
            target.database.writableDatabase.execSQL("CREATE TRIGGER controller_bad_receipt AFTER INSERT ON neutral_imports " +
                "BEGIN UPDATE generation_attempts SET outcome=X'01'; END")
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("导入失败"))
            for (table in listOf("messages", "generation_attempts", "child_tasks", "child_task_commands", "child_request_usage", "neutral_imports"))
                assertEquals(table, 0, count(table))
            assertFalse(app.daiyu.android.backup.BackupHistoryRows.exists(
                target.database.readableDatabase, "neutral_backup_history"))
            target.database.writableDatabase.execSQL("DROP TRIGGER controller_bad_receipt")
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("已导入"))
            assertEquals(23, target.database.readableDatabase.version)
            assertEquals(0, count("generation_outputs")); assertEquals(0, count("runtime_checkpoints"))
            assertEquals(2, count("generation_attempts")); assertEquals(1, count("child_task_commands")); assertEquals(1, count("child_request_usage"))
            originalRows.forEach { (conversation, rows) ->
                assertEquals(rows.map { it.second }, target.database.messages(conversation).map { it.second })
                assertTrue(target.database.messages(conversation).all { it.second.createdAt == 1_700_000_000_123L })
            }
            val receipt = SQLiteChildTaskStore(target.database).commandReceipt(command) as
                app.daiyu.android.domain.delegation.ChildTaskCommandReceipt.Accepted
            assertEquals(app.daiyu.android.domain.delegation.ChildTaskStatus.UNKNOWN, receipt.task.status)
            assertEquals("original-parent-owner", receipt.task.parentOwnerId)
            listOf(image, video).forEach { original ->
                val display = historicalOutputs(target.controller, original.conversation.id).single()
                val historyBinding = GenerationAttemptBinding(display.history.conversationId, display.history.assistantMessageId,
                    display.history.runId, display.history.toolCallId, display.history.source.toolName)
                checkNotNull(loadCardOriginal(target.controller, historyBinding,
                    "history:${display.history.identity.itemId}:${display.history.outputIndex}", AtomicBoolean(true))).use {
                    assertTrue(original.png.contentEquals(it.stream.readBytes()))
                }
                assertNull(loadCardOriginal(target.controller, historyBinding,
                    "live:${display.history.identity.itemId}:${display.history.outputIndex}", AtomicBoolean(true)))
                assertTrue(display.history.historicalOnly)
                assertEquals(original.png.size.toLong(), display.history.bytes)
                if (original === image) assertNotNull(display.thumbnail) else assertEquals(GenerationKind.VIDEO, display.history.kind)
                assertTrue(dataOperation(target.controller) {
                    target.controller.saveHistoricalGenerationOriginal(display.history, Uri.fromFile(saved))
                }.startsWith("历史生成原件已保存"))
                assertTrue(original.png.contentEquals(saved.readBytes()))
            }
            assertNull(field(target.controller, "generationResources"))
            assertTrue((field(target.controller, "generationOutputReaders") as Map<*, *>).isEmpty())
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("此完整备份已导入过"))
            assertTrue(dataOperation(target.controller) { target.controller.exportUserBackup(Uri.fromFile(saved), true) }.startsWith("完整中立备份已导出"))
            val reexported = payload(saved.readText())
            assertEquals(data.getJSONObject("generation_history").toString(), reexported.getJSONObject("generation_history").toString())
            assertEquals(data.getJSONObject("child_task_history").toString(), reexported.getJSONObject("child_task_history").toString())
            val blob = File(target.application.filesDir, "neutral-context-blobs/${displayHash(image)}")
            assertTrue(blob.isFile)
            val bad = byteArrayOf(9, 8, 7)
            blob.writeBytes(bad)
            val beforeFailure = target.database.exportBackup()
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("导入失败"))
            assertTrue(bad.contentEquals(blob.readBytes()))
            assertEquals(beforeFailure, target.database.exportBackup())
            assertEquals(1, count("neutral_imports")); assertEquals(0, count("generation_outputs"))
            assertEquals(0, fixture.opens.get()); assertEquals(0, target.opens.get())
            assertEquals(opensBefore, credentialOpens.get())
        } finally {
            forbidGenerationCredentials = false
            file.delete(); saved.delete(); target.close(); targetFiles.deleteRecursively()
        }
    }

    private fun displayHash(output: ExistingOutput) = app.daiyu.android.context.PrivateBlobs.digest(output.png)

    private fun branchThroughProduction(target: ChatController, conversation: ConversationId): Conversation {
        val captured = CountDownLatch(1); val snapshot = AtomicReference<MessageActionSnapshot?>()
        controlMain { assertTrue(target.loadMessageActionSnapshot(conversation) { snapshot.set(it); captured.countDown() }) }
        assertTrue(captured.await(10, TimeUnit.SECONDS))
        val done = CountDownLatch(1); val result = AtomicReference<MessageActionResult>()
        controlMain { assertTrue(target.messageAction(MessageAction.BRANCH, checkNotNull(snapshot.get()).targets.values.first()) {
            result.set(it); done.countDown()
        }) }
        assertTrue(done.await(15, TimeUnit.SECONDS)); assertTrue(result.get().toString(), result.get() is MessageActionResult.Branch)
        return (result.get() as MessageActionResult.Branch).conversation
    }
    private fun staticThroughProduction(target: ChatController, conversation: ConversationId): ChatController.StaticGenerationAssetDisplay {
        val done = CountDownLatch(1); val result = AtomicReference<List<ChatController.StaticGenerationAssetDisplay>?>()
        controlMain { assertTrue(target.loadStaticGenerationOutputs(conversation) { result.set(it); done.countDown() }) }
        assertTrue(done.await(15, TimeUnit.SECONDS)); return checkNotNull(result.get()).single()
    }

    fun testProductionLiveImageVideoAndImportedHistoryBranchUseSafeReadersWithoutNewAttempts() {
        val image = existingOutput(); val video = existingOutput(GenerationKind.VIDEO)
        fun count(database: ConversationDatabase, table: String) = database.readableDatabase.rawQuery("SELECT count(*) FROM $table", null)
            .use { check(it.moveToFirst()); it.getInt(0) }
        val credentialCount = credentialOpens.get(); forbidGenerationCredentials = true
        val imageCopy = branchThroughProduction(controller, image.conversation.id)
        val videoCopy = branchThroughProduction(controller, video.conversation.id)
        val imageDisplay = staticThroughProduction(controller, imageCopy.id)
        val videoDisplay = staticThroughProduction(controller, videoCopy.id)
        assertEquals(image.owner.binding.operationId, imageDisplay.output.source.operationId)
        assertEquals(video.owner.binding.operationId, videoDisplay.output.source.operationId)
        assertNotNull(imageDisplay.thumbnail); assertEquals(GenerationKind.VIDEO, videoDisplay.output.source.kind)
        assertEquals(2, count(fixture.database, "generation_attempts")); assertEquals(2, count(fixture.database, "generation_outputs"))
        assertEquals(credentialCount, credentialOpens.get())
        val file = File(fixture.application.cacheDir, "p37-production-${UUID.randomUUID()}.json")
        val targetFiles = File(fixture.application.cacheDir, "p37-target-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : android.content.ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = targetFiles }
        val target = RunControlFixture(isolated)
        try {
            awaitControl("P37 target ready") { target.controller.ready }
            // Metadata is minted by the real settings codec with in-memory synthetic credential
            // callbacks. Neither the branch nor its history reader opens any credential vault.
            val memory = mutableMapOf<String, String>()
            GenerationSettingsStore(target.application.getSharedPreferences(GenerationSettingsStore.METADATA_NAMESPACE, 0),
                { memory.getValue(it) }, { id, key -> memory[id] = key }).save(GenerationSettingsValues(listOf(
                    GenerationProfileSettings(label = "offline destination", kind = GenerationKind.IMAGE,
                        protocol = GenerationProtocol.OPENAI_IMAGES_JSON, endpoint = "https://example.invalid/images/generations",
                        model = "offline", apiKey = "synthetic-memory-only")), 0, false, emptySet()), null)
            assertTrue(dataOperation { controller.exportUserBackup(Uri.fromFile(file), true) }.startsWith("完整中立备份已导出"))
            assertEquals(5, org.json.JSONObject(file.readText()).getInt("version"))
            assertTrue(dataOperation(target.controller) { target.controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("已导入"))
            val historicalCopy = branchThroughProduction(target.controller, image.conversation.id)
            val historicalDisplay = staticThroughProduction(target.controller, historicalCopy.id)
            assertEquals(imageDisplay.output.source, historicalDisplay.output.source)
            val again = branchThroughProduction(target.controller, historicalCopy.id)
            assertEquals(historicalDisplay.output.source, staticThroughProduction(target.controller, again.id).output.source)
            assertEquals(2, count(target.database, "generation_attempts")); assertEquals(0, count(target.database, "generation_outputs"))
            assertEquals(4, app.daiyu.android.backup.StaticGenerationCodec.read(target.database.readableDatabase).size)
            assertEquals(credentialCount, credentialOpens.get()); assertEquals(0, fixture.opens.get()); assertEquals(0, target.opens.get())
        } finally { target.close(); file.delete(); targetFiles.deleteRecursively() }
    }

    fun testCompleteBackupUsesManifestScopeNotSelectedRowsAndRejectsAlteredRegisteredMapping() {
        val output = existingOutput()
        val preferences = fixture.application.getSharedPreferences(GenerationSettingsStore.METADATA_NAMESPACE, 0)
        val originalManifest = checkNotNull(preferences.getString(GenerationSettingsStore.KEY, null))
        val file = File(fixture.application.cacheDir, "scope-backup-${UUID.randomUUID()}.json")
        val readsBefore = credentialOpens.get()
        forbidGenerationCredentials = true
        try {
            assertTrue(dataOperation { controller.exportConversationBackup(Uri.fromFile(file), output.conversation.id) }
                .startsWith("单会话中立备份已导出"))
            val readers = field(controller, "generationOutputReaders") as Map<*, *>
            val conversations = field(controller, "generationOutputConversations") as Map<*, *>
            assertEquals(readers.keys, conversations.keys)
            val reader = readers.values.single() as ControllerGenerationOutputReader
            val registered = checkNotNull(SQLiteGenerationOutputStore(output.resources::database, output.owner, output.assets)
                .find(output.attempt, 0))
            assertTrue(output.png.contentEquals(reader.readBackup(registered)))
            var rejected = false
            try { reader.readBackup(registered.copy(width = registered.width + 1)) } catch (_: Exception) { rejected = true }
            assertTrue("selected scalar row is never authority", rejected)
            val changed = org.json.JSONObject(originalManifest).put("scope", "generation_" + "f".repeat(32))
            check(preferences.edit().putString(GenerationSettingsStore.KEY, changed.toString()).commit())
            assertTrue(dataOperation { controller.exportUserBackup(Uri.fromFile(file), true) }.startsWith("导出失败"))
            assertEquals(output.owner.accountScope, registered.accountScope)
            // Missing metadata must not adopt the live output row's scope either.
            check(preferences.edit().remove(GenerationSettingsStore.KEY).commit())
            assertTrue(dataOperation { controller.exportUserBackup(Uri.fromFile(file), true) }.startsWith("导出失败"))
            assertSame(output.resources, field(controller, "generationResources"))
            assertEquals(readsBefore, credentialOpens.get()); assertEquals(0, fixture.opens.get())
        } finally {
            check(preferences.edit().putString(GenerationSettingsStore.KEY, originalManifest).commit())
            forbidGenerationCredentials = false; file.delete()
        }
    }

    fun testCompleteExportHoldsDataAcceptanceNotControllerMonitorWhileWaitingForSharedOwnerGate() {
        val output = existingOutput()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val holder = Thread {
            try {
                output.resources.createFence(output.owner).admit {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        val file = File(fixture.application.cacheDir, "blocked-backup-${UUID.randomUUID()}.json")
        val worker = (field(controller, "executor") as java.util.concurrent.ExecutorService)
            .submit<Thread> { Thread.currentThread() }.get(5, TimeUnit.SECONDS)
        holder.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controlMain { controller.consumeDataNotice(); controller.exportUserBackup(Uri.fromFile(file), true) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun atGate() = worker.state == Thread.State.BLOCKED && worker.stackTrace.any {
                it.className == "app.daiyu.android.media.GenerationAssetPublicationGate" && it.methodName.startsWith("admit")
            }
            while (!atGate() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("real export worker must reach retained owner's gate", atGate())
            val monitorAvailable = CountDownLatch(1)
            val busy = AtomicBoolean()
            val probe = Thread { synchronized(controller) { busy.set(controller.dataBusy); monitorAvailable.countDown() } }
            probe.start()
            assertTrue("gate wait cannot retain Controller monitor", monitorAvailable.await(2, TimeUnit.SECONDS))
            assertTrue("acceptance spans prefetch and final snapshot", busy.get())
            controlMain {
                var rejected = false
                try { controller.mutateGenerationOwners(setOf(output.conversation.id.value), completed = {}) {} }
                catch (_: IllegalStateException) { rejected = true }
                assertTrue("delete cannot be admitted during original prefetch", rejected)
            }
        } finally {
            release.countDown(); holder.join(5000)
        }
        try {
            assertFalse(holder.isAlive)
            assertNull(failure.get())
            awaitControl("blocked backup completed") { !controller.dataBusy && controller.dataNotice != null }
            assertTrue(checkNotNull(controller.dataNotice).startsWith("完整中立备份已导出"))
            assertEquals(0, fixture.opens.get())
        } finally { file.delete() }
    }

    fun testExistingOutputSurvivesFailedDuplicateAndUnrelatedAppendImports() {
        val output = existingOutput()
        assertReadable(output)
        val oldRows = fixture.database.messages(output.conversation.id)
        val cachedReaders = (field(controller, "generationOutputReaders") as Map<*, *>).values.toList()
        val file = File(fixture.application.cacheDir, "generation-import-${UUID.randomUUID()}.json")
        val source = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("source ready") { source.controller.ready }
            // Failure before parsing; the old cached and uncached owner fences remain usable.
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("导入失败"))
            assertReadable(output)

            // Actual transaction rollback AFTER inserting an unrelated conversation: global
            // draft conflict must not seal old assets or leave the new conversation behind.
            val appended = source.database.create("default")
            fixture.database.draft("keep existing draft")
            source.database.draft("conflicting incoming draft")
            file.writeText(source.database.exportBackup())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), false) }.startsWith("导入失败"))
            assertFalse(fixture.database.conversations().any { it.id == appended.id })
            assertEquals("keep existing draft", fixture.database.draft())
            assertReadable(output)

            // A complete append, then that package's real idempotent duplicate path.
            source.database.draft("")
            file.writeText(NeutralBackupService(source.application, source.database).exportPackage())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("已导入"))
            assertTrue(fixture.database.conversations().any { it.id == appended.id })
            assertReadable(output)
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("此完整备份已导入过"))
            assertReadable(output)

            // The text importer's identity collision is a rollback, not owner replacement.
            file.writeText(source.database.exportBackup())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), false) }.startsWith("导入失败"))
            assertReadable(output)
            assertEquals(oldRows, fixture.database.messages(output.conversation.id))
            val retainedReaders = (field(controller, "generationOutputReaders") as Map<*, *>).values.toList()
            assertEquals(cachedReaders.size, retainedReaders.size)
            cachedReaders.zip(retainedReaders).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(0, fixture.opens.get())
        } finally { file.delete(); source.close() }
    }

    fun testHistoryAndSuccessiveRealSessionsKeepOneResourceAndStopDoesNotSealHistory() {
        val output = existingOutput()
        repeat(2) {
            fixture.database.draft(editor.text)
            val model = fixture.session(held = true)
            val entry = prepare()
            confirm(entry)
            assertTrue(model.entered.await(10, TimeUnit.SECONDS))
            assertSame(output.resources, field(checkNotNull(entry.session), "resources"))
            controlMain {
                assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) {})
                controller.stop()
            }
            model.release.countDown()
            awaitControl("session physically retired") {
                controller.currentGenerationEntry() == null && controller.active == null && field(controller, "resources") == null
            }
            assertReadable(output)
        }
    }

    fun testConversationDeleteWaitsForFinalRegistrationValidatorAndSealsFutureFences() {
        val output = existingOutput()
        val other = existingOutput()
        assertSame(output.resources, other.resources)
        assertReadable(output)
        val info = output.assets.inspect(GenerationKind.IMAGE, output.png, "image/png")
        val pending = output.assets.commit(output.attempt.identity, 1, GenerationKind.IMAGE, output.png, info)
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val validations = AtomicInteger()
        // register() validates the file first, then re-enters the same owner gate for its
        // final SQLite transaction. Hold that second validator, not a fake output store.
        val racingFence = output.resources.createFence(output.owner) { owner ->
            val valid = output.resources.validateOwner(owner)
            if (validations.incrementAndGet() == 2) {
                validating.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
            valid
        }
        val racingAssets = output.resources.assets(racingFence)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try { SQLiteGenerationOutputStore(output.resources::database, output.owner, racingAssets)
                .register(output.attempt, pending) }
            catch (problem: Throwable) { failure.set(problem) }
        }
        val mutation = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val success = AtomicBoolean()
        writer.start()
        try {
            assertTrue(validating.await(10, TimeUnit.SECONDS))
            controlMain {
                controller.mutateGenerationOwners(setOf(output.conversation.id.value), completed = {
                    success.set(it); completed.countDown()
                }) {
                    assertNotSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    assertFalse(output.resources.database().inTransaction())
                    mutation.countDown()
                    fixture.database.delete(output.conversation.id)
                }
            } // This must return without waiting for the held publication gate.
            assertFalse("deletion ran before publication barrier", mutation.await(100, TimeUnit.MILLISECONDS))
            // Also prove the control monitor is available while the worker waits on the gate.
            controlMain { assertTrue(controller.dataBusy) }
        } finally { release.countDown(); writer.join(10000) }
        assertFalse(writer.isAlive)
        assertNotNull("sealed final registration must fail", failure.get())
        assertTrue(completed.await(10, TimeUnit.SECONDS)); assertTrue(success.get())
        assertEquals(2, validations.get())
        assertFalse(fixture.database.conversations().any { it.id == output.conversation.id })
        assertNull(loadOutputs(output.conversation.id))
        assertSealed(output.resources, output.owner)
        assertReadable(other) // A conversation seal is not an account seal.
        assertSame(output.resources, field(controller, "generationResources"))
    }

    fun testDeleteAllWaitsForPublicationSealsOldIdsAndAllowsNewConversations() {
        val output = existingOutput()
        val published = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val mutation = CountDownLatch(1)
        val success = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val fence = output.resources.createFence(output.owner)
        val writer = Thread {
            try {
                fence.admit {
                    // Hold a real SQL publication transaction through its commit. No file IO
                    // under the gate: this is the same gate -> DB order as final registration.
                    val db = output.resources.database()
                    db.beginTransaction()
                    try {
                        db.execSQL("UPDATE generation_outputs SET width=width WHERE operation_id=?",
                            arrayOf(output.attempt.identity.operationId))
                        published.countDown(); check(release.await(10, TimeUnit.SECONDS))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }
        writer.start()
        try {
            assertTrue(published.await(10, TimeUnit.SECONDS))
            controlMain {
                controller.mutateGenerationOwners(completed = { success.set(it); completed.countDown() }) {
                    mutation.countDown()
                    app.daiyu.android.ui.RetainedConversationData(fixture.database,
                        app.daiyu.android.context.ContextService(fixture.application, fixture.database)).deleteAll()
                }
            }
            assertFalse(mutation.await(100, TimeUnit.MILLISECONDS))
            controlMain { assertTrue(controller.dataBusy) }
        } finally { release.countDown(); writer.join(10000) }
        assertFalse(writer.isAlive); assertNull(failure.get())
        assertTrue(completed.await(10, TimeUnit.SECONDS)); assertTrue(success.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertSealed(output.resources, output.owner)
        val fresh = existingOutput()
        assertNotSame(output.conversation.id, fresh.conversation.id)
        assertSame(output.resources, fresh.resources)
        assertReadable(fresh)
        fixture.database.draft(editor.text)
        val next = prepare()
        controlMain { controller.abandonGeneration(next) }
        assertSealed(output.resources, output.owner)
    }

    fun testFailedDeleteRemainsSealedAndImportCannotResurrectIt() {
        val output = existingOutput()
        assertReadable(output)
        val succeeded = AtomicBoolean(true)
        dataOperation {
            controller.mutateGenerationOwners(setOf(output.conversation.id.value), completed = { succeeded.set(it) }) {
                error("synthetic delete failure before transaction")
            }
        }
        assertFalse(succeeded.get())
        assertTrue(fixture.database.conversations().any { it.id == output.conversation.id })
        assertSealed(output.resources, output.owner)
        assertNull(loadOutputs(output.conversation.id))
        // Even successful no-op data work marked as import must not clear a deletion seal.
        dataOperation { controller.dataTask(importing = true) { "no-op append" } }
        assertSame(output.resources, field(controller, "generationResources"))
        assertSealed(output.resources, output.owner)
        assertNull(loadOutputs(output.conversation.id))
    }

    fun testDeletionBeforeFirstGenerationConfigDoesNotRequireSettingsAndCarriesSealForward() {
        val conversation = fixture.database.create("default")
        val success = AtomicBoolean()
        dataOperation {
            controller.mutateGenerationOwners(setOf(conversation.id.value), completed = { success.set(it) }) {
                fixture.database.delete(conversation.id)
            }
        }
        assertTrue(success.get())
        assertNull(field(controller, "generationResources"))
        val output = existingOutput() // the first real resource inherits the earlier deletion
        assertReadable(output)
        assertSealed(output.resources, GenerationAssetOwner(output.owner.accountScope,
            GenerationAttemptBinding(conversation.id.value, "old-assistant", "old-run", "old-call", RunGenerationTools.IMAGE_TOOL)))
    }

    fun testDeleteAllBeforeFirstConfigOnlySealsExistingConversations() {
        val deleted = fixture.database.create("default")
        val succeeded = AtomicBoolean()
        dataOperation {
            controller.mutateGenerationOwners(completed = { succeeded.set(it) }) {
                app.daiyu.android.ui.RetainedConversationData(fixture.database,
                    app.daiyu.android.context.ContextService(fixture.application, fixture.database)).deleteAll()
            }
        }
        assertTrue(succeeded.get())
        assertNull(field(controller, "generationResources"))
        val fresh = existingOutput()
        assertReadable(fresh)
        assertSealed(fresh.resources, GenerationAssetOwner(fresh.owner.accountScope,
            GenerationAttemptBinding(deleted.id.value, "old-assistant", "old-run", "old-call", RunGenerationTools.IMAGE_TOOL)))
    }

    fun testControllerCloseWaitsForAdmittedHistoryReadBeforeClosingDatabase() {
        val output = existingOutput()
        assertReadable(output)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closeDone = field(controller, "closeDone") as CountDownLatch
        val fence = output.resources.createFence(output.owner)
        val blocker = Thread {
            try { fence.admit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            catch (problem: Throwable) { failure.set(problem) }
        }
        blocker.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            controlMain {
                assertTrue(controller.loadGenerationOutputs(output.conversation.id) { readDone.countDown() })
                assertEquals(1, field(controller, "generationReaders"))
                controller.close()
            }
            assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
            assertTrue(fixture.database.readableDatabase.isOpen)
        } finally { release.countDown(); blocker.join(10000) }
        assertFalse(blocker.isAlive); assertNull(failure.get())
        assertTrue(readDone.await(10, TimeUnit.SECONDS))
        assertTrue(closeDone.await(10, TimeUnit.SECONDS))
        assertSame(output.resources, field(controller, "generationResources"))
    }

    private fun assertSealed(resources: ControllerGenerationResources, owner: GenerationAssetOwner) {
        val validated = AtomicBoolean()
        try {
            resources.createFence(owner) { validated.set(true); true }.admit { fail("sealed owner admitted") }
            fail("expected cancellation")
        } catch (failure: GenerationAssetException) {
            assertEquals(GenerationAssetFailure.CANCELLED, failure.failure)
        }
        assertFalse("seal must reject before owner validation", validated.get())
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
