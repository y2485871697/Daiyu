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
    @Test fun textMinimumLengthDefaultsPreserveExistingCallsAndEmptyValues() {
        val defaultType = ArgumentType.Text()
        val positionalType = ArgumentType.Text(6, setOf("", "hello"))
        assertEquals(0, defaultType.minimumLength)
        assertEquals(4096, defaultType.maxLength)
        assertEquals(0, positionalType.minimumLength)
        val schema = ToolSchema(listOf(ArgumentField("value", positionalType)))
        assertEquals("", schema.parse("{\"value\":\"\"}").text("value"))
        assertEquals("hello", schema.parse("{\"value\":\"hello\"}").text("value"))
        assertEquals(
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":6,\"enum\":[\"\",\"hello\"]}},\"required\":[\"value\"],\"additionalProperties\":false}",
            schema.jsonSchema()
        )
    }

    @Test fun textLengthBoundsAreInclusiveInParsingAndDirectValidation() {
        val schema = ToolSchema(listOf(ArgumentField("value", ArgumentType.Text(maxLength = 4, minimumLength = 2))))
        for (value in listOf("ab", "abc", "abcd")) {
            assertEquals(value, schema.parse("{\"value\":${jsonQuote(value)}}").text("value"))
            assertEquals(value, schema.validateObject(mapOf("value" to value)).text("value"))
        }
        for (value in listOf("", "a", "abcde")) {
            try { schema.parse("{\"value\":${jsonQuote(value)}}"); fail("accepted $value") } catch (_: IllegalArgumentException) {}
            try { schema.validateObject(mapOf("value" to value)); fail("accepted $value") } catch (_: IllegalArgumentException) {}
        }
        val exact = ToolSchema(listOf(ArgumentField("value", ArgumentType.Text(maxLength = 2, minimumLength = 2))))
        assertEquals("ab", exact.parse("{\"value\":\"ab\"}").text("value"))
        for (value in listOf("a", "abc")) {
            try { exact.parse("{\"value\":${jsonQuote(value)}}"); fail("accepted $value") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun textMinimumLengthCountsUnicodeCodePointsNotUtf16Units() {
        val schema = ToolSchema(listOf(ArgumentField("value", ArgumentType.Text(maxLength = 2, minimumLength = 2))))
        for (value in listOf("😀😀", "😀a")) {
            assertEquals(value, schema.parse("{\"value\":${jsonQuote(value)}}").text("value"))
            assertEquals(value, schema.validateObject(mapOf("value" to value)).text("value"))
        }
        assertEquals("😀😀", schema.parse("{\"value\":\"\\uD83D\\uDE00\\uD83D\\uDE00\"}").text("value"))
        for (input in listOf(
            "{\"value\":\"😀\"}", "{\"value\":\"😀😀a\"}",
            "{\"value\":\"\\uD800a\"}", "{\"value\":\"a\\uDC00\"}"
        )) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
        for (value in listOf("😀", "😀😀a", "\uD800a", "a\uDC00")) {
            try { schema.validateObject(mapOf("value" to value)); fail("accepted invalid text") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun advertisedTextBoundsAndChoicesMatchValidationAfterSchemaFreezing() {
        val choices = mutableSetOf("abc", "ab", "a", "abcde")
        val schema = ToolSchema(listOf(ArgumentField("value", ArgumentType.Text(4, choices, minimumLength = 2))))
        choices.add("abcd")
        assertEquals(
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":4,\"enum\":[\"a\",\"ab\",\"abc\",\"abcde\"]}},\"required\":[\"value\"],\"additionalProperties\":false}",
            schema.jsonSchema()
        )
        assertEquals(2, (schema.fields.single().type as ArgumentType.Text).minimumLength)
        assertEquals("ab", schema.parse("{\"value\":\"ab\"}").text("value"))
        for (value in listOf("a", "abcde", "abcd")) {
            try { schema.parse("{\"value\":${jsonQuote(value)}}"); fail("accepted $value") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun minimumLengthDoesNotRelaxRequiredOptionalNullOrUnknownFields() {
        val schema = ToolSchema(listOf(
            ArgumentField("required_text", ArgumentType.Text(minimumLength = 1)),
            ArgumentField("optional_text", ArgumentType.Text(minimumLength = 1), required = false)
        ))
        assertFalse(schema.parse("{\"required_text\":\"a\"}").contains("optional_text"))
        assertEquals("b", schema.parse("{\"required_text\":\"a\",\"optional_text\":\"b\"}").text("optional_text"))
        for (input in listOf(
            "{}", "{\"optional_text\":\"b\"}", "{\"required_text\":\"\"}",
            "{\"required_text\":\"a\",\"optional_text\":\"\"}",
            "{\"required_text\":null}", "{\"required_text\":\"a\",\"optional_text\":null}",
            "{\"required_text\":1}", "{\"required_text\":\"a\",\"optional_text\":false}",
            "{\"required_text\":\"a\",\"extra\":\"b\"}"
        )) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun textMinimumLengthAppliesInsideObjectsAndArrays() {
        val text = ArgumentType.Text(maxLength = 3, minimumLength = 2)
        val nested = ToolSchema(listOf(ArgumentField("label", text)))
        val schema = ToolSchema(listOf(
            ArgumentField("object", ArgumentType.ObjectValue(nested)),
            ArgumentField("array", ArgumentType.ArrayValue(text))
        ))
        assertEquals("ab", schema.parse("{\"object\":{\"label\":\"ab\"},\"array\":[\"abc\"]}").objectValue("object").text("label"))
        assertTrue(schema.jsonSchema().contains("\"label\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":3}"))
        assertTrue(schema.jsonSchema().contains("\"items\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":3}"))
        for (input in listOf(
            "{\"object\":{\"label\":\"a\"},\"array\":[\"ab\"]}",
            "{\"object\":{\"label\":\"ab\"},\"array\":[\"a\"]}"
        )) {
            try { schema.parse(input); fail("accepted $input") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun invalidTextLengthDefinitionsFailClosed() {
        for ((minimum, maximum) in listOf(-1 to 4, Int.MIN_VALUE to 4, 5 to 4, 0 to 0, 0 to -1, 1 to 0)) {
            try { ArgumentType.Text(maxLength = maximum, minimumLength = minimum); fail("accepted bounds $minimum..$maximum") } catch (_: IllegalArgumentException) {}
        }
        try { ArgumentType.Text(minimumLength = 4097); fail("accepted minimum above default maximum") } catch (_: IllegalArgumentException) {}
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
