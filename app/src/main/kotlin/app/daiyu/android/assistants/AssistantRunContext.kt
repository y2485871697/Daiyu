package app.daiyu.android.assistants

import java.util.Collections

class MissingAssistantBindingException : IllegalArgumentException("会话未绑定助手")
class AssistantNotFoundException(val assistantId: String) : IllegalArgumentException("会话绑定的助手不存在或已删除")

/**
 * One run's assistant input. No store/assets reference survives capture, and skill IDs are
 * selection metadata only: they never grant a tool or a device permission.
 */
class AssistantRunContext private constructor(
    val assistantId: String,
    val name: String,
    val prompt: String,
    val avatarFileName: String?,
    val memoryEnabled: Boolean,
    val resolvedMemory: String,
    enabledSkillIds: List<String>,
) {
    val enabledSkillIds: List<String> = Collections.unmodifiableList(ArrayList(enabledSkillIds))
    val systemPrompt: String = AssistantPrompt.build(name, prompt).let { personality ->
        if (resolvedMemory.isEmpty()) personality else "$personality\n\n记忆：\n$resolvedMemory"
    }

    companion object {
        /** The caller resolves memory for this binding; capture performs no I/O or active fallback. */
        fun capture(store: AssistantStore, assistantId: String?, resolvedMemory: String): AssistantRunContext {
            if (assistantId.isNullOrBlank()) throw MissingAssistantBindingException()
            val profile = store.read(assistantId) ?: throw AssistantNotFoundException(assistantId)
            return AssistantRunContext(
                assistantId = profile.id,
                name = profile.name,
                prompt = profile.prompt,
                avatarFileName = profile.avatarFileName,
                memoryEnabled = profile.memoryEnabled,
                resolvedMemory = if (profile.memoryEnabled) resolvedMemory else "",
                enabledSkillIds = profile.enabledSkillIds,
            )
        }
    }
}

fun AssistantStore.capture(assistantId: String?, resolvedMemory: String): AssistantRunContext =
    AssistantRunContext.capture(this, assistantId, resolvedMemory)
