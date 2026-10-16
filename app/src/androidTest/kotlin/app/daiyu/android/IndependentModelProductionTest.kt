package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.pet.PetModelSelection
import app.daiyu.android.storage.*
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real encrypted independent configuration and SQLite authorization; no model/asset IO. */
@Suppress("DEPRECATION")
class IndependentModelProductionTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: ConversationDatabase
    override fun setUp() {
        super.setUp()
        val id = UUID.randomUUID().toString()
        root = File(instrumentation.targetContext.cacheDir, "independent-$id").apply { check(mkdirs()) }
        context = object : ContextWrapper(testContext(instrumentation.targetContext, "independent-$id-")) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
        db = ConversationDatabase(context)
    }
    override fun tearDown() {
        try {
            db.close(); context.deleteDatabase("conversations.db"); root.deleteRecursively()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf("model-settings", "independent-pet-model", "independent-vision-model").forEach { name ->
                val raw = context.getSharedPreferences(name, 0)
                raw.getString("key-alias", null)?.let { keys.deleteEntry(it) }
                raw.edit().clear().commit()
            }
            context.getSharedPreferences("independent-model-bindings", 0).edit().clear().commit()
        } finally { super.tearDown() }
    }
    private fun reject(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue("must fail closed", rejected)
    }

    fun testIndependentRealBindingsPersistCapabilitiesAndRejectAllIdMismatches() {
        val main = ModelSettingsStore(context, "main-test-${UUID.randomUUID()}")
        main.save(ModelSettings("https://main.invalid", "main-api", "main-key", false))
        val assistants = SqliteAssistantStore(db)
        assistants.save(AssistantProfile("pet-assistant", "Pet", ""))
        val store = IndependentModelSettings(context)
        store.save(IndependentModelSettings.Slot.PET,
            ModelSettings("https://pet.invalid", "pet-api", "pet-secret", false), "pet-assistant", true, null)
        store.save(IndependentModelSettings.Slot.VISION,
            ModelSettings("https://vision.invalid", "vision-api", "vision-secret", false), null, true, true)
        val pet = store.record(IndependentModelSettings.Slot.PET)
        val vision = store.record(IndependentModelSettings.Slot.VISION)
        assertFalse(pet.providerId == vision.providerId)
        assertFalse(pet.localModelId == vision.localModelId)
        val selection = PetModelSelection("pet-assistant", pet.providerId, pet.localModelId)
        val resolved = checkNotNull(store.resolvePet(selection, assistants))
        assertEquals(selection, resolved.selection)
        assertEquals("pet-api", resolved.settings.model)
        assertEquals("pet-secret", resolved.settings.apiKey)
        assertNull(store.resolvePet(selection.copy(assistantId = "default"), assistants))
        assertNull(store.resolvePet(selection.copy(providerId = vision.providerId), assistants))
        assertNull(store.resolvePet(selection.copy(modelId = vision.localModelId), assistants))
        val reopened = IndependentModelSettings(context)
        val frozen = reopened.freeze(IndependentModelSettings.Slot.VISION)
        val binding = reopened.visionSelection().resolve(listOf(reopened.visionCandidate(frozen)))
        assertEquals("vision-api", binding.snapshot.model)
        assertEquals("vision-secret", binding.snapshot.asModelSettings().apiKey)
        assertEquals(true, binding.snapshot.imageInputSupported)
        val generation = reopened.generation()
        var revoked = false
        reopened.onChanging = { revoked = true; assertFalse(reopened.isCurrent(generation)) }
        reopened.save(IndependentModelSettings.Slot.VISION, frozen.provider.asModelSettings(), null, true, null)
        assertTrue(revoked); assertFalse(reopened.isCurrent(generation))
        val unknown = reopened.freeze(IndependentModelSettings.Slot.VISION)
        assertNull(unknown.provider.imageInputSupported)
        reject { reopened.visionSelection().resolve(listOf(reopened.visionCandidate(unknown))) }
        assertEquals("main-api", main.load().model)
        assistants.delete("pet-assistant")
        assertNull(store.resolvePet(selection, assistants))
    }

    fun testInvalidSaveStillRevokesEpochAndFailsClosedForBothSlots() {
        val assistants = SqliteAssistantStore(db)
        assistants.save(AssistantProfile("pet-assistant", "Pet", ""))
        val store = IndependentModelSettings(context)
        val valid = ModelSettings("https://independent.invalid", "api-model", "test-secret", false)
        for (slot in IndependentModelSettings.Slot.values()) {
            store.save(slot, valid, "pet-assistant", true, true)
            val record = store.record(slot)
            val generation = store.generation()
            val resolved = if (slot == IndependentModelSettings.Slot.PET)
                checkNotNull(store.resolvePet(PetModelSelection("pet-assistant", record.providerId, record.localModelId), assistants))
            else null
            var revocations = 0
            val changing = CountDownLatch(1); val continueSave = CountDownLatch(1); val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            store.onChanging = {
                revocations++
                assertFalse(store.isCurrent(generation))
                reject { store.freeze(slot) }
                changing.countDown()
                check(continueSave.await(10, TimeUnit.SECONDS))
            }
            val io = Thread {
                try { store.save(slot, valid.copy(model = ""), "pet-assistant", true, true) }
                catch (problem: Throwable) { failure.set(problem) }
                finally { finished.countDown() }
            }
            io.start()
            try {
                // Invalid input still enters revocation BEFORE validation, not just a catch that
                // disables the binding after the caller already kept its old frozen authority.
                assertTrue(changing.await(3, TimeUnit.SECONDS))
                assertFalse(store.isCurrent(generation))
                resolved?.let { assertFalse(it.isCurrent()) }
            } finally { continueSave.countDown(); io.join(10000) }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(failure.get() is IllegalArgumentException)
            assertEquals(1, revocations)
            assertFalse(store.isCurrent(generation))
            assertFalse(store.record(slot).enabled)
            assertEquals(record.providerId, store.record(slot).providerId)
            assertEquals(record.localModelId, store.record(slot).localModelId)
            assertEquals(valid.model, store.editing(slot).settings.model)
            reject { store.freeze(slot) }
            resolved?.let { assertFalse(it.isCurrent()) }
            // The compensated disabled record must also fail closed after reopening.
            reject { IndependentModelSettings(context).freeze(slot) }
            store.onChanging = null
            store.save(slot, valid, "pet-assistant", true, true)
            assertTrue(store.record(slot).enabled)
            assertEquals(valid.model, store.freeze(slot).provider.model)
            resolved?.let { assertFalse(it.isCurrent()) } // Saving back cannot revive old authority.
        }
    }

    fun testAuthorizationRequiresRawPhysicalRowsBeforeExpansionAndExactSyntheticCapabilities() {
        val conversation = db.create("default")
        val oldRun = RunId("old-source-run")
        val originalImage = MessageBlock.Image(MessageImageReference(
            MessageImageOwner(conversation.id.value, "original-user"), "original-image", "image/png", 4,
            "a".repeat(64), 1, 1))
        val user = Message(MessageId("original-user"), conversation.id, oldRun, Message.USER_ROLE,
            listOf(originalImage), RunStatus.COMPLETED)
        val oldAssistant = Message(MessageId("old-assistant"), conversation.id, oldRun,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        db.send(user, oldAssistant)
        db.save(oldAssistant.copy(status = RunStatus.COMPLETED))
        val run = RunId("current-run")
        val nextUser = Message(MessageId("next-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("input", "next")), RunStatus.COMPLETED)
        val initial = Message(MessageId("current-assistant"), conversation.id, run, Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.RUNNING)
        db.send(nextUser, initial)
        val segmentImage = MessageBlock.Image(MessageImageReference(
            MessageImageOwner(conversation.id.value, "supplement"), "segment-image", "image/png", 4,
            "b".repeat(64), 1, 1))
        val physical = initial.copy(historySegments = listOf(
            MessageHistorySegment(initial.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("answer", "partial"))),
            MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE, listOf(segmentImage))))
        db.save(physical)
        val summary = Message(MessageId("exact-summary"), conversation.id, RunId("summary-run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("summary", "exact frozen summary")), RunStatus.COMPLETED)
        var current = true
        val authorize = ControllerVisionAuthorization(db, initial, listOf(summary)) { current }
        val raw = listOf(summary, user, oldAssistant.copy(status = RunStatus.COMPLETED), nextUser,
            physical.copy(status = RunStatus.COMPLETED))
        val candidate = nextUser.copy(id = MessageId("not-yet-accepted"))
        authorize.preflight(listOf(candidate), candidate)
        reject { authorize.authorize(listOf(candidate)) } // Preflight never becomes persistent authority.
        val observations = authorize.authorize(raw)
        assertEquals(listOf(oldRun, run), observations.map { it.sourceRunId })
        assertEquals(listOf(originalImage.reference, segmentImage.reference), observations.map { it.image })
        // Expanded rows have matching images but no physical raw owner/segment authorization.
        reject { authorize.authorize(MessageHistory.expand(raw)) }
        reject { authorize.authorize(raw.dropLast(1) + physical.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("forged", "different outer row")))) }
        reject { authorize.authorize(listOf(summary.copy(id = MessageId("summary-forged")))) }
        reject { authorize.authorize(listOf(summary.copy(blocks = listOf(originalImage)))) }
        current = false
        reject { authorize.authorize(raw) }
        reject { authorize.authorize(listOf(summary)) } // Even text-only/cache-hit opens recheck authority.
    }

    fun testSettingsInventoryDispatchesActualPetAndVisionRoutes() {
        assertEquals(FeatureRoute.PET_SETTINGS, SettingsCatalog.entry("pet").route)
        assertEquals(FeatureRoute.AUXILIARY_VISION_SETTINGS, SettingsCatalog.entry("vision").route)
    }
}
