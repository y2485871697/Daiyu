package app.daiyu.android.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SavedSubAgentRuntimeBindingsTest {
    @Test fun deletedModelKeepsAcceptedSnapshotButRejectsNewAdmissionsAndNeverReadsMissingCatalog() {
        var deleted = false; var reads = 0
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val definition = bindings.freeze(owner, config, listOf(provider), { _, _ -> saved },
            latestConfig = { config }, latestModel = { reads++; error("catalog was deleted") },
            modelDeleted = { deleted }).getValue(profile.id)
        deleted = true
        assertFalse(definition.acceptsNewTask())
        definition.current().create()
        assertEquals(0, reads)
        assertEquals(saved.model, captured.single().model)
        assertEquals(saved.apiKey, captured.single().apiKey)
    }

    @Test fun deletionWhileResolverWaitedUsesLastAcceptedSnapshotWithoutRetryOrSwitch() {
        var deleted = false
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val definition = bindings.freeze(owner, config, listOf(provider), { _, _ -> saved },
            latestConfig = { config }, latestModel = { deleted = true; error("missing catalog") },
            modelDeleted = { deleted }).getValue(profile.id)
        definition.current().create()
        assertFalse(definition.acceptsNewTask())
        assertEquals(listOf(saved.model), captured.map { it.model })
    }

    private val owner = SubAgentConfigKey.Conversation("conversation")
    private val child = SubAgentModel("selected-record", "exact-api-model")
    private val provider = SubAgentProvider("explicit-provider", "Explicit", listOf(child))
    private val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
    private val config = ConversationSubAgentConfig(listOf(profile))
    private val saved = ModelSettings("https://example.invalid", child.modelId, "fixture-key", false)
    private val noNetwork = ModelPort { error("no network") }

    @Test fun deletionAfterHotModelChangeRetainsTheLastSuccessfulModelNotTheInitialOne() {
        var latest = config; var deletedModel: String? = null
        val changed = child.copy(id = "new-record", modelId = "new-api-model")
        val source = listOf(provider.copy(models = listOf(child, changed)))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val definition = bindings.freeze(owner, config, source, { _, _ -> saved },
            latestConfig = { latest }, latestModel = { id ->
                check(latest.profiles.single().modelId != deletedModel)
                bindings.freeze(owner, latest, source, { _, _ -> saved.copy(model = changed.modelId) }).getValue(id)
            }, modelDeleted = { it.modelId == deletedModel }).getValue(profile.id)
        latest = config.copy(profiles = listOf(profile.copy(modelId = changed.id)))
        definition.current().create()
        deletedModel = changed.id
        assertFalse(definition.acceptsNewTask())
        definition.current().create()
        assertEquals(listOf(changed.modelId, changed.modelId), captured.map { it.model })
    }

    @Test fun savedSelectionWorksWithoutShieldAndOldDefinitionsKeepTheirSnapshot() {
        var current = saved
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val resolve: (String, String) -> ModelSettings = { slot, selection ->
            assertEquals(provider.id, slot); assertEquals(child.id, selection); current
        }
        val first = bindings.freeze(owner, config, listOf(provider), resolve)
        assertTrue(captured.isEmpty())
        current = saved.copy(apiKey = "replacement-key")
        assertFalse(first.isCurrent())
        val second = bindings.freeze(owner, config, listOf(provider), resolve)
        first.getValue(profile.id).create(); second.getValue(profile.id).create()
        assertEquals(listOf("fixture-key", "replacement-key"), captured.map { it.apiKey })
        assertEquals(child.modelId, first.getValue(profile.id).display?.modelId)
        assertEquals(profile.name, first.getValue(profile.id).display?.agentName)
        assertTrue(captured.all { it.tools.isEmpty() })
    }

    @Test fun compatibleModelAndAdditionsUpdateOnlyCurrentSelectionNotOriginalFactory() {
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val changed = child.copy(id = "new-record", modelId = "new-api-model")
        val source = listOf(provider.copy(models = listOf(child, changed)))
        var latest = config
        val resolve: (String, String) -> ModelSettings = { _, id ->
            if (id == changed.id) saved.copy(model = changed.modelId) else saved
        }
        val original = bindings.freeze(owner, config, source, resolve,
            latestConfig = { latest }, latestModel = { id ->
                bindings.freeze(owner, latest.copy(profiles = latest.profiles.filter { it.id == id }), source, resolve).getValue(id)
            }).getValue(profile.id)
        latest = config.copy(profiles = listOf(profile.copy(modelId = changed.id),
            profile.copy(id = "added", name = "Added", providerId = "unavailable", modelId = "missing")))
        original.current().create(); original.create()
        assertEquals(listOf(changed.modelId, child.modelId), captured.map { it.model })
        latest = config.copy(profiles = listOf(profile.copy(modelId = changed.id)),
            parallelLimits = mapOf(SubAgentParallelModel(provider.id, child.modelId) to 0))
        original.current().create()
        assertEquals(changed.modelId, captured.last().model)
        latest = config.copy(profiles = listOf(profile.copy(role = "implementation")))
        assertThrows(IllegalStateException::class.java) { original.current().create() }
    }

    @Test fun newDispatchAndOldSelectionsShareTheSameStrictPolicy() {
        val changed = child.copy(id = "new-record", modelId = "new-api-model")
        val source = listOf(provider.copy(models = listOf(child, changed)))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val resolve: (String, String) -> ModelSettings = { _, id ->
            if (id == changed.id) saved.copy(model = changed.modelId) else saved
        }
        var latest = config
        fun freezeNext() = bindings.freeze(owner, latest, source, resolve)
        val prepared = bindings.freeze(owner, config, source, resolve,
            latestConfig = { latest }, latestModel = { id -> freezeNext().getValue(id) },
            latestModels = { freezeNext() })
        val original = prepared.getValue(profile.id)
        val extra = profile.copy(id = "added", name = "Added", modelId = changed.id)
        for (allowed in listOf(
            config.copy(profiles = listOf(profile.copy(modelId = changed.id))),
            config.copy(profiles = config.profiles + extra),
            config.copy(profiles = listOf(profile.copy(modelId = changed.id), extra))
        )) {
            latest = allowed
            val dispatched = prepared.forNewRequests()
            assertEquals(allowed.profiles.map { it.id }.toSet(), dispatched.keys)
            dispatched.getValue(profile.id).create(); original.current().create()
            assertEquals(captured[captured.size - 2].model, captured.last().model)
        }
        latest = config.copy(profiles = listOf(profile.copy(name = "Renamed", modelId = changed.id), extra),
            parallelLimits = mapOf(SubAgentParallelModel(provider.id, changed.modelId) to 2), appliedPresetId = "preset")
        assertEquals("Renamed", prepared.forNewRequests().getValue(profile.id).display?.agentName)
        assertEquals(2, original.current().parallelLimit)
        latest = config.copy(enabled = false)
        assertTrue(prepared.forNewRequests().isEmpty())
        // Old task selection is supplied independently of new-admission enabled flags.
        latest = config.copy(profiles = listOf(extra))
        assertEquals(setOf(extra.id), prepared.forNewRequests().keys)
        assertThrows(IllegalStateException::class.java) { original.current() }
    }

    @Test fun originalLifetimeIsRecheckedAfterLatestResolverReturns() {
        var valid = true
        val prepared = SavedSubAgentRuntimeBindings().freeze(owner, config, listOf(provider), { _, _ -> saved },
            latestConfig = { config }, latestModel = {
                valid = false
                app.daiyu.android.domain.delegation.ChildModelDefinition("replacement", 1) { error("must not open") }
            }, profileCurrent = { valid })
        assertThrows(IllegalStateException::class.java) { prepared.getValue(profile.id).current() }
    }

    @Test fun configurationRaceRejectsBeforeCreatingAnObsoleteModel() {
        var latest = config.copy(profiles = listOf(profile.copy(modelId = "new-record")))
        val changed = child.copy(id = "new-record", modelId = "new-api-model")
        val source = listOf(provider.copy(models = listOf(child, changed)))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val resolve: (String, String) -> ModelSettings = { _, id ->
            if (id == changed.id) saved.copy(model = changed.modelId) else saved
        }
        val prepared = bindings.freeze(owner, config, source, resolve,
            latestConfig = { latest }, latestModel = { id ->
                val selected = bindings.freeze(owner, latest, source, resolve).getValue(id)
                latest = latest.copy(enabled = false)
                selected
            })
        assertThrows(IllegalStateException::class.java) { prepared.getValue(profile.id).current().create() }
        assertTrue(captured.isEmpty())
    }

    @Test fun configurationRaceCannotAdmitAnUncheckedModelOrProfileSet() {
        var latest = config.copy(profiles = listOf(profile.copy(modelId = "new-record")))
        val changed = child.copy(id = "new-record", modelId = "new-api-model")
        val source = listOf(provider.copy(models = listOf(child, changed)))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val resolve: (String, String) -> ModelSettings = { _, id ->
            if (id == changed.id) saved.copy(model = changed.modelId) else saved
        }
        val prepared = bindings.freeze(owner, config, source, resolve,
            latestConfig = { latest }, latestModel = { id ->
                val selected = bindings.freeze(owner, latest, source, resolve).getValue(id)
                latest = latest.copy(enabled = false)
                selected
            }, latestModels = {
                val selected = bindings.freeze(owner, latest, source, resolve)
                latest = latest.copy(appliedPresetId = "preset")
                selected
            })
        assertThrows(IllegalStateException::class.java) { prepared.getValue(profile.id).current().create() }
        assertTrue(captured.isEmpty())
        latest = config.copy(profiles = listOf(profile.copy(modelId = changed.id)))
        assertThrows(IllegalStateException::class.java) { prepared.forNewRequests() }
        assertTrue(captured.isEmpty())
    }

    @Test fun disabledAdmissionReturnsEmptyWithoutReadingCredentials() {
        val bindings = SavedSubAgentRuntimeBindings()
        listOf(config.copy(enabled = false), config.copy(profiles = emptyList()),
            config.copy(profiles = listOf(profile.copy(enabled = false)))).forEach { selection ->
            assertTrue(bindings.freeze(owner, selection, listOf(provider), { _, _ -> error("credentials must not be read") }).isEmpty())
        }
    }

    @Test fun unavailableSelectionsAndMediaRejectBeforeReadingCredentials() {
        var reads = 0
        val bindings = SavedSubAgentRuntimeBindings()
        val resolver: (String, String) -> ModelSettings = { _, _ -> reads++; saved }
        for ((configured, source) in listOf(
            config to provider.copy(id = "other"),
            config to provider.copy(isEnabled = false),
            config to provider.copy(models = emptyList()),
            config to provider.copy(models = listOf(child.copy(isEnabled = false))),
            config to provider.copy(models = listOf(child.copy(supportsImageGeneration = true))),
            config.copy(profiles = listOf(profile.copy(role = "image_generation"))) to provider,
            config.copy(profiles = listOf(profile.copy(role = "implementation"))) to provider,
        )) assertThrows(IllegalArgumentException::class.java) {
            bindings.freeze(owner, configured, listOf(source), resolver)
        }
        assertEquals(0, reads)
    }

    @Test fun changedOwnerOrCatalogRevisionInvalidatesPreparationWithoutChangingOldModels() {
        var revision = 1
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val prepared = bindings.freeze(owner, config, listOf(provider), { _, _ -> saved }, bindingsCurrent = { revision == 1 })
        assertTrue(prepared.isCurrent())
        revision = 3
        assertFalse(prepared.isCurrent())
        prepared.getValue(profile.id).create()
        assertEquals("fixture-key", captured.single().apiKey)
    }

    @Test fun actualApiModelAndCustomModelMustMatchSavedSelection() {
        val bindings = SavedSubAgentRuntimeBindings()
        for (settings in listOf(saved.copy(model = "parent-model"), saved.copy(requestOptions =
            ProviderRequestOptions("""{"model":"unexpected"}""")), saved.copy(requestOptions =
            ProviderRequestOptions("""{"service_tier":"fast"}""")))) {
            assertThrows(IllegalArgumentException::class.java) {
                bindings.freeze(owner, config, listOf(provider), { _, _ -> settings })
            }
        }
    }

    @Test fun providerChangeDuringPreparationRejectsBeforeAnyModelIsCreated() {
        var reads = 0; var opens = 0
        val bindings = SavedSubAgentRuntimeBindings { opens++; noNetwork }
        assertThrows(IllegalArgumentException::class.java) {
            bindings.freeze(owner, config, listOf(provider), { _, _ ->
                if (++reads == 1) saved else saved.copy(apiKey = "replacement-key")
            })
        }
        assertEquals(0, opens)
    }

    @Test fun profilesShareProviderApiPoolButDifferentConversationsDoNot() {
        val second = profile.copy(id = "second")
        val pool = SubAgentParallelModel(provider.id, child.modelId)
        val configured = config.copy(profiles = listOf(profile, second), parallelLimits = mapOf(pool to 0))
        val bindings = SavedSubAgentRuntimeBindings()
        val first = bindings.freeze(owner, configured, listOf(provider), { _, _ -> saved })
        val other = bindings.freeze(SubAgentConfigKey.Conversation("other"), configured, listOf(provider), { _, _ -> saved })
        assertEquals(first.getValue(profile.id).poolKey, first.getValue(second.id).poolKey)
        assertNotEquals(first.getValue(profile.id).poolKey, other.getValue(profile.id).poolKey)
        assertEquals(0, first.getValue(second.id).parallelLimit)
    }

    private fun capture(selected: SubAgentProfile, settings: ModelSettings, model: SubAgentModel = child): ProviderSnapshot {
        var result: ProviderSnapshot? = null
        val bindings = SavedSubAgentRuntimeBindings { result = it; noNetwork }
        bindings.freeze(owner, config.copy(profiles = listOf(selected)), listOf(provider.copy(models = listOf(model))),
            { _, _ -> settings }).getValue(selected.id).create()
        return requireNotNull(result)
    }

    @Test fun reasoningAndSpeedRemainIndependentAndDoNotMutateSavedSettings() {
        val high = profile.copy(reasoning = ReasoningEffort.HIGH)
        val response = capture(high, saved)
        assertEquals("high", JSONObject(response.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("", saved.requestOptions.customBody)
        val messages = capture(high, saved.copy(protocol = ProviderProtocol.MESSAGES))
        assertEquals("adaptive", JSONObject(messages.requestOptions.customBody).getJSONObject("thinking").getString("type"))
        assertEquals("high", JSONObject(messages.requestOptions.customBody).getJSONObject("output_config").getString("effort"))
        val chat = capture(high, saved.copy(protocol = ProviderProtocol.CHAT_COMPLETIONS))
        assertEquals("high", JSONObject(chat.requestOptions.customBody).getString("reasoning_effort"))
        assertEquals("none", JSONObject(capture(profile, saved).requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("none", JSONObject(capture(profile.copy(reasoning = ReasoningEffort.DEFAULT), saved).requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        assertEquals("high", JSONObject(capture(profile, saved, child.copy(defaultReasoning = ReasoningEffort.HIGH)).requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        val off = capture(profile.copy(reasoning = ReasoningEffort.OFF), saved)
        assertEquals("none", JSONObject(off.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
    }

    @Test fun changedSpeedOnlyAffectsTheNextFrozenDefinition() {
        val gpt = child.copy(modelId = "gpt-6.1-sol", supportsGptSpeed = true, defaultReasoning = ReasoningEffort.HIGH)
        val key = SubAgentProfile.modelReasoningKey(provider.id, child.id)
        val fast = profile.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.FAST))
        val normal = fast.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.NORMAL))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val source = listOf(provider.copy(models = listOf(gpt)))
        val resolve: (String, String) -> ModelSettings = { _, _ -> saved.copy(model = gpt.modelId) }
        val first = bindings.freeze(owner, config.copy(profiles = listOf(fast)), source, resolve)
        val second = bindings.freeze(owner, config.copy(profiles = listOf(normal)), source, resolve)
        first.getValue(profile.id).create(); second.getValue(profile.id).create()
        assertEquals(listOf(GptSpeedMode.FAST, GptSpeedMode.NORMAL), captured.map { it.requestOptions.speedMode })
    }

    @Test fun codexAndOAuthFreezeAllThreeSpeedModesAndPreserveAuthentication() {
        val gpt = child.copy(modelId = "gpt-6.1-sol", supportsGptSpeed = true, defaultReasoning = ReasoningEffort.HIGH)
        val auth = app.daiyu.android.model.oauth.CodexAuth("00000000-0000-0000-0000-000000000001")
        val key = SubAgentProfile.modelReasoningKey(provider.id, child.id)
        for (oauth in listOf(false, true)) for (speed in GptSpeedMode.entries) {
            val settings = saved.copy(model = gpt.modelId,
                endpoint = if (oauth) app.daiyu.android.model.oauth.CodexOAuthProtocol.BASE_URL else saved.endpoint,
                responsesPath = if (oauth) "/responses" else "", apiKey = if (oauth) "" else saved.apiKey,
                oauth = auth.takeIf { oauth }, requestOptions = ProviderRequestOptions(codex = !oauth,
                    customBody = """{"metadata":{"fixture":"kept"}}"""))
            val frozen = capture(profile.copy(gptSpeedByModel = mapOf(key to speed)), settings, gpt)
            assertEquals(settings.oauth, frozen.oauth)
            assertEquals(settings.apiKey, frozen.apiKey)
            assertEquals(settings.requestOptions.codex, frozen.requestOptions.codex)
            assertEquals("kept", JSONObject(frozen.requestOptions.customBody).getJSONObject("metadata").getString("fixture"))
            assertEquals("high", JSONObject(frozen.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertEquals("""{"metadata":{"fixture":"kept"}}""", settings.requestOptions.customBody)
            assertEquals(speed, frozen.requestOptions.speedMode)
            val request = frozen.requestOptions.apply(JSONObject().put("model", gpt.modelId)
                .put("stream", true).put("input", "test"), ProviderProtocol.RESPONSES)
            if (oauth) app.daiyu.android.model.oauth.CodexRequestPolicy.apply(request, speed)
            val wire = request
            when (speed) {
                GptSpeedMode.NORMAL -> assertFalse(wire.has("service_tier"))
                GptSpeedMode.FAST -> assertEquals("priority", wire.getString("service_tier"))
                GptSpeedMode.ULTRA_FAST -> assertEquals("ultrafast", wire.getString("service_tier"))
            }
        }
    }

    @Test fun absentProfileOverrideInheritsSavedSpeedAndExplicitNormalWins() {
        val gpt = child.copy(modelId = "gpt-6.1-sol", supportsGptSpeed = true, defaultReasoning = ReasoningEffort.HIGH)
        val key = SubAgentProfile.modelReasoningKey(provider.id, child.id)
        for (speed in GptSpeedMode.entries) {
            val settings = saved.copy(model = gpt.modelId, requestOptions = ProviderRequestOptions(speedMode = speed))
            val inherited = capture(profile, settings, gpt)
            assertEquals(speed, inherited.requestOptions.speedMode)
            val explicit = capture(profile.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.NORMAL)), settings, gpt)
            assertEquals(GptSpeedMode.NORMAL, explicit.requestOptions.speedMode)
            assertEquals(speed, inherited.requestOptions.speedMode)
            assertEquals(speed, settings.requestOptions.speedMode)
            assertTrue(profile.gptSpeedByModel.isEmpty())
        }
    }
    @Test fun everyChildPreferenceReachesItsFactoryDespiteMandatoryModelMetadata() {
        val required = child.copy(modelId = "gpt-6.1-sol", reasoningMandatory = true, reasoningCanDisable = false)
        val captured = mutableListOf<ProviderSnapshot>()
        val binding = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        for (effort in ReasoningEffort.textChoices(required.modelId)) {
            val selected = profile.copy(reasoning = effort)
            val frozen = binding.freeze(owner, config.copy(profiles = listOf(selected)),
                listOf(provider.copy(models = listOf(required))), { _, _ -> saved.copy(model = required.modelId, reasoningOverride = false) })
            frozen.getValue(selected.id).create()
            assertEquals(if (effort == ReasoningEffort.OFF) "none" else effort.wireValue,
                JSONObject(captured.last().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertEquals(effort, selected.reasoning)
        }
        assertEquals(7, captured.size)
    }

}
