package app.daiyu.android.model.generation

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.ui.screens.settings.GenerationProfileDraftUi
import app.daiyu.android.ui.screens.settings.GenerationSettingsDraftUi
import app.daiyu.android.ui.screens.settings.toGenerationSettingsDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenerationSettingsStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var namespace: String
    private val keys = mutableMapOf<String, String>()
    private var reads = 0
    private var writes = 0

    @Before fun setup() {
        namespace = "generation-test-${UUID.randomUUID()}"
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(namespace, Context.MODE_PRIVATE)
        reads = 0; writes = 0; keys.clear()
    }

    private fun profile(protocol: GenerationProtocol = GenerationProtocol.OPENAI_IMAGES_JSON) = GenerationProfileSettings(
        label = "  explicit label  ", kind = if (protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART) GenerationKind.VIDEO else GenerationKind.IMAGE,
        protocol = protocol, endpoint = "https://api.example/v1/" + when (protocol) {
            GenerationProtocol.OPENAI_IMAGES_JSON -> "images/generations"
            GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "images/edits"
            GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "videos"
        }, model = "  exact-model  ", apiKey = "fixture-credential-never-a-real-key",
    )
    private fun values(profiles: List<GenerationProfileSettings> = listOf(profile()), hosts: Set<String> = emptySet(),
        enabled: Boolean = false) = GenerationSettingsValues(profiles, 4, enabled, hosts)
    private fun store(preferences: SharedPreferences = prefs, scope: String = namespace,
        save: (String, String) -> Unit = { id, key -> writes++; keys[id] = key },
        load: (String) -> String = { id -> reads++; keys.getValue(id) }) =
        GenerationSettingsStore(preferences, load, save, scope)
    private fun rejects(reason: GenerationSettingsFailure, action: () -> Unit) {
        try { action(); fail("expected rejection") }
        catch (failure: GenerationSettingsException) { assertEquals(reason, failure.reason) }
    }

    @Test fun missingNeverReadsCredentialsOrCreatesDefault() {
        rejects(GenerationSettingsFailure.MISSING) { store().loadSnapshot() }
        rejects(GenerationSettingsFailure.MISSING) { store().loadForEditing() }
        assertEquals(0, reads); assertEquals(0, writes); assertTrue(prefs.all.isEmpty())
    }

    @Test fun manifestOnlyScopeNeverReadsKeysOrWritesAndSurvivesUnavailableCredentialsAndSavingFence() {
        assertNull(store(load = { error("credential read forbidden") }, save = { _, _ -> error("write forbidden") })
            .readAccountScope())
        assertTrue(prefs.all.isEmpty())
        val first = store().save(values(), null)
        keys.clear()
        val before = prefs.all.toMap()
        val readsBefore = reads; val writesBefore = writes
        val metadataOnly = store(load = { error("credential read forbidden") }, save = { _, _ -> error("write forbidden") })
        assertEquals(first.accountScope, metadataOnly.readAccountScope())
        assertEquals(before, prefs.all)
        assertEquals(readsBefore, reads); assertEquals(writesBefore, writes)
        // A failed explicit save leaves durable saving + process fence. Identity is still
        // observationally readable, but this API can never produce runnable settings.
        rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) {
            store(save = { _, _ -> error("synthetic vault failure") }).save(first.values, first.revision)
        }
        val saving = prefs.all.toMap()
        assertEquals("saving", JSONObject(prefs.getString(GenerationSettingsStore.KEY, null)!!).getString("state"))
        assertEquals(first.accountScope, metadataOnly.readAccountScope())
        assertEquals(saving, prefs.all)
        val corrupt = JSONObject(prefs.getString(GenerationSettingsStore.KEY, null)!!).put("scope", "invented-scope")
        check(prefs.edit().putString(GenerationSettingsStore.KEY, corrupt.toString()).commit())
        rejects(GenerationSettingsFailure.CORRUPT) { metadataOnly.readAccountScope() }
        assertEquals(corrupt.toString(), prefs.getString(GenerationSettingsStore.KEY, null))
    }

    @Test fun allProtocolsRoundTripDetachedWithStableIdentityAndExactValues() {
        val original = values(GenerationProtocol.entries.map(::profile), setOf("cdn.example"), true)
        val first = store().save(original, null)
        val reopened = store().loadSnapshot()
        assertEquals(first.accountScope, reopened.accountScope)
        assertEquals(first.revision, reopened.revision)
        assertEquals(first.values.profiles, reopened.values.profiles)
        assertEquals(original.profiles.map { it.apiKey }, reopened.values.profiles.map { it.apiKey })
        assertEquals(original.profiles.map { it.endpoint }, reopened.profiles.map { it.endpoint })
        assertEquals(original.profiles.map { it.model }, reopened.profiles.map { it.model })
        val edited = values(reopened.values.profiles.map { it.copy(label = "changed human label") })
        val second = store().save(edited, reopened.revision)
        assertEquals(first.accountScope, second.accountScope)
        assertEquals(first.profiles.map { it.id }, second.profiles.map { it.id })
        assertNotEquals(first.revision, second.revision)
        assertEquals("  explicit label  ", first.profiles.first().label)
        val raw = prefs.getString(GenerationSettingsStore.KEY, null)!!
        assertFalse(raw.contains(profile().apiKey))
        assertFalse(first.toString().contains(profile().apiKey))
        assertFalse(first.values.toString().contains(profile().apiKey))
        assertFalse(first.values.profiles.first().toString().contains(profile().apiKey))
    }

    @Test fun endpointProtocolModelAndKeyEditsRetainPersistedScopeAndProfileId() {
        val first = store().save(values(), null)
        val changed = profile(GenerationProtocol.OPENAI_VIDEO_MULTIPART).copy(
            id = first.profiles.single().id, apiKey = "rotated-fixture-only", model = "explicit-video-model")
        val second = store().save(values(listOf(changed)), first.revision)
        assertEquals(first.accountScope, second.accountScope)
        assertEquals(first.profiles.single().id, second.profiles.single().id)
        assertEquals(GenerationProtocol.OPENAI_VIDEO_MULTIPART, second.profiles.single().protocol)
        assertEquals(changed, store().loadSnapshot().values.profiles.single())
        assertEquals(GenerationProtocol.OPENAI_IMAGES_JSON, first.profiles.single().protocol)
    }

    @Test fun staleEditorsAndFabricatedIdsCannotReplaceConfiguration() {
        val first = store().save(values(), null)
        rejects(GenerationSettingsFailure.STALE_EDIT) { store().save(values(), null) }
        val injected = first.values.profiles.single().copy(id = "profile_" + "a".repeat(32))
        rejects(GenerationSettingsFailure.STALE_EDIT) { store().save(values(listOf(injected)), first.revision) }
        assertEquals(first.revision, store().loadSnapshot().revision)
    }

    @Test fun newProfilesGetDifferentIdsButLabelEditsDoNotRekey() {
        val first = store().save(values(), null)
        val second = store().save(values(first.values.profiles + profile(GenerationProtocol.OPENAI_VIDEO_MULTIPART)), first.revision)
        assertEquals(first.profiles.single().id, second.profiles.first().id)
        assertEquals(2, second.profiles.map { it.id }.toSet().size)
        assertEquals(first.accountScope, second.accountScope)
    }

    @Test fun lostOrSwappedCredentialFailsClosedAndEditorPreservesOtherOriginalFields() {
        val first = store().save(values(), null)
        val id = first.profiles.single().id
        keys[id] = "different-fixture-key"
        rejects(GenerationSettingsFailure.CREDENTIAL_UNAVAILABLE) { store().loadSnapshot() }
        val editing = store().loadForEditing()
        assertEquals(setOf(id), editing.credentialUnavailableIds)
        assertEquals("", editing.values.profiles.single().apiKey)
        assertEquals(first.profiles.single().endpoint, editing.values.profiles.single().endpoint)
        assertEquals(first.profiles.single().model, editing.values.profiles.single().model)
        assertEquals(first.accountScope, editing.accountScope)
        val recovered = store().save(values(listOf(editing.values.profiles.single().copy(apiKey = profile().apiKey))), editing.revision)
        assertEquals(first.profiles.single().id, recovered.profiles.single().id)
        keys.clear()
        rejects(GenerationSettingsFailure.CREDENTIAL_UNAVAILABLE) { store().loadSnapshot() }
    }

    @Test fun unknownMissingOrMistypedMetadataRejectsBeforeCredentialRead() {
        store().save(values(), null)
        val good = prefs.getString(GenerationSettingsStore.KEY, null)!!
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.remove("scope") }, { it.put("version", "1") }, { it.put("extra", true) },
            { it.put("downloadsEnabled", "true") }, { it.put("maxPolls", 4.5) },
            { it.getJSONArray("profiles").getJSONObject(0).put("protocol", "FUTURE_PROTOCOL") },
            { it.getJSONArray("profiles").getJSONObject(0).remove("model") },
            { it.getJSONArray("profiles").getJSONObject(0).put("kind", "VIDEO") },
        )
        for (corrupt in corruptions) {
            val bad = JSONObject(good).also(corrupt)
            check(prefs.edit().putString(GenerationSettingsStore.KEY, bad.toString()).commit())
            val before = reads
            rejects(GenerationSettingsFailure.CORRUPT) { store().loadSnapshot() }
            assertEquals(before, reads)
        }
    }

    @Test fun validationRunsBeforeWritesAndNeverNormalizesEndpointsOrHosts() {
        val original = profile()
        val invalid = listOf(
            original.copy(endpoint = "http://api.example/images/generations"),
            original.copy(endpoint = "https://api.example"),
            original.copy(endpoint = "https://api.example/images/generations?x=1"),
            original.copy(endpoint = "https://api.example:444/images/generations"),
            original.copy(kind = GenerationKind.VIDEO), original.copy(apiKey = ""),
            original.copy(apiKey = "line\nbreak"),
        )
        invalid.forEach { rejects(GenerationSettingsFailure.INVALID_CONFIGURATION) { store().save(values(listOf(it)), null) } }
        listOf("*.example", "HTTPS://cdn.example", "CDN.example", "cdn.example:443", "cdn.example ", "127.0.0.1", "cdn..example").forEach {
            rejects(GenerationSettingsFailure.INVALID_CONFIGURATION) { store().save(values(hosts = setOf(it), enabled = true), null) }
        }
        assertEquals(0, writes); assertEquals(0, reads); assertTrue(prefs.all.isEmpty())
    }

    @Test fun downloadsAreSeparateExactHttpsAllowlistEmptyMeansDenyAll() {
        val first = store().save(values(hosts = setOf("cdn.example")), null)
        assertEquals(setOf("cdn.example"), first.values.downloadHosts)
        assertFalse(first.downloads.allows("https://cdn.example/file"))
        val enabled = store().save(values(first.values.profiles, setOf("cdn.example"), true), first.revision)
        assertTrue(enabled.downloads.allows("https://cdn.example/file?token=fixture"))
        listOf("http://cdn.example/file", "https://sub.cdn.example/file", "https://cdn.example.evil/file",
            "https://cdn.example:444/file", "https://api.example/file", "https://user@cdn.example/file").forEach {
            assertFalse(enabled.downloads.allows(it))
        }
        val empty = store().save(values(enabled.values.profiles, emptySet(), true), enabled.revision)
        assertFalse(empty.downloads.allows("https://cdn.example/file"))
    }

    @Test fun failedCredentialReadbackPersistsSavingAndRecoveryRetainsScopeAndIds() {
        rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) {
            store(save = { id, _ -> keys[id] = "incorrect-fixture-key" }).save(values(), null)
        }
        rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) { store().loadSnapshot() }
        // Separate process-fence namespace models a reopen without the in-memory fence.
        rejects(GenerationSettingsFailure.INCOMPLETE_SAVE) { store(scope = "$namespace-reopen").loadSnapshot() }
        val editing = store().loadForEditing()
        assertTrue(editing.incompleteSave)
        rejects(GenerationSettingsFailure.INCOMPLETE_SAVE) { store().save(values(), editing.revision) }
        val recovered = store().save(values(editing.values.profiles.map { it.copy(apiKey = profile().apiKey) }),
            editing.revision, confirmIncompleteSaveRecovery = true)
        assertEquals(editing.accountScope, recovered.accountScope)
        assertEquals(editing.values.profiles.map { it.id }, recovered.profiles.map { it.id })
        assertEquals(recovered.revision, store().loadSnapshot().revision)
    }

    private fun faultPreferences(failCommit: Int, throwAfterWrite: Boolean = false,
        mismatchRead: Boolean = false): SharedPreferences {
        var commits = 0
        return object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? =
                if (mismatchRead && commits == failCommit) "mismatched-readback" else prefs.getString(key, defValue)
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun commit(): Boolean {
                        val result = real.commit()
                        if (++commits == failCommit && !mismatchRead) {
                            if (throwAfterWrite) error("fixture post-write failure")
                            return false
                        }
                        return result
                    }
                }
            }
        }
    }

    @Test fun readyFalseThrowAndReadbackMismatchNeverReturnUsableSnapshot() {
        for (mode in 0..2) {
            setup()
            val faulty = faultPreferences(2, throwAfterWrite = mode == 1, mismatchRead = mode == 2)
            rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) { store(faulty).save(values(), null) }
            assertEquals("saving", JSONObject(prefs.getString(GenerationSettingsStore.KEY, null)!!).getString("state"))
            rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) { store().loadSnapshot() }
            rejects(GenerationSettingsFailure.INCOMPLETE_SAVE) { store(scope = "$namespace-disk").loadSnapshot() }
        }
    }

    @Test fun firstGateFailureDoesNotWriteCredentials() {
        rejects(GenerationSettingsFailure.SAVE_UNCONFIRMED) { store(faultPreferences(1)).save(values(), null) }
        assertEquals(0, writes)
    }

    @Test fun draftRequiresExplicitKindProtocolAndDownloadReviewPreservesRawFields() {
        val blank = GenerationSettingsDraftUi()
        try { blank.toSettingsValues(); fail() } catch (_: IllegalArgumentException) { }
        val saved = store().save(values(hosts = setOf("cdn.example"), enabled = true), null)
        val draft = saved.toGenerationSettingsDraft()
        assertEquals(saved.values.profiles.single().apiKey, draft.profiles.single().apiKey)
        assertEquals(saved.values.profiles.single().model, draft.profiles.single().model)
        assertEquals(saved.accountScope, draft.accountScope)
        assertFalse(draft.downloadHostsReviewed)
        try { draft.toSettingsValues(); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(saved.values.profiles, draft.copy(downloadHostsReviewed = true).toSettingsValues().profiles)
        listOf("cdn.example\n", "cdn.example\ncdn.example", " cdn.example", "https://cdn.example").forEach {
            try { draft.copy(downloadHosts = it, downloadHostsReviewed = true).toSettingsValues(); fail() }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(draft.toString().contains(profile().apiKey))
        assertFalse(GenerationProfileDraftUi(apiKey = profile().apiKey).toString().contains(profile().apiKey))
    }
}
