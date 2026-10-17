package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject

/** Contract extracted from frozen EtaBackupRepository/ConversationEntities, not its importer. */
internal object LegacyParser {
    private val conversationKeys = setOf("id", "title", "thinkingEnabled", "reasoningEffort", "historyJson", "appliedRuntimeRunIdsJson", "createdAt", "updatedAt", "folderId", "isPinned", "hasCompletionMarker", "providerId", "modelId", "assistantId")
    private val messageKeys = setOf("id", "conversationId", "sortIndex", "type", "content", "imagesJson", "isEdited", "renderMarkdown", "contextTokens", "inputTokens", "outputTokens", "reasoningTokens", "cachedTokens", "elapsedSeconds", "toolName", "toolStatus", "argumentsSummary", "resultSummary", "imageCount", "toolsJson", "generatedAtMillis")
    private val checkpointKeys = setOf("conversationId", "historyJson", "cloudUsageJson")
    private val singleKeys = setOf("format", "schemaVersion", "exportedAt", "conversation", "messages", "contextCheckpoint", "attachmentCount", "attachments", "subAgentConfigJson", "subAgentConfigGeneration")
    private val fullKeys = setOf("format", "schemaVersion", "exportedAt", "providers", "selectedProviderId", "selectedModelId", "conversations", "messages", "contextCheckpoints", "conversationState", "folders", "memoryMd", "assistantMemories", "assistants", "assistantAvatars", "skillRegistry", "skillFiles", "mcpServers", "mcpTokens", "settings", "includeLinuxEnvironment", "linuxWorkspaceIncluded", "attachmentCount", "importedFileCount", "agentPreferences", "messagesExternal", "messageCount")
    private val excludedRootKeys = setOf("providers", "selectedProviderId", "selectedModelId", "conversationState", "folders", "memoryMd", "assistantMemories", "assistants", "assistantAvatars", "skillRegistry", "skillFiles", "mcpServers", "mcpTokens", "settings", "agentPreferences")

