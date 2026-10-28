package app.daiyu.android.integration.tools

import app.daiyu.android.ControllerGenerationResources
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.model.generation.*

/** Dynamic prompts use independent configured generation profiles, never the chat model. */
internal class RunOrdinaryGenerationTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val settings: GenerationSettingsSnapshot, private val resources: ControllerGenerationResources,
    inputAssets: Map<String, GenerationInput> = emptyMap(),
    private val current: () -> Boolean) : RunOwnedToolScope(owner, authorization) {
    private val inputs = inputAssets.toMap()
    override val tools = listOf(false, true).mapNotNull { video ->
        val sources = inputs.filterValues { video || it.mimeType == "image/png" }
        val profiles = settings.profiles.filter { profile ->
            when (profile.protocol) {
                GenerationProtocol.OPENAI_VIDEO_MULTIPART -> video
                GenerationProtocol.OPENAI_IMAGES_JSON -> !video
                GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> !video && sources.isNotEmpty()
            }
        }
        if (profiles.isEmpty()) null else object : RegisteredTool {
            override val definition = ToolDefinition(if (video) RunGenerationTools.VIDEO_TOOL else RunGenerationTools.IMAGE_TOOL, 1,
                "用已配置的独立生成模型执行prompt。profile_id只能选择已配置档案；编辑图片必须指定本次消息中PNG的source_asset_id，可选同尺寸PNG的mask_asset_id；视频可带一张source。结果必须下载并登记后才返回asset引用，未知付费请求禁止重发。",
                RunGenerationTools.CAPABILITY, ToolEffect.WRITE, ToolSchema(buildList {
                    add(ArgumentField("prompt", ArgumentType.Text(32768, minimumLength = 1)))
                    add(ArgumentField("profile_id", ArgumentType.Text(128, profiles.map { it.id }.toSet()), required = profiles.size > 1))
                    if (sources.isNotEmpty() && (video || profiles.any { it.protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART })) {
                        add(ArgumentField("source_asset_id", ArgumentType.Text(128, sources.keys), required = false))
                        if (!video && profiles.any { it.protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART })
                            add(ArgumentField("mask_asset_id", ArgumentType.Text(128, sources.keys), required = false))
                    }
                }))
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val profile = if (arguments.contains("profile_id")) profiles.single { it.id == arguments.text("profile_id") } else profiles.single()
                val source = if (arguments.contains("source_asset_id")) sources.getValue(arguments.text("source_asset_id")) else null
                val mask = if (arguments.contains("mask_asset_id")) sources.getValue(arguments.text("mask_asset_id")) else null
                when (profile.protocol) {
                    GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> require(source != null) { "generation_edit_source_required" }
                    GenerationProtocol.OPENAI_IMAGES_JSON -> require(source == null && mask == null) { "generation_json_reference_not_supported" }
                    GenerationProtocol.OPENAI_VIDEO_MULTIPART -> require(mask == null)
                }
                val batch = profile.freeze("dynamic", listOf(GenerationRunConfig.Item("item_0", arguments.text("prompt"),
                    references = listOfNotNull(source), mask = mask)))
                val config = GenerationRunConfig(listOf(batch), maxPolls = settings.maxPolls)
                val effect = RunGenerationTools.writeEffect(settings.accountScope, owner, config, batch)
                return write(effect) { context ->
                    val network = resources.generationNetwork(GenerationDownloadAuthorization { assetOwner, url ->
                        current() && resources.validateOwner(assetOwner) && settings.downloads.allows(url)
                    })
                    val runtime = RunGenerationTools(config, owner, context.authorization, settings.accountScope,
                        resources::database, resources::assets,
                        validateOwner = { assetOwner -> live() && current() && resources.validateOwner(assetOwner) },
                        approval = GenerationRunApproval { review ->
                            if (live() && current() && review.context === context && review.effect == effect &&
                                context.authorization.allowedWrites == setOf(effect)) GenerationRunDecision.APPROVED else GenerationRunDecision.DENIED
                        }, downloadAuthorization = network.authorization, http = network.http,
                        fenceFactory = resources::createFence)
                    val leaf = GenerationCatalogLeaf(runtime)
                    retain(leaf, leaf::observeClose)
                    if (!live() || !current()) ExecutionReceipt.NotExecuted("generation_run_closed")
                    else runtime.executeApproved(context, batch)
                }
            }
        }
    }
}
