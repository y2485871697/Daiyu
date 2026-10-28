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
            val video = protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART
            GenerationProfileSettings("p$index", "profile $index", if (video) GenerationKind.VIDEO else GenerationKind.IMAGE,
                protocol, "https://example.test/v1/" + when (protocol) {
                    GenerationProtocol.OPENAI_IMAGES_JSON -> "images/generations"
                    GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "images/edits"
                    GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "videos"
                }, "model", "key")
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
    @Test fun editProfileIsAdvertisedOnlyWithAllowedPngSource() {
        val settings = settings(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART)
        assertEquals(1, leaf(settings).tools.size)
        assertTrue(leaf(settings, emptyMap()).tools.isEmpty())
        assertTrue(leaf(settings, mapOf("jpeg" to GenerationInput(byteArrayOf(1), "image/jpeg"))).tools.isEmpty())
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
        rejected { prepare(frozen, """{"prompt":"edit","profile_id":"p0","source_asset_id":"source"}""") }
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
