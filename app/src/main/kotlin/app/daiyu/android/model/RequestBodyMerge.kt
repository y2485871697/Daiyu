package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges a user supplied request body into the body assembled by the model.
 *
 * User values win. Objects are the one recursive case; arrays are
 * deliberately replaced as a whole, so a user supplied array can never be
 * accidentally combined with provider defaults.  Neither input object is
 * modified, and the returned tree does not share mutable JSON containers with
 * either input.
 */
object RequestBodyMerge {
    /**
     * Example: `merge(providerBody, userBody)` keeps provider-only fields while
     * applying every field from [userBody] according to the rules above.
     */
    @JvmStatic
    fun merge(providerBody: JSONObject, userBody: JSONObject): JSONObject {
        val result = copyObject(providerBody)
        val keys = userBody.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val userValue = userBody.get(key)
            val mergedValue = if (result.has(key)) {
                val providerValue = result.get(key)
                if (providerValue is JSONObject && userValue is JSONObject) {
                    merge(providerValue, userValue)
                } else {
                    copyValue(userValue)
                }
            } else {
                copyValue(userValue)
            }
            result.put(key, mergedValue)
        }
        return result
    }

    /**
     * Applies [userBodies] in list order. Later values override earlier values,
     * except that two objects merge recursively. Each entry is applied before
     * reading the next, so repeated keys retain intermediate type changes;
     * flattening the list into one object would lose that behavior.
     *
     * The provider and every list entry remain unchanged. The returned tree
     * shares no mutable JSON containers with any input, even for an empty list.
     */
    @JvmStatic
    fun merge(providerBody: JSONObject, userBodies: List<JSONObject>): JSONObject {
        var result = copyObject(providerBody)
        for (userBody in userBodies) {
            result = merge(result, userBody)
        }
        return result
    }

    private fun copyObject(source: JSONObject): JSONObject {
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            copy.put(key, copyValue(source.get(key)))
        }
        return copy
    }

    private fun copyArray(source: JSONArray): JSONArray {
        val copy = JSONArray()
        for (index in 0 until source.length()) {
            copy.put(copyValue(source.get(index)))
        }
        return copy
    }

    private fun copyValue(value: Any?): Any {
        return when (value) {
            is JSONObject -> copyObject(value)
            is JSONArray -> copyArray(value)
            // JSONObject.NULL is intentional JSON null, not a Kotlin null.
            null -> JSONObject.NULL
            else -> value
        }
    }
}
