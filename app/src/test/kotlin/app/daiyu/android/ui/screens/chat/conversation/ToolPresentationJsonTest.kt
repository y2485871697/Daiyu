package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolPresentationJsonTest {
    @Test fun consumersShareOneDecodeButKeepTheirOwnInputLimits() {
        var parses = 0
        val source = "{\"text\":\"" + "x".repeat(70_000) + "\"}"
        val input = ToolPresentationJson(source) { parses++; JSONObject(it) }
        assertNull(input.read(65_536))
        assertEquals(0, parses)
        val decoded = input.read(131_072)
        assertNotNull(decoded)
        assertSame(decoded, input.read(1_048_576))
        assertNull(input.read(65_536))
        assertEquals(1, parses)
    }

    @Test fun everyOriginalBudgetIncludesItsBoundaryAndRejectsTheNextCharacter() {
        for (limit in listOf(65_536, 131_072, 1_048_576)) {
            var parses = 0
            val source = "{\"x\":\"" + "a".repeat(limit - 8) + "\"}"
            assertEquals(limit, source.length)
            val input = ToolPresentationJson(source) { parses++; JSONObject(it) }
            assertNull(input.read(limit - 1))
            assertEquals(0, parses)
            assertNotNull(input.read(limit))
            assertNull(input.read(limit - 1))
            assertEquals(1, parses)
        }
    }

    @Test fun malformedObjectsRememberFailureAndNonObjectsNeverInvokeParser() {
        var parses = 0
        val malformed = ToolPresentationJson("{broken") { parses++; throw IllegalArgumentException() }
        repeat(3) { assertNull(malformed.read(131_072)) }
        assertEquals(1, parses)
        for (source in listOf(null, "", "  ", "[]", "plain receipt")) {
            var nonObjectParses = 0
            val input = ToolPresentationJson(source) { nonObjectParses++; JSONObject() }
            assertNull(input.read(1_048_576))
            assertEquals(0, nonObjectParses)
        }
        val spaced = ToolPresentationJson("  {\"ok\":true}")
        assertTrue(spaced.read(65_536)!!.getBoolean("ok"))
    }

    @Test fun completeTerminalPresentationDecodesEachSourceOnceWithoutChangingRichOutput() {
        for (name in listOf("terminal_exec", "terminal_read")) {
            var argsParses = 0
            var resultParses = 0
            val arguments = "{\"command\":\"printf hello\",\"environment\":\"linux\"}"
            val result = JSONObject().put("stdout", "汉".repeat(40_000)).put("exit_code", 0).toString()
            val expected = ToolCardPresentations.present(name, arguments, result)
            val actual = ToolCardPresentations.presentParsed(name, arguments, result,
                ToolPresentationJson(arguments) { argsParses++; JSONObject(it) },
                ToolPresentationJson(result) { resultParses++; JSONObject(it) })
            assertEquals(expected, actual)
            assertTrue(actual.details.rich.documents.any { it.title == "标准输出" && it.text.contains("汉") })
            assertEquals(1, argsParses)
            assertEquals(1, resultParses)
        }
    }

    @Test fun richScreenshotBranchNeverRequestsTheEncodedResult() {
        val result = "{\"data_base64\":\"ENCODED_IMAGE\"}"
        var resultParses = 0
        val details = ToolRichDetails.project("gui", "screen", "{\"operation\":\"screenshot\"}", result, true,
            resultJson = ToolPresentationJson(result) { resultParses++; JSONObject(it) })
        assertEquals(0, resultParses)
        val disclosures = details.documents + details.technical + listOfNotNull(details.prose) +
            details.entries.flatMap { it.documents }
        assertTrue(disclosures.none { it.text.contains("ENCODED_IMAGE") })
        assertTrue((details.fields + details.entries.flatMap { it.fields }).none { it.value.contains("ENCODED_IMAGE") })
    }
}
