package app.daiyu.android.model.generation

import org.json.JSONObject

/** Shared explicit directives and guarded natural-language output settings. */
internal object ImagePromptOptions {
    data class Parsed(val prompt: String, val options: ImageGenerationOptions)

    fun parse(prompt: String, overrides: ImageGenerationOptions = ImageGenerationOptions()): Parsed {
        // One standalone JSON directive, not quoted examples/fences or loose numbers such as times.
        val lines = prompt.lines()
        val indexes = lines.indices.filter { lines[it].trimStart().startsWith("image_options:") }
        if (indexes.isEmpty()) return parseShapeClauses(prompt, overrides)
        if (indexes.size != 1 || prompt.contains("```"))
            ImageGenerationOptions.invalid("请只在独立一行填写一次 image_options: {...}，不要放在代码块或示例中。")
        val index = indexes.single()
        val raw = lines[index].trim().removePrefix("image_options:").trim()
        val json = try { JSONObject(raw) } catch (_: Exception) {
            ImageGenerationOptions.invalid("image_options 行需要有效 JSON 对象。")
        }
        val options = ImageGenerationOptions.fromJson(json).also { it.validateShape() }
        return Parsed(lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim(), options)
    }
    private fun parseShapeClauses(prompt: String, overrides: ImageGenerationOptions): Parsed =
        Parsed(prompt, NaturalImagePromptOptions.parse(prompt, overrides))

}

/** Private configuration is consumed locally and must never reach a provider. */
internal object ImageRequestParameters {
    const val CONFIG_KEY = "daiyu_image_config"
    val keys = setOf("aspect_ratio", "resolution", "size", "n", "quality", "response_format")
    data class Prepared(
        val body: JSONObject,
        val options: ImageGenerationOptions,
        val editProtocol: String,
        val summary: String,
        val endpoint: String = "openai_images",
        val explicitEndpoint: Boolean = false,
        val nativeParameters: JSONObject = JSONObject(),
        val expectedSize: String? = null,
    )

