package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import app.daiyu.android.model.ProviderProtocol
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID

@Suppress("DEPRECATION")
class ModelSettingsRestoreTest : InstrumentationTestCase() {
    private enum class CommitOutcome { OK, FALSE_AFTER_WRITE, THROW_AFTER_WRITE, FALSE_BEFORE_WRITE }
    private val configKeys = setOf("endpoint", "model", "responses-path", "battery", "device-status", "protocol")
    private val materialKeys = setOf("key", "iv", "key-alias", "key-owner")
    private val gate = "credential-rebind-required"

    /** getAll, even without decrypting, counts as a credential read. Guard both reads and edits. */
    private inner class GuardedPreferences(val raw: SharedPreferences) : SharedPreferences by raw {
        var protectCredentials = true
        var reads = mutableSetOf<String?>()
        var writes = mutableSetOf<String?>()
        var commits = 0
        val outcomes = mutableListOf<CommitOutcome>()
        private fun read(name: String?) {
            reads.add(name)
            if(protectCredentials) check(name in configKeys || name == gate || name == "request-options") { "credential/unknown read: $name" }
        }
        private fun write(name: String?) {
            writes.add(name)
            if(protectCredentials) check(name in configKeys || name == gate) { "credential/unknown write: $name" }
        }
        override fun getAll(): MutableMap<String, *> {
            check(!protectCredentials) { "restore or gated load must not read all preferences" }
            return raw.all
        }
        override fun contains(key: String?): Boolean { read(key); return raw.contains(key) }
        override fun getString(key: String?, defValue: String?): String? { read(key); return raw.getString(key, defValue) }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean { read(key); return raw.getBoolean(key, defValue) }
        override fun edit(): SharedPreferences.Editor {
            val editor = raw.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { write(key); editor.putString(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { write(key); editor.putBoolean(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { write(key); editor.remove(key); return this }
                override fun commit(): Boolean {
                    commits++
                    val outcome = if(outcomes.isEmpty()) CommitOutcome.OK else outcomes.removeAt(0)
                    if(outcome == CommitOutcome.FALSE_BEFORE_WRITE) return false
                    check(editor.commit()) { "test preferences write failed" }
                    return when(outcome) {
                        CommitOutcome.FALSE_AFTER_WRITE -> false
                        CommitOutcome.THROW_AFTER_WRITE -> throw IllegalStateException("injected commit failure")
                        else -> true
                    }
                }
            }
        }
        fun resetObservations() { reads.clear(); writes.clear(); commits = 0 }
    }
    private inner class Fixture {
        val prefix = "restore-test-${UUID.randomUUID()}"
        val isolated = testContext(instrumentation.targetContext, "$prefix-")
        val raw = isolated.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        val spy = GuardedPreferences(raw)
        val context = object : ContextWrapper(isolated) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if(name == "model-settings") spy else super.getSharedPreferences(name, mode)
        }
        val store = ModelSettingsStore(context, prefix)
        fun reopen() = ModelSettingsStore(context, prefix)
        val settings = ModelSettings("https://example.invalid/gateway", "fixture-model", "synthetic-key", true, "/responses", true)
        fun save(value: ModelSettings = settings) {
            spy.protectCredentials = false
            try { store.save(value) } finally { spy.protectCredentials = true }
        }
        fun seedMaterial() {
            assertTrue(raw.edit().putString("key", "synthetic-ciphertext").putString("iv", "synthetic-iv")
                .putString("key-alias", "synthetic-alias").putString("key-owner", "synthetic-owner")
                .putString("unrelated", "untouched").commit())
        }
        fun seedConfiguration() {
            assertTrue(raw.edit().putString("endpoint", settings.endpoint).putString("model", settings.model)
                .putString("responses-path", settings.responsesPath).putBoolean("battery", true)
                .putBoolean("device-status", true).commit())
        }
        fun material() = materialKeys.associateWith { raw.getString(it, null) }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fresh() = Fixture().also { fixtures.add(it) }
    private fun rejected(work: () -> Unit) {
        try { work(); fail("operation must be rejected") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.raw.edit().clear().commit()
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val aliases = java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.prefix}.") }
                aliases.forEach { keys.deleteEntry(it) }
            }
        } finally { super.tearDown() }
    }

    fun testPrepareIsReadOnlyImmutableAndLimitedToNonSecretKeys() {
        val f = fresh(); f.seedConfiguration(); f.seedMaterial()
        val before = HashMap(f.raw.all)
        val json = JSONObject().put("model", "restored-model").put("battery", true)
        val plan = f.store.prepareNonSecretRestore(json)
        assertEquals(before, f.raw.all); assertEquals(0, f.spy.commits); assertTrue(f.spy.writes.isEmpty())
        assertEquals(configKeys, f.spy.reads); assertEquals(configKeys - "protocol", plan.before.keys)
        assertEquals(plan.before, f.store.nonSecretConfiguration())
        assertEquals(f.settings.endpoint, plan.after["endpoint"]); assertEquals("restored-model", plan.after["model"])
        assertEquals(false, plan.after["battery"]); assertEquals(false, plan.after["device-status"])
        assertTrue(plan.credentialRebindRequired); assertFalse(plan.isNoOp)
        json.put("model", "mutated-after-prepare")
        assertEquals("restored-model", plan.after["model"])
        try { (plan.after as MutableMap<String, Any?>)["model"] = "tampered"; fail("plan must be immutable") }
        catch (_: UnsupportedOperationException) {}
        try { (plan.before as MutableMap<String, Any?>).clear(); fail("baseline must be immutable") }
        catch (_: UnsupportedOperationException) {}
        assertEquals(before, f.raw.all)
    }

    fun testEmptyJsonIsTrueNoOpIncludingFieldPresenceAndAuthorizations() {
        for(seed in listOf(false, true)) {
            val f = fresh(); f.seedMaterial(); if(seed) f.seedConfiguration()
            val before = HashMap(f.raw.all)
            val plan = f.store.prepareNonSecretRestore(JSONObject())
            assertTrue(plan.isNoOp); assertFalse(plan.credentialRebindRequired); assertEquals(plan.before, plan.after)
            f.store.applyNonSecretRestore(plan, authorized = true)
            assertEquals(0, f.spy.commits); assertEquals(before, f.raw.all)
        }
    }

    fun testPartialRestorePreservesOmissionsAndMaterialAndRevokesTools() {
        val f = fresh(); f.seedConfiguration(); f.seedMaterial()
        assertTrue(f.raw.edit().remove("responses-path").commit())
        val material = f.material()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
        f.store.applyNonSecretRestore(plan, authorized = true)
        assertEquals(f.settings.endpoint, f.raw.getString("endpoint", null)); assertFalse(f.raw.contains("responses-path"))
        assertEquals("restored", f.raw.getString("model", null)); assertFalse(f.raw.getBoolean("battery", true))
        assertFalse(f.raw.getBoolean("device-status", true)); assertTrue(f.raw.getBoolean(gate, false))
        assertEquals(material, f.material()); assertEquals("untouched", f.raw.getString("unrelated", null))
        assertTrue(f.spy.reads.intersect(materialKeys).isEmpty()); assertTrue(f.spy.writes.intersect(materialKeys).isEmpty())
    }

    fun testExplicitEmptyStringsClearRatherThanKeepPreviousValues() {
        val f = fresh(); f.seedConfiguration(); f.seedMaterial()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("endpoint", "").put("model", "").put("responses-path", ""))
        f.store.applyNonSecretRestore(plan, true)
        listOf("endpoint", "model", "responses-path").forEach { assertTrue(f.raw.contains(it)); assertEquals("", f.raw.getString(it, null)) }
        assertTrue(f.raw.getBoolean(gate, false)); assertTrue(f.reopen().loadForEditing().credentialUnavailable)
    }

