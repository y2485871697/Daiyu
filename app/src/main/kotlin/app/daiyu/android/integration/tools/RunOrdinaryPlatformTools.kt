package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.actions.*

internal class RunOrdinaryPlatformTools(owner: RunExecutionOwner, authorization: TaskAuthorization,
    backend: PlatformActionBackend) : RunOwnedToolScope(owner, authorization) {
    override val tools = PlatformActionKind.entries.filter { backend.unavailable(it) == null }.map { kind ->
        object : RegisteredTool {
            override val definition = ToolDefinition(kind.toolName, 1,
                "执行本次参数对应的系统操作。提交但未获系统效果回执时返回 UNKNOWN，禁止重发。",
                kind.capability, ToolEffect.WRITE, kind.schema)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val action = PlatformAction.parse(kind, arguments.canonicalJson())
                return write(action.effect) { context -> backend.execute(action) { !live() || context.cancelled() } }
            }
        }
    }
}
