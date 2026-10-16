package app.daiyu.android.vd

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Fail closed on unknown fields, wrong types, mismatched identity and incomplete success. */
internal object OwnerResponseValidator {
    private val common = setOf("v", "op", "ok", "generation", "receipt", "receiptOnly", "found", "executed")
    private val failure = setOf("error", "message", "retryable", "sideEffectsAttempted", "handoffPhase",
        "attemptedTaskIds", "relocatedTaskIds", "completedTaskIds", "handoffProgress", "focusSamples", "rootTaskInventory")
    private val fields = mapOf(
        "initialize" to setOf("initialized", "displayId", "uniqueId"),
        "shutdown_uninitialized" to setOf("initialized", "receiptsClosed"),
        "receipt" to setOf("executed"),
        "status" to setOf("ready", "displayId", "uniqueId", "name", "width", "height", "densityDpi", "flags",
            "ownerPackage", "frameCount", "hasFrame", "frameTimestampNs", "session", "sourceState", "sourceEmpty",
            "sourceTaskCount", "sourceError", "sourcePackages", "sourcePackagesKnown", "retainedTaskIds", "liveTaskIds",
            "goneTaskIds", "finishing", "handoffComplete", "handoffCleanupOnly", "releaseAttempted", "mutationUncertain",
            "supported", "missing", "rootTaskInventory", "executed"),
        "launch" to setOf("taskIds", "launched", "displayId", "exitCode", "output", "reused", "reusedTaskId", "executed"),
        "input" to setOf("executed", "kind", "displayId", "exitCode"),
        "snapshot" to setOf("displayId", "frameCount", "timestampNs", "format", "width", "height", "bytes", "data", "executed"),
        "handoff" to setOf("handedOff", "sourceEmpty", "keptTaskIds", "removedTaskIds", "goneTaskIds", "cleanupOnly", "focusSamples", "executed"),
        "release" to setOf("released", "displayId", "uniqueId", "executed"))
    private val bools = setOf("ok", "receiptOnly", "found", "initialized", "executed", "ready", "hasFrame", "sourceEmpty",
        "sourcePackagesKnown", "finishing", "handoffComplete", "handoffCleanupOnly", "releaseAttempted", "mutationUncertain",
        "launched", "reused", "handedOff", "cleanupOnly", "released", "receiptsClosed", "retryable", "sideEffectsAttempted")
    private val ints = setOf("v", "displayId", "width", "height", "densityDpi", "flags", "frameCount", "sourceTaskCount",
        "exitCode", "reusedTaskId", "bytes")
    private val intArrays = setOf("retainedTaskIds", "liveTaskIds", "goneTaskIds", "taskIds", "keptTaskIds", "removedTaskIds",
        "attemptedTaskIds", "relocatedTaskIds", "completedTaskIds")
    private val textArrays = setOf("supported", "missing", "sourcePackages", "focusSamples")