    fun testRequiresExplicitAuthorizationAndStoreBoundPlan() {
        val f = fresh(); f.seedConfiguration(); val before = HashMap(f.raw.all)
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
        rejected { f.store.applyNonSecretRestore(plan, false) }
        rejected { f.reopen().applyNonSecretRestore(plan, true) }
        rejected { fresh().store.applyNonSecretRestore(plan, true) }
        assertEquals(before, f.raw.all); assertEquals(0, f.spy.commits)
    }

    fun testStalePlansRejectValueAndPresenceChangesBeforeCommit() {
        for(name in configKeys) {
            val f = fresh(); f.seedConfiguration()
            if(name == "protocol") assertTrue(f.raw.edit().putString("protocol", "responses").commit())
            val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
            assertTrue(f.raw.edit().remove(name).commit()); val afterConcurrentChange = HashMap(f.raw.all)
            rejected { f.store.applyNonSecretRestore(plan, true) }
            assertEquals(0, f.spy.commits); assertEquals(afterConcurrentChange, f.raw.all)
        }
        val f = fresh(); f.seedConfiguration()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
        assertTrue(f.raw.edit().putString("model", "concurrent-save").commit())
        rejected { f.store.applyNonSecretRestore(plan, true) }; assertEquals(0, f.spy.commits)
    }