    fun parse(container: LegacyArchive.Container): LegacyPlan {
        val files = container.entries
        val rootName = if (container.zip) {
            val candidates = files.keys.filter { it == "eta-conversation.json" || it == "eta-backup.json" }
            require(candidates.size == 1) { "Exactly one Eta manifest required" }; candidates.single()
        } else "<json>"
        val root = LegacyJson.objectOf(files.getValue(rootName))
        val format = LegacyJson.string(root, "format"); val versionLong = LegacyJson.integer(root, "schemaVersion")
        require(versionLong in 1..4); val version = versionLong.toInt()
        require((format == "eta-conversation" && version in 1..3) || (format == "eta-backup" && version in 1..4)) { "Unsupported Eta format/version" }
        if (container.zip) require(rootName == "$format.json")
        LegacyJson.integer(root, "exportedAt").also { require(it >= 0) }
        keys(root, if (format == "eta-conversation") singleKeys else fullKeys)
        val issues = mutableListOf(LegacyIssue("READ_ONLY_COMPATIBILITY", 1, "UI messages, model history, checkpoints, usage and tools are archival records only; no runnable context or native assistant messages are created."),
            LegacyIssue("SOURCE_TIME_SEMANTICS", 1, "generatedAtMillis is retained as generation metadata, never message created_at; import time is not source time."),
            LegacyIssue("TEXT_AND_OPAQUE_FIELDS_PRESERVED", 1, "Source prose/code/file-reference blocks, nested history, usage and unsupported fields within known records remain literal read-only evidence. No filesystem-path text substitution is performed; declared/unreferenced originals remain available through the separate archive inventory. No claim of native-model-context losslessness is made."))
        val documents: List<JSONObject>
        val usedFiles = mutableSetOf(rootName)
        val originals = files.filterKeys { it.startsWith("attachments/") }
        if (format == "eta-conversation") {
            if (version == 1) require(rows(root, "attachments").isEmpty()) { "v1 has no portable attachment manifest" }
            documents = listOf(root)
        } else {
            issues += LegacyIssue("EXCLUDED_GLOBAL_CONFIGURATION", excludedRootKeys.count { root.has(it) }, "Provider credentials, preferences, account settings, MCP tokens, skills, assistant configuration and global memory are not retained or installed; only scoped conversation records and archive attachment originals are imported.")
            val conversations = rows(root, "conversations"); val ownerIds = unique(conversations.map { id(it, "id") }, "conversation")
            if (bool(root, "messagesExternal", false)) {
                require(rows(root, "messages").isEmpty() && rows(root, "contextCheckpoints").isEmpty()) { "Ambiguous inline/external messages" }
                val payloads = files.keys.filter { it.startsWith("conversations/") }
                require(container.zip && payloads.all { it.endsWith(".json") && it.count { c -> c == '/' } == 1 })
                val parsed = payloads.map { name -> usedFiles += name; LegacyJson.objectOf(files.getValue(name)) }
                val byId = parsed.associateBy { id(it.getJSONObject("conversation"), "id") }
                require(byId.size == parsed.size && byId.keys == ownerIds) { "External internal-ID set differs from manifest" }
                documents = conversations.map { conv ->
                    val doc = byId.getValue(id(conv, "id"))
                    require(LegacyJson.string(doc, "format") == "eta-conversation" && LegacyJson.integer(doc, "schemaVersion") in 1..3)
                    keys(doc, singleKeys)
                    require(LegacyJson.integer(doc, "exportedAt") >= 0)
                    require(rows(doc, "attachments").isEmpty() && count(doc, "attachmentCount") == 0L) { "Full external payload cannot embed a single-conversation attachment manifest" }
                    require(equivalent(conv, doc.getJSONObject("conversation"))) { "External conversation metadata differs from manifest owner" }
                    doc
                }
                require(documents.sumOf { rows(it, "messages").size }.toLong() == count(root, "messageCount")) { "External message count mismatch" }
            } else {
                val messages = rows(root, "messages"); val checkpoints = rows(root, "contextCheckpoints")
                require(messages.all { id(it, "conversationId") in ownerIds } && checkpoints.all { id(it, "conversationId") in ownerIds }) { "Unknown message/checkpoint owner" }
                unique(checkpoints.map { id(it, "conversationId") }, "checkpoint owner")
                documents = conversations.map { conv -> JSONObject().put("conversation", conv)
                    .put("messages", JSONArray(messages.filter { id(it, "conversationId") == id(conv, "id") }))
                    .put("contextCheckpoint", checkpoints.find { id(it, "conversationId") == id(conv, "id") } ?: JSONObject.NULL) }
                if (root.has("messageCount") && count(root, "messageCount") != 0L) require(count(root, "messageCount") == messages.size.toLong())
            }
            require(files.keys.none { it.startsWith("linux/") }) { "Linux/workspace archives cannot safely enter conversation compatibility storage; import a conversation-only export instead" }
            require(originals.keys.all { it.startsWith("attachments/imports/") || it.startsWith("attachments/chat-images/") })
            val expectedImages = count(root, "attachmentCount"); val expectedImports = count(root, "importedFileCount")
            if (container.zip) {
                require(originals.keys.count { it.startsWith("attachments/chat-images/") }.toLong() == expectedImages) { "Chat image count mismatch" }
                require(originals.keys.count { it.startsWith("attachments/imports/") }.toLong() == expectedImports) { "Imported file count mismatch" }
            } else if (expectedImages > 0 || expectedImports > 0) {
                issues += LegacyIssue("JSON_ORIGINALS_ABSENT", 1, "JSON declares $expectedImages chat images and $expectedImports imported files but contains no ZIP originals; references are read-only and unavailable, never opened on this device.")
            }
        }
        var historyCount = 0
        val globalMessageIds = hashSetOf<String>()
        val conversations = documents.map { doc ->
            val conv = doc.getJSONObject("conversation"); keys(conv, conversationKeys)
            listOf("title", "reasoningEffort", "folderId", "providerId", "modelId", "assistantId").forEach { typedString(conv, it) }
            listOf("thinkingEnabled", "isPinned", "hasCompletionMarker").forEach { typedBoolean(conv, it) }
            require(LegacyJson.string(conv, "title").toByteArray(Charsets.UTF_8).size <= 65536) { "Conversation title exceeds safe catalogue row size; not truncated" }
            val owner = id(conv, "id"); val messages = rows(doc, "messages"); val checkpoint = nullableObject(doc, "contextCheckpoint")
            val order = hashSetOf<Long>()
            messages.forEach { message ->
                keys(message, messageKeys)
                listOf("type", "content", "toolName", "toolStatus", "argumentsSummary", "resultSummary").forEach { typedString(message, it, it !in setOf("type", "content")) }
                listOf("contextTokens", "inputTokens", "outputTokens", "reasoningTokens", "cachedTokens", "elapsedSeconds", "imageCount").forEach { if (message.has(it) && !message.isNull(it)) require(LegacyJson.integer(message, it) >= 0) }
                typedBoolean(message, "isEdited"); typedBoolean(message, "renderMarkdown", true)
                require(id(message, "conversationId") == owner && globalMessageIds.add(id(message, "id"))) { "Duplicate message or wrong owner" }
                require(order.add(LegacyJson.integer(message, "sortIndex").also { require(it >= 0) })) { "Duplicate message order" }
                arrayString(message, "imagesJson"); arrayString(message, "toolsJson")
            }
            checkpoint?.let { keys(it, checkpointKeys); typedString(it, "cloudUsageJson"); require(id(it, "conversationId") == owner) { "Wrong checkpoint owner" } }
            val history = arrayString(conv, "historyJson"); val checkpointHistory = checkpoint?.let { arrayString(it, "historyJson") }
            historyCount += history.length()
            validateHistory(history); checkpointHistory?.let(::validateHistory)
            // appliedRuntimeRunIdsJson is preserved as source evidence, never installed in runtime tables.
            arrayString(conv, "appliedRuntimeRunIdsJson")
            val config = if (format == "eta-conversation" || doc.has("format")) LegacySubAgentGate.inspect(doc, issues) else null
            val media = LegacyMediaMapper.collect(conv, messages, checkpoint)
            val resolved = LegacyMediaMapper.resolve(media, owner, if (format == "eta-conversation") version else 1,
                if (format == "eta-conversation") doc else null, originals, container.zip)
            val raw = JSONObject().put("conversation", conv).put("messages", JSONArray(messages)).put("contextCheckpoint", checkpoint ?: JSONObject.NULL)
                .put("sourceFormat", format).put("sourceSchemaVersion", version).put("sourceExportedAt", root.get("exportedAt"))
            if (doc.has("attachments")) raw.put("attachments", doc.getJSONArray("attachments"))
            if (format == "eta-backup" && doc.has("format")) raw.put("externalPayloadSchemaVersion", doc.get("schemaVersion")).put("externalPayloadExportedAt", doc.opt("exportedAt") ?: JSONObject.NULL)
            config?.let { raw.put("subAgentCompatibility", it) }
            val unknownTypes = messages.count { LegacyJson.string(it, "type") !in setOf("user", "assistant") }
            if (unknownTypes > 0) issues += LegacyIssue("NON_DIALOGUE_UI_RECORDS", unknownTypes, "Thinking/tool/tool_summary/notices/questions and unknown UI types remain typed, read-only UI records, not assistant or executable tool messages.")
            if (resolved.any { !it.available }) issues += LegacyIssue("UNAVAILABLE_MEDIA", resolved.count { !it.available }, "Remote URLs are not downloaded; JSON-only exports cannot supply local originals. Preview never substitutes for original video.")
            usedFiles += resolved.mapNotNull { it.entry }.filter { it in files }
            LegacyConversation(owner, LegacyJson.string(conv, "title"), time(conv, "createdAt"), time(conv, "updatedAt"), raw.toString(),
                messages.sortedBy { LegacyJson.integer(it, "sortIndex") }.map { LegacySourceMessage(id(it, "id"), LegacyJson.string(it, "type"), LegacyJson.string(it, "content"), time(it, "generatedAtMillis")) },
                history.toString(), checkpointHistory?.toString(), resolved)
        }
        unique(conversations.map { it.sourceId }, "conversation")
        // v1/full archives can contain unreferenced originals. Retain them explicitly, never delete silently.
        if (format == "eta-backup" || version == 1) {
            if (format == "eta-conversation") {
                val owner = conversations.single().sourceId
                require(originals.keys.all { it.startsWith("attachments/imports/") || it.startsWith("attachments/chat-images/$owner/") }) { "v1 archive contains another owner's assets" }
                if (originals.size.toLong() != count(root, "attachmentCount")) issues += LegacyIssue("V1_LEGACY_COUNT_ADVISORY", 1,
                    "Historical v1 attachmentCount=${count(root, "attachmentCount")} differs from ${originals.size} verified ZIP originals; v1 did not define an attachment manifest, so all safe scoped originals are retained and inventoried.")
            }
            val unreferenced = originals.keys - usedFiles
            if (unreferenced.isNotEmpty()) issues += LegacyIssue("UNREFERENCED_ORIGINALS", unreferenced.size, "Verified ZIP originals retained in the import's read-only attachment inventory; no executable/file-system destination is created.")
            usedFiles += originals.keys
        }
        require(files.keys == usedFiles) { "Archive entry set differs from declared/retained entries" }
        require(originals.values.sumOf { it.size.toLong() } <= LegacyArchive.BLOB_LIMIT)
        val rawBytes = conversations.sumOf { it.raw.toByteArray(Charsets.UTF_8).size.toLong() }
        require(rawBytes <= LegacyArchive.TEXT_LIMIT) { "Compatibility text exceeds budget; not truncated" }
        return LegacyPlan(format, version, conversations, originals, LegacyConversionReport(issues), historyCount)
    }

