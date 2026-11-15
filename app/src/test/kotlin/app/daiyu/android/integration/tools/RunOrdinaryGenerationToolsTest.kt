package app.daiyu.android.integration.tools

import app.daiyu.android.ControllerGenerationResources
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.model.generation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RunOrdinaryGenerationToolsTest {
    private val message = Message(MessageId("a"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
        emptyList(), RunStatus.RUNNING)
    private val owner = runnerOwner(message)
    private val auth = TaskAuthorization("s", "r")
    private val png = GenerationInput(byteArrayOf(1, 2, 3), "image/png")
    private val mask = GenerationInput(byteArrayOf(4, 5, 6), "image/png")
    private val assets = mapOf("source" to png, "mask" to mask)
    private fun settings(vararg protocols: GenerationProtocol): GenerationSettingsSnapshot = GenerationSettingsSnapshot(
        "account", "revision", GenerationSettingsValues(protocols.mapIndexed { index, protocol ->
            GenerationProfileSettings("p$index", "profile $index", protocol.kind,
                protocol, "https://example.test/v1" + protocol.suffix, "model", "unique-generation-fixture-secret")
        }, 4, false, emptySet()))
    private fun leaf(settings: GenerationSettingsSnapshot, inputs: Map<String, GenerationInput> = assets) =
        RunOrdinaryGenerationTools(owner, auth, settings,
            ControllerGenerationResources("account", { error("prepare opened DB") }, { "account" }, File("/unused")), inputs) { true }
    private fun prepare(leaf: RunOrdinaryGenerationTools, json: String, video: Boolean = false): PreparedTool {
        val tool = leaf.tools.single { it.definition.name == if (video) RunGenerationTools.VIDEO_TOOL else RunGenerationTools.IMAGE_TOOL }
        return tool.prepare(tool.definition.schema.parse(json))
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("invalid generation input accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun dynamicMediaToolsAndPaidEffectUseTheLatestTrustedConfiguration() {
        val image = settings(GenerationProtocol.OPENAI_IMAGES_JSON)
        val video = settings(GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        var current: GenerationSettingsSnapshot? = null
        val scope = RunOrdinaryGenerationTools(owner, auth, null, null,
            latestConfiguration = { current?.let { snapshot ->
                OrdinaryGenerationConfiguration(snapshot) { MediaTaskAdmission({ true }) }
            } }) { true }
        try {
            assertTrue(scope.tools.isEmpty())
            current = image
            assertEquals(RunGenerationTools.IMAGE_TOOL, scope.tools.single().definition.name)
            current = video
            assertEquals(RunGenerationTools.VIDEO_TOOL, scope.tools.single().definition.name)
            val prepared = prepare(scope, """{"prompt":"animate"}""", video = true)
            val batch = video.profiles.single().freeze("dynamic", listOf(GenerationRunConfig.Item("item_0", "animate")))
            assertEquals(RunGenerationTools.writeEffect(video.accountScope, owner,
                GenerationRunConfig(listOf(batch), video.maxPolls), batch), prepared.writeEffect)
            current = null
            assertTrue(scope.tools.isEmpty())
        } finally { scope.close() }
    }

    @Test fun theSamePreparedMediaInvocationRefreshesItsExactEffectBeforeTheFirstReservation() {
        val old = settings(GenerationProtocol.OPENAI_IMAGES_JSON)
        val updated = GenerationSettingsSnapshot("account", "edited", GenerationSettingsValues(
            old.values.profiles.map { it.copy(model = "updated-model") }, old.maxPolls, false, emptySet()))
        var current = old
        val scope = RunOrdinaryGenerationTools(owner, auth, null, null,
            latestConfiguration = { OrdinaryGenerationConfiguration(current) { MediaTaskAdmission({ true }) } }) { true }
        try {
            val exposed = scope.tools.single()
            val arguments = exposed.definition.schema.parse("""{"prompt":"same invocation"}""")
            val original = exposed.prepare(arguments)
            current = updated
            var reservedEffect: String? = null
            val ledger = ToolLedger(object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    val rows = mutableListOf<LedgerEntry>(); block(rows)
                    reservedEffect = rows.single().effectKey
                    throw java.io.IOException("stop before execution")
                }
            })
            val catalog = ToolRegistry().register(object : RegisteredTool {
                override val definition = exposed.definition
                override fun prepare(arguments: ToolArguments) = original
            }).snapshot()
            val result = ToolDispatcher(catalog, SessionCapabilities("s", setOf(RunGenerationTools.CAPABILITY)), ledger)
                .dispatchOwned(auth, listOf(ToolInvocation(ToolCallId("same-call"), exposed.definition.name,
                    arguments.canonicalJson())), owner).single().outcome
            val batch = updated.profiles.single().freeze("dynamic", listOf(GenerationRunConfig.Item("item_0", "same invocation")))
            val expected = RunGenerationTools.writeEffect(updated.accountScope, owner, GenerationRunConfig(listOf(batch), updated.maxPolls), batch)
            var expectedKey: String? = null
            val expectedLedger = ToolLedger(object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    val rows = mutableListOf<LedgerEntry>(); block(rows)
                    expectedKey = rows.single().effectKey
                    throw java.io.IOException("stop before execution")
                }
            })
            val expectedCatalog = ToolRegistry().register(object : RegisteredTool {
                override val definition = exposed.definition
                override fun prepare(arguments: ToolArguments) = PreparedTool.approvedWrite(expected,
                    approve = { null }, admit = { null }, execute = { error("must not execute") })
            }).snapshot()
            ToolDispatcher(expectedCatalog, SessionCapabilities("s", setOf(RunGenerationTools.CAPABILITY)), expectedLedger)
                .dispatchOwned(auth, listOf(ToolInvocation(ToolCallId("expected-call"), exposed.definition.name,
                    arguments.canonicalJson())), owner)
            assertNotNull(reservedEffect); assertEquals(expectedKey, reservedEffect)
            assertNotEquals(original.writeEffect, expected)
            assertEquals("ledger_unavailable", (result as DispatchOutcome.NotExecuted).code)
        } finally { scope.close() }
    }

    @Test fun admissionBindsOnlyTheActuallySelectedMediaProfile() {
        val snapshot = settings(GenerationProtocol.OPENAI_IMAGES_JSON, GenerationProtocol.OPENAI_IMAGES_JSON)
        val selected = mutableListOf<String>()
        val scope = RunOrdinaryGenerationTools(owner, auth, null, null,
            latestConfiguration = { OrdinaryGenerationConfiguration(snapshot) { profile ->
                selected.add(profile.id); MediaTaskAdmission({ true })
            } }) { true }
        try {
            prepare(scope, """{"profile_id":"p1","prompt":"draw"}""")
            assertEquals(listOf("p1"), selected)
        } finally { scope.close() }
    }

    @Test fun mediaDescriptionsExposeTrueRolesAndPathsWithoutGenerationOrCredentials() {
        val snapshot = settings(GenerationProtocol.OPENAI_IMAGES_JSON, GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        val scope = leaf(snapshot)
        try {
            val image = scope.tools.single { it.definition.name == RunGenerationTools.IMAGE_TOOL }.definition.description
            val video = scope.tools.single { it.definition.name == RunGenerationTools.VIDEO_TOOL }.definition.description
            assertTrue(image.contains("\"role\":\"image_generation\""))
            assertTrue(image.contains("\"dispatch_tools\":[\"generate_images\"]"))
            assertTrue(video.contains("\"role\":\"video_generation\""))
            assertTrue(video.contains("\"dispatch_tools\":[\"generate_videos\"]"))
            assertTrue(image.contains("\"profile_id\":\"p0\"")); assertTrue(video.contains("\"profile_id\":\"p1\""))
            assertTrue(image.contains("\"task_tier\":null")); assertTrue(video.contains("\"task_tier\":null"))
            assertFalse(image.contains("https://example.test")); assertFalse(video.contains("https://example.test"))
            assertFalse(image.contains("unique-generation-fixture-secret"))
            assertFalse(video.contains("unique-generation-fixture-secret"))
            assertFalse(image.contains("\"apiKey\"")); assertFalse(video.contains("\"credential\""))
        } finally { scope.close() }
    }

    @Test fun configurationGuardIsCapturedPerExplicitMediaDelegation() {
        var epoch = 0
        val guards = mutableListOf<() -> Boolean>()
        val scope = RunOrdinaryGenerationTools(owner, auth, settings(GenerationProtocol.OPENAI_IMAGES_JSON),
            ControllerGenerationResources("account", { error("prepare opened DB") }, { "account" }, File("/unused")),
            captureAdmission = {
                val captured = epoch
                val guard = { epoch == captured }
                guards += guard
                guard
            }) { true }
        try {
            prepare(scope, """{"prompt":"first"}""")
            assertTrue(guards.single().invoke())
            epoch++
            assertFalse(guards.single().invoke())
            prepare(scope, """{"prompt":"explicit new delegation"}""")
            assertEquals(2, guards.size)
            assertFalse(guards.first().invoke()); assertTrue(guards.last().invoke())
        } finally { scope.close() }
    }

    @Test fun editProfileIsAdvertisedOnlyWithAllowedPngSource() {
        val settings = settings(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART)
        assertEquals(1, leaf(settings).tools.size)
        assertTrue(leaf(settings, emptyMap()).tools.isEmpty())
        assertEquals(1, leaf(settings, mapOf("jpeg" to GenerationInput(byteArrayOf(1), "image/jpeg"))).tools.size)
        rejected { prepare(leaf(settings), """{"prompt":"edit"}""") }
        rejected { prepare(leaf(settings), """{"prompt":"edit","source_asset_id":"/private/path"}""") }
    }
    @Test fun sourceAndMaskBytesAreInTheOriginalExactGenerationEffect() {
        val settings = settings(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART)
        val prepared = prepare(leaf(settings), """{"prompt":"edit","source_asset_id":"source","mask_asset_id":"mask"}""")
        val batch = settings.profiles.single().freeze("dynamic", listOf(GenerationRunConfig.Item("item_0", "edit", listOf(png), mask)))
        val config = GenerationRunConfig(listOf(batch), settings.maxPolls)
        assertEquals(RunGenerationTools.writeEffect(settings.accountScope, owner, config, batch), prepared.writeEffect)
        val noMask = prepare(leaf(settings), """{"prompt":"edit","source_asset_id":"source"}""")
        assertNotEquals(noMask.writeEffect, prepared.writeEffect)
    }
    @Test fun callerCannotReplaceSnapshotAndJsonCannotSilentlyDiscardReference() {
        val settings = settings(GenerationProtocol.OPENAI_IMAGES_JSON, GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART)
        val mutable = assets.toMutableMap()
        val frozen = leaf(settings, mutable)
        val original = prepare(frozen, """{"prompt":"edit","profile_id":"p1","source_asset_id":"source"}""")
        mutable["source"] = GenerationInput(byteArrayOf(9), "image/png")
        val same = prepare(frozen, """{"prompt":"edit","profile_id":"p1","source_asset_id":"source"}""")
        assertEquals(original.writeEffect, same.writeEffect)
        prepare(frozen, """{"prompt":"edit","profile_id":"p0","source_asset_id":"source"}""")
        prepare(frozen, """{"prompt":"generate","profile_id":"p0"}""")
    }
    @Test fun videoMayFreezeOneReferenceButNeverAMask() {
        val settings = settings(GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        val plain = prepare(leaf(settings), """{"prompt":"animate"}""", video = true)
        val reference = prepare(leaf(settings), """{"prompt":"animate","source_asset_id":"source"}""", video = true)
        assertNotEquals(plain.writeEffect, reference.writeEffect)
        rejected { prepare(leaf(settings), """{"prompt":"animate","source_asset_id":"source","mask_asset_id":"mask"}""", video = true) }
    }
}
