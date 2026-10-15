package app.daiyu.android.integration.tools

import app.daiyu.android.context.ContextRegistry
import app.daiyu.android.context.ContextRegistryScope
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.*
import org.json.JSONObject

/** Only a host-created scoped model adapter enters the central registry. No local/write route. */
internal object ScopedContextTools {
    fun create(registry: ContextRegistry, scope: ContextRegistryScope,
        runId: RunId, preparedArchiveId: String?): List<RegisteredTool> {
        val conversationId = scope.conversationId
        val adapter = registry.forModel(scope)
        val schemas = adapter.schemas
        return (0 until schemas.length()).mapNotNull { index ->
            val source = schemas.getJSONObject(index)
            val name = source.getString("name")
            val hint = when (name) {
                "context_archive_page" -> {
                    if (preparedArchiveId == null || !scope.allowsArchive(conversationId)) return@mapNotNull null
                    "；本轮可信准备的档案locator：" + JSONObject().put("archive_id", preparedArchiveId)
                }
                "context_usage" -> "；本轮宿主会话/任务：" + JSONObject()
                    .put("branch_id", conversationId.value).put("run_id", runId.value) +
                    "；零条回执不代表零消耗，不用估算补齐"
                else -> error("unsupported_context_tool")
            }
            check(source.getString("sideEffect") == "none")
            val definition = ToolDefinition(name, 1, source.getString("description") + hint,
                "context.read.$name", ToolEffect.READ_ONLY, schema(source.getJSONObject("parameters")))
            object : RegisteredTool {
                override val definition = definition
                override fun prepare(arguments: ToolArguments): PreparedTool {
                    val canonical = arguments.canonicalJson()
                    return PreparedTool {
                        val result = adapter.execute(name, JSONObject(canonical))
                        if (result.opt("ok") !is Boolean) ExecutionReceipt.Unknown("invalid_backend_receipt")
                        else ExecutionReceipt.Confirmed(result.toString(), isError = !result.getBoolean("ok"))
                    }
                }
            }
        }
    }

    private fun schema(source: JSONObject): ToolSchema {
        fun keys(value: JSONObject) = value.keys().asSequence().toSet()
        require(keys(source) == setOf("type", "properties", "required", "additionalProperties"))
        require(source.getString("type") == "object" && source.get("additionalProperties") == false)
        val properties = source.getJSONObject("properties")
        val required = source.getJSONArray("required").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        require(required.all { properties.has(it) })
        return ToolSchema(keys(properties).sorted().map { name ->
            val field = properties.getJSONObject(name)
            val type = when (field.getString("type")) {
                "string" -> {
                    require(keys(field) == setOf("type"))
                    ArgumentType.Text(maxLength = 200, minimumLength = 1)
                }
                "integer" -> {
                    require(keys(field).all { it in setOf("type", "minimum", "maximum") })
                    ArgumentType.Integer(if (field.has("minimum")) field.getLong("minimum") else 0,
                        if (field.has("maximum")) field.getLong("maximum") else Int.MAX_VALUE.toLong())
                }
                else -> error("unsupported_context_schema")
            }
            ArgumentField(name, type, required = name in required)
        })
    }
}
