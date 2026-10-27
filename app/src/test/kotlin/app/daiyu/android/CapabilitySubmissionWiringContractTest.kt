package app.daiyu.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard the actual submission wiring as well as the pure single-use token gates. */
class CapabilitySubmissionWiringContractTest {
    private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/$path") }.first { it.isFile }.readText()

    @Test fun platformOriginalExecuteAndLocationReadyContinueSameSubmitWithoutPermissionDialog() {
        val host = source("ui/PlatformActionApprovalHost.kt")
        assertFalse(host.contains("AlertDialog"))
        assertFalse(host.contains("platform-action-confirm"))
        assertFalse(host.contains("LaunchedEffect"))
        assertTrue(host.contains("if (!frozen.requiresLocation) submit(frozen)"))
        assertTrue(host.contains("else if (route is TaskSurfaceDecision.Ready) submit(frozen)"))
        assertTrue(host.contains("if (locationState is TaskSurfaceDecision.Ready) submit(frozen)"))
        assertTrue(host.contains("controller.confirmPlatformAction(frozen)"))
        assertTrue(host.contains("controller.rejectPlatformAction(frozen)"))
    }

    @Test fun mcpOriginalSubmitMustAuthorizeAtControllerGateNotThroughComposition() {
        val controller = source("ChatController.kt")
        val gate = controller.substringAfter("internal fun sendMcp(").substringBefore("internal fun prepareBrowserSend(")
        assertTrue(gate.contains("pending.authorizeForSubmission(owner, text)"))
        assertTrue(gate.contains("candidate.submitted"))
        assertTrue(gate.contains("candidate.revoked"))
        val host = source("ui/McpApprovalHost.kt")
        assertFalse(host.contains("onApprove"))
        assertFalse(host.contains("AlertDialog"))
        assertFalse(host.contains("LaunchedEffect"))
        val main = source("MainActivity.kt")
        val submit = main.substringAfter("private fun approveMcp(").substringBefore("private fun openMcpApproval()")
        assertFalse(submit.contains("pending.confirm("))
        val input = main.substringAfter("private fun openMcpApproval()").substringBefore("private fun cancelBrowserEditor(")
        assertTrue(input.contains("approveMcp(it, visit)"))
    }

    @Test fun shizukuDirectSendRejectsRevokedMountAndOriginalEditorBeforePreparing() {
        val host = source("ui/ShizukuApprovalHost.kt")
        val sendStart = "sendButton(preview == null && text.isNotBlank(), send@{"
        assertTrue(host.contains(sendStart))
        val click = host.substringAfter(sendStart).substringBefore("preview?.let")
        assertTrue(click.contains("if (!active.get() || !isCurrent()) return@send"))
        assertTrue(click.indexOf("if (!active.get() || !isCurrent()) return@send") <
            click.indexOf("controller.prepareShizukuSend("))
        val postPrepareRejection = "if (!active.get() || !isCurrent()) {\n" +
            "                    controller.rejectShizuku(prepared)\n" +
            "                    return@send\n" +
            "                }"
        assertTrue(click.contains(postPrepareRejection))
        assertTrue(click.indexOf("controller.prepareShizukuSend(") <
            click.indexOf(postPrepareRejection))
        assertTrue(click.indexOf(postPrepareRejection) <
            click.indexOf("controller.confirmShizukuSend(prepared)"))
        assertTrue(host.contains("active.set(false)"))
        assertTrue(host.contains("saved != null && active.get() && isCurrent()"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("isCurrent = { shizukuEditorCurrent(editor) }"))
        val owner = main.substringAfter("private fun shizukuEditorCurrent(").substringBefore("private fun acceptShizuku(")
        for (check in listOf("shizukuEditor === editor", "editorGeneration == editor.generation",
            "draftRevision == editor.revision", "draft == editor.text", "selected?.id == editor.conversation?.id"))
            assertTrue(owner.contains(check))
    }

    @Test fun generationRetainsPaidSubmitButDoesNotIntroduceAnotherCapabilityGate() {
        val preparation = source("ControllerGenerationPreparation.kt")
        assertTrue(preparation.contains("preparation.authorizeForSubmission(preparation.preview, currentEditor, currentIdentity)"))
        val host = source("ui/GenerationApprovalHost.kt")
        assertTrue(host.contains("confirmationHandoff.confirm(frozen, editor, identity, onConfirmed)"))
        assertTrue(host.contains("提交生成（可能产生费用）"))
        assertTrue(host.contains("preview.blockers.isEmpty()"))
        // The existing effect only invalidates changed drafts; it must not simulate paid submit.
        val effect = host.substringAfter("LaunchedEffect(").substringBefore("    fun edit(")
        assertFalse(effect.contains("confirmationHandoff.confirm"))
    }
}
