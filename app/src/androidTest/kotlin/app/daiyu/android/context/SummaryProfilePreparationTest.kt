package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

@Suppress("DEPRECATION")
class SummaryProfilePreparationTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    private lateinit var prefs: SharedPreferences
    private lateinit var store: SummaryProfileStore
    private lateinit var saved: ModelSettings
    private var modelWrites = 0
    private var connections = 0
    private val binding = SummaryModelBinding("summary-provider", "opaque-summary-id")

    override fun setUp() {
        super.setUp()
        val namespace = "p09-composition-${UUID.randomUUID()}"
        val base = testContext(instrumentation.targetContext, namespace)
        val files = File(base.cacheDir, namespace).apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        service = ContextService(context, db)
        prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        store = SummaryProfileStore(prefs, { saved }, { model, _, _ -> saved = model; modelWrites++ }, namespace)
        modelWrites = 0; connections = 0
    }

    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively()
        prefs.edit().clear().commit()
        super.tearDown()
    }

    private fun profile(protocol: ProviderProtocol, body: String) = SummaryProfileSettings(binding,
        ModelSettings("https://summary.invalid", "wire-summary", "synthetic-only", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(customBody = body)),
        SummaryProfileBudgets(32000, 27000, 2000, 1000, 2000, 30000))

    private fun coordinator(wire: Wire = Wire()): ContextPreparationCoordinator {
        val settings = ProductionCompressionSettings(true, 10000, 1000, summaryModel = binding)
        return ContextPreparationCoordinator(service, { settings.coordinatorSettings() },
            ProductionCompressionPolicyResolver,
            ProviderSummaryModelResolver({ store.configuration(it) }, { connections++; wire },
                object : SummaryDeadlineScheduler {
                    override fun schedule(delayMillis: Long, expire: () -> Unit) = AutoCloseable { }
                }))
    }

    fun testSummaryCustomBodyRejectedBeforeSaveAndDuringLoadAndFreeze() {
        val owner = db.create("default").id
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            for (body in listOf("{\"model\":\"override\"}", "{\"temperature\":99}",
                "{\"top_p\":2}", "{\"unknown_option\":true}")) {
                val bad = profile(protocol, body)
                val writesBefore = modelWrites
                val metadataBefore = prefs.getString(SummaryProfileStore.KEY, null)
                try { store.save(bad); fail("must reject before persistence") }
                catch (_: IllegalArgumentException) { }
                assertEquals(writesBefore, modelWrites)
                assertEquals(metadataBefore, prefs.getString(SummaryProfileStore.KEY, null))

                // Emulate the old writer: identity is consistent, but summary-only options are invalid.
                store.save(profile(protocol, "{}"))
                installLegacyProvider(bad.provider)
                for (read in listOf<() -> Unit>({ store.load(binding) },
                    { store.configuration(binding) }, { coordinator().freeze(owner, emptyList()) })) {
                    try { read(); fail("invalid summary profile must fail at freeze, not later at request") }
                    catch (failure: SummaryProfileUnavailableException) {
                        assertEquals(SummaryProfileUnavailableReason.INVALID_CONFIGURATION, failure.reason)
                    }
                }
                assertEquals(0, connections)
            }
        }
    }

    fun testSavedProfileFreezeThenPrepareUsesFrozenValidatedOptionsAndPublishesSummary() {
        store.save(profile(ProviderProtocol.RESPONSES,
            "{\"temperature\":0.4,\"top_p\":0.8,\"service_tier\":\"auto\"}"))
        val owner = db.create("default").id
        repeat(6) { n ->
            val run = RunId("run-$n")
            val user = Message(MessageId("u-$n"), owner, run, "user",
                listOf(MessageBlock.Text("u", "事实$n ".repeat(100))), RunStatus.COMPLETED)
            val assistant = Message(MessageId("a-$n"), owner, run, "assistant",
                listOf(MessageBlock.Text("a", "已确认$n ".repeat(100))), RunStatus.RUNNING)
            db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        val original = service.displayHistory(owner)
        val wire = Wire()
        val coordinator = coordinator(wire)
        val frozen = coordinator.freeze(owner, emptyList())
        assertEquals(0, connections)
        // Editing after freeze must not change the detached provider configuration.
        store.save(profile(ProviderProtocol.RESPONSES, "{\"temperature\":1.2}"))
        val prepared = coordinator.prepare(frozen)
        assertEquals(1, connections)
        assertNotNull(prepared.archiveId)
        assertTrue(prepared.history.messages.any { message -> message.blocks.any {
            it is MessageBlock.Text && it.text.contains("最终事实摘要")
        } })
        assertEquals(original, service.displayHistory(owner))
        val request = JSONObject(wire.sent.toString("UTF-8"))
        assertEquals("wire-summary", request.getString("model"))
        assertEquals(0.4, request.getDouble("temperature"), 0.0)
        assertEquals(0.8, request.getDouble("top_p"), 0.0)
        assertEquals(2000, request.getInt("max_output_tokens"))
        assertTrue(!request.has("tools") || request.getJSONArray("tools").length() == 0)
    }

    private fun installLegacyProvider(provider: ModelSettings) {
        saved = provider
        // Storage identity fixture only; no summary validation rules are duplicated here.
        val canonical = JSONObject().put("endpoint", provider.endpoint).put("model", provider.model)
            .put("protocol", provider.protocol.wireName).put("responsesPath", provider.responsesPath)
            .put("options", provider.requestOptions.encode())
            .put("battery", provider.batteryEnabled).put("deviceStatus", provider.deviceStatusEnabled).toString()
        val identity = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val metadata = JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!).put("identity", identity)
        check(prefs.edit().putString(SummaryProfileStore.KEY, metadata.toString()).commit())
    }

    private class Wire : HttpsURLConnection(URL("https://summary.invalid")) {
        val sent = ByteArrayOutputStream()
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream() = ("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
            "{\"type\":\"message\",\"id\":\"final\",\"role\":\"assistant\",\"content\":[" +
            "{\"type\":\"output_text\",\"text\":\"最终事实摘要\"}]}]}}\n\n").byteInputStream()
    }
}
