package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ToolRichDetailsTest {
    private fun text(value: ToolRichDetailsUi): String = (value.documents + value.technical + value.entries.flatMap { it.documents })
        .joinToString("\n") { it.text } + value.entries.joinToString { it.title } + value.fields.joinToString { it.value } + value.entries.flatMap { it.fields }.joinToString { it.value }

    @Test fun inputDocumentsNeverSuppressPlainTextReceipts() {
        listOf("memory", "files", "browser", "apps", "personal_search", "terminal", "speech", "image_read", "generation").forEach { category ->
            val value = ToolRichDetails.project(category, "read_result", """{"query":"needle","path":"/tmp/report","url":"https://example.com","command":"pwd","text":"sample"}""", "unique receipt body")
            assertTrue(category, value.documents.any { it.text.contains("unique receipt body") })
        }
    }
    @Test fun cookieCardsExposeOnlyWhitelistedMetadata() {
        val value = ToolRichDetails.project("browser", "browser_use", """{"action":"get_cookies"}""",
            """{"cookies":[{"name":"session","domain":"example.com","secure":true,"value":"COOKIE_VALUE","content":"COOKIE_CONTENT"}]}""")
        assertTrue(value.documents.isEmpty()); assertTrue(value.technical.isEmpty())
        assertFalse(text(value).contains("COOKIE_VALUE")); assertFalse(text(value).contains("COOKIE_CONTENT"))
        assertTrue(value.entries.single().fields.any { it.value == "example.com" })
    }
    @Test fun nestedAuthAndKnownSecretsNeverAppearInInspect() {
        val value = ToolRichDetails.project("generic", "new_tool", """{"auth":{"token":"CUSTOM_SECRET"},"sensitive":"NESTED_SECRET"}""",
            """{"content":"CUSTOM_SECRET NESTED_SECRET bearer abcdefg https://user:pass@example.com/p?token=QUERY_SECRET#fragment"}""")
        val rendered = text(value)
        listOf("CUSTOM_SECRET", "NESTED_SECRET", "abcdefg", "QUERY_SECRET", "user:pass", "fragment").forEach { assertFalse(it, rendered.contains(it)) }
        assertTrue(rendered.contains("https://example.com/p"))
    }
    @Test fun personalSourcesMaskCodesPhonesAndPrivateChatCachePaths() {
        val value = ToolRichDetails.project("personal_search", "read_sms_code", "{}",
            """{"code":"123456","text":"验证码：654321 联系13800138000 /data/user/0/chat/cache/image.jpg","items":[{"content":"OTP: ABCD56"}]}""")
        val rendered = text(value)
        listOf("123456", "654321", "13800138000", "ABCD56", "/data/user").forEach { assertFalse(it, rendered.contains(it)) }
    }
    @Test fun nativeSkillBodyAndGroupedSourcesAreProjected() {
        val value = ToolRichDetails.project("skills", "skills_read", """{"skillId":"sample"}""",
            """{"bodyMarkdown":"## Skill instructions","skills":[{"id":"a","name":"A"}],"compatibility":[{"name":"Android","status":"supported"}]}""")
        assertTrue(value.documents.any { it.text.contains("Skill instructions") })
        assertEquals(setOf("技能", "兼容检查"), value.entries.map { it.group }.toSet())
    }
    @Test fun generationDetailsKeepStageAndVisualTextWithoutLoadingAnAsset() {
        val value = ToolRichDetails.project("generation", "generate_image", "{}",
            """{"job":{"id":"job1","state":"pending","progress":0.4,"artifacts":[{"id":"out1","mime":"image/png"}]}}""")
        assertTrue(value.fields.any { it.label == "生成阶段" && it.value == "pending" })
        assertEquals("已登记产物", value.entries.single().group)
    }
    @Test fun boundedLongDocumentsAndListsReportTruncation() {
        val long = ToolRichDetails.scrub("a".repeat(20_000))
        assertTrue(long.length < 17_000); assertTrue(long.endsWith("内容已截断"))
        val result = "{\"entries\":[" + (1..40).joinToString { "{\"name\":\"file$it\"}" } + "]}"
        val value = ToolRichDetails.project("files", "list_directory", "{}", result)
        assertEquals(32, value.entries.size)
        assertTrue(value.fields.any { it.value.contains("共40项") })
    }
    @Test fun productionPlatformEnvelopesKeepSpecializedBodies() {
        val apps = ToolRichDetails.project("apps", "search_apps", "{}",
            """{"ok":true,"data":{"items":[{"label":"Editor","package":"test.editor","enabled":true}]}}""")
        assertEquals("匹配应用", apps.entries.single().group)
        assertTrue(apps.entries.single().fields.any { it.value == "test.editor" })
        val file = ToolRichDetails.project("files", "read_text_file", "{}",
            """{"ok":true,"data":{"text":"native text body","size_bytes":16,"mime_type":"text/plain"}}""")
        assertTrue(file.documents.any { it.title == "文件内容" && it.text == "native text body" })
        val memory = ToolRichDetails.project("memory", "assistant_memory_search", "{}",
            """{"ok":true,"revision":"rev","hits":[{"offset":12,"content":"native memory hit"}]}""")
        assertEquals("记忆命中", memory.entries.single().group)
        assertTrue(memory.entries.single().documents.any { it.text == "native memory hit" })
    }
    @Test fun cookieOperationsNeverExposeUnexpectedBodyAndGuiInputStaysHidden() {
        val cookie = ToolRichDetails.project("browser", "browser_read", """{"action":"get_cookies"}""",
            """{"text":"UNEXPECTED_COOKIE_BODY","content":"UNEXPECTED_COOKIE_CONTENT"}""")
        assertTrue(cookie.documents.isEmpty()); assertTrue(cookie.technical.isEmpty())
        val gui = ToolRichDetails.project("gui", "screen_action", """{"operation":"input","text":"PRIVATE_TYPED_TEXT"}""",
            """{"text":"PRIVATE_TYPED_TEXT"}""")
        assertFalse(text(gui).contains("PRIVATE_TYPED_TEXT"))
    }
    @Test fun sensitiveContainersRedactAllNestedStringsAndArrayValues() {
        val value = ToolRichDetails.project("generic", "new_tool",
            """{"auth":{"items":["PRIVATE_ONE",{"value":"PRIVATE_TWO"}]}}""",
            """{"text":"PRIVATE_ONE PRIVATE_TWO"}""")
        assertFalse(text(value).contains("PRIVATE_ONE")); assertFalse(text(value).contains("PRIVATE_TWO"))
    }
    @Test fun cookieWhitelistRejectsPrimitiveRowsAndUnlistedTitles() {
        val value = ToolRichDetails.project("browser", "browser_read", """{"action":"get_cookies"}""",
            """{"cookies":["COOKIE_VALUE",{"domain":"example.com","title":"PRIVATE_TITLE"}],"results":[{"title":"PRIVATE_RESULT"}],"title":"PRIVATE_TOP_TITLE"}""")
        assertEquals(1, value.entries.size)
        assertEquals("Cookie属性 2", value.entries.single().title)
        assertFalse(text(value).contains("PRIVATE_TOP_TITLE"))
        assertTrue(value.entries.single().documents.isEmpty())
        assertTrue(value.documents.isEmpty()); assertTrue(value.technical.isEmpty())
    }
    @Test fun sensitiveScalarsAreHiddenAcrossReturnedBodies() {
        val value = ToolRichDetails.project("generic", "new_tool",
            """{"cookies":"CUSTOM_COOKIE_SECRET","verification_code":123456}""",
            """{"content":"CUSTOM_COOKIE_SECRET code value 123456"}""")
        assertFalse(text(value).contains("CUSTOM_COOKIE_SECRET")); assertFalse(text(value).contains("123456"))
    }
    @Test fun returnedMetadataCannotSuppressOperationReceipts() {
        val value = ToolRichDetails.project("files", "list_files", "{}",
            """{"data":{"items":[{"name":"a"}],"message":"incomplete listing"}}""")
        assertTrue(value.documents.any { it.title == "操作回执" && it.text == "incomplete listing" })
    }
    @Test fun outerEnvelopeReceiptSurvivesNonEmptyItems() {
        val value = ToolRichDetails.project("files", "list_files", "{}",
            """{"message":"outer incomplete listing","data":{"items":[{"name":"a"}]}}""")
        assertTrue(value.documents.any { it.title == "操作回执" && it.text == "outer incomplete listing" })
    }
    @Test fun encodedScreenshotNeverFallsBackToRawText() {
        val value = ToolRichDetails.project("gui", "screen", """{"operation":"screenshot"}""",
            """{"data_base64":"PRIVATE_IMAGE_ENCODING"}""")
        assertFalse(text(value).contains("PRIVATE_IMAGE_ENCODING"))
        assertTrue(value.technical.isEmpty())
    }
    @Test fun documentDebugStringNeverContainsContent() {
        assertFalse(ToolDisclosureUi("正文", "PRIVATE_CONTENT").toString().contains("PRIVATE_CONTENT"))
    }
}
