package app.daiyu.android.model

import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class RequestBodyFieldsAndroidTest : InstrumentationTestCase() {
    fun testAndroidNumberMaterializationCannotRewriteUntouchedOrEnteredTokens() {
        val integers = listOf("9223372036854775808", "0.123456789012345678901234567890", "1e-200", "-0", "1.00")
        for (token in integers) {
            val original = "{\"nested\":{\"values\":[$token]},\"number\":$token}"
            val edited = RequestBodyFields.put(original, null,
                RequestBodyField("flag", RequestBodyValueType.BOOLEAN, "true"), ProviderProtocol.RESPONSES)
            val fields = RequestBodyFields.read(edited).associateBy { it.name }
            assertEquals(token, fields.getValue("number").text)
            assertEquals("{\"values\":[$token]}", fields.getValue("nested").text)
            val entered = RequestBodyFields.put("", null,
                RequestBodyField("number", RequestBodyValueType.NUMBER, token), ProviderProtocol.RESPONSES)
            assertEquals(token, RequestBodyFields.read(entered).single().text)
        }
    }
}