    fun testPlanExpiresAfterNormalSaveOnAnotherStoreInstance() {
        val f = fresh(); f.save()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
        f.spy.protectCredentials = false
        try { f.reopen().save(f.settings.copy(model = "concurrently-saved", apiKey = "explicit-new-key")) }
        finally { f.spy.protectCredentials = true }
        val before = HashMap(f.raw.all); f.spy.resetObservations()
        rejected { f.store.applyNonSecretRestore(plan, true) }
        assertEquals(before, f.raw.all); assertEquals(0, f.spy.commits); assertTrue(f.spy.writes.isEmpty())
    }

    fun testIllegalKeysTypesEndpointsPathsAndModelLengthsRejectedReadOnly() {
        val f = fresh(); f.seedConfiguration(); f.seedMaterial(); val before = HashMap(f.raw.all)
        val invalid = mutableListOf<JSONObject>()
        (materialKeys + setOf(gate, "unknown", "apiKey")).forEach { invalid.add(JSONObject().put(it, "synthetic")) }
        listOf("endpoint", "model", "responses-path").forEach { name ->
            listOf<Any>(false, 1, JSONObject.NULL, JSONObject(), org.json.JSONArray()).forEach { invalid.add(JSONObject().put(name, it)) }
        }
        listOf("battery", "device-status").forEach { name ->
            listOf<Any>("true", 1, JSONObject.NULL).forEach { invalid.add(JSONObject().put(name, it)) }
        }
        listOf("http://example.invalid", "https://user:pass@example.invalid", "https://example.invalid?q=1",
            "https://example.invalid#fragment", "https://example.invalid:0", "https://example.invalid/chat/completions",
            "https://example.invalid\n").forEach { invalid.add(JSONObject().put("endpoint", it)) }
        listOf("responses", "//evil.invalid/responses", "/responses?q=1", "/responses#fragment", "/../responses",
            "/%2e%2e/responses", "/responses\n", "/chat/completions").forEach { invalid.add(JSONObject().put("responses-path", it)) }
        listOf("m".repeat(201), "model\nname", "model\rname", " ").forEach { invalid.add(JSONObject().put("model", it)) }
        invalid.add(JSONObject().put("endpoint", "x".repeat(2001)))
        invalid.forEach { rejected { f.store.prepareNonSecretRestore(it) } }
        assertEquals(before, f.raw.all); assertEquals(0, f.spy.commits); assertTrue(f.spy.writes.isEmpty())
        assertEquals("m".repeat(200), f.store.prepareNonSecretRestore(JSONObject().put("model", "m".repeat(200))).after["model"])
    }

