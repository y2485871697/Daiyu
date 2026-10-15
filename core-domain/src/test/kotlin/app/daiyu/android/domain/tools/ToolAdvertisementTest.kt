package app.daiyu.android.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAdvertisementTest {
    @Test
    fun advertisementUsesDefinitionSchemaAndRetainsMetadata() {
        val schema = ToolSchema(listOf(
            ArgumentField(
                name = "mode",
                type = ArgumentType.Text(maxLength = 12, minimumLength = 2, choices = setOf("slow", "fast")),
                required = true
            ),
            ArgumentField(
                name = "note",
                type = ArgumentType.Text(maxLength = 80, minimumLength = 1),
                required = false
            )
        ))
        val definition = ToolDefinition(
            name = "set_mode",
            version = 3,
            description = "Set the requested mode",
            capability = "device.mode.write",
            effect = ToolEffect.WRITE,
            schema = schema
        )

        val advertisement = ToolAdvertisement.from(definition)

        assertEquals(definition.name, advertisement.name)
        assertEquals(definition.description, advertisement.description)
        assertEquals(definition.capability, advertisement.capability)
        assertEquals(definition.effect, advertisement.effect)
        assertEquals(definition.schema.jsonSchema(), advertisement.parameters)
        assertTrue(advertisement.parameters.contains("\"required\":[\"mode\"]"))
        assertTrue(advertisement.parameters.contains("\"minLength\":2"))
        assertTrue(advertisement.parameters.contains("\"maxLength\":12"))
        assertTrue(advertisement.parameters.contains("\"enum\":[\"fast\",\"slow\"]"))
        assertFalse(advertisement.parameters.contains("\"required\":[\"mode\",\"note\"]"))

        // Schema is embedded as JSON, not escaped as a second JSON string.
        val json = advertisement.toJson()
        assertTrue(json.contains("\"effect\":\"WRITE\""))
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\""))
    }

    @Test
    fun catalogAdvertisementFiltersCapabilitiesAndDoesNotExecuteBarrierTools() {
        var executed = false
        fun registered(definition: ToolDefinition) = object : RegisteredTool {
            override val definition = definition
            override fun prepare(arguments: ToolArguments): PreparedTool {
                executed = true
                return PreparedTool { ExecutionReceipt.NotExecuted("test") }
            }
        }
        val registry = ToolRegistry()
        registry.register(registered(ToolDefinition(
            name = "ask_user",
            version = 1,
            description = "Ask the user one question",
            capability = "conversation.ask",
            effect = ToolEffect.USER_BARRIER,
            schema = ToolSchema(listOf(ArgumentField("question", ArgumentType.Text(maxLength = 200))))
        )))
        registry.register(registered(ToolDefinition(
            name = "hidden_read",
            version = 1,
            description = "Not enabled in this session",
            capability = "device.hidden.read",
            effect = ToolEffect.READ_ONLY,
            schema = ToolSchema()
        )))

        val catalog = registry.snapshot()
        val session = SessionCapabilities("session-1", setOf("conversation.ask"))
        val advertised = catalog.advertisements(session)

        assertEquals(listOf("ask_user"), advertised.map { it.name })
        assertEquals(ToolEffect.USER_BARRIER, advertised.single().effect)
        assertTrue(advertised.single().parameters.contains("question"))
        assertFalse(executed)
        assertEquals("[]", ToolAdvertisements.encode(catalog, SessionCapabilities("session-2", emptySet())))
    }
}
