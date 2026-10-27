package app.daiyu.android.integration.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production wiring contracts complement the executable gate/dispatcher/session JVM tests.
 * No Activity, system permission prompt, process or shell is started by these tests.
 */
class CommandPermissionProductionContractTest {
    private fun source(name: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/$name") }.first { it.isFile }.readText()
    private fun between(text: String, start: String, end: String): String {
        val begin = text.indexOf(start)
        assertTrue("missing $start", begin >= 0)
        val finish = text.indexOf(end, begin + start.length)
        assertTrue("missing $end", finish > begin)
        return text.substring(begin, finish)
    }
    private fun ordered(text: String, first: String, second: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(second)
        assertTrue("$first must precede $second", a >= 0 && b > a)
    }

    @Test fun ordinaryProductionUsesAutomaticGateAndOriginalExactDispatcherGrant() {
        assertFalse(CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL)
        val controller = source("ChatController.kt")
        val creation = between(controller, "private val commandApprovals = CommandApprovalGate(",
            "internal fun attachCommandApprovalUi")
        assertFalse(creation.contains("requireInteractiveApproval = true"))
        assertTrue(creation.contains("!it.fenced && owner.owns(it.initial)"))
        assertTrue(controller.contains("commandApprovals.await(CommandApprovalRequest(realOwner, context.invocation.id.value,"))
        assertTrue(controller.contains("effect, disclosure), context.cancelled)"))
        val leaf = source("integration/tools/RunCommandTools.kt")
        assertTrue(leaf.contains("PreparedTool.approvedWrite(effect,"))
        assertTrue(leaf.contains("TaskAuthorization(authorization.sessionId, authorization.taskId, setOf(effect))"))
        assertTrue(leaf.contains("call.authorization.allowedWrites != setOf(effect)"))
        assertTrue(leaf.contains("terminal_closed_or_unknown"))
        val defaults = source("integration/tools/CommandPermissionDefaults.kt")
        assertFalse(defaults.contains("SharedPreferences"))
        assertFalse(defaults.contains("getBoolean"))
    }

    @Test fun ptySendConfirmsFrozenPlanOnlyInsideOriginalLiveOwnerAdmissionBeforeTransfer() {
        val send = between(source("ChatController.kt"), "internal fun sendTerminal(pending:",
            "/** Worker-only snapshot. Merely opening approval")
        ordered(send, "actual != (assistant to provider)", "val token = synchronized(this)")
        ordered(send, "candidate.terminal !== pending || candidate.revoked || candidate.submitted || closed",
            "pending.confirm(displayed, current, assistant, provider)")
        assertTrue(send.contains("!CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL"))
        assertTrue(send.contains("pending.state == ControllerTerminalPreparation.State.PREVIEW"))
        ordered(send, "pending.confirm(displayed, current, assistant, provider)", "pending.takeConfirmed(displayed, current, assistant, provider)")
        ordered(send, "pending.takeConfirmed(displayed, current, assistant, provider)", "candidate.submitted = true")
        val preparation = source("ControllerTerminalPreparation.kt")
        assertTrue(preparation.contains("displayed !== preview || current != draft"))
        assertTrue(preparation.contains("preview.plan.effects.values.toSet()"))
        assertTrue(preparation.contains("phase = State.UNKNOWN"))
    }

    @Test fun asyncAndDaemonSendUseOriginalExactConfirmAndSingleTransferNotComposition() {
        val send = between(source("ChatController.kt"), "internal fun sendTerminalJob(pending:",
            "/** Snapshot outside the admission monitor:")
        ordered(send, "actual != (pending.assistant to pending.provider)", "val token = synchronized(this)")
        ordered(send, "candidate.terminalJob !== pending || candidate.revoked || candidate.submitted || closed",
            "pending.confirm(current, pending.assistant, pending.provider)")
        assertTrue(send.contains("!CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL && !pending.isConfirmed"))
        ordered(send, "pending.confirm(current, pending.assistant, pending.provider)", "pending.transfer(current, pending.assistant, pending.provider)")
        ordered(send, "pending.transfer(current, pending.assistant, pending.provider)", "candidate.submitted = true")
        val preparation = source("ControllerTerminalJobPreparation.kt")
        assertTrue(preparation.contains("confirmed || cancelled || attempted || current != draft"))
        assertTrue(preparation.contains("TaskAuthorization(plan.sessionId, plan.runId.value, setOf(plan.effect))"))
        assertTrue(preparation.contains("!confirmed || cancelled || transferred || current != draft"))
    }

    @Test fun shizukuTextSubmitHasNoConsentSwitchOrDialogAndKeepsSystemAuthorizationSeparate() {
        val host = source("ui/ShizukuApprovalHost.kt")
        assertFalse(host.contains("AlertDialog"))
        assertFalse(host.contains("Switch("))
        assertTrue(host.contains("sendButton: @Composable (Boolean, () -> Unit) -> Unit = { enabled, send ->\n" +
            "        ShizukuApprovalSendButton(enabled, send)\n    }"))
        val submit = between(host, "sendButton(preview == null && text.isNotBlank(), send@{", "preview?.let")
        ordered(submit, "controller.prepareShizukuSend", "controller.confirmShizukuSend(prepared)")
        val defaultButton = between(host, "internal fun ShizukuApprovalSendButton(", "\n}")
        assertTrue(defaultButton.contains("Button(enabled = enabled, modifier = Modifier.testTag(\"shizuku-text-send\"), onClick = onClick)"))
        assertTrue(defaultButton.contains("Text(\"发送 Shizuku 命令\")"))
        val invalidation = between(host, "LaunchedEffect(controller, conversation?.id, text)", "val preview =")
        assertFalse(invalidation.contains("confirmShizukuSend"))
        assertFalse(host.contains("requestPermission("))
        assertFalse(host.contains(".connect("))
        val session = source("shizuku/ShizukuRunSession.kt")
        assertTrue(session.contains("!CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL && !closing && !started"))
        assertTrue(session.contains("check(backend.identity.uid == selection.first)"))
        val access = source("shizuku/ShizukuAuthorization.kt")
        assertTrue(access.contains("service.checkSelfPermission()"))
        assertTrue(access.contains("actualUid == expectedUid"))
    }
}
