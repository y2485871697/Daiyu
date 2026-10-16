package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.RunStatus
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

    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") { controller.ready }
        store = GenerationSettingsStore(fixture.application)
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

    private fun saveSettings(): GenerationSettingsSnapshot = store.save(GenerationSettingsValues(
        listOf(GenerationProfileSettings(label = "explicit image profile", kind = GenerationKind.IMAGE,
            protocol = GenerationProtocol.OPENAI_IMAGES_JSON,
            endpoint = "https://generation.invalid/images/generations", model = "explicit-image-model",
            apiKey = "synthetic-generation-test-key")), maxPolls = 0,
        downloadsEnabled = false, downloadHosts = emptySet()), null)

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
        assertTrue(ControllerGenerationResources.DOWNLOAD_STATUS.contains("未启用"))
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

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
