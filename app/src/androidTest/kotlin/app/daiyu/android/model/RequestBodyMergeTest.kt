package app.daiyu.android.model

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class RequestBodyMergeTest : InstrumentationTestCase() {
    fun testDeepObjectsMergeAndNonOverriddenKeysRemain() {
        // This is the call site shape used by a request builder:
        // val body = RequestBodyMerge.merge(providerBody, userBody)
        val providerBody = JSONObject()
            .put("model", "provider-model")
            .put("input", JSONObject()
                .put("role", "user")
                .put("options", JSONObject()
                    .put("temperature", 0.2)
                    .put("nested", JSONObject().put("providerOnly", true))))
            .put("stream", true)
        val userBody = JSONObject()
            .put("input", JSONObject()
                .put("options", JSONObject()
                    .put("temperature", 0.8)
                    .put("nested", JSONObject().put("userOnly", "kept"))))

        val merged = RequestBodyMerge.merge(providerBody, userBody)

        assertEquals("provider-model", merged.getString("model"))
        assertTrue(merged.getBoolean("stream"))
        val options = merged.getJSONObject("input").getJSONObject("options")
        assertEquals(0.8, options.getDouble("temperature"))
        val nested = options.getJSONObject("nested")
        assertTrue(nested.getBoolean("providerOnly"))
        assertEquals("kept", nested.getString("userOnly"))
    }

    fun testUserArraysReplaceWholeArrayIncludingEmptyArray() {
        val providerBody = JSONObject()
            .put("messages", JSONArray().put("provider-1").put("provider-2"))
            .put("nested", JSONObject().put("items", JSONArray().put(1).put(2)))
        val userArray = JSONArray().put(JSONObject().put("text", "user"))
        val userBody = JSONObject()
            .put("messages", userArray)
            .put("nested", JSONObject().put("items", JSONArray()))

        val merged = RequestBodyMerge.merge(providerBody, userBody)

        assertEquals(1, merged.getJSONArray("messages").length())
        assertEquals("user", merged.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(0, merged.getJSONObject("nested").getJSONArray("items").length())
    }

    fun testScalarObjectAndArrayTypeChangesOverride() {
        val providerBody = JSONObject()
            .put("objectToScalar", JSONObject().put("old", true))
            .put("scalarToObject", "old")
            .put("arrayToScalar", JSONArray().put(1))
            .put("scalarToArray", "old")
        val userBody = JSONObject()
            .put("objectToScalar", "new")
            .put("scalarToObject", JSONObject().put("new", true))
            .put("arrayToScalar", false)
            .put("scalarToArray", JSONArray().put("new"))

        val merged = RequestBodyMerge.merge(providerBody, userBody)

        assertEquals("new", merged.getString("objectToScalar"))
        assertTrue(merged.getJSONObject("scalarToObject").getBoolean("new"))
        assertFalse(merged.getBoolean("arrayToScalar"))
        assertEquals("new", merged.getJSONArray("scalarToArray").getString(0))
    }

    fun testJsonNullAndDuplicateKeysAreExplicitUserValues() {
        val providerBody = JSONObject().put("nullable", "provider").put("untouched", 7)
        val userBody = JSONObject()
            .put("nullable", JSONObject.NULL)
            // JSONObject follows JSON's last-value-wins behavior for duplicate names.
            .put("duplicate", "first")
            .put("duplicate", "last")

        val merged = RequestBodyMerge.merge(providerBody, userBody)

        assertTrue(merged.has("nullable"))
        assertTrue(merged.isNull("nullable"))
        assertEquals("last", merged.getString("duplicate"))
        assertEquals(7, merged.getInt("untouched"))
    }

    fun testOrderedBodiesMergeRepeatedObjectKeysInsteadOfKeepingOnlyLastEntry() {
        val providerBody = JSONObject()
            .put("settings", JSONObject().put("provider", true))
        val userBodies = listOf(
            JSONObject().put("settings", JSONObject().put("a", 1)),
            JSONObject().put("settings", JSONObject().put("b", 2))
        )

        val merged = mergeOrderedAndCheckInputs(providerBody, userBodies)

        val settings = merged.getJSONObject("settings")
        assertEquals(3, settings.length())
        assertTrue(settings.getBoolean("provider"))
        assertEquals(1, settings.getInt("a"))
        assertEquals(2, settings.getInt("b"))
    }

    fun testOrderedObjectArrayObjectChangesDiscardEarlierObjectFields() {
        val providerBody = JSONObject()
            .put("value", JSONObject().put("provider", true))
        val objectEntry = JSONObject().put("value", JSONObject().put("a", 1))
        val arrayEntry = JSONObject().put("value", JSONArray().put("replacement"))
        val lastEntry = JSONObject().put("value", JSONObject().put("b", 2))

        val throughArray = mergeOrderedAndCheckInputs(providerBody, listOf(objectEntry, arrayEntry))
        assertEquals(1, throughArray.getJSONArray("value").length())
        assertEquals("replacement", throughArray.getJSONArray("value").getString(0))

        val merged = mergeOrderedAndCheckInputs(providerBody, listOf(objectEntry, arrayEntry, lastEntry))
        val value = merged.getJSONObject("value")
        assertEquals(1, value.length())
        assertEquals(2, value.getInt("b"))
        assertFalse(value.has("provider"))
        assertFalse(value.has("a"))
    }

    fun testOrderedJsonNullIsAValueAndResetsPreviousObject() {
        val providerBody = JSONObject()
            .put("nullable", JSONObject().put("provider", true))
            .put("reset", JSONObject().put("provider", true))
        val userBodies = listOf(
            JSONObject()
                .put("nullable", JSONObject().put("a", 1))
                .put("reset", JSONObject().put("a", 1)),
            JSONObject().put("nullable", JSONObject.NULL).put("reset", JSONObject.NULL),
            JSONObject().put("reset", JSONObject().put("b", 2))
        )

        val merged = mergeOrderedAndCheckInputs(providerBody, userBodies)

        assertTrue(merged.has("nullable"))
        assertSame(JSONObject.NULL, merged.get("nullable"))
        val reset = merged.getJSONObject("reset")
        assertEquals(1, reset.length())
        assertEquals(2, reset.getInt("b"))
    }

    fun testOrderedJsonLookingStringsRemainStrings() {
        val providerBody = JSONObject()
            .put("number", 0).put("boolean", false).put("null", JSONObject.NULL)
        val userBodies = listOf(
            JSONObject().put("number", "123").put("boolean", "true").put("null", "null"),
            JSONObject().put("other", "unchanged")
        )

        val merged = mergeOrderedAndCheckInputs(providerBody, userBodies)

        for ((key, expected) in listOf("number" to "123", "boolean" to "true", "null" to "null")) {
            val value = merged.get(key)
            assertTrue(value is String)
            assertEquals(expected, value)
        }
    }

    fun testOrderedBodiesAndProviderStayIndependentInBothDirections() {
        val providerBody = JSONObject()
            .put("providerOnly", JSONArray().put(JSONObject().put("value", "provider")))
            .put("nested", JSONObject().put("provider", JSONArray().put(1)))
        val userBodies = listOf(
            JSONObject().put("nested", JSONObject()
                .put("first", JSONArray().put(JSONObject().put("value", "first")))),
            JSONObject().put("nested", JSONObject()
                .put("second", JSONArray().put(JSONArray().put("second"))))
        )
        val inputs = listOf(providerBody) + userBodies
        val before = inputs.map { it.toString() }

        val merged = mergeOrderedAndCheckInputs(providerBody, userBodies)
        merged.getJSONArray("providerOnly").getJSONObject(0).put("later", true)
        val nested = merged.getJSONObject("nested")
        nested.getJSONArray("provider").put(2)
        nested.getJSONArray("first").getJSONObject(0).put("later", true)
        nested.getJSONArray("second").getJSONArray(0).put("later")
        assertEquals(before, inputs.map { it.toString() })

        val mergedBeforeInputChanges = merged.toString()
        providerBody.getJSONArray("providerOnly").getJSONObject(0).put("inputOnly", true)
        providerBody.getJSONObject("nested").getJSONArray("provider").put(3)
        userBodies[0].getJSONObject("nested").getJSONArray("first")
            .getJSONObject(0).put("inputOnly", true)
        userBodies[1].getJSONObject("nested").getJSONArray("second")
            .getJSONArray(0).put("inputOnly")
        assertEquals(mergedBeforeInputChanges, merged.toString())
    }

    fun testEmptyOrderedBodiesStillDeepCopyProvider() {
        val providerBody = JSONObject()
            .put("nested", JSONArray().put(JSONObject().put("value", 1)))

        val merged = mergeOrderedAndCheckInputs(providerBody, emptyList())

        assertEquals(1, merged.getJSONArray("nested").getJSONObject(0).getInt("value"))
        merged.getJSONArray("nested").getJSONObject(0).put("value", 2)
        assertEquals(1, providerBody.getJSONArray("nested").getJSONObject(0).getInt("value"))
    }

    private fun mergeOrderedAndCheckInputs(
        providerBody: JSONObject,
        userBodies: List<JSONObject>
    ): JSONObject {
        val entriesBefore = userBodies.toList()
        val inputs = listOf(providerBody) + userBodies
        val before = inputs.map { it.toString() }
        val inputContainers = inputs.flatMap { jsonContainers(it) }

        val merged = RequestBodyMerge.merge(providerBody, userBodies)

        assertEquals(before, inputs.map { it.toString() })
        assertEquals(entriesBefore.size, userBodies.size)
        for (index in entriesBefore.indices) {
            assertSame(entriesBefore[index], userBodies[index])
        }
        for (container in jsonContainers(merged)) {
            assertFalse("Output must not share an input JSON container",
                inputContainers.any { it === container })
        }
        return merged
    }

    private fun jsonContainers(value: Any?): List<Any> {
        val containers = mutableListOf<Any>()
        when (value) {
            is JSONObject -> {
                containers.add(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    containers.addAll(jsonContainers(value.get(keys.next())))
                }
            }
            is JSONArray -> {
                containers.add(value)
                for (index in 0 until value.length()) {
                    containers.addAll(jsonContainers(value.get(index)))
                }
            }
        }
        return containers
    }

    fun testMergingDoesNotMutateOrShareInputContainers() {
        val providerBody = JSONObject()
            .put("nested", JSONObject().put("provider", true))
            .put("array", JSONArray().put(JSONObject().put("value", "provider")))
        val userBody = JSONObject()
            .put("nested", JSONObject().put("user", true))
            .put("array", JSONArray().put(JSONObject().put("value", "user")))
        val providerBefore = providerBody.toString()
        val userBefore = userBody.toString()

        val merged = RequestBodyMerge.merge(providerBody, userBody)
        merged.getJSONObject("nested").put("later", "output-only")
        merged.getJSONArray("array").getJSONObject(0).put("later", "output-only")

        assertEquals(providerBefore, providerBody.toString())
        assertEquals(userBefore, userBody.toString())
        assertFalse(providerBody.getJSONObject("nested").has("later"))
        assertFalse(userBody.getJSONObject("nested").has("later"))
        assertFalse(userBody.getJSONArray("array").getJSONObject(0).has("later"))
    }
}
