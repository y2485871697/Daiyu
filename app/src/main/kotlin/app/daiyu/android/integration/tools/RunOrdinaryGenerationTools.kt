package app.daiyu.android.integration.tools

import app.daiyu.android.delegation.SubAgentDescriptor
import app.daiyu.android.ControllerGenerationResources
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.model.generation.*

/** Dynamic prompts use independent configured generation profiles, never the chat model. */
internal class RunOrdinaryGenerationTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    private val settings: GenerationSettingsSnapshot, private val resources: ControllerGenerationResources,
    inputAssets: Map<String, GenerationInput> = emptyMap(),
    private val captureAdmission: () -> (() -> Boolean) = { { true } },
    private val current: () -> Boolean) : RunOwnedToolScope(owner, authorization) {
    private val inputs = inputAssets.toMap()
    override val tools = listOf(false, true).mapNotNull { video ->
        val sources = inputs
        val profiles = settings.profiles.filter { profile ->
            profile.protocol.isVideo == video && (profile.protocol !in setOf(
                GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, GenerationProtocol.OPENAI_IMAGE_EDIT_JSON,
                GenerationProtocol.OPENAI_IMAGE_REFERENCE_JSON) || sources.isNotEmpty())
        }
        if (profiles.isEmpty()) null else object : RegisteredTool {
            override val definition = ToolDefinition(if (video) RunGenerationTools.VIDEO_TOOL else RunGenerationTools.IMAGE_TOOL, 1,
                "使用已配置的图片/视频生成模型，不使用聊天模型。图片支持日常语言的比例（如9:16）、2K/4K、精确像素、张数和并发；" +
                    "也可用image_options覆盖本次设置，不修改保存配置。source_asset_ids传本次消息参考图，mask_asset_id只能传同尺寸PNG遮罩；" +
                    "所选协议不支持输入或参数时明确失败，不丢弃、不换端点。视频参数按已配置协议发送。" +
                    "成功只给实际分辨率、比例/时长和asset引用；不要复述请求体。未知付费请求禁止重发。" +
                    SubAgentDescriptor.initialDescription(profiles.map { profile ->
                        SubAgentDescriptor(profile.id, profile.label,
                            if (video) "video_generation" else "image_generation", null, profile.model, profile.model)
                    }),
                RunGenerationTools.CAPABILITY, ToolEffect.WRITE, ToolSchema(buildList {
                    add(ArgumentField("prompt", ArgumentType.Text(32768, minimumLength = 1)))
                    add(ArgumentField("profile_id", ArgumentType.Text(128, profiles.map { it.id }.toSet()), required = profiles.size > 1))
                    if (!video) add(ArgumentField("image_options", ArgumentType.ObjectValue(ToolSchema(listOf(
                        ArgumentField("aspect_ratio", ArgumentType.Text(32), false),
                        ArgumentField("resolution", ArgumentType.Text(32), false),
                        ArgumentField("size", ArgumentType.Text(64), false),
                        ArgumentField("n", ArgumentType.Integer(1, 10), false),
                        ArgumentField("quality", ArgumentType.Text(64), false),
                        ArgumentField("response_format", ArgumentType.Text(16, setOf("url", "b64_json")), false),
                        ArgumentField("concurrency", ArgumentType.Integer(1, 8), false),
                    ))), false))
                    if (sources.isNotEmpty()) {
                        add(ArgumentField("source_asset_id", ArgumentType.Text(128, sources.keys), required = false))
                        add(ArgumentField("source_asset_ids", ArgumentType.ArrayValue(ArgumentType.Text(128, sources.keys),
                            minimumItems = 1, maximumItems = 16, unique = true), required = false))
                        if (!video)
                            add(ArgumentField("mask_asset_id", ArgumentType.Text(128, sources.keys), required = false))
                    }
                }))
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val admitted = captureAdmission()
                val profile = if (arguments.contains("profile_id")) profiles.single { it.id == arguments.text("profile_id") } else profiles.single()
                require(!(arguments.contains("source_asset_id") && arguments.contains("source_asset_ids")))
                val references = when {
                    arguments.contains("source_asset_ids") -> arguments.array("source_asset_ids").map { sources.getValue(it as String) }
                    arguments.contains("source_asset_id") -> listOf(sources.getValue(arguments.text("source_asset_id")))
                    else -> emptyList()
                }
                val mask = if (arguments.contains("mask_asset_id")) sources.getValue(arguments.text("mask_asset_id")) else null
                require(app.daiyu.android.GenerationFormInputs.supported(profile.protocol, references, mask)) {
                    "generation_reference_not_supported"
                }
                val options = if (arguments.contains("image_options")) org.json.JSONObject(arguments.objectValue("image_options").canonicalJson()) else org.json.JSONObject()
                val parameters = options.keys().asSequence().associateWith { options.get(it).toString() }
                val batch = profile.freeze("dynamic", listOf(GenerationRunConfig.Item("item_0", arguments.text("prompt"),
                    references = references, mask = mask, parameters = parameters)))
                val config = GenerationRunConfig(listOf(batch), maxPolls = settings.maxPolls)
                val effect = RunGenerationTools.writeEffect(settings.accountScope, owner, config, batch)
                fun configurationDenial() = if (admitted()) null else
                    ExecutionReceipt.NotExecuted("generation_configuration_paused_redispatch_required")
                return PreparedTool.approvedWrite(effect, approve = { context ->
                    if (denial(context) != null || configurationDenial() != null) null
                    else TaskAuthorization(authorization.sessionId, authorization.taskId, setOf(effect))
                }, admit = { denial(it) ?: configurationDenial() }) execute@ { context ->
                    denial(context, original = false)?.let { return@execute it }
                    configurationDenial()?.let { return@execute it }
                    if (context.authorization.sessionId != authorization.sessionId || context.authorization.taskId != authorization.taskId ||
                        context.authorization.allowedWrites != setOf(effect)) return@execute ExecutionReceipt.NotExecuted("task_write_not_authorized")
                    val network = resources.generationNetwork(GenerationDownloadAuthorization { assetOwner, url ->
                        current() && admitted() && resources.validateOwner(assetOwner) && settings.downloads.allows(url)
                    })
                    val runtime = RunGenerationTools(config, owner, context.authorization, settings.accountScope,
                        resources::database, resources::assets,
                        validateOwner = { assetOwner -> live() && current() && admitted() && resources.validateOwner(assetOwner) },
                        approval = GenerationRunApproval { review ->
                            if (live() && current() && admitted() && review.context === context && review.effect == effect &&
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