    fun validate(line: String, wireOp: String, operation: String, generation: String, id: String?, digest: String,
                 bindingDigest: String, parameters: JSONObject?): JSONObject {
        val o = StrictOwnerJson.parse(line)
        require(o.get("v") == 2 && o.get("op") == wireOp && o.get("ok") is Boolean)
        val ok = o.getBoolean("ok")
        keys(o, common + fields.getValue(wireOp) + if (ok) emptySet() else failure)
        for (key in o.keys()) {
            val v = o.get(key)
            when {
                key in bools -> require(v is Boolean)
                key in ints -> integer(v)
                key == "timestampNs" || key == "frameTimestampNs" -> nonnegativeLong(v)
                key in intArrays -> intArray(v)
                key in textArrays -> textArray(v)
                key == "receipt" -> Unit
                key == "rootTaskInventory" -> inventory(v as JSONObject)
                key == "handoffProgress" -> {
                    val p = v as JSONObject
                    keys(p, setOf("mutationAttemptedTaskIds", "relocatedTaskIds", "completedTaskIds"))
                    require(p.length() == 3)
                    for (k in p.keys()) intArray(p.get(k))
                }
                key == "data" -> require(v is String && v.length <= 8 * 1024 * 1024)
                else -> require(v is String && v.length <= 4096 && v.none { it == '\u0000' })
            }
        }
        if (wireOp == "shutdown_uninitialized") {
            // This is process control, not a generation or operation receipt. Never accept a
            // release-shaped success, receipt-only success, or unknown fields as proof of close.
            keys(o, setOf("v", "op", "ok") + if (ok) setOf("initialized", "receiptsClosed")
                else setOf("error", "message"))
            require(o.length() == 5)
            if (ok) require(o.get("initialized") == false && o.get("receiptsClosed") == true)
            else {
                require(Regex("[A-Z][A-Z0-9_]{0,95}").matches(o.getString("error")))
                require(o.getString("message").length <= 200)
            }
            return o
        }
        if (o.has("generation")) require(o.get("generation") == generation)
        val receiptOnly = o.opt("receiptOnly") == true
        if (receiptOnly) {
            require(ok && o.get("executed") == false && o.get("found") is Boolean)
            require(o.getBoolean("found") == o.has("receipt"))
            // initialize returns receipt-only only after it found/reserved a generation record.
            // Only an explicit receipt lookup may legitimately report no record.
            if (wireOp == "initialize") require(o.getBoolean("found"))
            val allowed = common + setOf("executed") + if (wireOp == "status") setOf("mutationUncertain") else emptySet()
            keys(o, allowed)
        } else if (ok) {
            require(wireOp != "receipt" && o.has("receipt") && !o.has("found"))
            require(o.get("generation") == generation)
            if (wireOp != "input") require(!o.has("executed"))
            if (wireOp != "initialize") require(o.get("receiptOnly") == false)
            else require(!o.has("receiptOnly"))
            success(o, wireOp, parameters)
        } else {
            keys(o, setOf("v", "op", "ok", "generation", "receipt", "receiptOnly") + failure +
                if (wireOp == "status") setOf("mutationUncertain") else emptySet())
            require(o.get("error") is String && Regex("[A-Z][A-Z0-9_]{0,95}").matches(o.getString("error")))
            require(o.get("message") is String && o.getString("message").length <= 200)
            require(!o.has("found") && !receiptOnly)
        }
        if (o.has("receipt")) {
            val r = o.getJSONObject("receipt")
            keys(r, setOf("generation", "operationId", "operation", "generationReceipt", "digest", "bindingDigest", "state", "result"))
            require(r.get("generation") == generation && r.get("operationId") == (id ?: ""))
            require(r.get("operation") == if (id == null) "" else operation)
            require(r.get("generationReceipt") == (id == null))
            require(r.get("digest") == digest && r.get("bindingDigest") == bindingDigest)
            val state = r.getString("state")
            // FAILED exists in the store enum but the current Java coordinator never emits it:
            // every business/creation failure is conservatively completed as UNKNOWN.
            require(state in setOf("RESERVED", "STARTED", "SUCCEEDED", "UNKNOWN"))
            val terminal = state in setOf("SUCCEEDED", "UNKNOWN")
            require(r.has("result") == terminal)
            if (terminal) {
                val result = r.getJSONObject("result")
                keys(result, setOf("ok", "released"))
                require(result.length() == 2 && result.get("ok") is Boolean && result.get("released") is Boolean)
                if (state == "SUCCEEDED") {
                    require(result.get("ok") == true)
                    require(result.get("released") == (id == null || operation == "release"))
                } else {
                    require(result.get("ok") == false)
                }
            }
            if (!receiptOnly) {
                require(state == if (ok) { if (wireOp == "initialize") "STARTED" else "SUCCEEDED" } else "UNKNOWN")
                if (!ok) require(r.getJSONObject("result").get("ok") == false)
            }
        }
        return o
    }

