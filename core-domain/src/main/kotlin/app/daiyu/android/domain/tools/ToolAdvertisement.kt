package app.daiyu.android.domain.tools

/** 已注册工具的供应商无关描述。
 * [parameters] 直接由实际校验使用的 [ToolDefinition.schema] 生成，保持声明与约束一致。
 * [ToolEffect.USER_BARRIER] 的单调用屏障由分发器执行。
 */
data class ToolAdvertisement(
    val name: String,
    val description: String,
    val capability: String,
    val effect: ToolEffect,
    val parameters: String
) {
    init {
        require(name.isNotBlank())
        require(description.isNotBlank())
        require(capability.isNotBlank())
        require(parameters.isNotBlank())
    }

    /** Alias for callers that name the JSON Schema field `schema`. */
    val schema: String get() = parameters

    /** A complete, deterministic JSON object suitable for a model adapter. */
    fun toJson(): String = buildString {
        append('{')
        append(jsonQuote("name")); append(':'); append(jsonQuote(name)); append(',')
        append(jsonQuote("description")); append(':'); append(jsonQuote(description)); append(',')
        append(jsonQuote("capability")); append(':'); append(jsonQuote(capability)); append(',')
        append(jsonQuote("effect")); append(':'); append(jsonQuote(effect.name)); append(',')
        append(jsonQuote("parameters")); append(':'); append(parameters)
        append('}')
    }

    companion object {
        /** Build only from the canonical definition; no execution or adapter lookup occurs. */
        fun from(definition: ToolDefinition): ToolAdvertisement = ToolAdvertisement(
            name = definition.name,
            description = definition.description + if (definition.schema.fields.isEmpty())
                " 此工具无参数；调用参数必须为 {}，不要添加 _placeholder 或其它占位字段。" else "",
            capability = definition.capability,
            effect = definition.effect,
            parameters = definition.schema.jsonSchema()
        )
    }
}

/**
 * Public conversion helpers kept separate from model/provider implementations.
 * A session is required when converting a catalog so disabled capabilities are not
 * accidentally advertised to a model.
 */
object ToolAdvertisements {
    fun forSession(catalog: ToolCatalog, session: SessionCapabilities): List<ToolAdvertisement> =
        catalog.available(session).map(ToolAdvertisement::from)

    fun from(definitions: Iterable<ToolDefinition>): List<ToolAdvertisement> =
        definitions.map(ToolAdvertisement::from)

    fun encode(advertisements: Iterable<ToolAdvertisement>): String =
        advertisements.joinToString(",", prefix = "[", postfix = "]", transform = ToolAdvertisement::toJson)

    fun encode(catalog: ToolCatalog, session: SessionCapabilities): String =
        encode(forSession(catalog, session))
}

/** Convenient catalog-facing spelling for model adapters. */
fun ToolCatalog.advertisements(session: SessionCapabilities): List<ToolAdvertisement> =
    ToolAdvertisements.forSession(this, session)

/** Convenient catalog-facing JSON serialization for model adapters. */
fun ToolCatalog.advertisementsJson(session: SessionCapabilities): String =
    ToolAdvertisements.encode(this, session)
