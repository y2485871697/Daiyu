package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

/** 用户值优先，对象递归合并，数组整组替换；输入保持不变，结果使用独立的 JSON 容器。 */
object RequestBodyMerge {
    
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

    /** 按列表顺序合并 [userBodies]，后值覆盖前值，对象递归合并。
     * 逐项应用以保留同名字段的中间类型变化；结果使用独立容器，空列表也返回副本。
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
