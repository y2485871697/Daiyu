package app.daiyu.android.workspace

import org.json.JSONArray
import org.json.JSONObject

internal object ConversationWorkspaceCodec {
    fun encode(binding: ConversationWorkspaceBinding): String? = when (binding) {
        is ConversationWorkspaceBinding.Private -> binding.path?.let { path ->
            JSONObject().put("version", 1).put("kind", "private").put("path", path).toString()
        }
        is ConversationWorkspaceBinding.Rootfs -> JSONObject().put("version", 1).put("kind", "rootfs")
            .put("environment_id", binding.environmentId).put("generation", binding.generation)
            .put("guest_path", binding.guestPath).toString()
    }

    fun encode(workspaces: ConversationWorkspaces): String? {
        if (workspaces.bindings.size == 1) return encode(workspaces.active)
        val values = JSONArray()
        workspaces.bindings.forEach { binding ->
            values.put(encode(binding)?.let(::JSONObject) ?: JSONObject.NULL)
        }
        val encoded = JSONObject().put("version", 2).put("kind", "collection")
            .put("active", workspaces.activeIndex).put("bindings", values).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 4096) { "workspace_collection_too_large" }
        return encoded
    }

    fun decode(value: String?): ConversationWorkspaceBinding = decodeWorkspaces(value).active

    fun decodeWorkspaces(value: String?): ConversationWorkspaces {
        if (value == null) return ConversationWorkspaces(listOf(ConversationWorkspaceBinding.Private()), 0)
        require(value.toByteArray(Charsets.UTF_8).size <= 4096) { "workspace_binding_invalid" }
        try {
            val json = app.daiyu.android.model.MessagesRequest.jsonObject(value, maxDepth = 3, rejectDuplicateKeys = true)
            if (json.optString("kind") != "collection") return ConversationWorkspaces(listOf(decodeSingle(value)), 0)
            require(json.keys().asSequence().toSet() == setOf("version", "kind", "active", "bindings") &&
                json.get("version") == 2 && json.get("active") is Int) { "workspace_binding_invalid" }
            val values = json.getJSONArray("bindings")
            val bindings = (0 until values.length()).map { index ->
                if (values.isNull(index)) ConversationWorkspaceBinding.Private()
                else decodeSingle(values.getJSONObject(index).toString())
            }
            return ConversationWorkspaces(bindings, json.getInt("active"))
        } catch (failure: Exception) {
            throw IllegalArgumentException("workspace_binding_invalid", failure)
        }
    }

    private fun decodeSingle(value: String?): ConversationWorkspaceBinding {
        if (value == null) return ConversationWorkspaceBinding.Private()
        require(value.toByteArray(Charsets.UTF_8).size <= 4096) { "workspace_binding_invalid" }
        try {
            val json = JSONObject(value)
            val kind = json.getString("kind")
            val fields = when (kind) {
                "private" -> setOf("version", "kind", "path")
                "rootfs" -> setOf("version", "kind", "environment_id", "generation", "guest_path")
                else -> error("workspace_binding_invalid")
            }
            require(json.keys().asSequence().toSet() == fields && json.get("version") == 1) { "workspace_binding_invalid" }
            fun text(key: String): String = (json.get(key) as? String)?.takeIf {
                it.isNotBlank() && '\u0000' !in it
            } ?: error("workspace_binding_invalid")
            return when (kind) {
                "private" -> ConversationWorkspaceBinding.Private(text("path"))
                else -> ConversationWorkspaceBinding.Rootfs(text("environment_id"), text("generation"), text("guest_path"))
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException("workspace_binding_invalid", failure)
        }
    }
}
