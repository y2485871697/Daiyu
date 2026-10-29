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
        val withMemoryPolicy = if (memoryReadPort == null) base else "$base\n\n${AssistantMemoryLimits.MODEL_POLICY}"
        "$withMemoryPolicy\n\n$EXECUTION_POLICY"
    }

    companion object {
        const val EXECUTION_POLICY = "仅使用本轮提供的工具、选定执行身份和前后台位置；工具未执行、权限不足或后端不可用不授权换通道。" +
            "屏幕任务先观察当前窗口，不沿用前一任务的应用包名；观察或屏幕准入失败后，不得改用 shell、Shizuku、su、input、screencap 或 uiautomator 绕过目标、显示器及位置限制。" +
            "Root 通道失败不得换 Shizuku 或应用身份重试。结果未知表示可能已执行：立即停止，不重复、不改参数或换通道重放，仅以独立获准的只读方式核实。" +
            "操作成功并达到用户目标后结束，不继续无关探测；只有确实需要读取后续界面或确认最终结果时才再次观察。"

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
