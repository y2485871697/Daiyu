package app.daiyu.android.domain.tools

import org.junit.Assert.*
import org.junit.Test

class ToolSchemaTest {
    @Test fun strictEmptySchemaMatchesExistingReadOnlyArgumentContract() {
        val schema = ToolSchema()
        assertEquals("{}", schema.parse(" \r\n { \t } ").canonicalJson())
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}", schema.jsonSchema())
        for (input in listOf("", "[]", "null", "{}junk", "{}{}", "{/*comment*/}", "{;}", "{\u00a0}", "{\"extra\":1}")) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun strictTypedSchemaRejectsAmbiguousOrCoercedValues() {
        val schema = ToolSchema(listOf(
            ArgumentField("text", ArgumentType.Text(maxLength = 6, choices = setOf("hello", "world"))),
            ArgumentField("count", ArgumentType.Integer(1, 5)),
            ArgumentField("flag", ArgumentType.BooleanValue, required = false)
        ))
        val valid = "{\"text\":\"hello\",\"count\":2,\"flag\":true}"
        assertEquals("{\"count\":2,\"flag\":true,\"text\":\"hello\"}", schema.parse(valid).canonicalJson())
        assertEquals(schema.parse(valid).canonicalJson(), schema.parse("{ \"flag\":true, \"count\":2, \"text\":\"h\\u0065llo\" }").canonicalJson())
        for (input in listOf(
            "{\"text\":\"hello\",\"count\":2,\"count\":3}", "{\"text\":\"hello\",\"count\":02}",
            "{\"text\":\"hello\",\"count\":2.0}", "{\"text\":\"hello\",\"count\":2e0}",
            "{\"text\":\"hello\",\"count\":\"2\"}", "{\"text\":\"hello\",\"count\":null}",
            "{\"text\":\"hello\",\"count\":0}", "{\"text\":\"hello\",\"count\":6}",
            "{\"text\":\"other\",\"count\":2}", "{\"text\":\"hello\",\"count\":2,}",
            "{\"text\":\"hello\",\"count\":2,\"flag\":1}", "{\"text\":\"hello\",\"count\":2,\"flag\":True}",
            "{\"text\":\"hello\",\"count\":999999999999999999999999}", "{\"text\":\"h\\uZZZZllo\",\"count\":2}"
        )) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
        assertFalse(schema.parse("{\"text\":\"hello\",\"count\":1}").contains("flag"))
        assertTrue(schema.jsonSchema().contains("\"additionalProperties\":false"))
        assertTrue(schema.jsonSchema().contains("\"enum\":[\"hello\",\"world\"]"))
    }
    @Test fun nestedSchemasSupportRealAdapterOptionsAndCanonicalDecimalValues() {
        val option = ToolSchema(listOf(ArgumentField("label", ArgumentType.Text(16))))
        val schema = ToolSchema(listOf(
            ArgumentField("options", ArgumentType.ArrayValue(ArgumentType.ObjectValue(option), 2, 8, unique = true)),
            ArgumentField("ratio", ArgumentType.Decimal(java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE))
        ))
        val parsed = schema.parse("{\"ratio\":0.50,\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}")
        assertEquals(java.math.BigDecimal("0.5"), parsed.decimal("ratio"))
        assertEquals("A", (parsed.array("options")[0] as ToolArguments).text("label"))
        assertEquals("{\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"ratio\":0.5}", parsed.canonicalJson())
        assertEquals(parsed.canonicalJson(), schema.parse("{\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"ratio\":5e-1}").canonicalJson())
        assertTrue(schema.jsonSchema().contains("\"minItems\":2,\"maxItems\":8,\"uniqueItems\":true"))
        for (input in listOf(
            "{\"options\":[{\"label\":\"A\"}],\"ratio\":0.5}",
            "{\"options\":[{\"label\":\"A\"},{\"label\":\"A\"}],\"ratio\":0.5}",
            "{\"options\":[{\"label\":\"A\",\"extra\":1},{\"label\":\"B\"}],\"ratio\":0.5}",
            "{\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"ratio\":2}",
            "{\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"ratio\":1e999999999}"
        )) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
        val text = ToolSchema(listOf(ArgumentField("value", ArgumentType.Text())))
        try { text.parse("{\"value\":\"\\uD800\"}"); fail("unpaired surrogate") } catch (_: IllegalArgumentException) {}
        assertEquals("😀", text.parse("{\"value\":\"\\uD83D\\uDE00\"}").text("value"))
        try { text.parse("{\"value\":" + "[".repeat(40) + "true" + "]".repeat(40) + "}"); fail("excess nesting") } catch (_: IllegalArgumentException) {}
    }

    @Test fun duplicateRegistryNamesAndInvalidDefinitionsFailClosed() {
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("read", 1, "Read", "read", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool { ExecutionReceipt.Confirmed("ok") }
        }
        val registry = ToolRegistry().register(tool)
        try { registry.register(tool); fail() } catch (_: IllegalArgumentException) {}
        try { ToolDefinition("ask_user", 1, "Ask", "ask", ToolEffect.READ_ONLY, ToolSchema()); fail() } catch (_: IllegalArgumentException) {}
    }
}