    fun prepare(input: JSONObject, inline: ImageGenerationOptions, explicit: ImageGenerationOptions, defaultCount: Int? = null): Prepared {
        // Planning must not mutate saved configuration or a caller's body, even on validation failure.
        val body = JSONObject(input.toString())
        body.remove("daiyu_media_reasoning") // Private media capability metadata is never a wire field.
        val rawConfig = body.remove(CONFIG_KEY)
        val config = when (rawConfig) {
            null -> JSONObject()
            is JSONObject -> rawConfig
            else -> ImageGenerationOptions.invalid("daiyu_image_config 必须是对象。")
        }
        if (config.keys().asSequence().any { it !in setOf("endpoint", "protocol", "fields", "values", "sizes", "edit_protocol", "native_parameters", "profile") })
            ImageGenerationOptions.invalid("daiyu_image_config 含未知配置。")
        fun text(key: String, default: String): String = if (!config.has(key)) default else
            config.opt(key) as? String ?: ImageGenerationOptions.invalid("$key 必须是字符串。")
        fun obj(key: String): JSONObject = if (!config.has(key)) JSONObject() else
            config.optJSONObject(key) ?: ImageGenerationOptions.invalid("$key 必须是对象。")
        val endpoint = text("endpoint", "openai_images")
        if (endpoint !in setOf("openai_images", "novelai_native"))
            ImageGenerationOptions.invalid("不支持该生图端点协议；Chat/Responses/Anthropic 不能冒充 Images。")
        val fields = obj("fields")
        val values = obj("values")
        val sizes = obj("sizes")
        val native = obj("native_parameters")
        // Preserve existing explicit field mappings; ordinary Images uses an explicit client pixel policy.
        val protocol = text("protocol", if (fields.length() > 0) "passthrough" else "size_long_edge")
        if (protocol !in setOf("passthrough", "size", "size_long_edge"))
            ImageGenerationOptions.invalid("参数协议应为 size_long_edge、size 或 passthrough。")
        if (endpoint == "novelai_native" && (fields.length() > 0 || values.length() > 0 || protocol == "passthrough" || config.has("edit_protocol")))
            ImageGenerationOptions.invalid("NovelAI 原生协议不使用 OpenAI 字段映射或编辑协议。")
        if (endpoint != "novelai_native" && native.length() > 0)
            ImageGenerationOptions.invalid("native_parameters 仅用于 novelai_native。")
        val edit = text("edit_protocol", "multipart")
        if (edit !in setOf("multipart", "json_image_url", "generations_image"))
            ImageGenerationOptions.invalid("编辑协议应为 multipart、json_image_url 或 generations_image。")
        if (fields.keys().asSequence().any { it !in keys } || values.keys().asSequence().any { it !in keys })
            ImageGenerationOptions.invalid("fields/values 仅允许生图参数名。")
        val paths = keys.associateWith { key ->
            val value = if (fields.has(key)) fields.opt(key) as? String
                ?: ImageGenerationOptions.invalid("字段映射必须是字符串。") else key
            val parts = value.split('.')
            if (parts.size > 6 || "concurrency" in parts || parts.any { !Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(it) } ||
                parts.first() in setOf("model", "prompt", "input", "messages", "image", "images", "mask", "stream", "tools", "tool_choice", CONFIG_KEY))
                ImageGenerationOptions.invalid("生图字段映射路径无效或覆盖保留字段。")
            parts
        }
        if (protocol != "passthrough" && listOf("ratio", "aspectRatio", "imageSize").any {
            body.has(it) && paths.values.none { path -> path == listOf(it) }
        }) ImageGenerationOptions.invalid("额外请求体中含未映射的比例或档位字段，请显式配置 fields 或 passthrough，不会同时盲发。")
        val allPaths = paths.values.toList()
        for (i in allPaths.indices) for (j in i+1 until allPaths.size) {
            val a = allPaths[i]; val b = allPaths[j]
            if (a != b && (a.take(b.size) == b || b.take(a.size) == a))
                ImageGenerationOptions.invalid("生图字段映射路径冲突。")
        }
        val maps = values.keys().asSequence().associateWith { key ->
            val mapping = values.optJSONObject(key) ?: ImageGenerationOptions.invalid("values 项必须是对象。")
            mapping.keys().asSequence().associateWith { from ->
                val to = mapping.opt(from) as? String ?: ImageGenerationOptions.invalid("values 仅接受字符串取值转换。")
                if (!Regex("[A-Za-z0-9_.:/-]{1,40}").matches(to)) ImageGenerationOptions.invalid("values 取值格式无效。")
                to
            }.also { if (it.values.distinct().size != it.size) ImageGenerationOptions.invalid("values 反向映射不唯一。") }
        }
        val canonical = JSONObject()
        keys.forEach { key ->
            // e.g. resolution -> size: configured size=2K is a resolution, not WIDTHxHEIGHT.
            val redirected = keys.any { other -> other != key && fields.has(other) && paths.getValue(other) == listOf(key) }
            val value = if (body.has(key) && !redirected) body.get(key) else {
                if (!fields.has(key) && redirected) null else readPath(body, paths.getValue(key))
            }
            if (value != null) {
                val decoded = maps[key]?.entries?.firstOrNull { it.value == value }?.key ?: value
                canonical.put(key, decoded)
            }
        }
        body.remove("concurrency")?.let { canonical.put("concurrency", it) }
        inline.applyTo(canonical)
        explicit.applyTo(canonical)
        if (!canonical.has("n") && defaultCount != null) canonical.put("n", defaultCount)
        var options = ImageGenerationOptions.fromJson(canonical).also { it.validateShape() }
        if (canonical.has("resolution")) canonical.put("resolution", options.resolution)
        if (protocol != "passthrough" && (options.aspectRatio != null || options.resolution != null)) {
            if (options.size != null && options.resolution != null)
                ImageGenerationOptions.invalid("不能同时指定精确 size 和分辨率档位。")
            if (options.size == null) {
                val ratio = options.aspectRatio ?: ImageGenerationOptions.invalid("像素尺寸转换需要明确比例。")
                val mappingKey = if (options.resolution == null) ratio else "$ratio@${options.resolution}"
                val legacyMappingKey = if (options.resolution == null) ratio else "$ratio@${ImageResolutionTier.legacy(options.resolution)}"
                val selectedKey = if (sizes.has(mappingKey)) mappingKey else legacyMappingKey
                val mapped = if (sizes.has(selectedKey)) sizes.opt(selectedKey) as? String
                    ?: ImageGenerationOptions.invalid("sizes 映射值必须是像素尺寸字符串。")
                else if (protocol == "size") ImageGenerationOptions.invalid("未配置 sizes[$mappingKey]；不会替换为近似比例。")
                else ImageTargetSize.resolve(options.aspectRatio, options.resolution)
                options = options.copy(size = mapped).also {
                    ImageGenerationOptions.fromJson(it.toJson()); it.validateShape()
                }
            }
            canonical.remove("aspect_ratio"); canonical.remove("resolution")
            canonical.put("size", options.size)
        }
        canonical.remove("concurrency") // local batch scheduling, never an upstream API field
        val activePaths = canonical.keys().asSequence().map { paths.getValue(it) }.toList()
        if (activePaths.distinct().size != activePaths.size)
            ImageGenerationOptions.invalid("本次生图参数映射到相同字段，不能覆盖或丢弃其中一个。")
        keys.forEach { key -> body.remove(key); removePath(body, paths.getValue(key)) }
        val sent = JSONObject()
        canonical.keys().forEach { key ->
            val value = canonical.get(key)
            val mapping = maps[key]
            val encoded = if (mapping == null) {
                if (key == "resolution" && value.toString() in ImageResolutionTier.values)
                    ImageGenerationOptions.invalid("原生分辨率协议需要 values.resolution 显式映射低、中、高、超高档位，不能直接猜测上游参数。")
                value
            } else mapping[value.toString()] ?: (if (key == "resolution") mapping[ImageResolutionTier.legacy(value.toString())] else null)
                ?: ImageGenerationOptions.invalid("values 未配置本次 $key 的取值；不会猜测。")
            writePath(body, paths.getValue(key), encoded)
            sent.put(paths.getValue(key).joinToString("."), encoded)
        }
        return Prepared(body, options, edit, "参数协议：$protocol；发送参数：$sent", endpoint,
            config.has("endpoint"), native)
    }

    private fun readPath(root: JSONObject, parts: List<String>): Any? {
        var node = root
        for (part in parts.dropLast(1)) node = node.optJSONObject(part) ?: return null
        return node.opt(parts.last())
    }
    private fun removePath(root: JSONObject, parts: List<String>) {
        var node = root
        for (part in parts.dropLast(1)) node = node.optJSONObject(part) ?: return
        node.remove(parts.last())
    }
    private fun writePath(root: JSONObject, parts: List<String>, value: Any) {
        var node = root
        for (part in parts.dropLast(1)) {
            if (node.has(part) && node.optJSONObject(part) == null) ImageGenerationOptions.invalid("映射路径与已有非对象参数冲突。")
            node = node.optJSONObject(part) ?: JSONObject().also { node.put(part, it) }
        }
        node.put(parts.last(), value)
    }
}
