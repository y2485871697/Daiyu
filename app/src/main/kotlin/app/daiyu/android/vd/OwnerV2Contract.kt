package app.daiyu.android.vd.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Same-package adapter to the authoritative Java contract; no second payload/default dialect. */
internal object OwnerV2Contract {
    fun binding(values: List<String>): OwnerOperationIdentity.RunBinding {
        require(values.size == 6)
        return OwnerOperationIdentity.bindingFromTrustedHost(
            values[0], values[1], values[2], values[3], values[4], values[5])
    }

    fun normalize(op: String, parameters: JSONObject): JSONObject =
        OwnerProtocol.businessParameters(op, parameters)

    fun generationDigest(generation: String, binding: OwnerOperationIdentity.RunBinding): String =
        OwnerOperationIdentity.generationDigest(generation, binding)

    fun bindingDigest(binding: OwnerOperationIdentity.RunBinding): String = OwnerOperationIdentity.bindingDigest(binding)

    fun identity(generation: String, id: String, op: String,
                 binding: OwnerOperationIdentity.RunBinding, parameters: JSONObject): String {
        val effective = map(parameters).toMutableMap()
        if (op != "status" && !effective.containsKey("displayId")) effective["displayId"] = null
        if (op == "launch") for (key in listOf("package", "component", "action"))
            if (!effective.containsKey(key)) effective[key] = null
        if (op == "handoff" && !effective.containsKey("taskIds")) effective["taskIds"] = null
        return OwnerOperationIdentity(generation, id, op, binding, effective).digest
    }

    private fun map(obj: JSONObject): Map<String, Any?> = obj.keys().asSequence().associateWith { value(obj.get(it)) }
    private fun value(raw: Any): Any? = when (raw) {
        JSONObject.NULL -> null
        is JSONObject -> map(raw)
        is JSONArray -> (0 until raw.length()).map { value(raw.get(it)) }
        is Double, is Float -> BigDecimal(raw.toString())
        else -> raw
    }
}