    fun testEndpointPathAndModelChangesPersistRebindGateBeforeAnyCredentialRead() {
        val changes = mapOf("endpoint" to "https://restored.invalid", "responses-path" to "/new-responses", "model" to "restored-model")
        changes.forEach { (name, value) ->
            val f = fresh(); f.save(); val material = f.material()
            f.spy.resetObservations()
            f.store.applyNonSecretRestore(f.store.prepareNonSecretRestore(JSONObject().put(name, value)), true)
            assertEquals(material, f.material()); assertTrue(f.raw.getBoolean(gate, false))
            val reopened = f.reopen()
            rejected { reopened.load() }
            val editing = reopened.loadForEditing()
            assertTrue(editing.credentialUnavailable); assertEquals("", editing.settings.apiKey)
            assertEquals(value, f.raw.getString(name, null)); assertEquals(emptySet<Any>(), editing.settings.enabledTools())
            assertTrue(f.spy.reads.intersect(materialKeys).isEmpty())
            f.store.applyNonSecretRestore(f.store.prepareNonSecretRestore(JSONObject().put("battery", true)), true)
            assertTrue(f.raw.getBoolean(gate, false)); rejected { reopened.load() }
        }
    }

    fun testSameModelOrToolOnlyRestoreDoesNotRequireRebindButAlwaysDisablesTools() {
        val f = fresh(); f.save()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", f.settings.model).put("battery", true).put("device-status", true))
        assertFalse(plan.credentialRebindRequired)
        f.store.applyNonSecretRestore(plan, true)
        assertFalse(f.raw.contains(gate)); assertFalse(f.raw.getBoolean("battery", true)); assertFalse(f.raw.getBoolean("device-status", true))
        f.spy.protectCredentials = false
        assertEquals(f.settings.copy(batteryEnabled = false, deviceStatusEnabled = false), f.reopen().load())
    }

    fun testOnlySuccessfulExplicitKeySaveClearsPersistentRebindGate() {
        val f = fresh(); f.save()
        f.store.applyNonSecretRestore(f.store.prepareNonSecretRestore(JSONObject().put("model", "restored")), true)
        val editing = f.reopen().loadForEditing()
        rejected { f.save(editing.settings) }; assertTrue(f.raw.getBoolean(gate, false))
        val replacement = editing.settings.copy(apiKey = "explicit-replacement-key")
        f.save(replacement)
        assertFalse(f.raw.contains(gate))
        f.spy.protectCredentials = false
        assertEquals(replacement, f.reopen().load())
    }

    fun testFalseAndThrowCommitCompensateValuesPresenceAndGateWithoutTouchingMaterial() {
        for(outcome in listOf(CommitOutcome.FALSE_AFTER_WRITE, CommitOutcome.THROW_AFTER_WRITE)) {
            for(existingGate in listOf(false, true)) {
                val f = fresh(); f.seedMaterial()
                assertTrue(f.raw.edit().putString("model", "old-model").putBoolean("battery", true).commit())
                if(existingGate) assertTrue(f.raw.edit().putBoolean(gate, true).commit())
                val before = HashMap(f.raw.all)
                val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored").put("endpoint", "https://restored.invalid"))
                f.spy.outcomes.add(outcome)
                rejected { f.store.applyNonSecretRestore(plan, true) }
                assertEquals(2, f.spy.commits); assertEquals(before, f.raw.all)
                assertTrue(f.spy.reads.intersect(materialKeys).isEmpty()); assertTrue(f.spy.writes.intersect(materialKeys).isEmpty())
                // Successful compensation does not permanently poison the store.
                f.store.applyNonSecretRestore(plan, true); assertEquals("restored", f.raw.getString("model", null))
            }
        }
    }

