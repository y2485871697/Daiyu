package app.daiyu.android.assistants

import java.util.Collections

class MissingAssistantBindingException : IllegalArgumentException("会话未绑定助手")
class AssistantNotFoundException(val assistantId: String) : IllegalArgumentException("会话绑定的助手不存在或已删除")

/**
 * One run's frozen assistant input. Only the optional revision-scoped read port retains
 * store/assets; skill IDs are metadata only, never a tool or device permission.
 */
class AssistantRunContext private constructor(
    val assistantId: String,
    val name: String,
    val prompt: String,
    val avatarFileName: String?,
    val memoryEnabled: Boolean,
    val resolvedMemory: String,
    enabledSkillIds: List<String>,
    val memoryRevision: String? = null,
    val memoryReadPort: AssistantMemoryReadPort? = null,
) {
    val enabledSkillIds: List<String> = Collections.unmodifiableList(ArrayList(enabledSkillIds))
    val systemPrompt: String = AssistantPrompt.build(name, prompt).let { personality ->
        val base = if (resolvedMemory.isEmpty()) personality else "$personality\n\n记忆：\n$resolvedMemory"
        if (memoryReadPort == null) base else "$base\n\n${AssistantMemoryLimits.MODEL_POLICY}"
    }

    companion object {
        /** Preferred host entry: resolve exactly this binding, freeze its revision and core.
         * Disabled memory performs zero asset reads and exposes no memory port. */
        fun capture(store: AssistantStore, assistantId: String?, assets: AssistantAssets): AssistantRunContext {
            if (assistantId.isNullOrBlank()) throw MissingAssistantBindingException()
            val profile = store.read(assistantId)?.let { it.copy(enabledSkillIds = it.enabledSkillIds.toList()) }
                ?: throw AssistantNotFoundException(assistantId)
            val snapshot = if (profile.memoryEnabled) assets.memory(profile.id) else null
            if (store.read(assistantId) != profile) throw StaleAssistantProfileException()
            val port = snapshot?.let { RevisionAssistantMemoryReadPort(profile.id, it.revision, store, assets) }
            return AssistantRunContext(profile.id, profile.name, profile.prompt, profile.avatarFileName,
                profile.memoryEnabled, boundedAssistantMemoryCore(snapshot?.content.orEmpty()),
                profile.enabledSkillIds, snapshot?.revision, port)
        }

        /** Legacy caller-resolved input is bounded too, but grants no read tool or revision.
         * Parent integration must migrate to the assets overload for on-demand retrieval. */
        fun capture(store: AssistantStore, assistantId: String?, resolvedMemory: String): AssistantRunContext {
            if (assistantId.isNullOrBlank()) throw MissingAssistantBindingException()
            val profile = store.read(assistantId) ?: throw AssistantNotFoundException(assistantId)
            return AssistantRunContext(
                assistantId = profile.id,
                name = profile.name,
                prompt = profile.prompt,
                avatarFileName = profile.avatarFileName,
                memoryEnabled = profile.memoryEnabled,
                resolvedMemory = if (profile.memoryEnabled) boundedAssistantMemoryCore(resolvedMemory) else "",
                enabledSkillIds = profile.enabledSkillIds,
            )
        }
    }
}

fun AssistantStore.capture(assistantId: String?, resolvedMemory: String): AssistantRunContext =
    AssistantRunContext.capture(this, assistantId, resolvedMemory)

fun AssistantStore.capture(assistantId: String?, assets: AssistantAssets): AssistantRunContext =
    AssistantRunContext.capture(this, assistantId, assets)
