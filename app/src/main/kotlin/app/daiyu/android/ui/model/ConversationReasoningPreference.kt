package app.daiyu.android.ui.model

import android.content.SharedPreferences
import app.daiyu.android.delegation.ReasoningEffort

/** Presentation-only preference. Never use this value to build model requests. */
internal class ConversationReasoningPreference(private val values: KeyValue) {
    /** Injectable string storage keeps preference behavior testable on the JVM. */
    interface KeyValue {
        fun get(key: String): String?
        fun set(key: String, value: String)
    }

    constructor(preferences: SharedPreferences) : this(object : KeyValue {
        override fun get(key: String): String? = try {
            preferences.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }

        override fun set(key: String, value: String) {
            preferences.edit().putString(key, value).apply()
        }
    })

    fun get(conversationId: String?): ReasoningEffort =
        ReasoningEffort.fromWireValue(values.get(key(conversationId))) ?: ReasoningEffort.DEFAULT

    fun set(conversationId: String?, effort: ReasoningEffort) {
        values.set(key(conversationId), effort.wireValue)
    }

    // Separate namespaces prevent a real conversation ID (including "draft") colliding with null.
    private fun key(conversationId: String?): String =
        if (conversationId == null) "reasoning:draft" else "reasoning:conversation:$conversationId"
}