    fun testCompensationFailureBlocksLoadAndRestoreUntilSuccessfulExplicitSave() {
        for(compensation in listOf(CommitOutcome.FALSE_BEFORE_WRITE, CommitOutcome.THROW_AFTER_WRITE)) {
            val f = fresh(); f.seedConfiguration(); f.seedMaterial()
            val plan = f.store.prepareNonSecretRestore(JSONObject().put("model", "restored"))
            f.spy.outcomes.addAll(listOf(CommitOutcome.FALSE_AFTER_WRITE, compensation))
            rejected { f.store.applyNonSecretRestore(plan, true) }
            val reopened = f.reopen()
            rejected { reopened.load() }; assertTrue(reopened.loadForEditing().credentialUnavailable)
            assertEquals("", reopened.loadForEditing().settings.apiKey)
            val retry = reopened.prepareNonSecretRestore(JSONObject().put("battery", false))
            val commits = f.spy.commits
            rejected { reopened.applyNonSecretRestore(retry, true) }; assertEquals(commits, f.spy.commits)
            rejected { reopened.applyNonSecretRestore(reopened.prepareNonSecretRestore(JSONObject()), true) }
            f.save(f.settings.copy(apiKey = "explicit-repair-key")); assertFalse(f.raw.contains(gate))
            f.spy.protectCredentials = false
            assertEquals("explicit-repair-key", reopened.load().apiKey)
        }
    }

    fun testRestoreCannotClearFailureBarrierCreatedByNormalSave() {
        val f = fresh(); f.save()
        f.store.applyNonSecretRestore(f.store.prepareNonSecretRestore(JSONObject().put("model", "restored")), true)
        f.spy.outcomes.addAll(listOf(CommitOutcome.FALSE_AFTER_WRITE, CommitOutcome.FALSE_BEFORE_WRITE))
        rejected { f.save(f.settings.copy(apiKey = "failed-replacement")) }
        assertTrue(f.raw.getBoolean(gate, false))
        val reopened = f.reopen()
        rejected { reopened.load() }; assertTrue(reopened.loadForEditing().credentialUnavailable)
        val plan = reopened.prepareNonSecretRestore(JSONObject().put("model", "another-restore"))
        val commits = f.spy.commits
        rejected { reopened.applyNonSecretRestore(plan, true) }; assertEquals(commits, f.spy.commits)
        f.save(f.settings.copy(apiKey = "explicit-repair"))
        f.spy.protectCredentials = false
        assertEquals("explicit-repair", reopened.load().apiKey)
    }

    fun testNormalSaveFalseAndThrowFailuresPreserveRebindGateAndOldCredential() {
        for(outcome in listOf(CommitOutcome.FALSE_AFTER_WRITE, CommitOutcome.THROW_AFTER_WRITE)) {
            val f = fresh(); f.save()
            f.store.applyNonSecretRestore(f.store.prepareNonSecretRestore(JSONObject().put("model", "restored")), true)
            val before = HashMap(f.raw.all)
            f.spy.outcomes.add(outcome)
            rejected { f.save(f.settings.copy(apiKey = "replacement-not-committed")) }
            assertEquals(before, f.raw.all); assertTrue(f.raw.getBoolean(gate, false))
            rejected { f.reopen().load() }; assertEquals("", f.reopen().loadForEditing().settings.apiKey)
            f.save(f.settings.copy(apiKey = "replacement-committed"))
            assertFalse(f.raw.contains(gate)); f.spy.protectCredentials = false
            assertEquals("replacement-committed", f.reopen().load().apiKey)
        }
    }
    fun testLegacyProtocolAndMalformedProtocolCanBeEditedWithoutCredentialRead() {
        val f=fresh();f.save()
        assertFalse(f.raw.contains("protocol"))
        f.spy.protectCredentials=false
        assertEquals(ProviderProtocol.RESPONSES,f.reopen().load().protocol)
        for(bad in listOf<Any>("", "unknown", "RESPONSES", false)) {
            val edit=f.raw.edit()
            if(bad is String) edit.putString("protocol",bad) else edit.putBoolean("protocol",false)
            assertTrue(edit.commit());f.spy.protectCredentials=true;f.spy.resetObservations()
            rejected {f.reopen().load()}
            val editing=f.reopen().loadForEditing()
            assertEquals(ProviderProtocol.UNKNOWN,editing.settings.protocol)
            assertEquals("",editing.settings.apiKey);assertTrue(editing.credentialUnavailable)
            assertTrue(f.spy.reads.intersect(materialKeys).isEmpty())
            f.spy.protectCredentials=false
            f.store.save(f.settings,protocolChangeAuthorized=true)
            assertFalse(f.raw.contains("protocol"))
        }
    }

