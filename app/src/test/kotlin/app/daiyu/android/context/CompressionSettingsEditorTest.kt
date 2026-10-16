package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

/** Real configuration stores with synthetic credentials; deterministic queue, no sleeps/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class CompressionSettingsEditorTest {
    private lateinit var compressionPrefs: SharedPreferences
    private lateinit var profilePrefs: SharedPreferences
    private var storedModel: ModelSettings? = null
    private var modelReads = 0
    private var modelWrites = 0
    private var compressionOpens = 0
    private var profileOpens = 0
    private var authorizations = mutableListOf<Pair<Boolean, Boolean>>()
    private var beforeCompressionOpen: () -> Unit = {}
    private var beforeProfileOpen: () -> Unit = {}
    private var beforeModelSave: () -> Unit = {}
    private var afterModelSave: () -> Unit = {}
    private var afterModelLoad: () -> Unit = {}
    private val binding = SummaryModelBinding("independent-summary", "stable-draft-binding")

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        compressionPrefs = context.getSharedPreferences("editor-compression-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        profilePrefs = context.getSharedPreferences("editor-profile-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        storedModel = null
        modelReads = 0; modelWrites = 0; compressionOpens = 0; profileOpens = 0
        authorizations = mutableListOf()
        beforeCompressionOpen = {}; beforeProfileOpen = {}; beforeModelSave = {}
        afterModelSave = {}; afterModelLoad = {}
    }

    private fun profile() = SummaryProfileSettings(binding,
        ModelSettings("https://summary.invalid", "summary-api-model", "synthetic-secret", false),
        SummaryProfileBudgets(32000, 27000, 2000, 1000, 2000, 30000))

    private fun profiles() = SummaryProfileStore(profilePrefs,
        loadModel = {
            modelReads++
            checkNotNull(storedModel).also { afterModelLoad() }
        },
        saveModel = { model, protocol, options ->
            beforeModelSave()
            modelWrites++
            authorizations += protocol to options
            storedModel = model
            afterModelSave()
        })

    private fun editor(prefs: SharedPreferences = compressionPrefs) = CompressionSettingsEditor(
        compressionStore = {
            compressionOpens++
            beforeCompressionOpen()
            ProductionCompressionSettingsStore(prefs)
        },
        profileStore = {
            profileOpens++
            beforeProfileOpen()
            profiles()
        })

    private class Queue : Executor {
        private val jobs = ArrayDeque<Runnable>()
        var running = false
            private set
        override fun execute(command: Runnable) { jobs.add(command) }
        fun next() {
            check(!running)
            running = true
            try { jobs.removeFirst().run() } finally { running = false }
        }
    }

    @Test fun queuedLoadAndSaveAreRevokedBeforeAnyStoreAccess() {
        for (save in listOf(false, true)) {
            val editor = editor()
            val work = requireNotNull(if (save) editor.prepareSave(
                CompressionSettingsEditor.SaveRequest(ProductionCompressionSettings(summaryModel = binding), profile()))
                else editor.prepareLoad())
            var result: CompressionSettingsEditor.Result? = null
            val queue = Queue()
            queue.execute { result = work.runWorker() }
            assertEquals(0, compressionOpens)
            assertEquals(0, profileOpens)
            assertNull(editor.prepareLoad())
            editor.revoke(); editor.revoke()
            queue.next()
            assertSame(CompressionSettingsEditor.Outcome.Revoked, result!!.outcome)
            assertFalse(editor.isCurrent(result!!))
            assertNull(editor.prepareLoad())
            assertNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(ProductionCompressionSettings())))
        }
        assertEquals(0, compressionOpens); assertEquals(0, profileOpens)
        assertEquals(0, modelReads); assertEquals(0, modelWrites)
        assertTrue(compressionPrefs.all.isEmpty()); assertTrue(profilePrefs.all.isEmpty())
    }

    @Test fun publicConstructorIsLazyAndDefaultLoadNeverOpensMainModelSettings() {
        val queue = Queue()
        val opened = mutableListOf<String>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(queue.running)
                check(name == ProductionCompressionSettingsStore.NAMESPACE) { "unexpected model access" }
                opened += name
                return compressionPrefs
            }
        }
        val editor = CompressionSettingsEditor(context)
        val work = requireNotNull(editor.prepareLoad())
        assertTrue(opened.isEmpty())
        queue.execute {
            val loaded = work.runWorker().outcome as CompressionSettingsEditor.Outcome.Loaded
            assertEquals(ProductionCompressionSettings(), loaded.snapshot.settings)
            assertNull(loaded.snapshot.profile)
        }
        queue.next()
        assertEquals(listOf(ProductionCompressionSettingsStore.NAMESPACE), opened)
    }

    @Test fun loadAndSelectedCredentialReadRunOnlyOnParentWorker() {
        val settings = ProductionCompressionSettings(automatic = false, summaryModel = binding)
        ProductionCompressionSettingsStore(compressionPrefs).save(settings)
        profiles().save(profile())
        val queue = Queue()
        beforeCompressionOpen = { check(queue.running) }
        beforeProfileOpen = { check(queue.running) }
        val editor = editor()
        val work = requireNotNull(editor.prepareLoad())
        assertEquals(0, modelReads)
        var result: CompressionSettingsEditor.Result? = null
        queue.execute { result = work.runWorker() }
        assertEquals(0, compressionOpens); assertEquals(0, profileOpens)
        queue.next()
        val loaded = result!!.outcome as CompressionSettingsEditor.Outcome.Loaded
        assertEquals(settings, loaded.snapshot.settings)
        assertEquals(profile(), loaded.snapshot.profile)
        assertNull(loaded.snapshot.profileUnavailableReason)
        assertEquals(1, modelReads)
        assertTrue(editor.isCurrent(result!!))
    }

    @Test fun disabledAndUnknownWindowSaveWithoutInventingModelOrBudget() {
        val editor = editor()
        for (settings in listOf(ProductionCompressionSettings(false, 32000),
            ProductionCompressionSettings(automatic = true, contextWindowTokens = null))) {
            val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(settings))).runWorker()
            val saved = result.outcome as CompressionSettingsEditor.Outcome.Saved
            assertEquals(settings, saved.snapshot.settings)
            assertNull(saved.snapshot.profile)
            assertEquals(settings, ProductionCompressionSettingsStore(compressionPrefs).snapshot())
        }
        assertEquals(0, profileOpens); assertEquals(0, modelReads); assertEquals(0, modelWrites)
    }

    @Test fun completeProfileSavePreservesBindingAndForwardsOnlyExplicitAuthorizations() {
        val editor = editor()
        val settings = ProductionCompressionSettings(true, 32000, summaryModel = binding)
        val first = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(settings, profile()))).runWorker()
        assertEquals(binding, (first.outcome as CompressionSettingsEditor.Outcome.Saved).snapshot.profile!!.binding)
        assertEquals(listOf(false to false), authorizations)
        val changed = profile().copy(provider = profile().provider.copy(protocol = ProviderProtocol.MESSAGES))
        val second = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(settings, changed,
            protocolChangeAuthorized = true, requestOptionsReplacementAuthorized = true))).runWorker()
        val saved = second.outcome as CompressionSettingsEditor.Outcome.Saved
        assertEquals(changed, saved.snapshot.profile)
        assertEquals(binding, saved.snapshot.settings.summaryModel)
        assertEquals(listOf(false to false, true to true), authorizations)
        assertEquals(2, modelReads) // successful saves really read credentials back
    }

    @Test fun admittedSaveFinishesAcrossRevokeButCannotPublishToOldOrNewPage() {
        val editor = editor()
        val replacement = this.editor()
        beforeModelSave = { editor.revoke() } // strictly after admission, before either save completes
        val settings = ProductionCompressionSettings(true, 32000, summaryModel = binding)
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(settings, profile()))).runWorker()
        assertTrue(result.outcome is CompressionSettingsEditor.Outcome.Saved)
        assertEquals(settings, ProductionCompressionSettingsStore(compressionPrefs).snapshot())
        assertEquals(profile(), profiles().load(binding))
        assertFalse(editor.isCurrent(result)); assertFalse(replacement.isCurrent(result))
        assertNull(editor.prepareLoad())
    }

    @Test fun queuedUiReceiptIsRejectedAfterRevokeOrNewerOperation() {
        val editor = editor()
        val receipt = requireNotNull(editor.prepareLoad()).runWorker()
        assertTrue(editor.isCurrent(receipt))
        val newer = requireNotNull(editor.prepareLoad())
        assertFalse(editor.isCurrent(receipt))
        val latest = newer.runWorker()
        assertTrue(editor.isCurrent(latest))
        editor.revoke()
        assertFalse(editor.isCurrent(latest))
        assertFalse(this.editor().isCurrent(latest))
    }

    @Test fun missingSelectedProfileIsEditableButCannotBeClaimedAsSuccessfulSave() {
        val settings = ProductionCompressionSettings(summaryModel = binding)
        ProductionCompressionSettingsStore(compressionPrefs).save(settings)
        val editor = editor()
        val load = requireNotNull(editor.prepareLoad()).runWorker().outcome as CompressionSettingsEditor.Outcome.Loaded
        assertEquals(settings, load.snapshot.settings)
        assertNull(load.snapshot.profile)
        assertEquals(SummaryProfileUnavailableReason.MISSING, load.snapshot.profileUnavailableReason)
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(settings))).runWorker()
        val failure = result.outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.READ_BACK, failure.stage)
        assertEquals(SummaryProfileUnavailableReason.MISSING, failure.profileUnavailableReason)
        assertTrue(failure.writesMayHaveOccurred)
        assertEquals(0, modelReads)
    }

    @Test fun mismatchedBindingAndInvalidProfileNeverWriteCompression() {
        val editor = editor()
        val mismatch = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(), profile()))).runWorker().outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.VALIDATE_SAVE, mismatch.stage)
        assertFalse(mismatch.writesMayHaveOccurred)
        assertEquals(0, compressionOpens); assertEquals(0, profileOpens)
        val bad = profile().copy(budgets = profile().budgets.copy(inputBudgetTokens = 28000))
        val invalid = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(summaryModel = binding), bad))).runWorker().outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.SAVE_PROFILE, invalid.stage)
        assertTrue(profilePrefs.all.isEmpty()); assertTrue(compressionPrefs.all.isEmpty())
        assertEquals(0, compressionOpens); assertEquals(0, modelWrites)
    }

    @Test fun partialProfileFailureIsSanitizedAndDoesNotSaveCompressionOrRetry() {
        afterModelSave = { throw IllegalStateException("synthetic-secret must not escape") }
        val editor = editor()
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(summaryModel = binding), profile()))).runWorker()
        val failure = result.outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.SAVE_PROFILE, failure.stage)
        assertTrue(failure.writesMayHaveOccurred)
        assertFalse(failure.toString().contains("synthetic-secret"))
        assertEquals(1, modelWrites); assertEquals(0, modelReads); assertEquals(0, compressionOpens)
        assertTrue(compressionPrefs.all.isEmpty())
        assertTrue(editor.isCurrent(result))
    }

    @Test fun unconfirmedCompressionCommitIsNeverSuccessEvenIfMemoryChanged() {
        val wrapped = object : SharedPreferences by compressionPrefs {
            override fun edit(): SharedPreferences.Editor {
                val real = compressionPrefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        real.putString(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                    override fun commit(): Boolean { real.commit(); return false }
                }
            }
        }
        val editor = editor(wrapped)
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(keepTurns = 2)))).runWorker()
        val failure = result.outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.SAVE_SETTINGS, failure.stage)
        assertTrue(failure.writesMayHaveOccurred)
        assertEquals(1, compressionOpens) // no optimistic read-back after rejected commit
    }

    @Test fun successfulWritesWithChangedReadbackAreNotReportedAsSaved() {
        val editor = editor()
        // Mutate the compression record between its save and the editor's read-back factory.
        beforeCompressionOpen = {
            if (compressionOpens == 2) ProductionCompressionSettingsStore(compressionPrefs)
                .save(ProductionCompressionSettings(keepTurns = 7))
        }
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(keepTurns = 2)))).runWorker()
        val failure = result.outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.READ_BACK, failure.stage)
        assertTrue(failure.writesMayHaveOccurred)
    }

    @Test fun credentialReadbackFailureAfterWritesCannotBecomeSaved() {
        afterModelLoad = { throw IllegalStateException("synthetic-secret from provider") }
        val editor = editor()
        val result = requireNotNull(editor.prepareSave(CompressionSettingsEditor.SaveRequest(
            ProductionCompressionSettings(summaryModel = binding), profile()))).runWorker()
        val failure = result.outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.READ_BACK, failure.stage)
        assertEquals(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE, failure.profileUnavailableReason)
        assertTrue(failure.writesMayHaveOccurred)
        assertEquals(1, modelWrites); assertEquals(1, modelReads)
        assertFalse(failure.toString().contains("synthetic-secret"))
    }

    @Test fun runWorkerIsOneShotAndReadFailuresDoNotPoisonTheOwner() {
        val editor = editor()
        beforeCompressionOpen = { throw IllegalStateException("synthetic-secret") }
        val work = requireNotNull(editor.prepareLoad())
        val failure = work.runWorker().outcome as CompressionSettingsEditor.Outcome.Failed
        assertEquals(CompressionSettingsEditor.Stage.LOAD_SETTINGS, failure.stage)
        assertFalse(failure.writesMayHaveOccurred)
        try { work.runWorker(); fail("must not run twice") } catch (_: IllegalStateException) { }
        beforeCompressionOpen = {}
        assertTrue(requireNotNull(editor.prepareLoad()).runWorker().outcome is CompressionSettingsEditor.Outcome.Loaded)
    }
}
