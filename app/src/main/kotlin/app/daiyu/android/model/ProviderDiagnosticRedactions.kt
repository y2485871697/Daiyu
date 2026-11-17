package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

internal fun providerRequestRedactions(request: JSONObject?): List<String> {
    val values = mutableListOf<String>()
    fun collect(value: Any?, depth: Int) {
        // An empty sentinel disables provider message export if collection is incomplete.
        if (depth > 32 || values.size >= 8192) { if ("" !in values) values += ""; return }
        when (value) {
            is JSONObject -> value.keys().forEach { collect(value.opt(it), depth + 1) }
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), depth + 1)
            is String -> if (value.isNotEmpty()) values += value
        }
    }
    collect(request, 0)
    return values.distinct()
}

internal fun providerHeaderRedactions(name: String, value: String): List<String> = buildList {
    add(value)
    if (value.startsWith("Bearer ", true) || value.startsWith("Basic ", true)) add(value.substringAfter(' '))
    if (name.equals("Cookie", true) || name.equals("Set-Cookie", true)) value.split(';').forEach {
        val index = it.indexOf('=')
        if (index >= 0) it.substring(index + 1).trim().takeIf(String::isNotEmpty)?.let(::add)
    }
}
