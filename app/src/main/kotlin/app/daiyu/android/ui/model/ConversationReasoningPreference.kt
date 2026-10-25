package app.daiyu.android.ui.model

import android.content.SharedPreferences
import app.daiyu.android.delegation.ReasoningEffort

/** Separate standalone-draft and per-conversation preferences. Runtime must use snapshot(),
 * never the tolerant presentation projection get(). Frozen runs do not read this store again. */
internal class ConversationReasoningPreference(private val values: KeyValue) {
    private val unconfirmed = mutableSetOf<String>()
    /** Injectable string storage keeps preference behavior testable on the JVM. */
    interface KeyValue {
        fun get(key: String): String?
        fun set(key: String, value: String)
    }

    constructor(preferences: SharedPreferences) : this(object : KeyValue {
        override fun get(key: String): String? = preferences.getString(key, null)

        override fun set(key: String, value: String) {
            check(preferences.edit().putString(key, value).commit() && preferences.getString(key, null) == value) {
                "思考偏好保存未确认；请重新保存"
            }
        }
    })

    fun get(conversationId: String?): ReasoningEffort = try {
        ReasoningEffort.textPreference(ReasoningEffort.fromWireValue(values.get(key(conversationId))))
    } catch (_: Exception) { ReasoningEffort.OFF }

    @Synchronized fun snapshot(conversationId: String?): ReasoningEffort {
        return snapshotKey(key(conversationId))
    }

    @Synchronized fun set(conversationId: String?, effort: ReasoningEffort) {
        save(key(conversationId), effort)
    }

    private fun save(key: String, requested: ReasoningEffort) {
        val effort = ReasoningEffort.textPreference(requested)
        try {
            values.set(key, effort.wireValue)
            check(values.get(key) == effort.wireValue) { "思考偏好保存未确认" }
            unconfirmed.remove(key)
        } catch (failure: Exception) {
            unconfirmed.add(key)
            throw failure
        }
    }

    /** Copy the accepted standalone choice, never overwrite a later conversation edit. */
    @Synchronized fun retainAccepted(conversationId: String, effort: ReasoningEffort) {
        check(key(conversationId) !in unconfirmed) { "会话思考偏好保存未确认；请重新保存" }
        if (values.get(key(conversationId)) == null) set(conversationId, effort)
        else snapshot(conversationId) // Existing edits survive, but corrupt/unconfirmed values cannot be admitted.
    }

    /** Explicit New chat copies the current choice once, not a stale standalone draft. */
    @Synchronized fun inheritDraft(sourceConversationId: String?) {
        if (sourceConversationId != null) set(null, snapshot(sourceConversationId))
    }

    @Synchronized fun rememberModel(providerId: String, modelId: String, effort: ReasoningEffort) =
        save(modelKey(providerId, modelId), effort)

    @Synchronized fun rememberedModel(providerId: String, modelId: String): ReasoningEffort =
        snapshotKey(modelKey(providerId, modelId))

    private fun snapshotKey(key: String): ReasoningEffort {
        check(key !in unconfirmed) { "思考偏好保存未确认；请重新保存" }
        val raw = values.get(key) ?: return ReasoningEffort.OFF
        val stored = ReasoningEffort.fromWireValue(raw)
            ?: throw IllegalStateException("思考偏好无效；请重新保存，未启动模型请求")
        return ReasoningEffort.textPreference(stored)
    }

    private fun modelKey(providerId: String, modelId: String): String {
        require(providerId.isNotBlank() && modelId.isNotBlank())
        return "reasoning:model:${providerId.length}:$providerId:$modelId"
    }

    // Separate namespaces prevent a real conversation ID (including "draft") colliding with null.
    private fun key(conversationId: String?): String =
        if (conversationId == null) "reasoning:draft" else "reasoning:conversation:$conversationId"
}