    fun testProtocolSaveRequiresRebindAndCompensatesProtocolPresence() {
        val f=fresh();f.save();f.spy.protectCredentials=false
        val next=f.settings.copy(endpoint="https://example.invalid/v1/messages",responsesPath="",
            protocol=ProviderProtocol.MESSAGES,apiKey="explicit-messages-key")
        val before=HashMap(f.raw.all)
        rejected {f.store.save(next)};assertEquals(before,f.raw.all)
        for(outcome in listOf(CommitOutcome.FALSE_AFTER_WRITE,CommitOutcome.THROW_AFTER_WRITE)) {
            f.spy.outcomes.add(outcome)
            rejected {f.store.save(next,protocolChangeAuthorized=true)}
            assertEquals(before,f.raw.all)
        }
        f.store.save(next,protocolChangeAuthorized=true)
        assertEquals(next,f.reopen().load());assertEquals("messages",f.raw.getString("protocol",null))
        val messages=HashMap(f.raw.all)
        f.spy.outcomes.add(CommitOutcome.FALSE_AFTER_WRITE)
        rejected {f.store.save(f.settings,protocolChangeAuthorized=true)}
        assertEquals(messages,f.raw.all)
        f.store.save(f.settings,protocolChangeAuthorized=true);assertFalse(f.raw.contains("protocol"))
    }

    fun testProtocolRestoreKeepsLegacyOmissionsAndPersistentRebindGate() {
        val f=fresh();f.save();val material=f.material()
        val incoming=JSONObject().put("protocol","messages").put("responses-path","")
            .put("endpoint","https://example.invalid/v1/messages")
        val plan=f.store.prepareNonSecretRestore(incoming)
        assertTrue(plan.credentialRebindRequired)
        f.spy.outcomes.add(CommitOutcome.FALSE_AFTER_WRITE)
        val before=HashMap(f.raw.all)
        rejected {f.store.applyNonSecretRestore(plan,true)};assertEquals(before,f.raw.all)
        f.store.applyNonSecretRestore(plan,true)
        assertEquals(material,f.material());assertTrue(f.raw.getBoolean(gate,false))
        assertFalse(f.raw.getBoolean("battery",true));assertFalse(f.raw.getBoolean("device-status",true))
        assertEquals(ProviderProtocol.MESSAGES,f.reopen().loadForEditing().settings.protocol)
        val legacy=f.store.prepareNonSecretRestore(JSONObject().put("model","legacy"))
        assertEquals("messages",legacy.after["protocol"])
        val empty=f.store.prepareNonSecretRestore(JSONObject())
        assertTrue(empty.isNoOp);assertEquals(empty.before,empty.after)
        rejected {f.reopen().load()}
        rejected {f.store.prepareNonSecretRestore(JSONObject().put("protocol","unexpected"))}
        rejected {f.store.prepareNonSecretRestore(JSONObject().put("responses-path","/responses"))}
        assertEquals(material,f.material())
    }

    fun testProtocolRestoreRemainsBehindCredentialReadAndWriteGuards() {
        val f=fresh();f.seedConfiguration();f.seedMaterial()
        val material=f.material();val before=HashMap(f.raw.all)
        f.spy.resetObservations()
        assertTrue(f.spy.protectCredentials)
        val plan=f.store.prepareNonSecretRestore(JSONObject().put("protocol","messages")
            .put("responses-path","").put("endpoint","https://example.invalid/v1/messages"))
        assertTrue(plan.credentialRebindRequired);assertEquals(before,f.raw.all)
        assertEquals(configKeys,f.spy.reads);assertTrue(f.spy.writes.isEmpty());assertEquals(0,f.spy.commits)
        f.store.applyNonSecretRestore(plan,true)
        assertTrue(f.spy.protectCredentials);assertEquals(material,f.material())
        assertEquals("messages",f.raw.getString("protocol",null));assertTrue(f.raw.getBoolean(gate,false))
        assertFalse(f.raw.getBoolean("battery",true));assertFalse(f.raw.getBoolean("device-status",true))
        rejected {f.reopen().load()}
        val editing=f.reopen().loadForEditing()
        assertEquals("",editing.settings.apiKey);assertEquals(ProviderProtocol.MESSAGES,editing.settings.protocol)
        assertTrue(editing.credentialUnavailable)
        assertTrue(f.spy.reads.all {it in configKeys || it==gate || it=="request-options"})
        assertTrue(f.spy.writes.all {it in configKeys || it==gate})
        assertTrue(f.spy.reads.intersect(materialKeys).isEmpty())
        assertTrue(f.spy.writes.intersect(materialKeys).isEmpty())
    }

