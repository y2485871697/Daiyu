package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import org.junit.Assert.*
import org.junit.Test

class MarkdownCodePolicyTest {
    @Test fun fileNamesUseSafeLanguageAliasesAndNeverPaths() {
        mapOf("sh" to "sh", "BASH" to "sh", "python" to "py", "typescript" to "ts",
            "kotlin" to "kt", "json" to "json", "powershell" to "ps1", "YML" to "yaml",
            "" to "txt", "unknown" to "txt", "../../sh" to "txt", "sh\n" to "txt").forEach { (language, extension) ->
            assertEquals("code.$extension", markdownCodeFileName(language))
        }
    }
    @Test fun closingCodeCompletesWithoutWaitingForLaterProse() {
        val code = MarkdownBlock.Code("echo 代鱼\n", "sh", closed = true)
        assertFalse(markdownCodeProducing(code, code.text.length, generationComplete = false))
        assertTrue(markdownCodeProducing(code, code.text.length - 1, generationComplete = false))
    }
    @Test fun openCodeWaitsForTerminalEvenWhenStreamingPhasePauses() {
        val code = MarkdownBlock.Code("echo 代鱼\n", "sh", closed = false)
        assertTrue(markdownCodeProducing(code, code.text.length, generationComplete = false))
        assertFalse(markdownCodeProducing(code, code.text.length, generationComplete = true))
        assertTrue(markdownCodeProducing(code, code.text.length - 1, generationComplete = true))
    }
    @Test fun restoredCodeDoesNotNeedALiveGeneration() {
        val code = MarkdownBlock.Code("echo 代鱼\n", "sh", closed = false)
        assertFalse(markdownCodeProducing(code, Int.MAX_VALUE, generationComplete = true))
    }
    @Test fun closedCodeHidesOnlyTheFinalLineTerminatorWithoutChangingPayload() {
        listOf("", "\n", "echo hi", "echo hi\n", "\n  echo 代鱼  \n\n", "\tvalue \n").forEach { original ->
            val code = MarkdownBlock.Code(original, "sh", closed = true)
            assertEquals(original.removeSuffix("\n"), markdownCodeDisplayText(code, original))
            assertEquals(original, code.text)
        }
    }
    @Test fun openOrPartiallyRevealedCodeKeepsItsCurrentTrailingLine() {
        val original = "echo first\necho second\n"
        val open = MarkdownBlock.Code(original, "sh", closed = false)
        assertEquals(original, markdownCodeDisplayText(open, original))
        val closed = open.copy(closed = true)
        val partial = "echo first\n"
        assertEquals(partial, markdownCodeDisplayText(closed, partial))
        assertEquals("", markdownCodeDisplayText(closed, ""))
    }

}
