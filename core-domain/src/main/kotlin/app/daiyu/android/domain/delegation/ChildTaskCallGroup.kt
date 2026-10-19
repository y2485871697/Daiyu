package app.daiyu.android.domain.delegation

import java.security.MessageDigest

/** Durable display association, independent of the process-local execution lease. */
fun childTaskCallGroup(conversationId: String, runId: String, messageId: String, toolName: String, callId: String): String {
    val encoded = listOf(conversationId, runId, messageId, toolName, callId)
        .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
    return "pcall-v1-" + MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
