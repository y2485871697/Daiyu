package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.integration.tools.*
import app.daiyu.android.terminal.TerminalEnvironmentHost
import java.io.File
import java.util.UUID

/** Host-authored finite plan. The separate editor is the ONLY source of input bytes.
 * No trimming, newline insertion, draft parsing, command inference or automatic retry. */
internal object TerminalChatPlan {
    fun create(conversationId: ConversationId, command: String, launch: TerminalPtyLaunch,
        journal: File, runId: String = UUID.randomUUID().toString()): TerminalPtyPlan {
        val bytes = command.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65536 && '\u0000' !in command) { "terminal_exact_input_required" }
        // Reject malformed UTF-16 rather than silently replacing user input on encoding.
        require(String(bytes, Charsets.UTF_8) == command) { "terminal_invalid_unicode" }
        val linux = launch as? TerminalPtyLaunch.Linux
        return TerminalPtyPlan(conversationId, runId, runId,
            linux?.expected?.environmentId ?: TerminalEnvironmentHost.ANDROID_ENVIRONMENT,
            linux?.expected?.generation ?: TerminalEnvironmentHost.ANDROID_GENERATION,
            launch, journal, "chat-$runId",
            listOf(TerminalPtyStep.Open("open"), TerminalPtyStep.Input("input", "input-0", 0, bytes)),
            listOf(TerminalPtyRead("read-0", 0, 16384), TerminalPtyRead("read-1", 16384, 16384)))
    }
}
