package app.daiyu.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProfileSettings
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationSettingsFailure
import app.daiyu.android.model.generation.GenerationSettingsStore
import app.daiyu.android.model.generation.GenerationSettingsValues
import app.daiyu.android.ui.screens.settings.GenerationProfileDraftUi
import app.daiyu.android.ui.screens.settings.GenerationSettingsDraftUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenerationSettingsEditorTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var namespace: String
    private val keys = ConcurrentHashMap<String, String>()
    private val reads = AtomicInteger()
    private val writes = AtomicInteger()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before fun setUp() {
        namespace = "generation-editor-${UUID.randomUUID()}"
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(namespace, Context.MODE_PRIVATE)
        keys.clear(); reads.set(0); writes.set(0)
    }

    private fun store(
        preferences: SharedPreferences = prefs,
        load: (String) -> String = { reads.incrementAndGet(); keys.getValue(it) },
        save: (String, String) -> Unit = { id, key -> writes.incrementAndGet(); keys[id] = key },
    ) = GenerationSettingsStore(preferences, load, save, namespace)

    private fun profile() = GenerationProfileSettings(label = "  raw label  ", kind = GenerationKind.IMAGE,
        protocol = GenerationProtocol.OPENAI_IMAGES_JSON, endpoint = "https://api.example/v1/images/generations",
        model = "  raw model  ", apiKey = "fixture-secret-only")
    private fun values(value: GenerationProfileSettings = profile()) =
        GenerationSettingsValues(listOf(value), 7, false, linkedSetOf("one.example", "two.example"))
    private fun draft() = GenerationSettingsDraftUi(profiles = listOf(GenerationProfileDraftUi(
        label = profile().label, kind = profile().kind, protocol = profile().protocol,
        endpoint = profile().endpoint, model = profile().model, apiKey = profile().apiKey)), maxPolls = "7")
    private fun editor(settingsStore: GenerationSettingsStore = store()) = GenerationSettingsEditor(settingsStore, dispatcher, dispatcher)
    private fun page(settingsEditor: GenerationSettingsEditor = editor(), current: () -> Boolean = { true }) =
        settingsEditor.openPage(current).also { it.start(); scheduler.advanceUntilIdle() }

    @Test fun onlyMissingCreatesBlankDraftAndCorruptNeverBecomesDefaults() {
        val missing = page()
        assertEquals(GenerationSettingsDraftUi(), missing.state.draft)
        assertNull(missing.state.failure)
        assertEquals(0, reads.get()); assertEquals(0, writes.get())
        missing.revoke()

        prefs.edit().putString(GenerationSettingsStore.KEY, "{secret-corrupt-payload").commit()
        val corrupt = page()
        assertNull(corrupt.state.draft)
        assertEquals(GenerationSettingsFailure.CORRUPT, corrupt.state.failure)
        assertFalse(corrupt.state.errorText!!.contains("secret-corrupt-payload"))
        corrupt.save(); scheduler.advanceUntilIdle()
        assertEquals(0, writes.get())
        corrupt.revoke()
    }

    @Test fun missingCredentialPreservesExactRawFieldsIdsRevisionAndWarningUntilRealSave() {
        val saved = store().save(values(), null)
        keys.clear()
        val page = page()
        val draft = page.state.draft!!
        assertEquals(saved.accountScope, draft.accountScope)
        assertEquals(saved.revision, draft.expectedRevision)
        assertEquals(saved.values.profiles.single().id, draft.profiles.single().id)
        assertEquals(profile().endpoint, draft.profiles.single().endpoint)
        assertEquals(profile().label, draft.profiles.single().label)
        assertEquals(profile().model, draft.profiles.single().model)
        assertEquals("one.example\ntwo.example", draft.downloadHosts)
        assertEquals("", draft.profiles.single().apiKey)
        assertEquals(setOf(saved.profiles.single().id), page.state.credentialUnavailableIds)
        assertFalse(page.state.incompleteSave)
        page.edit(draft.copy(profiles = listOf(draft.profiles.single().copy(apiKey = profile().apiKey))))
        assertFalse(page.state.credentialUnavailableIds.isEmpty())
        page.save(); scheduler.advanceUntilIdle()
        assertTrue(page.state.saved)
        assertTrue(page.state.credentialUnavailableIds.isEmpty())
        assertEquals(saved.profiles.single().id, page.state.draft!!.profiles.single().id)
        assertNotEquals(saved.revision, page.state.draft!!.expectedRevision)
        page.revoke()
    }

    @Test fun incompleteLoadPreservesCandidateAndRequiresExplicitRecoveryWithoutInventingRevision() {
        val saved = store().save(values(), null)
        val candidate = saved.values.profiles.single().copy(label = " new raw candidate ", model = "new-model")
        try {
            store(save = { _, _ -> error("private storage exception") }).save(values(candidate), saved.revision)
            fail("save must fail")
        } catch (_: app.daiyu.android.model.generation.GenerationSettingsException) { }
        val persistedCandidate = store().loadForEditing()
        val page = page()
        assertTrue(page.state.incompleteSave)
        assertEquals(persistedCandidate.revision, page.state.draft!!.expectedRevision)
        assertNotEquals(saved.revision, page.state.draft!!.expectedRevision)
        assertEquals(candidate.label, page.state.draft!!.profiles.single().label)
        assertFalse(page.state.draft!!.confirmIncompleteSaveRecovery)
        val repaired = page.state.draft!!.let { it.copy(profiles = listOf(it.profiles.single().copy(apiKey = profile().apiKey))) }
        page.edit(repaired)
        page.save(); scheduler.advanceUntilIdle()
        assertEquals(GenerationSettingsFailure.INCOMPLETE_SAVE, page.state.failure)
        assertTrue(page.state.incompleteSave)
        page.edit(repaired.copy(confirmIncompleteSaveRecovery = true))
        page.save(); scheduler.advanceUntilIdle()
        assertTrue(page.state.saved)
        assertFalse(page.state.incompleteSave)
        assertFalse(page.state.draft!!.confirmIncompleteSaveRecovery)
        assertEquals(saved.accountScope, page.state.draft!!.accountScope)
        page.revoke()
    }

    @Test fun observableStateAdoptsOnlyReadbackAndQueuedRevokedPageCannotSaveOrBorrowNewOwner() {
        val owner = AtomicBoolean(true)
        val editor = editor()
        val old = page(editor, owner::get)
        old.edit(draft())
        var stateChanges = 0
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        observer.observeReads(Unit, { _: Unit -> stateChanges++ }) { old.state }
        try {
            old.save()
            Snapshot.sendApplyNotifications()
            assertTrue(old.state.saving)
            assertNull(old.state.draft!!.expectedRevision)
            assertTrue(prefs.all.isEmpty()) // work still queued; no optimistic success
            owner.set(false)
            old.revoke()
            owner.set(true) // A -> B -> A cannot revive the old Page
            val replacement = editor.openPage(owner::get)
            replacement.start()
            scheduler.advanceUntilIdle()
            assertTrue(prefs.all.isEmpty())
            assertEquals(0, writes.get())
            assertNull(old.state.draft)
            assertFalse(old.state.saved)
            assertEquals(GenerationSettingsDraftUi(), replacement.state.draft)
            assertTrue(stateChanges > 0)
            replacement.edit(draft()); replacement.save(); scheduler.advanceUntilIdle()
            assertTrue(replacement.state.saved)
            val readback = store().loadForEditing()
            assertEquals(readback.revision, replacement.state.draft!!.expectedRevision)
            assertEquals(readback.values.profiles.single().id, replacement.state.draft!!.profiles.single().id)
            replacement.revoke()
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun leavingDuringLoadDiscardsLateResultAndRetainedCallbacksAreInert() {
        store().save(values(), null)
        val page = editor().openPage { true }
        page.start()
        page.revoke()
        scheduler.advanceUntilIdle()
        assertNull(page.state.draft)
        assertNull(page.state.failure)
        val previousWrites = writes.get()
        page.edit(draft()); page.save(); page.start(); page.reload()
        scheduler.advanceUntilIdle()
        assertEquals(previousWrites, writes.get())
        assertNull(page.state.draft)
    }

    @Test fun ownerFalseWithoutDisposalStillDeniesWorkerSaveAndClearsOldUi() {
        val owner = AtomicBoolean(true)
        val page = page(current = owner::get)
        page.edit(draft()); page.save()
        owner.set(false) // route invalidation can precede Compose onDispose
        scheduler.advanceUntilIdle()
        assertTrue(prefs.all.isEmpty())
        assertEquals(0, writes.get())
        assertNull(page.state.draft)
    }

    @Test fun workerObservedOwnerLossIsTerminalEvenIfPredicateLaterReturnsTrue() {
        val denyOnce = AtomicBoolean(false)
        val page = page(current = { !denyOnce.compareAndSet(true, false) })
        page.edit(draft()); page.save()
        denyOnce.set(true) // first consumed by the save admission check, not a UI callback
        scheduler.advanceUntilIdle()
        assertTrue(prefs.all.isEmpty())
        assertNull(page.state.draft)
        page.edit(draft()); page.save(); page.reload()
        scheduler.advanceUntilIdle()
        assertTrue(prefs.all.isEmpty())
        assertEquals(0, writes.get())
        assertNull(page.state.draft)
    }

    @Test fun readbackFailureKeepsDraftAndSpecificSanitizedEnumThenExplicitReloadOffersRecovery() {
        val page = page(editor(store(load = { error("fixture-secret-private-exception") })))
        page.edit(draft()); page.save(); scheduler.advanceUntilIdle()
        assertEquals(GenerationSettingsFailure.SAVE_UNCONFIRMED, page.state.failure)
        assertEquals(app.daiyu.android.model.generation.GenerationSettingsException(
            GenerationSettingsFailure.SAVE_UNCONFIRMED).message, page.state.errorText)
        assertFalse(page.state.errorText!!.contains("fixture-secret"))
        assertEquals(draft(), page.state.draft)
        assertFalse(page.state.saved)
        page.reload(); scheduler.advanceUntilIdle()
        assertTrue(page.state.incompleteSave)
        assertTrue(page.state.credentialUnavailableIds.isNotEmpty())
        assertNotNull(page.state.draft!!.expectedRevision)
        page.revoke()
    }

    @Test fun saveWaitingOnActualStoreLockIsDeniedAfterPageRevocationBeforeAnyStoreReadOrWrite() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdLoad = AtomicBoolean(false)
        val store = store(load = { id ->
            reads.incrementAndGet()
            if (holdLoad.get()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            keys.getValue(id)
        })
        store.save(values(), null)
        val workerThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "settings-lock-test").also(workerThread::set) }
        val io = executor.asCoroutineDispatcher()
        val editor = GenerationSettingsEditor(store, io, dispatcher)
        fun drain() { repeat(3) { executor.submit {}.get(5, TimeUnit.SECONDS); scheduler.advanceUntilIdle() } }
        val page = editor.openPage { true }
        page.start(); drain()
        page.edit(page.state.draft!!.let { it.copy(profiles = listOf(it.profiles.single().copy(label = "must-not-save"))) })
        val before = prefs.getString(GenerationSettingsStore.KEY, null)
        val beforeWrites = writes.get()
        val blockerFailure = AtomicReference<Throwable?>()
        holdLoad.set(true)
        val blocker = Thread {
            try { store.loadForEditing() } catch (failure: Throwable) { blockerFailure.set(failure) }
        }
        blocker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val readsWithBlocker = reads.get()
            page.save()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (workerThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("save must actually be waiting on the store monitor", Thread.State.BLOCKED, workerThread.get().state)
            page.revoke() // returns immediately while the store monitor is held elsewhere
            holdLoad.set(false); release.countDown(); blocker.join(5000)
            drain()
            assertNull(blockerFailure.get())
            assertEquals(readsWithBlocker, reads.get()) // denied at entry, not after readRecord/credentials
            assertEquals(beforeWrites, writes.get())
            assertEquals(before, prefs.getString(GenerationSettingsStore.KEY, null))
            assertNull(page.state.draft)
        } finally {
            holdLoad.set(false); release.countDown(); blocker.join(5000)
            page.revoke(); io.close()
        }
    }

    @Test fun admittedSaveFinishesCredentialAndManifestReadbackAfterLeavingButNeverRepopulatesOldPage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = store(save = { id, key ->
            writes.incrementAndGet(); keys[id] = key
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val executor = Executors.newSingleThreadExecutor()
        val io = executor.asCoroutineDispatcher()
        val editor = GenerationSettingsEditor(store, io, dispatcher)
        fun drain() { repeat(3) { executor.submit {}.get(5, TimeUnit.SECONDS); scheduler.advanceUntilIdle() } }
        val old = editor.openPage { true }
        try {
            old.start(); drain()
            old.edit(draft()); old.save()
            assertTrue(entered.await(5, TimeUnit.SECONDS)) // already admitted, saving fence is on disk
            old.revoke()
            val replacement = editor.openPage { true }
            assertNull(replacement.state.draft)
            release.countDown(); drain()
            assertEquals(1, writes.get())
            assertTrue("credential write AND final editing readback must complete", reads.get() >= 2)
            assertEquals("ready", org.json.JSONObject(prefs.getString(GenerationSettingsStore.KEY, null)!!).getString("state"))
            assertNull(old.state.draft)
            assertFalse(old.state.saved)
            assertNull(replacement.state.draft) // no cross-page publication
            replacement.start(); drain()
            assertEquals(store.loadSnapshot().revision, replacement.state.draft!!.expectedRevision)
            assertEquals(profile().apiKey, replacement.state.draft!!.profiles.single().apiKey)
            replacement.revoke()
        } finally { release.countDown(); old.revoke(); io.close() }
    }
}
