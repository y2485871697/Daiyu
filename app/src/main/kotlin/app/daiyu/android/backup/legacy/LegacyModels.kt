package app.daiyu.android.backup.legacy

/** No class in this package is a runtime request, message action or accepted model context. */
data class LegacyIssue(val code: String, val count: Int, val detail: String)
data class LegacyConversionReport(val issues: List<LegacyIssue>, val readOnly: Boolean = true,
    val nativeMessagesCreated: Int = 0, val runtimeRequestsCreated: Int = 0)
data class LegacyPreview(val sha256: String, val format: String, val schemaVersion: Int,
    val conversations: Int, val uiMessages: Int, val modelHistoryMessages: Int,
    val attachments: Int, val blobBytes: Long, val report: LegacyConversionReport)
data class LegacyImportResult(val importId: String, val conversationIds: List<String>, val report: LegacyConversionReport)
data class LegacyHistorySummary(val id: String, val sourceId: String, val title: String,
    val sourceCreatedAt: Long?, val sourceUpdatedAt: Long?, val importId: String)
data class LegacyUiMessage(val id: String, val sourceId: String, val type: String, val content: String,
    val generatedAtMillis: Long?, val createdAt: Long? = null)
data class LegacyMedia(val reference: String, val entry: String?, val sha256: String?, val bytes: Long?,
    /** preview is not evidence of a video original. Remote URLs remain unresolved. */
    val purpose: String, val available: Boolean)
data class LegacyReadOnlyHistory(val summary: LegacyHistorySummary, val messages: List<LegacyUiMessage>,
    val modelHistoryJson: String, val checkpointHistoryJson: String?, val media: List<LegacyMedia>,
    /** Allowlisted conversation/message/checkpoint data, NOT the credential-bearing root manifest. */
    val compatibilityJson: String, val report: LegacyConversionReport, val readOnly: Boolean = true)

internal data class LegacyConversation(val sourceId: String, val title: String, val createdAt: Long?, val updatedAt: Long?,
    val raw: String, val messages: List<LegacySourceMessage>, val history: String, val checkpoint: String?, val media: List<LegacyMedia>)
internal data class LegacySourceMessage(val id: String, val type: String, val content: String, val generatedAtMillis: Long?)
internal data class LegacyPlan(val format: String, val version: Int, val conversations: List<LegacyConversation>,
    val originals: Map<String, ByteArray>, val report: LegacyConversionReport, val historyCount: Int)
internal fun digest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
