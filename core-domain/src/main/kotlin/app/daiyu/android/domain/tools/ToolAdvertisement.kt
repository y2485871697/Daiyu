package app.daiyu.android.domain.tools

/**
 * Provider-neutral description of a registered tool.
 *
 * [parameters] is the JSON Schema produced by the very same [ToolDefinition.schema]
 * that validates an invocation.  Keeping the serialized schema here (rather than a
 * second model-specific schema) means that required fields and constraints cannot
 * drift between validation and advertising.
 *
 * This is metadata only.  In particular, advertising [ToolEffect.USER_BARRIER] does
 * not ask a question or otherwise execute a tool; the dispatcher remains responsible
 * for enforcing its single-invocation barrier.
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
            description = definition.description,
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
