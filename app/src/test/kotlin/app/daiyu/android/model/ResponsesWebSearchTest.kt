package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ResponsesWebSearchTest {
    private val options = ProviderRequestOptions(openAiWebSearchEnabled = true)
    private val settings = ModelSettings("https://api.openai.com/v1", "search-fixture", "synthetic-key", false, requestOptions = options)
    private fun reject(work: () -> Unit) { try { work(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    private fun search(status: String = "completed") = JSONObject().put("type", "web_search_call").put("id", "ws")
        .put("status", status).put("action", JSONObject().put("type", "search").put("queries", JSONArray().put("fixture")))
    private fun citation(url: String = "https://example.org/source?q=a&b=c") = JSONObject().put("type", "url_citation")
        .put("start_index", 0).put("end_index", 2).put("url", url).put("title", "[untrusted](javascript:alert(1))")
    private fun message(annotation: JSONObject = citation()) = JSONObject().put("type", "message").put("id", "msg")
        .put("role", "assistant").put("status", "completed").put("content", JSONArray().put(JSONObject()
            .put("type", "output_text").put("text", "😀 facts").put("annotations", JSONArray().put(annotation))))
    private fun wire(vararg output: JSONObject) = "data: " + JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", "completed").put("output", JSONArray(output.toList()))) + "\n\n"
    private fun user() = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("input", "fixture")), RunStatus.COMPLETED)
    private class Connection(body: String) : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        private val input = body.byteInputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = sent
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    @Test fun optionCodecIsStrictBackwardCompatibleAndClosedByDefault() {
        assertFalse(ProviderRequestOptions.decode(null).openAiWebSearchEnabled)
        assertFalse(ProviderRequestOptions.decode("""{"custom_body":"","speed_mode":null,"codex":false}""").openAiWebSearchEnabled)
        assertFalse(JSONObject(ProviderRequestOptions().encode()).has("openai_web_search_enabled"))
        assertEquals(options, ProviderRequestOptions.decode(options.encode()))
        for (value in listOf<Any>("true", 1, JSONObject.NULL, JSONArray(), JSONObject())) {
            val raw = JSONObject(ProviderRequestOptions().encode()).put("openai_web_search_enabled", value).toString()
            try { ProviderRequestOptions.decode(raw); fail("wrong type") } catch (_: IllegalStateException) {}
        }
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.RESPONSES }) {
            reject { options.validate(protocol) }
            reject { options.apply(JSONObject(), protocol) }
        }
        reject { options.copy(customBody = """{"tools":[]}""").validate(ProviderProtocol.RESPONSES) }
    }
    @Test fun hostedSearchAcceptsConfiguredHttpsResponsesGatewaysWithoutRewritingEndpoints() {
        settings.validate()
        for (endpoint in listOf("https://API.OPENAI.COM:443/v1/responses", "https://gateway.invalid/v1/responses",
            "https://gateway.invalid:8443/custom/responses")) {
            val configured = settings.copy(endpoint = endpoint)
            configured.validate()
            assertEquals(endpoint, configured.requestEndpoint())
            assertTrue(configured.requestSnapshot().asModelSettings().requestOptions.openAiWebSearchEnabled)
        }
        val customPath = settings.copy(endpoint = "https://gateway.invalid/api", responsesPath = "/openai/responses")
        customPath.validate()
        customPath.copy(model = "").validateConnection()
        assertEquals("https://gateway.invalid/api/openai/responses", customPath.requestEndpoint())
        for (endpoint in listOf("http://gateway.invalid/v1/responses", "https://user@gateway.invalid/v1/responses",
            "https://gateway.invalid:0/v1/responses", "https://gateway.invalid/v1/responses?tenant=x",
            "https://gateway.invalid/v1/responses#tail", "https://gateway.invalid/v1/chat/completions")) {
            reject { settings.copy(endpoint = endpoint).validate() }
        }
        reject { settings.copy(responsesPath = "//other.invalid/responses").validate() }
        settings.copy(endpoint = "https://gateway.invalid/v1/responses", requestOptions = ProviderRequestOptions()).validate()
        val snapshot = settings.requestSnapshot()
        assertTrue(snapshot.asModelSettings().requestOptions.openAiWebSearchEnabled)
        assertNotEquals(snapshot, settings.copy(requestOptions = ProviderRequestOptions()).requestSnapshot())
    }
    @Test fun bodyCombinesFixedHostedDeclarationWithUnchangedLocalFunctionSchemas() {
        val input = JSONArray(); val tool = ToolDefinition("fixture", "local", """{"type":"object"}""")
        assertFalse(ResponsesRequest.body("m", input, emptyList()).has("tools"))
        val body = ResponsesRequest.body("m", input, listOf(tool), true)
        val tools = body.getJSONArray("tools")
        assertEquals(2, tools.length()); assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("fixture", tools.getJSONObject(0).getString("name")); assertFalse(tools.getJSONObject(0).getBoolean("strict"))
        assertEquals(setOf("type"), tools.getJSONObject(1).keys().asSequence().toSet())
        assertEquals("web_search", tools.getJSONObject(1).getString("type"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertFalse(body.has("tool_choice")); assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
    }
    @Test fun hostedOutputCreatesVisibleEscapedSourcesAndRawReplayButNoLocalTool() {
        val output = listOf(search(), message()); val events = mutableListOf<ModelEvent>()
        val raw = ResponsesStream.read(wire(*output.toTypedArray()).reader().buffered(), { false }, events::add)
        assertTrue(events.filterIsInstance<ModelEvent.ToolReady>().isEmpty())
        val final = events.filterIsInstance<ModelEvent.TextFinal>().single()
        assertTrue(final.text.startsWith("\u2060😀 ")); assertTrue(final.text.contains(" [1](<https://example.org/source?q=a&b=c>)facts"))
        assertTrue(final.text.contains("](<https://example.org/source?q=a&b=c>)"))
        assertTrue(final.text.contains("\\[untrusted\\]"))
        assertEquals(output.map { it.toString() }, raw)
        val assistant = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Text("msg", final.text)), RunStatus.COMPLETED, protocolItems = raw)
        val replay = ResponsesRequest.history(listOf(assistant))
        assertEquals(raw, (0 until replay.length()).map { replay.getJSONObject(it).toString() })
    }
    @Test fun derivedSourcesRemainMarkdownLinksAfterUnclosedFencesAndNormalizeOnlyDisplayTargets() {
        fun links(blocks: List<app.daiyu.android.MarkdownBlock>): List<app.daiyu.android.MarkdownInline.Link> {
            fun inline(rows: List<app.daiyu.android.MarkdownInline>): List<app.daiyu.android.MarkdownInline.Link> = rows.flatMap {
                when (it) {
                    is app.daiyu.android.MarkdownInline.Link -> listOf(it)
                    is app.daiyu.android.MarkdownInline.Emphasis -> inline(it.content)
                    is app.daiyu.android.MarkdownInline.Strike -> inline(it.content)
                    else -> emptyList()
                }
            }
            return blocks.flatMap {
                when (it) {
                    is app.daiyu.android.MarkdownBlock.Paragraph -> inline(it.content)
                    is app.daiyu.android.MarkdownBlock.Heading -> inline(it.content)
                    is app.daiyu.android.MarkdownBlock.Items -> it.items.flatMap { item -> links(item.blocks) }
                    is app.daiyu.android.MarkdownBlock.Quote -> links(it.blocks)
                    else -> emptyList()
                }
            }
        }
        for (plain in listOf("```text\nfacts", "~~~text\nfacts", "````text\nfacts\n```", "```\nfacts\n```", "ordinary facts")) {
            val original = message(citation("HTTPS://example.org/a(b"))
            original.getJSONArray("content").getJSONObject(0).put("text", plain)
            val raw = original.toString()
            val display = ResponsesSearchProtocol.displayText(original, plain)
            val sources = links(app.daiyu.android.MarkdownParser.parse(display))
            assertEquals("plain=$plain", 2, sources.size)
            assertTrue(sources.all { it.destination == "https://example.org/a%28b" })
            assertEquals(raw, original.toString())
        }
        reject { ResponsesSearchProtocol.validateItem(message(citation("https://example.org:0/source"))) }
        val snapshot = settings.requestSnapshot()
        val auxiliary = snapshot.withoutNativeSearch()
        assertFalse(auxiliary.requestOptions.openAiWebSearchEnabled)
        assertTrue(snapshot.requestOptions.openAiWebSearchEnabled)
        assertEquals(snapshot.customHeaders, auxiliary.customHeaders)
        assertEquals(snapshot.imageInputSupported, auxiliary.imageInputSupported)
    }
    @Test fun citationsAreInlineAtPartLocalUnicodeBoundariesWithoutSplittingSurrogates() {
        val first = message(citation().put("end_index", 1))
        val content = first.getJSONArray("content")
        content.put(JSONObject().put("type", "output_text").put("text", "second claim")
            .put("annotations", JSONArray().put(citation("https://example.org/second").put("start_index", 0).put("end_index", 6))))
        val original = first.toString()
        val display = ResponsesSearchProtocol.displayText(first, "😀 factssecond claim")
        assertTrue(display.contains("😀 [1](<https://example.org/source?q=a&b=c>) facts"))
        assertTrue(display.contains("second [2](<https://example.org/second>) claim"))
        assertEquals(original, first.toString())
        val paragraph = app.daiyu.android.MarkdownParser.parse(display).first() as app.daiyu.android.MarkdownBlock.Paragraph
        assertEquals(listOf("https://example.org/source?q=a&b=c", "https://example.org/second"),
            paragraph.content.filterIsInstance<app.daiyu.android.MarkdownInline.Link>().map { it.destination })
        for (text in listOf("`quoted code`", "    indented", "[label](https://example.org/x)", "```text\ncode", "| cell |")) {
            val item = message(citation().put("end_index", 2))
            item.getJSONArray("content").getJSONObject(0).put("text", text)
            val blocks = app.daiyu.android.MarkdownParser.parse(ResponsesSearchProtocol.displayText(item, text))
            val body = blocks.first() as app.daiyu.android.MarkdownBlock.Paragraph
            assertEquals(text, 1, body.content.filterIsInstance<app.daiyu.android.MarkdownInline.Link>().size)
        }
    }
    @Test fun failedHostedSearchIsPreservedNotTurnedIntoSuccessOrLocalDispatch() {
        val events = mutableListOf<ModelEvent>()
        val raw = ResponsesStream.read(wire(search("failed"), message()).reader().buffered(), { false }, events::add)
        assertEquals("failed", JSONObject(raw.first()).getString("status"))
        assertTrue(events.none { it is ModelEvent.ToolReady })
        for (action in listOf(JSONObject().put("type", "search"), JSONObject().put("type", "open_page").put("url", JSONObject.NULL),
            JSONObject().put("type", "find_in_page").put("pattern", "text").put("url", "https://example.org/source"))) {
            ResponsesSearchProtocol.validateItem(search().put("action", action))
        }
        reject { ResponsesSearchProtocol.validateItem(search().put("name", "shell")) }
        reject { ResponsesSearchProtocol.validateItem(search().put("status", "searching")) }
    }
    @Test fun optionalSearchMetadataMayBeNullAndIncompleteSearchRemainsRawProtocol() {
        val action = JSONObject().put("type", "search").put("query", JSONObject.NULL)
            .put("queries", JSONObject.NULL).put("sources", JSONObject.NULL)
        val item = search("incomplete").put("action", action)
        val events = mutableListOf<ModelEvent>()
        val raw = ResponsesStream.read(wire(item, message()).reader().buffered(), { false }, events::add)
        assertEquals("incomplete", JSONObject(raw.first()).getString("status"))
        assertEquals(action.toString(), JSONObject(raw.first()).getJSONObject("action").toString())
        assertTrue(events.none { it is ModelEvent.ToolReady })
        reject { ResponsesSearchProtocol.validateItem(search().put("action", JSONObject().put("type", "find_in_page").put("pattern", "text"))) }
        reject { ResponsesSearchProtocol.validateItem(search().put("action", JSONObject().put("type", "find_in_page").put("pattern", "text").put("url", JSONObject.NULL))) }
        for (field in listOf("query", "queries", "sources")) {
            reject { ResponsesSearchProtocol.validateItem(search().put("action", JSONObject().put("type", "search").put(field, 1))) }
        }
    }
    @Test fun pollutedCitationsAreRejectedBeforeAnyTerminalTextOrUsage() {
        val cases = listOf(citation("javascript:alert(1)"), citation("file:///etc/passwd"), citation("https://u:p@example.org"),
            citation().put("start_index", -1), citation().put("end_index", 9999), citation().put("end_index", "2"),
            citation().put("end_index", 2.5), citation().put("start_index", 3).put("end_index", 2),
            citation().put("extra", "ignored?"), citation().put("type", "file_citation"))
        for (bad in cases) {
            val events = mutableListOf<ModelEvent>()
            try { ResponsesStream.read(wire(search(), message(bad)).reader().buffered(), { false }, events::add); fail("pollution") }
            catch (failure: ModelRequestException) { assertEquals(ModelFailure.PROTOCOL, failure.failure) }
            assertTrue(events.isEmpty())
            reject { ResponsesSearchProtocol.validateTextPart(message(bad).getJSONArray("content").getJSONObject(0)) }
        }
    }
    @Test fun actualMainRequestUsesFrozenSwitchButSummaryClearsItWithoutChangingMainSnapshot() {
        val connection = Connection(wire(message()))
        var count = 0
        ResponsesModel(settings) { count++; connection }.open(listOf(user()), emptyList<ToolDefinition>()).next(emptyList()) {}
        assertEquals(1, count); assertEquals("web_search", connection.body().getJSONArray("tools").getJSONObject(0).getString("type"))
        assertFalse(connection.instanceFollowRedirects); assertTrue(connection.closed)
        val snapshot = settings.requestSnapshot()
        val summary = Connection(wire(message()))
        SummaryProviderFactory.create(snapshot, 64, "Summarize only") { summary }
            .open(listOf(user()), emptyList<ToolDefinition>()).next(emptyList()) {}
        assertFalse(summary.body().has("tools"))
        assertTrue(snapshot.requestOptions.openAiWebSearchEnabled)
        val gateway = settings.copy(endpoint = "https://gateway.invalid:8443/api", responsesPath = "/responses")
        val gatewayConnection = Connection(wire(search(), message()))
        val targets = mutableListOf<URL>()
        ResponsesModel(gateway) { target -> targets += target; gatewayConnection }
            .open(listOf(user()), emptyList<ToolDefinition>()).next(emptyList()) {}
        assertEquals(listOf("https://gateway.invalid:8443/api/responses"), targets.map { it.toString() })
        assertEquals("web_search", gatewayConnection.body().getJSONArray("tools").getJSONObject(0).getString("type"))
        assertFalse(gatewayConnection.instanceFollowRedirects)
        assertTrue(gatewayConnection.closed)
    }
}
