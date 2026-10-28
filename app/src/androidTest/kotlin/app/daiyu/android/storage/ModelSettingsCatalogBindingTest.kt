package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.catalog.*
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID
import org.json.JSONObject

@Suppress("DEPRECATION")
class ModelSettingsCatalogBindingTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: ModelSettingsStore
    private lateinit var raw: SharedPreferences
    private lateinit var prefix: String
    private val initial = ModelSettings("https://example.invalid", "model-a", "test-only-key", true)

    override fun setUp() {
        super.setUp()
        prefix = "catalog-binding-${UUID.randomUUID()}"
        val isolated = testContext(instrumentation.targetContext, prefix)
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(isolated) { override fun getFilesDir() = root }
        raw = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        store = ModelSettingsStore(context, prefix)
    }

    override fun tearDown() {
        try { root.deleteRecursively() } finally { super.tearDown() }
    }

    fun testPerModelSearchSurvivesRunProjectionProviderSaveSwitchAndReopen() {
        val current = store.load().copy(endpoint = "https://models.example.invalid", model = "search-a", apiKey = "synthetic",
            requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
        store.save(current, protocolChangeAuthorized = true)
        val migrated = store.modelCatalogSnapshot()
        assertTrue(migrated.models.single().settings.requestOptions.openAiWebSearchEnabled)
        val extra = CatalogModelParameters.newModel(RemoteModel("search-b"))
        assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(
            migrated.providerIdentity, migrated.revision, listOf(extra)) { false })
        val snapshot = store.modelCatalogSnapshot()
        val ticket = snapshot.selectionFor("search-a")!!
        val disabled = app.daiyu.android.model.catalog.CatalogModelEdit.from(ticket.record)
            .copy(nativeSearch = false).applyTo(ticket.record)
        assertEquals(app.daiyu.android.model.catalog.CatalogLocalEditResult.APPLIED,
            store.modelCatalog.replaceSelection(ticket, disabled) { false })
        assertFalse(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertTrue(store.load().requestOptions.openAiWebSearchEnabled)
        store.save(store.load().copy(batteryEnabled = true))
        assertFalse(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        store.selectCatalogModel(store.load(), store.modelCatalogSnapshot().selectionFor("search-b")!!)
        assertFalse(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        store.selectCatalogModel(store.load(), store.modelCatalogSnapshot().selectionFor("search-a")!!)
        assertFalse(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        val enableTicket = store.modelCatalogSnapshot().selectionFor("search-a")!!
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.replaceSelection(enableTicket,
            CatalogModelEdit.from(enableTicket.record).copy(nativeSearch = true).applyTo(enableTicket.record)) { false })
        assertTrue(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        store.save(store.load().copy(deviceStatusEnabled = true))
        assertTrue(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        store.selectCatalogModel(store.load(), store.modelCatalogSnapshot().selectionFor("search-b")!!)
        assertFalse(store.loadForRun().requestOptions.openAiWebSearchEnabled)
        val reopened = ModelSettingsStore(context, prefix)
        assertFalse(reopened.loadForRun().requestOptions.openAiWebSearchEnabled)
        reopened.selectCatalogModel(reopened.load(), reopened.modelCatalogSnapshot().selectionFor("search-a")!!)
        assertTrue(reopened.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertFalse(reopened.modelCatalogSnapshot().models.single { it.id == "search-b" }.settings.requestOptions.openAiWebSearchEnabled)
    }

    fun testSavedCustomModelRemainsUsableWithDefaultCapabilities() {
        store.save(initial.copy(model = "catalog-model", requestOptions = ProviderRequestOptions(
            customBody = "{\"model\":\"relay/actual-model\"}")))
        val loaded = store.loadForRun()
        loaded.validate()
        assertFalse(loaded.hasModelOverrides)
        assertEquals(true, loaded.requestSnapshot().imageInputSupported)
        assertEquals("relay/actual-model", org.json.JSONObject(loaded.requestOptions.customBody).getString("model"))
    }

    fun testProtocolAndPathSaveKeepTheWholeDirectoryAndInvalidateOldTickets() {
        val configured = initial.copy(responsesPath = "/responses")
        store.save(configured)
        append(importedModel("model-b"), importedModel("model-c", device = true))
        val before = store.modelCatalogSnapshot()
        val ticket = before.selectionFor("model-b")!!
        val switched = configured.copy(protocol = app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS)
        try { store.save(switched); fail("implicit protocol changes must remain forbidden") } catch (_: IllegalArgumentException) {}
        assertEquals(configured, store.load())
        store.save(switched, protocolChangeAuthorized = true)
        val after = store.modelCatalogSnapshot()
        assertEquals(before.providerIdentity, after.providerIdentity)
        assertTrue(after.revision > before.revision)
        assertEquals(before.models, after.models)
        assertEquals(switched, store.load())
        assertEquals(switched, ModelSettingsStore(context, prefix).load())
        assertEquals(after, ModelSettingsStore(context, prefix).modelCatalogSnapshot())
        rejectsSelection { store.selectCatalogModel(switched, ticket) }
        store.save(switched.copy(responsesPath = "/v1/responses"))
        assertEquals(after.providerIdentity, store.modelCatalogSnapshot().providerIdentity)
        assertEquals(after.models, store.modelCatalogSnapshot().models)
        store.save(switched.copy(apiKey = "new-account-key"))
        assertTrue(after.providerIdentity != store.modelCatalogSnapshot().providerIdentity)
        assertEquals(listOf(initial.model), store.modelCatalogSnapshot().models.map { it.id })
    }

    fun testConnectionCanBeSavedWithoutInventingAModelAndRunStillRequiresSelection() {
        val repository = ProviderRepository(context, primary = store)
        val before = repository.snapshot()
        val connection = initial.copy(model = "")
        val registered = repository.registerConfigured(before.revision, "Connection only", connection, false) {}
        val added = registered.providers.single { it.slotId != ProviderRegistry.PRIMARY }
        assertFalse(added.enabled)
        assertEquals(before.activeSlotId, registered.activeSlotId)
        val editor = repository.editor(added.slotId)
        assertEquals(connection, repository.editing(editor).settings)
        val empty = repository.catalogSnapshot(editor)
        assertTrue(empty.models.isEmpty())
        try {
            connection.validate()
            fail("connection without a model must not run")
        } catch (expected: IllegalArgumentException) {
            assertEquals("请先在模型页选择模型", expected.message)
        }
        rejectsSelection { repository.activate(registered.revision, added.slotId) }
        repository.withEditor(editor) { target ->
            val custom = CatalogModelEdit("manual-id", "My model", "8192", true, false).newRecord()
            assertEquals(CatalogCommitResult.COMMITTED, target.modelCatalog.compareAndAppend(
                empty.providerIdentity, empty.revision, listOf(custom)) { false })
            assertEquals("", target.load().model)
            target.selectCatalogModel(connection, target.localModelCatalogSnapshot()!!.selectionFor("manual-id")!!)
            assertEquals("manual-id", target.loadForRun().model)
            assertEquals(8192, target.loadForRun().contextWindowOverride)
        }
    }

    fun testInlineProviderRenameKeepsSlotAndRejectsOldEditor() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val editor = repository.editor(ProviderRegistry.PRIMARY)
        val expected = repository.editing(editor)
        repository.save(editor, initial, expected, false, false, providerName = "Renamed")
        assertEquals("Renamed", repository.snapshot().providers.single().name)
        assertEquals(ProviderRegistry.PRIMARY, repository.snapshot().activeSlotId)
        assertEquals(initial, store.load())
        rejectsSelection { repository.save(editor, initial.copy(apiKey = "stale"), expected, false, false) }
        assertEquals(initial, store.load())
    }

    fun testProviderNameAndEnabledAreSavedInOneRegistryRevisionAndDoNotAutoActivate() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val editor = repository.activeEditor()
        val expected = repository.editing(editor)
        repository.save(editor, initial, expected, false, false, providerName = "Disabled primary", providerEnabled = false)
        val disabled = repository.snapshot()
        assertEquals(editor.registryRevision + 1, disabled.revision)
        assertEquals("Disabled primary", disabled.providers.single().name)
        assertFalse(disabled.providers.single().enabled)
        assertNull(disabled.activeSlotId)
        assertEquals(disabled, ProviderRepository(context, primary = store).snapshot())
        rejectsSelection { repository.save(editor, initial.copy(apiKey = "stale"), expected, false, false, providerEnabled = true) }
        assertEquals(initial, store.load())
        val current = repository.editor(ProviderRegistry.PRIMARY)
        repository.save(current, initial, repository.editing(current), false, false, providerEnabled = true)
        assertTrue(repository.snapshot().providers.single().enabled)
        assertNull(repository.snapshot().activeSlotId)
        val created = repository.registerConfigured(repository.snapshot().revision, "Enabled new", initial,
            protocolChangeAuthorized = false, enabled = true, admit = {})
        assertTrue(created.providers.single { it.name == "Enabled new" }.enabled)
        assertNull(created.activeSlotId)
    }

    fun testChatCatalogSelectionActivatesExactProviderAndPreservesOtherAccount() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val targetSettings = initial.copy(endpoint = "https://other.example.invalid", apiKey = "other-account-key")
        val registered = repository.registerConfigured(repository.snapshot().revision, "Other", targetSettings,
            protocolChangeAuthorized = false, enabled = true, admit = {})
        val target = repository.editor(registered.providers.single { it.name == "Other" }.slotId)
        val active = repository.activeEditor()
        val ticket = repository.catalogSnapshot(target).selectionFor(initial.model)!!
        val before = repository.snapshot()
        rejectsSelection { repository.selectActiveCatalogModel(active, initial, target, targetSettings, ticket) { true } }
        assertEquals(before, repository.snapshot())
        repository.selectActiveCatalogModel(active, initial, target, targetSettings, ticket) { false }
        assertEquals(target.slotId, repository.snapshot().activeSlotId)
        assertEquals(initial, store.load())
        assertEquals(targetSettings.apiKey, repository.freezeActive().settings.apiKey)
        assertEquals(targetSettings.endpoint, repository.freezeActive().settings.endpoint)
        rejectsSelection { repository.selectActiveCatalogModel(active, initial, target, targetSettings, ticket) { false } }
        assertEquals(target.slotId, repository.snapshot().activeSlotId)
    }

    fun testChatCatalogSelectionRejectsStaleCatalogAndSupportsNoActiveProvider() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val editor = repository.activeEditor()
        val stale = repository.catalogSnapshot(editor).selectionFor(initial.model)!!
        append(importedModel("new-local"))
        rejectsSelection { repository.selectActiveCatalogModel(editor, initial, editor, initial, stale) { false } }
        assertEquals(initial, store.load())
        repository.setEnabled(editor.registryRevision, editor.slotId, false)
        val enabled = repository.setEnabled(repository.snapshot().revision, editor.slotId, true)
        assertNull(enabled.activeSlotId)
        val target = repository.editor(editor.slotId)
        val current = repository.catalogSnapshot(target).selectionFor("new-local")!!
        repository.selectActiveCatalogModel(null, null, target, initial, current) { false }
        assertEquals(editor.slotId, repository.snapshot().activeSlotId)
        assertEquals("new-local", store.load().model)
    }

    fun testRuntimeOverridesAreFrozenWithoutPreferenceWritesAndEditsInvalidateOldConfiguration() {
        store.save(initial)
        val ticket = store.modelCatalogSnapshot().selectionFor(initial.model)!!
        val edited = ticket.record.copy(settings = ticket.record.settings.copy(
            contextWindowOverride = 8192, reasoningOverride = false, visionOverride = true))
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.replaceSelection(ticket, edited) { false })
        val directory = store.localModelCatalogSnapshot()
        val preferences = raw.all
        val repository = ProviderRepository(context, primary = store)
        val frozen = repository.freezeActive()
        assertEquals(8192, frozen.settings.contextWindowOverride)
        assertEquals(false, frozen.settings.reasoningOverride)
        assertEquals(true, frozen.settings.requestSnapshot().imageInputSupported)
        assertTrue(repository.isCurrentActive(frozen))
        assertEquals(directory, store.localModelCatalogSnapshot())
        assertEquals(preferences, raw.all)
        assertNull(store.load().contextWindowOverride)
        val nextTicket = store.localModelCatalogSnapshot()!!.selectionFor(initial.model)!!
        val automatic = nextTicket.record.copy(settings = nextTicket.record.settings.copy(
            contextWindowOverride = null, reasoningOverride = null, visionOverride = null))
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.replaceSelection(nextTicket, automatic) { false })
        assertFalse(repository.isCurrentActive(frozen))
        val next = repository.freezeActive()
        assertNull(next.settings.contextWindowOverride)
        assertNull(next.settings.reasoningOverride)
        assertNull(next.settings.visionOverride)
        assertEquals(true, next.settings.requestSnapshot().imageInputSupported)
        assertTrue(repository.isCurrentActive(next))
        assertEquals(8192, frozen.settings.contextWindowOverride)
    }

    fun testExplicitCatalogFreezeDoesNotSelectMainModelAndRejectsStaleTicket() {
        store.save(initial)
        val extra = importedModel("summary-only").let { it.copy(settings = it.settings.copy(
            contextWindowOverride = 32768, reasoningOverride = false, visionOverride = null)) }
        append(extra)
        val repository = ProviderRepository(context, primary = store)
        val catalog = store.localModelCatalogSnapshot()!!
        val target = ProviderCatalogRunTarget(repository.editor(ProviderRegistry.PRIMARY), catalog.providerIdentity,
            catalog.revision, extra.settings.localIdentity, extra.id, initial.protocol)
        val preferences = raw.all
        val frozen = repository.freezeCatalog(target)
        assertEquals(extra.id, frozen.settings.model)
        assertEquals(extra.settings.requestOptions, frozen.settings.requestOptions)
        assertEquals(32768, frozen.settings.contextWindowOverride)
        assertEquals(false, frozen.settings.reasoningOverride)
        assertEquals(true, frozen.settings.requestSnapshot().imageInputSupported)
        assertEquals(initial.model, store.load().model)
        assertEquals(preferences, raw.all)
        assertEquals(catalog, store.localModelCatalogSnapshot())
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.replaceSelection(catalog.selectionFor(extra.id)!!,
            extra.copy(displayName = "Changed summary")) { false })
        rejectsSelection { repository.freezeCatalog(target) }
        assertEquals(extra.id, frozen.settings.model)
    }

    fun testInterruptedAccountSaveBlocksExecutionAndDirectoryUntilExplicitRecovery() {
        store.save(initial)
        append(importedModel("old-account-model"))
        val old = checkNotNull(store.localModelCatalogSnapshot())
        // Reproduce the durable post-preference/pre-catalog crash state without executing compensation.
        check(raw.edit().putBoolean("catalog-configuration-pending", true).commit())
        val reopened = ModelSettingsStore(context, prefix)
        var rejected = false
        try { reopened.load() } catch (_: CredentialUnavailableException) { rejected = true }
        assertTrue(rejected)
        assertTrue(reopened.loadForEditing().credentialUnavailable)
        assertEquals("", reopened.loadForEditing().settings.apiKey)
        rejectsSelection { reopened.localModelCatalogSnapshot() }
        rejectsSelection { reopened.modelCatalogSnapshot() }
        val replacement = initial.copy(apiKey = "explicitly-reentered-account-key")
        reopened.save(replacement)
        assertEquals(replacement, reopened.load())
        assertFalse(raw.contains("catalog-configuration-pending"))
        val fresh = checkNotNull(reopened.localModelCatalogSnapshot())
        assertFalse(old.providerIdentity == fresh.providerIdentity)
        assertEquals(listOf(initial.model), fresh.models.map { it.id })
    }

    fun testDirectoryProtocolReadDoesNotDecryptAnUnavailableCredential() {
        store.save(initial)
        val before = store.modelCatalogSnapshot()
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val rawBefore = raw.all
        assertEquals(app.daiyu.android.model.ProviderProtocol.RESPONSES, store.localModelCatalogProtocol())
        assertEquals(before, store.localModelCatalogSnapshot())
        assertEquals(rawBefore, raw.all)
        var rejected = false
        try { store.load() } catch (_: CredentialUnavailableException) { rejected = true }
        assertTrue("execution still requires a readable credential", rejected)
        raw.edit().putString("protocol", "not-a-protocol").commit()
        rejected = false
        try { store.localModelCatalogProtocol() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("unknown protocol is not a browsing fallback", rejected)
    }

    fun testSaveSelectionAndRestoreFenceSamePersistentCatalog() {
        store.save(initial)
        val first = store.modelCatalogSnapshot()
        assertEquals(listOf("model-a"), first.models.map { it.id })
        store.save(initial.copy(batteryEnabled = false))
        val afterSave = store.modelCatalogSnapshot()
        assertEquals(first.providerIdentity, afterSave.providerIdentity)
        assertTrue(afterSave.revision > first.revision)
        assertEquals(first.models.single().settings.localIdentity, afterSave.models.single().settings.localIdentity)
        assertFalse(afterSave.models.single().settings.batteryEnabled)
        val reopened = ModelSettingsStore(context, prefix)
        assertEquals(afterSave, reopened.modelCatalogSnapshot())
        reopened.selectModel(reopened.load(), "model-b")
        val afterSelect = reopened.modelCatalogSnapshot()
        assertTrue(afterSelect.revision > afterSave.revision)
        val plan = reopened.prepareNonSecretRestore(JSONObject().put("model", "model-c"))
        reopened.applyNonSecretRestore(plan, true)
        val afterRestore = reopened.modelCatalogSnapshot()
        assertTrue(afterRestore.revision > afterSelect.revision)
        assertEquals(first.providerIdentity, afterRestore.providerIdentity)
        assertEquals(afterSelect.models, afterRestore.models)
    }

    private fun importedModel(id: String, battery: Boolean = false, device: Boolean = false) = SavedCatalogModel(
        id, "Imported $id", CatalogModelParameters(batteryEnabled = battery, deviceStatusEnabled = device,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.2}")),
    )

    private fun append(vararg models: SavedCatalogModel<CatalogModelParameters>) {
        val snapshot = store.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(
            snapshot.providerIdentity, snapshot.revision, models.toList()) { false })
    }

    private fun rejectsSelection(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("stale selection must fail closed", rejected)
    }

    fun testImportedPolicyRoundTripsAcrossSwitchesAndReopenWithoutLosingOtherModels() {
        val a = initial.copy(requestOptions = ProviderRequestOptions("{\"temperature\":0.7}"))
        store.save(a)
        val first = store.modelCatalogSnapshot()
        val b = importedModel("model-b", device = true)
        val c = importedModel("model-c")
        append(b, c)
        val reopened = ModelSettingsStore(context, prefix)
        val local = reopened.localModelCatalogSnapshot()!!
        assertEquals(listOf("model-a", "model-b", "model-c"), local.models.map { it.id })
        reopened.selectCatalogModel(a, local.selectionFor("model-b")!!)
        val expectedB = b.settings.applyTo(a, "model-b")
        assertEquals(expectedB.copy(reasoningOverride = null, visionOverride = null, defaultVisionSupported = null), reopened.load())
        assertEquals(expectedB, reopened.loadForRun())
        val editedB = reopened.load().copy(batteryEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.4}"))
        reopened.save(editedB)
        val beforeReturn = reopened.localModelCatalogSnapshot()!!
        assertEquals(b.settings.localIdentity, beforeReturn.models.single { it.id == b.id }.settings.localIdentity)
        assertEquals(c, beforeReturn.models.single { it.id == c.id })
        reopened.selectCatalogModel(editedB, beforeReturn.selectionFor("model-a")!!)
        assertEquals(a, reopened.load())
        val finalSnapshot = reopened.localModelCatalogSnapshot()!!
        assertEquals(first.models.single(), finalSnapshot.models.single { it.id == "model-a" })
        assertEquals(editedB.requestOptions, finalSnapshot.models.single { it.id == b.id }.settings.requestOptions)
        assertEquals(c, finalSnapshot.models.single { it.id == c.id })
        assertEquals(finalSnapshot, ModelSettingsStore(context, prefix).localModelCatalogSnapshot())
    }

    fun testChangedConfigurationAndDeletionRejectCapturedSelections() {
        store.save(initial)
        append(importedModel("model-b"))
        val before = store.localModelCatalogSnapshot()!!
        val ticket = before.selectionFor("model-b")!!
        store.save(initial.copy(deviceStatusEnabled = true))
        val changed = store.load()
        rejectsSelection { store.selectCatalogModel(changed, ticket) }
        assertEquals(changed, store.load())
        val latest = store.localModelCatalogSnapshot()!!
        val latestTicket = latest.selectionFor("model-b")!!
        assertTrue(store.modelCatalog.deleteProvider(latest.providerIdentity))
        assertNull(store.localModelCatalogSnapshot()) // read does not re-register the tombstone
        rejectsSelection { store.selectCatalogModel(changed, latestTicket) }
        assertEquals(changed, store.load())
    }

    fun testSameWireModelUnderDifferentAccountNeverInheritsImportedPolicy() {
        store.save(initial)
        val imported = importedModel("shared-id", battery = true, device = true)
        append(imported, importedModel("other-old-account-model"))
        val old = store.localModelCatalogSnapshot()!!
        val oldTicket = old.selectionFor(imported.id)!!
        val otherAccount = initial.copy(apiKey = "different-account-key", model = imported.id,
            batteryEnabled = false, requestOptions = ProviderRequestOptions())
        store.save(otherAccount)
        val fresh = store.localModelCatalogSnapshot()!!
        assertFalse(old.providerIdentity == fresh.providerIdentity)
        assertEquals(listOf(imported.id), fresh.models.map { it.id })
        assertFalse(imported.settings.localIdentity == fresh.models.single().settings.localIdentity)
        rejectsSelection { store.selectCatalogModel(otherAccount, oldTicket) }
        store.selectCatalogModel(otherAccount, fresh.selectionFor(imported.id)!!)
        assertEquals(otherAccount, store.load())
        assertFalse(store.load().deviceStatusEnabled)
        assertEquals(ProviderRequestOptions(), store.load().requestOptions)
    }

    fun testLegacyRemoteSelectionUsesDetachedDefaultsNotActiveOverrides() {
        val a = initial.copy(deviceStatusEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.7}"))
        store.save(a)
        store.modelCatalogSnapshot()
        store.selectModel(a, "new-remote-model")
        val selected = store.load()
        assertFalse(selected.batteryEnabled)
        assertFalse(selected.deviceStatusEnabled)
        assertEquals(ProviderRequestOptions(), selected.requestOptions)
        val local = store.localModelCatalogSnapshot()!!
        store.selectCatalogModel(selected, local.selectionFor(a.model)!!)
        assertEquals(a, store.load())
    }

    fun testReadOnlyLocalCatalogDoesNotDecryptCredentialsOrAccessNetwork() {
        store.save(initial)
        append(importedModel("model-b"))
        val before = store.localModelCatalogSnapshot()
        val guarded = object : SharedPreferences by raw {
            override fun getAll(): MutableMap<String, *> = error("must not read preferences")
            override fun getString(key: String?, defValue: String?): String? = error("must not read preferences")
            override fun contains(key: String?): Boolean = error("must not read preferences")
        }
        val guardedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = guarded
        }
        assertEquals(before, ModelSettingsStore(guardedContext, prefix).localModelCatalogSnapshot())
    }

    fun testCorruptRequestOptionsNeverInitializeFromFallbackDefaults() {
        check(raw.edit().putString("endpoint", initial.endpoint).putString("model", initial.model)
            .putString("request-options", "invalid-json").commit())
        var rejected = false
        try { store.modelCatalogSnapshot() } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertFalse(File(root, "model-catalog/primary.bin").exists())
        check(raw.edit().putString("request-options", ProviderRequestOptions().encode()).commit())
        assertEquals(listOf(initial.model), store.modelCatalogSnapshot().models.map { it.id })
    }

    fun testCatalogMigrationReadsNoCredentialMaterial() {
        check(raw.edit().putString("endpoint", initial.endpoint).putString("model", initial.model).commit())
        val guarded = object : SharedPreferences by raw {
            override fun getAll(): MutableMap<String, *> = error("must not read all preferences")
            override fun getString(key: String?, defValue: String?): String? {
                check(key !in setOf("key", "iv", "key-alias", "key-owner"))
                return raw.getString(key, defValue)
            }
            override fun contains(key: String?): Boolean {
                check(key !in setOf("key", "iv", "key-alias", "key-owner"))
                return raw.contains(key)
            }
        }
        val guardedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "model-settings") guarded else super.getSharedPreferences(name, mode)
        }
        assertEquals(initial.model, ModelSettingsStore(guardedContext, prefix).modelCatalogSnapshot().models.single().id)
    }
    fun testCurrentModelDeletionClearsOnlySelectionAndNeverReadsMalformedCredentials() {
        store.save(initial); append(importedModel("extra"))
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val before = raw.all
        val catalog = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals(CatalogLocalEditResult.APPLIED,
            store.deleteCatalogModel(checkNotNull(catalog.selectionFor(initial.model))) { false })
        assertEquals("", raw.getString("model", null))
        assertEquals(before - "model", raw.all - "model")
        assertEquals(catalog.revision + 1, store.localModelCatalogSnapshot()?.revision)
        assertEquals(listOf("extra"), store.modelCatalogSnapshot().models.map { it.id })
        assertEquals(listOf("extra"), ModelSettingsStore(context, prefix).modelCatalogSnapshot().models.map { it.id })
    }

    fun testLocalRenameAndDeletionNeverReadCredentialsOrChangeActiveSettings() {
        store.save(initial); append(importedModel("extra"))
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val before = raw.all
        val first = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals(CatalogLocalEditResult.APPLIED,
            store.renameCatalogModel(checkNotNull(first.selectionFor("extra")), "Renamed extra") { false })
        val renamed = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals("Renamed extra", renamed.models.single { it.id == "extra" }.displayName)
        assertEquals(first.models.single { it.id == "extra" }.settings, renamed.models.single { it.id == "extra" }.settings)
        assertEquals(CatalogLocalEditResult.STALE,
            store.deleteCatalogModel(checkNotNull(first.selectionFor("extra"))) { false })
        assertEquals(CatalogLocalEditResult.CANCELLED,
            store.deleteCatalogModel(checkNotNull(renamed.selectionFor("extra"))) { true })
        assertEquals(CatalogLocalEditResult.APPLIED,
            store.deleteCatalogModel(checkNotNull(renamed.selectionFor("extra"))) { false })
        assertEquals(listOf(initial.model), checkNotNull(store.localModelCatalogSnapshot()).models.map { it.id })
        assertEquals(before, raw.all)
    }

    fun testCatalogSummaryConsentUsesMetadataAndRejectsChangedDescriptorBeforeCredentials() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val editor = repository.activeEditor()
        val catalog = repository.catalogSnapshot(editor)
        val record = catalog.models.single()
        val target = ProviderCatalogRunTarget(editor, catalog.providerIdentity, catalog.revision,
            record.settings.localIdentity, record.id, initial.protocol)
        val binding = app.daiyu.android.context.CatalogSummaryBindings.encode(target)
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val descriptor = app.daiyu.android.context.catalogSummaryDescriptor(context, binding)
        assertEquals(initial.requestEndpoint(), descriptor.endpoint)
        assertEquals(initial.model, descriptor.apiModel)
        for (changed in listOf(descriptor.copy(revision = "stale"),
            descriptor.copy(endpoint = "https://wrong.invalid/v1/responses"), descriptor.copy(apiModel = "wrong"))) {
            try {
                app.daiyu.android.context.catalogSummaryConfiguration(context, changed)
                fail("changed consent must be rejected before reading malformed credentials")
            } catch (_: app.daiyu.android.context.SummaryProfileChangedException) { }
        }
        assertEquals(descriptor, app.daiyu.android.context.catalogSummaryDescriptor(context, binding))
        rejectsSelection { app.daiyu.android.context.catalogSummaryConfiguration(context, descriptor) }
    }

    fun testCatalogSummaryFreezesOnlySelectedAccountAndRejectsDisabledProvider() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val other = initial.copy(endpoint = "https://summary-other.invalid", apiKey = "summary-other-key")
        val registered = repository.registerConfigured(repository.snapshot().revision, "Summary other", other,
            false, enabled = true, admit = {})
        val editor = repository.editor(registered.providers.single { it.name == "Summary other" }.slotId)
        repository.withEditor(editor) { targetStore ->
            val catalog = targetStore.modelCatalogSnapshot()
            assertEquals(CatalogCommitResult.COMMITTED, targetStore.modelCatalog.compareAndAppend(
                catalog.providerIdentity, catalog.revision,
                listOf(CatalogModelEdit("summary-local", "Summary local", "32768", true, false).newRecord())) { false })
        }
        val catalog = repository.withEditor(editor) { checkNotNull(it.localModelCatalogSnapshot()) }
        val record = catalog.models.single { it.id == "summary-local" }
        val target = ProviderCatalogRunTarget(editor, catalog.providerIdentity, catalog.revision,
            record.settings.localIdentity, record.id, other.protocol)
        val binding = app.daiyu.android.context.CatalogSummaryBindings.encode(target)
        val descriptor = app.daiyu.android.context.catalogSummaryDescriptor(context, binding)
        val frozen = app.daiyu.android.context.catalogSummaryConfiguration(context, descriptor)
        assertEquals("summary-local", frozen.apiModel)
        assertEquals(other.apiKey, frozen.provider.apiKey)
        assertEquals(other.copy(model = "summary-local").requestEndpoint(), descriptor.endpoint)
        assertEquals(initial, store.load())
        repository.setEnabled(repository.snapshot().revision, editor.slotId, false)
        rejectsSelection { app.daiyu.android.context.catalogSummaryConfiguration(context, descriptor) }
        assertEquals(initial, store.load())
    }

    fun testManualSummaryMetadataAndChangedConsentNeverReadMalformedCredentials() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val before = raw.all
        val descriptor = app.daiyu.android.context.ManualSummaryTargets.describe(repository,
            app.daiyu.android.context.ContextManagementSelection(null, app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS))
        assertEquals(initial.model, descriptor.apiModel)
        assertTrue(descriptor.endpoint.endsWith("/chat/completions"))
        assertEquals(before, raw.all)
        try {
            app.daiyu.android.context.ManualSummaryTargets.configuration(repository,
                descriptor.copy(endpoint = "https://wrong.invalid/v1/chat/completions"))
            fail("modified consent")
        } catch (_: app.daiyu.android.context.SummaryProfileChangedException) { }
        assertEquals(before, raw.all)
    }

    fun testManualSummarySelectedProviderAndProtocolDoNotMutateChatOrSavedSelection() {
        store.save(initial)
        val repository = ProviderRepository(context, primary = store)
        val other = initial.copy(endpoint = "https://manual-other.invalid", apiKey = "manual-other-key")
        val registered = repository.registerConfigured(repository.snapshot().revision, "Manual other", other,
            false, enabled = true, admit = {})
        val editor = repository.editor(registered.providers.single { it.name == "Manual other" }.slotId)
        repository.withEditor(editor) { targetStore ->
            val catalog = targetStore.modelCatalogSnapshot()
            assertEquals(CatalogCommitResult.COMMITTED, targetStore.modelCatalog.compareAndAppend(
                catalog.providerIdentity, catalog.revision,
                listOf(CatalogModelEdit("one-shot-summary", "One shot", "32768", true, false).newRecord())) { false })
        }
        val catalog = repository.catalogSnapshot(editor)
        val record = catalog.models.single { it.id == "one-shot-summary" }
        val target = ProviderCatalogRunTarget(editor, catalog.providerIdentity, catalog.revision,
            record.settings.localIdentity, record.id, other.protocol)
        val selection = app.daiyu.android.context.ContextManagementSelection(
            app.daiyu.android.context.CatalogSummaryBindings.encode(target), app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS)
        val saved = repository.snapshot()
        val descriptor = app.daiyu.android.context.ManualSummaryTargets.describe(repository, selection)
        val frozen = app.daiyu.android.context.ManualSummaryTargets.configuration(repository, descriptor)
        assertEquals("one-shot-summary", frozen.apiModel)
        assertEquals(other.apiKey, frozen.provider.apiKey)
        assertEquals(app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS, frozen.provider.protocol)
        assertTrue(descriptor.endpoint.endsWith("/chat/completions"))
        assertEquals(saved, repository.snapshot())
        assertEquals(initial, store.load())
        assertEquals(other, repository.editing(editor).settings)
        repository.setEnabled(saved.revision, editor.slotId, false)
        rejectsSelection { app.daiyu.android.context.ManualSummaryTargets.configuration(repository, descriptor) }
    }

}
