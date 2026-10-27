package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ToolRichDetailsTest {
    private fun text(value: ToolRichDetailsUi): String = (value.documents + value.technical + listOfNotNull(value.prose) + value.entries.flatMap { it.documents })
        .joinToString("\n") { it.text } + value.entries.joinToString { it.title } + value.fields.joinToString { it.value } + value.entries.flatMap { it.fields }.joinToString { it.value }

    @Test fun inputDocumentsNeverSuppressPlainTextReceipts() {
        listOf("memory", "files", "browser", "apps", "personal_search", "terminal", "speech", "image_read", "generation").forEach { category ->
            val value = ToolRichDetails.project(category, "read_result", """{"query":"needle","path":"/tmp/report","url":"https://example.com","command":"pwd","text":"sample"}""", "unique receipt body")
            assertTrue(category, value.documents.any { it.text.contains("unique receipt body") })
        }
    }
    @Test fun compactDeviceProseKeepsOnlyReturnedValuesAndTechnicalReceipt() {
        val time = ToolRichDetails.project("device_read", "device_time", "{}",
            """{"ok":true,"data":{"local_iso":"2026-04-18T21:22:00+08:00","timezone":"Asia/Shanghai","epoch_ms":1}}""")
        assertEquals("2026-04-18 21:22:00\nAsia/Shanghai · UTC+08:00", time.prose!!.text)
        assertFalse(time.prose!!.text.contains("epoch_ms"))
        assertTrue(time.technical.any { it.title == "原始回执" && it.text.contains("epoch_ms") })
        val location = ToolRichDetails.project("device_read", "last_known_location", "{}",
            """{"ok":true,"data":{"latitude":31.2,"longitude":121.4,"accuracy_m":20,"age_ms":6000}}""")
        assertEquals("31.2, 121.4\n精度 20m · 6 秒前", location.prose!!.text)
        val absent = ToolRichDetails.project("device_read", "last_known_location", "{}",
            """{"ok":false,"status":"no_cached_location","error":"no_cached_location"}""")
        assertEquals("没有可用的最近位置缓存", absent.prose!!.text)
        assertEquals("等待执行回执", ToolRichDetails.project("device_read", "device_time", "{}", null).prose!!.text)
        for (payload in listOf("""{"ok":true,"data":{"latitude":"unknown","longitude":121}}""",
            """{"ok":true,"data":{"latitude":999,"longitude":121}}""")) {
            assertFalse(ToolRichDetails.project("device_read", "last_known_location", "{}", payload).prose!!.text.contains("最近位置"))
        }
        val private = ToolRichDetails.project("device_read", "last_known_location", "{}",
            """{"privacy_redacted":true,"status":"private_result_withheld","data":{"latitude":31,"longitude":121}}""")
        assertEquals("结果未获展示授权", private.prose!!.text)
    }

    @Test fun systemActionProseNeverPromotesRequestedValuesToConfirmation() {
        val pending = ToolRichDetails.project("system_action", "platform_set_wifi", """{"enabled":true}""",
            """{"accepted":true}""")
        assertEquals("请求已受理，结果未确认", pending.prose!!.text)
        assertFalse(pending.prose!!.text.contains("true"))
        assertTrue(pending.technical.any { it.title == "调用参数" && it.text.contains("enabled") })
        val receipt = ToolRichDetails.project("system_action", "platform_set_wifi", """{"api_key":"PRIVATE_KEY"}""",
            """{"ok":true,"message":"PRIVATE_KEY request recorded"}""")
        assertEquals("[已隐藏] request recorded", receipt.prose!!.text)
        val denied = ToolRichDetails.project("system_action", "platform_set_wifi", "{}",
            """{"ok":false,"status":"permission_denied"}""")
        assertEquals("缺少本次操作所需权限", denied.prose!!.text)
    }

    @Test fun terminalMetadataRemainsTechnicalWhileCommandAndLogsKeepCodeStyle() {
        for (category in listOf("terminal", "background_job")) {
            val value = ToolRichDetails.project(category, "terminal_pty_step", """{"command":"printf hello","cwd":"/tmp"}""",
                """{"opened":true,"command_completion":"unknown","stdout":""}""")
            assertEquals(setOf("命令", "工作目录", "标准输出"), value.documents.map { it.title }.toSet())
            assertTrue(value.documents.all { it.code })
            assertEquals("已收到运行回执，未返回输出", value.prose!!.text)
            assertTrue(value.technical.any { it.text.contains("command_completion") })
            val logged = ToolRichDetails.project(category, "terminal_pty_read", "{}", """{"stdout":"hello","exit_code":0}""")
            assertEquals("hello", logged.documents.single { it.title == "标准输出" && it.code }.text)
            assertFalse(logged.documents.any { it.title == "运行回执" })
            val plain = ToolRichDetails.project(category, "terminal_pty_read", "{}", "actual receipt")
            assertEquals("actual receipt", plain.documents.single().text)
            assertTrue(plain.documents.single().code)
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
    @Test fun formattedTimeUsesReturnedOffsetAndHandlesFractionalUtcAndInvalidValues() {
        for ((iso, expected) in listOf(
            "2026-04-18T21:22:00.123-03:30" to "2026-04-18 21:22:00\nUTC-03:30",
            "2026-04-18T21:22:00Z" to "2026-04-18 21:22:00\nUTC",
            "invalid time" to "invalid time")) {
            val value = ToolRichDetails.project("device_read", "device_time", "{}",
                org.json.JSONObject().put("data", org.json.JSONObject().put("local_iso", iso)).toString())
            assertEquals(expected, value.prose!!.text)
        }
    }
    @Test fun systemActionBodyUsesReturnedValuesWithoutPromotingTheRequest() {
        val value = ToolRichDetails.project("system_action", "platform_set_wifi", """{"enabled":true}""",
            """{"enabled":false,"read_back":true}""")
        assertEquals("返回启用值：否\n回读确认：是", value.prose!!.text)
        val volume = ToolRichDetails.project("system_action", "platform_set_volume", """{"index":90}""",
            """{"stream":"music","index":3,"read_back":true}""")
        assertTrue(volume.prose!!.text.contains("返回音量：3"))
        assertFalse(volume.prose!!.text.contains("90"))
    }
    @Test fun nestedDeviceSnapshotsAndAudioChannelsHaveReadableBodies() {
        val status = ToolRichDetails.project("device_read", "device_status", "{}",
            """{"ok":true,"battery":{"ok":true,"percent":78,"charging":true},"android":{"api_level":36,"release":"16"},"memory":{"ok":true,"total_bytes":8000,"available_bytes":4000},"storage":{"ok":false,"error":"no_sample"}}""")
        assertTrue(status.prose!!.text.contains("电量百分比：78"))
        assertTrue(status.prose!!.text.contains("内存可用字节：4000"))
        assertTrue(status.prose!!.text.contains("存储：本次采样不可用"))
        val audio = ToolRichDetails.project("device_read", "device_audio", "{}",
            """{"ok":true,"data":{"streams":{"music":{"level":3,"minimum":0,"maximum":15}}}}""")
        assertEquals("音频通道", audio.entries.single().group)
        assertTrue(audio.entries.single().fields.any { it.label == "当前音量" && it.value == "3" })
    }
    @Test fun knownStructuredReceiptsStayTechnicalAndUnknownToolsKeepFallback() {
        for (category in ToolCardPresentations.categories - setOf("generic", "user_question")) {
            val value = ToolRichDetails.project(category, "fixture", "{}", """{"future_shape":{"value":42}}""")
            assertFalse(category, value.documents.any { it.title == "已记录返回" })
            assertTrue(category, value.technical.any { it.text.contains("future_shape") })
        }
        val unknown = ToolRichDetails.project("future_category", "future_tool", "{}", """{"new_value":42}""")
        assertTrue(unknown.documents.any { it.text.contains("new_value") })
    }
    @Test fun hiddenMetadataStillHasAnExplicitReadableReceipt() {
        for ((category, payload, expected) in listOf(Triple("memory", """{"revision":"r1"}""", "已收到回执"),
            Triple("files", """{"data":{"scope":"complete"}}""", "读取范围：complete"))) {
            val value = ToolRichDetails.project(category, "fixture", "{}", payload)
            assertEquals(expected, value.prose!!.text)
            assertFalse(value.documents.any { it.title == "已记录返回" })
            assertTrue(value.technical.any { it.title == "原始回执" })
        }
    }
    @Test fun failedOuterAndInnerReceiptsRestrictCategoryBodiesButKeepDiagnostics() {
        for (category in listOf("apps", "browser", "files", "personal_search", "device_read", "system_action")) {
            for (state in listOf("\"ok\":false" to "本次调用未完成",
                "\"status\":\"permission_denied\"" to "缺少本次操作所需权限")) {
                for (nested in listOf(false, true)) {
                    val body = "{" + state.first + ",\"text\":\"FAILED_BODY\"}"
                    val payload = if (nested) "{\"data\":" + body + "}" else body
                    val value = ToolRichDetails.project(category, "fixture", "{}", payload)
                    assertTrue(category, value.bodyRestricted)
                    assertEquals(state.second, value.prose!!.text)
                    assertTrue(value.technical.any { it.text.contains("FAILED_BODY") })
                }
            }
        }
    }
    @Test fun privacyWithheldNeverKeepsUnauthorizedDataInAnyDisclosure() {
        for (category in listOf("apps", "browser", "files", "personal_search", "device_read", "system_action")) {
            for (state in listOf("\"privacy_redacted\":true", "\"status\":\"private_result_withheld\"",
                "\"status\":\"private_result_consent_required\"", "\"privacy_redacted\":true,\"status\":\"permission_denied\"")) {
                for (nested in listOf(false, true)) {
                    val body = "{" + state + ",\"text\":\"PRIVATE_BODY\",\"items\":[{\"content\":\"PRIVATE_ITEM\"}]}"
                    val payload = if (nested) "{\"data\":" + body + "}" else body
                    val value = ToolRichDetails.project(category, "fixture", "{}", payload)
                    assertTrue(category, value.bodyRestricted)
                    assertEquals("结果未获展示授权", value.prose!!.text)
                    assertTrue(value.fields.isEmpty()); assertTrue(value.entries.isEmpty()); assertTrue(value.documents.isEmpty())
                    assertFalse(text(value).contains("PRIVATE_BODY")); assertFalse(text(value).contains("PRIVATE_ITEM"))
                }
            }
            val mixed = ToolRichDetails.project(category, "fixture", "{}",
                """{"ok":false,"status":"permission_denied","data":{"privacy_redacted":true,"text":"PRIVATE_BODY"}}""")
            assertEquals("结果未获展示授权", mixed.prose!!.text)
            assertFalse(text(mixed).contains("PRIVATE_BODY"))
        }
    }
    @Test fun aliasesAndEntryIdentityAreNotDuplicatedButOutputChannelsStaySeparate() {
        val file = ToolRichDetails.project("files", "read_text_file", "{}", """{"text":"same body","content":"same body"}""")
        assertEquals(1, file.documents.count { it.text == "same body" })
        val terminal = ToolRichDetails.project("terminal", "terminal_pty_read", "{}", """{"stdout":"same body","output":"same body","stderr":"same body"}""")
        assertEquals(setOf("标准输出", "标准错误"), terminal.documents.map { it.title }.toSet())
        val listing = ToolRichDetails.project("files", "list_directory", "{}", """{"entries":[{"name":"example.txt","text":"one body","content":"one body"}]}""")
        assertEquals("example.txt", listing.entries.single().title)
        assertFalse(listing.entries.single().fields.any { it.value == "example.txt" })
        assertEquals(1, listing.entries.single().documents.size)
    }
    @Test fun documentDebugStringNeverContainsContent() {
        assertFalse(ToolDisclosureUi("正文", "PRIVATE_CONTENT").toString().contains("PRIVATE_CONTENT"))
    }
    @Test fun productionZonedTimeKeepsHtmlTwoLineLayoutForLiveAndHistoricalReceipts() {
        val value = ToolRichDetails.project("device_read", "device_time", "{}",
            """{"ok":true,"data":{"local_iso":"2026-10-21T15:36:32.861853+08:00[Asia/Shanghai]","timezone":"Asia/Shanghai","utc_offset_seconds":28800}}""")
        assertEquals("2026-10-21 15:36:32\nAsia/Shanghai · UTC+08:00", value.prose!!.text)
        assertEquals("{}", value.technical.single { it.title == "调用参数" }.text)
        assertTrue(value.technical.single { it.title == "原始回执" }.text.contains("861853"))
        assertEquals(2, value.prose!!.text.lines().size)
    }

    @Test fun allOrdinaryCategoriesPreserveNonEmptyArgumentsAndExplicitEmptyObjects() {
        val arguments = """{"path":"/workspace/report.txt","limit":4,"enabled":false,"options":{"count":2},"items":[1,true,null]}"""
        for (category in ToolCardPresentations.categories - "user_question") {
            val rich = ToolRichDetails.project(category, "fixture", arguments, """{"ok":true}""")
            val actual = org.json.JSONObject(rich.technical.single { it.title == "调用参数" }.text)
            assertEquals(category, "/workspace/report.txt", actual.getString("path"))
            assertEquals(4, actual.getInt("limit"))
            assertFalse(actual.getBoolean("enabled"))
            assertEquals(2, actual.getJSONObject("options").getInt("count"))
            assertTrue(actual.getJSONArray("items").isNull(2))
            assertEquals("{}", ToolRichDetails.project(category, "fixture", "{}", null)
                .technical.single { it.title == "调用参数" }.text)
        }
        val memory = ToolRichDetails.project("memory", "assistant_memory_read", "{}",
            """{"text":"same body","content":"same body"}""")
        assertEquals(1, memory.documents.count { it.text == "same body" })
    }

    @Test fun technicalArgumentsKeepNestedArrayTypesWhileHidingSecrets() {
        val args = """{"enabled":false,"count":0,"items":[null,3,true,["text",{"token":"CUSTOM_SECRET","safe":"ok"}]]}"""
        val rich = ToolRichDetails.project("generic", "future_tool", args, null)
        val document = rich.technical.single { it.title == "调用参数" }
        val parsed = org.json.JSONObject(document.text)
        assertFalse(parsed.getBoolean("enabled"))
        assertEquals(0, parsed.getInt("count"))
        val items = parsed.getJSONArray("items")
        assertTrue(items.isNull(0))
        assertEquals(3, items.getInt(1))
        assertTrue(items.getBoolean(2))
        assertEquals("text", items.getJSONArray(3).getString(0))
        assertEquals("ok", items.getJSONArray(3).getJSONObject(1).getString("safe"))
        assertFalse(document.text.contains("CUSTOM_SECRET"))
    }

    @Test fun emptyRowsAndDuplicateReceiptMessagesDoNotCreateExtraBodyGroups() {
        val empty = ToolRichDetails.project("files", "list_files", "{}", """{"entries":[{}]}""")
        assertTrue(empty.entries.isEmpty())
        assertNotNull(empty.prose)
        val receipt = ToolRichDetails.project("files", "read_text_file", "{}", """{"text":"same body","message":"same body"}""")
        assertEquals(1, receipt.documents.count { it.text == "same body" })
        val cookie = ToolRichDetails.project("browser", "browser_write", """{"action":"get_cookies"}""", """{"cookies":[]}""")
        assertTrue(cookie.technical.isEmpty())
        assertEquals("未返回可展示的 Cookie 属性", cookie.prose!!.text)
        val failed = ToolRichDetails.project("browser", "browser_write", """{"action":"get_cookies"}""", """{"ok":false}""")
        assertEquals("本次调用未完成", failed.prose!!.text)
    }

    @Test fun ordinaryCommandToolsKeepCommandAndReturnedOutputInExistingScrubbedTerminalBody() {
        for (name in listOf("terminal_exec", "terminal_read")) {
            val args = if (name == "terminal_exec")
                """{"command":"printf token=PRIVATE_TERMINAL_SECRET","environment":"android"}"""
            else """{"job_id":"00000000-0000-0000-0000-000000000001","offset":0,"limit":16384}"""
            val card = ToolCardPresentations.present(name, args,
                """{"ok":true,"execution":"executed","state":"exited","stdout":"hello token=PRIVATE_TERMINAL_SECRET","stderr_merged":true,"exit_code":0,"output_complete":true,"log_truncated":false,"offset":0,"next_offset":9,"has_more":false,"total_bytes":9}""")
            val rich = card.details.rich
            assertEquals("terminal", card.category)
            assertEquals(name == "terminal_exec", rich.documents.any { it.title == "命令" && it.code && it.text.startsWith("printf") })
            assertTrue(rich.documents.any { it.title == "标准输出" && it.code && it.text.startsWith("hello") })
            assertTrue(rich.documents.none { it.title == "标准错误" })
            assertTrue(card.details.fields.contains(ToolFieldUi("退出码", "0")))
            assertFalse(text(rich).contains("PRIVATE_TERMINAL_SECRET"))
            assertFalse(rich.documents.any { it.title == "已记录返回" })
        }
    }

}