    private fun success(o: JSONObject, op: String, p: JSONObject?) {
        fun positive(key: String) { require(integer(o.get(key)) > 0) }
        when (op) {
            "initialize" -> { require(o.get("initialized") == true); positive("displayId"); nonempty(o, "uniqueId") }
            "status" -> {
                require(o.get("ready") == true); positive("displayId"); nonempty(o, "uniqueId")
                for (k in listOf("width", "height", "densityDpi")) positive(k)
                for (k in listOf("hasFrame", "finishing", "handoffComplete", "releaseAttempted", "mutationUncertain")) require(o.get(k) is Boolean)
                require(o.get("session") in setOf("active", "released"))
                require(o.get("sourceState") in setOf("empty", "occupied", "unknown"))
                if (o.get("sourceState") == "unknown") nonempty(o, "sourceError")
                else {
                    require(o.get("sourceEmpty") == (o.get("sourceState") == "empty"))
                    require(integer(o.get("sourceTaskCount")) >= 0)
                }
                for (k in listOf("name", "ownerPackage")) nonempty(o, k)
                integer(o.get("flags")); require(integer(o.get("frameCount")) >= 0)
                nonnegativeLong(o.get("frameTimestampNs"))
                if (o.has("sourcePackages")) require(!o.has("sourcePackagesKnown"))
                else require(o.get("sourcePackagesKnown") == false)
                intArray(o.get("retainedTaskIds")); textArray(o.get("supported")); textArray(o.get("missing"))
                require(o.getJSONArray("supported").toString() == org.json.JSONArray(
                    app.daiyu.android.vd.runtime.OwnerProtocol.SUPPORTED_OPS.toList()).toString())
                require(o.getJSONArray("missing").length() == 0)
            }
            "launch" -> {
                require(o.get("launched") == true); positive("displayId"); intArray(o.get("taskIds"))
                if (o.opt("reused") == true) integer(o.get("reusedTaskId")) else require(integer(o.get("exitCode")) == 0 && o.get("output") is String)
            }
            "input" -> {
                require(o.get("executed") == true && o.get("kind") == p!!.get("kind"))
                positive("displayId"); require(integer(o.get("exitCode")) == 0)
            }
            "snapshot" -> {
                positive("displayId"); positive("width"); positive("height"); positive("frameCount")
                nonnegativeLong(o.get("timestampNs")); require(o.get("format") == "png")
                if (p!!.getBoolean("include")) {
                    val bytes = integer(o.get("bytes")); require(bytes in 1..p.getInt("maxBytes"))
                    val data = o.getString("data")
                    require(Regex("[A-Za-z0-9+/]*={0,2}").matches(data) && data.length % 4 == 0)
                    val png = Base64.getDecoder().decode(data)
                    require(png.size == bytes && png.size >= 8)
                    require(png.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)))
                } else require(!o.has("data") && !o.has("bytes"))
            }
            "handoff" -> {
                require(o.get("handedOff") == true && o.get("sourceEmpty") == true && o.get("cleanupOnly") is Boolean)
                for (k in listOf("keptTaskIds", "removedTaskIds", "goneTaskIds")) intArray(o.get(k))
                textArray(o.get("focusSamples"))
            }
            "release" -> { require(o.get("released") == true); positive("displayId"); nonempty(o, "uniqueId") }
        }
        if (p?.has("displayId") == true && o.has("displayId")) require(o.get("displayId") == p.get("displayId"))
    }

    private fun inventory(o: JSONObject) {
        keys(o, setOf("known", "error", "rootCount", "emitted", "truncated", "tasks"))
        require(o.get("known") is Boolean)
        if (!o.getBoolean("known")) { require(o.length() == 2); nonempty(o, "error"); return }
        integer(o.get("rootCount")); integer(o.get("emitted")); require(o.get("truncated") is Boolean)
        val tasks = o.getJSONArray("tasks"); require(tasks.length() <= 16 && tasks.length() == o.getInt("emitted"))
        for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            keys(t, setOf("id", "displayId", "userId", "activityType", "numActivities", "componentsKnown", "base",
                "baseActivity", "topActivity", "realActivity", "origActivity", "childrenKnown", "childTaskIds", "childNamesKnown", "childTaskNames"))
            for (k in listOf("id", "displayId", "userId", "activityType", "numActivities")) integer(t.get(k))
            for (k in listOf("componentsKnown", "childrenKnown", "childNamesKnown")) require(t.get(k) is Boolean)
            intArray(t.get("childTaskIds"))
            val names = t.getJSONArray("childTaskNames")
            require(names.length() <= 8)
            for (n in 0 until names.length()) require(names.get(n) == JSONObject.NULL || names.get(n) is String)
            for (k in listOf("base", "baseActivity", "topActivity", "realActivity", "origActivity")) if (t.has(k)) nonempty(t, k)
        }
    }

    private fun nonempty(o: JSONObject, k: String) { require(o.get(k) is String && o.getString(k).isNotEmpty()) }
    private fun keys(o: JSONObject, allowed: Set<String>) { require(o.keys().asSequence().all { it in allowed }) }
    private fun integer(v: Any): Int {
        require(v is Int || v is Long)
        val n = (v as Number).toLong(); require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); return n.toInt()
    }
    private fun nonnegativeLong(v: Any) { require((v is Int || v is Long) && (v as Number).toLong() >= 0) }
    private fun intArray(v: Any) {
        val a = v as JSONArray; require(a.length() <= 8192)
        for (i in 0 until a.length()) integer(a.get(i))
    }
    private fun textArray(v: Any) {
        val a = v as JSONArray; require(a.length() <= 8192)
        for (i in 0 until a.length()) require(a.get(i) is String && a.getString(i).length <= 4096)
    }
}

