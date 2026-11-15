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
        for (limit in listOf(65_536, 131_072, 1_048_576, 2_097_152)) {
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

    @Test fun terminalTargetsAndRichOutputShareLargeReceiptsWithoutExpandingBudgets() {
        for (name in listOf("terminal_exec", "terminal_job_read")) {
            for (withTarget in listOf(false, true)) {
                for (length in listOf(65_536, 65_537, 131_072, 131_073, 1_048_576, 1_048_577, 2_097_152, 2_097_153)) {
                    val receipt = JSONObject().put("stdout", "OUT").put("stderr", "ERR")
                        .put("text", "FILE").put("exit_code", 0).put("padding", "")
                    if (withTarget) receipt.put("backend", "android").put("uid", 0)
                    val overhead = receipt.toString().length
                    val result = receipt.put("padding", "x".repeat(length - overhead)).toString()
                    assertEquals(length, result.length)
                    var parses = 0
                    val decoded = ToolPresentationJson(result) { parses++; JSONObject(it) }
                    val card = ToolCardPresentations.presentParsed(name, "{}", result, ToolPresentationJson("{}"), decoded)
                    assertEquals(if (withTarget && length <= 2_097_152) "Android • root" else null, card.targetLabel)
                    assertEquals(length <= 65_536, card.details.fields.contains(ToolFieldUi("退出码", "0")))
                    assertEquals(length <= 1_048_576, card.details.rich.documents.any { it.title == "标准输出" && it.text == "OUT" })
                    assertEquals(length <= 1_048_576, card.details.rich.documents.any { it.title == "标准错误" && it.text == "ERR" })
                    val files = ToolRichDetails.project("files", "read_text_file", "{}", result, resultJson = decoded)
                    assertEquals(length <= 131_072, files.documents.any { it.title == "文件内容" && it.text == "FILE" })
                    assertEquals(if (length <= 2_097_152) 1 else 0, parses)
                }
            }
        }
    }

    @Test fun targetMetadataUsesTheSameInjectedDecoderInBuilderAndStandaloneCategory() {
        val result = JSONObject().put("stdout", "x".repeat(70_000)).put("backend", "android").put("uid", 0).toString()
        for (directCategory in listOf(false, true)) {
            var parses = 0
            val decoded = ToolPresentationJson(result) {
                parses++
                JSONObject(it).put("backend", "linux:debian").put("uid", 1000)
            }
            val details = if (directCategory) {
                ToolCategoryDetails.project("terminal", "{}", result, toolName = "terminal_exec", resultJson = decoded)
            } else {
                val card = ToolCardPresentations.presentParsed("terminal_exec", "{}", result, ToolPresentationJson("{}"), decoded)
                assertEquals("Debian • user", card.targetLabel)
                card.details
            }
            assertTrue(details.fields.contains(ToolFieldUi("执行环境", "Debian • user")))
            assertFalse(details.fields.contains(ToolFieldUi("执行环境", "Android • root")))
            assertEquals(1, parses)
        }
    }

    @Test fun malformedTerminalReceiptsCacheFailureForTargetCategoryAndRichConsumers() {
        for (result in listOf("{\"stdout\":\"", "{\"stdout\":\"" + "x".repeat(70_000))) {
            for ((name, category) in listOf("terminal_exec" to "terminal", "terminal_job_read" to "background_job")) {
                var parses = 0
                val decoded = ToolPresentationJson(result) { parses++; JSONObject(it) }
                val card = ToolCardPresentations.presentParsed(name, "{}", result, ToolPresentationJson("{}"), decoded)
                assertNull(card.targetLabel)
                val details = ToolCategoryDetails.project(category, "{}", result, toolName = name, resultJson = decoded)
                assertFalse(details.fields.any { it.label == "执行环境" })
                assertNull(decoded.read(2_097_152))
                assertEquals(1, parses)
            }
        }
    }

    @Test fun presentationConsumersNeverReenterTheRawTargetParser() {
        for (name in listOf("ToolCardPresentation.kt", "ToolCategoryDetails.kt")) {
            val relative = "src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/$name"
            val file = listOf(java.io.File(relative), java.io.File("app/$relative")).firstOrNull { it.isFile }
            assertNotNull("Cannot locate presentation source: $name", file)
            val source = file!!.readText()
            assertFalse(Regex("""TerminalToolTarget\s*\.\s*fromResult\s*\(""").containsMatchIn(source))
            assertEquals(1, Regex("""TerminalToolTarget\s*\.\s*fromPresentation\s*\(\s*toolName\s*,\s*resultJson\s*\)""").findAll(source).count())
        }
    }
}
