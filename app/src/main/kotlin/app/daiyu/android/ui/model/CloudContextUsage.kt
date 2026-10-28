package app.daiyu.android.ui.model

import app.daiyu.android.domain.ModelEvent
import java.text.NumberFormat
import java.util.Locale

/** Non-secret identity of the configuration that actually sent the request. */
internal data class ContextUsageModel(val provider: String, val model: String,
    val configuration: Long, val windowTokens: Long?, val registryRevision: Long = 0,
    val receiptIdentity: String? = null) {
    fun accepts(receipt: ContextUsageModel): Boolean = provider == receipt.provider && model == receipt.model &&
        if (receipt.receiptIdentity != null) receiptIdentity == receipt.receiptIdentity
        else configuration == receipt.configuration && registryRevision == receipt.registryRevision
}

/** Stable receipt scope; editor revisions remain separate admission/optimistic-lock tokens. */
internal fun contextReceiptIdentity(providerIdentity: String, localIdentity: String, protocol: String,
    endpoint: String, customBody: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    listOf("daiyu-context-receipt-v1", providerIdentity, localIdentity, protocol, endpoint, customBody).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class CloudContextUsageUi(val inputTokens: Long? = null, val windowTokens: Long? = null) {
    val fraction: Float? get() = inputTokens?.takeIf { it >= 0 }?.let { input ->
        windowTokens?.takeIf { it > 0 }?.let { (input.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    fun label(locale: Locale = Locale.getDefault()): String {
        val input = inputTokens?.takeIf { it >= 0 } ?: return "上下文"
        val window = windowTokens?.takeIf { it > 0 } ?: return "上下文"
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1; maximumFractionDigits = 1; isGroupingUsed = false
        }
        return "${format.format(input.toDouble() / window * 100)}% 上下文"
    }
    fun tokensLabel(locale: Locale = Locale.getDefault()): String {
        fun compact(tokens: Long?): String {
            if (tokens == null || tokens < 0) return "—"
            if (tokens < 1_000) return tokens.toString()
            val format = NumberFormat.getNumberInstance(locale).apply {
                maximumFractionDigits = 1; isGroupingUsed = false
            }
            return "${format.format(tokens / 1_000.0)}K"
        }
        return "${compact(inputTokens)}/${compact(windowTokens?.takeIf { it > 0 })}"
    }
}

/** Input is already normalized by the transport and includes both cache subsets. */
internal fun acceptedContextInput(usage: ModelEvent.Usage, window: Long?): Long? {
    val input = usage.inputTokens?.takeIf { it >= 0 } ?: return null
    if (window != null && window > 0 && input.toDouble() > window.toDouble() * 1.3) return null
    val cached = usage.cachedTokens
    if (cached != null && (cached > input || (window != null && window > 0 && cached > window))) return null
    return input
}
