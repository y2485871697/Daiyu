package app.daiyu.android.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestBodyFieldsTest {
    private val protocol = ProviderProtocol.RESPONSES
    private fun put(raw: String, name: String, type: RequestBodyValueType, text: String, old: String? = null) =
        RequestBodyFields.put(raw, old, RequestBodyField(name, type, text), protocol)
    private fun reject(work: () -> Unit) {
        try { work(); fail("must reject") } catch (_: IllegalArgumentException) {}
    }
    @Test fun allSixTypesRemainDistinctAndStringsAreNeverInferred() {
        var raw = ""
        val rows = listOf(RequestBodyField("string", RequestBodyValueType.STRING, "true"),
            RequestBodyField("number", RequestBodyValueType.NUMBER, "123.5"),
            RequestBodyField("bool", RequestBodyValueType.BOOLEAN, "true"),
            RequestBodyField("null", RequestBodyValueType.NULL, "null"),
            RequestBodyField("object", RequestBodyValueType.OBJECT, "{\"nested\":[1,false,null]}"),
            RequestBodyField("array", RequestBodyValueType.ARRAY, "[]"))
        for (row in rows) raw = RequestBodyFields.put(raw, null, row, protocol)
        assertEquals(rows.associate { it.name to it.type }, RequestBodyFields.read(raw).associate { it.name to it.type })
        assertEquals("true", JSONObject(raw).get("string"))
        assertTrue(JSONObject(raw).get("bool") is Boolean)
        assertTrue(JSONObject(raw).has("null")); assertTrue(JSONObject(raw).isNull("null"))
        assertEquals(0, JSONObject(raw).getJSONArray("array").length())
        for (text in listOf("123", "true", "null", "", "  exact\ntext  ")) {
            val encoded = put("", "value", RequestBodyValueType.STRING, text)
            assertEquals(text, JSONObject(encoded).get("value"))
        }
    }
    @Test fun replaceRenameDeleteDoNotMutateTheOriginalOrConfuseNullWithRemoval() {
        val original = """{ "a": {"x":1}, "b":false }"""
        val edited = put(original, "renamed", RequestBodyValueType.NULL, "null", "a")
        assertTrue(JSONObject(original).has("a")); assertFalse(JSONObject(edited).has("a"))
        assertTrue(JSONObject(edited).has("renamed")); assertFalse(JSONObject(edited).getBoolean("b"))
        assertFalse(JSONObject(RequestBodyFields.remove(edited, "renamed", protocol)).has("renamed"))
        reject { put(original, "b", RequestBodyValueType.STRING, "text", "a") }
        reject { put(original, "b", RequestBodyValueType.STRING, "text") }
        reject { put(original, "c", RequestBodyValueType.STRING, "text", "missing") }
        reject { RequestBodyFields.remove(original, "missing", protocol) }
    }
    @Test fun invalidRawAndValuesNeverBecomeAnEmptyReplacement() {
        for (raw in listOf("not-json", "[]", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}")) {
            reject { RequestBodyFields.read(raw) }
            reject { put(raw, "x", RequestBodyValueType.STRING, "secret") }
        }
        for ((type, text) in listOf(RequestBodyValueType.NUMBER to "true", RequestBodyValueType.NUMBER to "NaN",
            RequestBodyValueType.BOOLEAN to "\"true\"", RequestBodyValueType.NULL to "false",
            RequestBodyValueType.OBJECT to "[]", RequestBodyValueType.ARRAY to "{}",
            RequestBodyValueType.OBJECT to "{\"a\":1,\"a\":2}", RequestBodyValueType.NUMBER to "1,\"extra\":2")) {
            reject { put("", "value", type, text) }
        }
    }
    @Test fun ownedFieldsProtocolRulesAndSizeDepthLimitsRemainEnforced() {
        reject { put("", "tools", RequestBodyValueType.ARRAY, "[]") }
        reject { put("", "stream", RequestBodyValueType.BOOLEAN, "true") }
        reject { RequestBodyFields.put("", null, RequestBodyField("temperature", RequestBodyValueType.NUMBER, "0.5"), ProviderProtocol.GEMINI) }
        reject { RequestBodyFields.put("", null, RequestBodyField("max_tokens", RequestBodyValueType.STRING, "123"), ProviderProtocol.MESSAGES) }
        reject { put("", "value", RequestBodyValueType.STRING, "a".repeat(ProviderRequestOptions.MAX_BODY_BYTES)) }
        reject { put("", "value", RequestBodyValueType.STRING, "中".repeat(25000)) }
        reject { put("", "value", RequestBodyValueType.OBJECT, "{\"x\":".repeat(65)+"0"+"}".repeat(65)) }
        assertEquals(10, JSONObject(RequestBodyFields.put("", null,
            RequestBodyField("max_tokens", RequestBodyValueType.NUMBER, "10"), ProviderProtocol.MESSAGES)).getInt("max_tokens"))
    }
    @Test fun projectionsAreRedactedAndOpeningDoesNotRewriteRawBody() {
        val raw = "{  \"token\" : \"private-secret\" }"
        val field = RequestBodyFields.read(raw).single()
        assertFalse(field.toString().contains("private-secret")); assertFalse(field.toString().contains("token"))
        assertEquals("{  \"token\" : \"private-secret\" }", raw)
        assertTrue(RequestBodyFields.read("").isEmpty())
    }
    @Test fun untouchedAndEditedNumbersKeepTheirExactTokensIncludingNestedPrecision() {
        val integer = "9223372036854775808"
        val decimal = "0.123456789012345678901234567890"
        val nested = "{\"values\":["+integer+","+decimal+",1e-200]}"
        val raw = "{\"integer\":"+integer+",\"decimal\":"+decimal+",\"nested\":"+nested+"}"
        val result = put(raw, "other", RequestBodyValueType.BOOLEAN, "true")
        val fields = RequestBodyFields.read(result).associateBy { it.name }
        assertEquals(integer, fields.getValue("integer").text)
        assertEquals(decimal, fields.getValue("decimal").text)
        assertEquals(nested, fields.getValue("nested").text)
        for (token in listOf(integer, decimal, "1e-200", "-0", "1.00")) {
            val added = put("", "number", RequestBodyValueType.NUMBER, token)
            assertEquals(token, RequestBodyFields.read(added).single().text)
        }
    }

}