/** Android's JSONTokener accepts comments/single quotes/trailing material: gate strict JSON first. */
internal object StrictOwnerJson {
    fun parse(text: String): JSONObject {
        require(text.length <= 9 * 1024 * 1024)
        Parser(text).parse()
        return JSONObject(text)
    }
    private class Parser(val s: String) {
        var pos = 0
        var nodes = 0
        fun parse() { space(); require(pos < s.length && s[pos] == '{'); value(0); space(); require(pos == s.length) }
        fun space() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        fun value(depth: Int) {
            require(depth <= 32 && ++nodes <= 50000); space(); require(pos < s.length)
            when (s[pos]) {
                '{' -> {
                    pos++; space(); val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do { space(); val key = string(); require(keys.add(key)); space(); require(take(':')); value(depth + 1); space() } while (take(','))
                    require(take('}'))
                }
                '[' -> {
                    pos++; space(); if (take(']')) return
                    do { value(depth + 1); space() } while (take(',')); require(take(']'))
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val begin = pos
                    if (take('-')) require(pos < s.length)
                    if (!take('0')) { require(pos < s.length && s[pos] in '1'..'9'); while (pos < s.length && s[pos].isDigit()) pos++ }
                    if (take('.')) digits()
                    if (pos < s.length && s[pos] in "eE") { pos++; if (pos < s.length && s[pos] in "+-") pos++; digits() }
                    require(pos > begin)
                }
            }
        }
        fun digits() { val begin = pos; while (pos < s.length && s[pos] in '0'..'9') pos++; require(pos > begin) }
        fun string(): String {
            require(take('"')); val out = StringBuilder()
            while (pos < s.length) {
                var c = s[pos++]
                if (c == '"') {
                    val result = out.toString()
                    var i = 0
                    while (i < result.length) {
                        val ch = result[i++]
                        if (ch.isHighSurrogate()) require(i < result.length && result[i++].isLowSurrogate()) else require(!ch.isLowSurrogate())
                    }
                    return result
                }
                require(c >= ' ')
                if (c == '\\') {
                    require(pos < s.length); c = s[pos++]
                    c = when (c) {
                        '"', '\\', '/' -> c
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { require(pos + 4 <= s.length); val hex = s.substring(pos, pos + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); pos += 4; hex.toInt(16).toChar() }
                        else -> error("invalid json")
                    }
                }
                out.append(c)
            }
            error("invalid json")
        }
        fun literal(v: String) { require(s.startsWith(v, pos)); pos += v.length }
        fun take(c: Char): Boolean { if (pos < s.length && s[pos] == c) { pos++; return true }; return false }
    }
}