    fun testRequestOptionsSaveFailureRestoresValueAndPresence() {
        val configured = app.daiyu.android.model.ProviderRequestOptions("""{"temperature":0.2}""",
            app.daiyu.android.model.GptSpeedMode.FAST)
        for (outcome in listOf(CommitOutcome.FALSE_AFTER_WRITE, CommitOutcome.THROW_AFTER_WRITE)) {
            for (transition in 0..2) {
                val f = fresh()
                val old = f.settings.copy(requestOptions = if (transition == 2) app.daiyu.android.model.ProviderRequestOptions() else configured)
                f.save(old)
                val before = HashMap(f.raw.all)
                val next = old.copy(requestOptions = if (transition == 1) app.daiyu.android.model.ProviderRequestOptions()
                    else configured.copy(customBody = """{"temperature":0.7}"""))
                f.spy.outcomes.add(outcome)
                rejected { f.save(next) }
                assertEquals(before, f.raw.all)
                f.spy.protectCredentials = false
                assertEquals(old, f.reopen().load())
            }
        }
    }

    fun testHeaderEnvelopeSaveFailureRestoresValueAndPresence() {
        val header = app.daiyu.android.model.ProviderHeaders.parse("""{"x-tenant":"original-secret"}""")
        for (outcome in listOf(CommitOutcome.FALSE_BEFORE_WRITE, CommitOutcome.FALSE_AFTER_WRITE, CommitOutcome.THROW_AFTER_WRITE)) {
            for (transition in 0..2) {
                val f = fresh()
                val original = f.settings.copy(customHeaders = if (transition == 2) app.daiyu.android.model.ProviderHeaders.EMPTY else header)
                f.save(original)
                val before = HashMap(f.raw.all)
                val replacement = original.copy(customHeaders = if (transition == 1) app.daiyu.android.model.ProviderHeaders.EMPTY
                    else app.daiyu.android.model.ProviderHeaders.parse("""{"x-tenant":"replacement-secret"}"""))
                f.spy.outcomes.add(outcome)
                rejected { f.save(replacement) }
                assertEquals(before, f.raw.all)
                f.spy.protectCredentials = false
                assertEquals(original, f.reopen().load())
            }
        }
    }

    fun testToolOnlyRestoreNeverReadsOrWritesHeaderEnvelope() {
        val f = fresh()
        f.save(f.settings.copy(customHeaders = app.daiyu.android.model.ProviderHeaders.parse("""{"x-tenant":"kept-secret"}""")))
        val envelope = listOf("request-headers", "request-headers-iv", "key-alias").associateWith { f.raw.getString(it, null) }
        f.spy.resetObservations()
        val plan = f.store.prepareNonSecretRestore(JSONObject().put("battery", false))
        f.store.applyNonSecretRestore(plan, true)
        assertTrue(f.spy.reads.none { it in envelope })
        assertTrue(f.spy.writes.none { it in envelope })
        assertEquals(envelope, envelope.keys.associateWith { f.raw.getString(it, null) })
        f.spy.protectCredentials = false
        assertEquals("kept-secret", JSONObject(f.store.load().customHeaders.encode()).getString("x-tenant"))
    }

}
