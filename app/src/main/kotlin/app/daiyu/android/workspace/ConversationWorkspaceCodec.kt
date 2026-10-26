package app.daiyu.android.workspace

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

    fun decode(value: String?): ConversationWorkspaceBinding {
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