    private fun validateHistory(history: JSONArray) { LegacyJson.rows(history).forEach { row ->
        LegacyJson.string(row, "role")
        if (row.has("contentJson") && !row.isNull("contentJson") && LegacyJson.string(row, "contentJson").isNotBlank()) {
            require(LegacyJson.parse(LegacyJson.string(row, "contentJson")) is JSONArray) { "contentJson must encode an array" }
        }
    } }
    private fun typedString(obj: JSONObject, key: String, nullable: Boolean = false) { if (obj.has(key) && !(nullable && obj.isNull(key))) LegacyJson.string(obj, key) }
    private fun typedBoolean(obj: JSONObject, key: String, nullable: Boolean = false) { if (obj.has(key) && !(nullable && obj.isNull(key))) require(obj.get(key) is Boolean) { "Expected boolean: $key" } }
    internal fun rows(obj: JSONObject, key: String): List<JSONObject> = if (!obj.has(key)) emptyList() else LegacyJson.rows(obj.getJSONArray(key))
    internal fun arrayString(obj: JSONObject, key: String): JSONArray = if (!obj.has(key)) JSONArray() else {
        val s = LegacyJson.string(obj, key); if (s.isBlank()) JSONArray() else LegacyJson.parse(s) as? JSONArray ?: error("Expected encoded array: $key")
    }
    internal fun time(obj: JSONObject, key: String): Long? = if (!obj.has(key) || obj.isNull(key)) null else LegacyJson.integer(obj, key).also { require(it >= 0) }
    internal fun count(obj: JSONObject, key: String): Long = if (!obj.has(key)) 0 else LegacyJson.integer(obj, key).also { require(it >= 0) }
    internal fun bool(obj: JSONObject, key: String, default: Boolean): Boolean = if (!obj.has(key)) default else (obj.get(key) as? Boolean ?: error("Expected boolean: $key"))
    internal fun id(obj: JSONObject, key: String): String = LegacyJson.string(obj, key).also { require(it.isNotBlank() && it.length <= 200 && it.none { c -> c.code < 32 }) { "Invalid identity" } }
    private fun nullableObject(obj: JSONObject, key: String): JSONObject? = if (!obj.has(key) || obj.isNull(key)) null else obj.getJSONObject(key)
    private fun unique(ids: List<String>, type: String): Set<String> = ids.toSet().also { require(it.size == ids.size) { "Duplicate $type" } }
    internal fun keys(obj: JSONObject, allowed: Set<String>) { require(obj.keys().asSequence().all { it in allowed }) { "Unknown field outside frozen schema; refusing to discard or retain potential secrets" } }
    private fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { equivalent(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equivalent(a.get(it), b.get(it)) }
        else -> a == b
    }
}
