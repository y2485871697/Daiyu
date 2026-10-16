package app.daiyu.android.pet

import android.content.Context
import app.daiyu.android.domain.pet.*
import org.json.JSONArray
import org.json.JSONObject

/** Separate private preferences; synchronous commit checked. No main-agent settings/history. */
class AndroidPetStore(context: Context) : PetStore {
    private val prefs = context.getSharedPreferences("daiyu_pet_v1", Context.MODE_PRIVATE)
    @Synchronized override fun load(): PetState {
        val raw = prefs.getString("state", null) ?: return PetState()
        return PetStateJson.decode(raw)
    }
    @Synchronized override fun save(state: PetState) {
        // Finish encoding and capacity validation before touching the previous snapshot.
        val raw = PetStateJson.encode(state)
        check(prefs.edit().putString("state", raw).commit()) { "Pet storage commit failed" }
    }
}
/** Versioned whole-state codec. Corruption is an error, never an empty successful load. */
object PetStateJson {
    private const val MAX_STATE_CHARS = 8_000_000

    fun encode(state: PetState): String {
        val settings = state.settings
        val root = JSONObject().put("version", 2).put("settings", JSONObject()
            .put("enabled", settings.enabled).put("workReactions", settings.workReactions)
            .put("globalVisible", settings.globalVisible).put("audioEnabled", settings.audioEnabled)
            .put("scale", settings.scale.toDouble()).put("x", settings.x).put("y", settings.y)
            .put("assistant", settings.model.assistantId).put("provider", settings.model.providerId).put("model", settings.model.modelId))
            .put("satiety", state.satiety).put("mood", state.mood.name).put("speech", state.speech)
            .put("speechVisible", state.speechVisible).put("thinking", state.thinking)
        root.put("memories", JSONArray().also { a -> state.memories.forEach { m -> a.put(JSONObject()
            .put("mood", m.mood.name).put("speech", m.speech).put("spent", m.satietySpent)
            .put("at", m.atMillis).put("event", m.event.name)) } })
        root.put("tasks", JSONArray().also { a -> state.recentTasks.forEach { t ->
            a.put(JSONObject().put("title", t.title).put("at", t.atMillis)) } })
        root.put("usage", JSONArray().also { a -> state.usage.forEach { u -> a.put(JSONObject()
            .put("id", u.requestId).put("assistant", u.assistantId).put("provider", u.providerId).put("model", u.modelId)
            .put("event", u.event.name).put("status", u.status.name)
            .put("input", u.inputTokens ?: JSONObject.NULL).put("output", u.outputTokens ?: JSONObject.NULL)) } })
        root.put("completedRuns", JSONArray().also { a -> state.completedRuns.forEach {
            a.put(JSONObject().put("conversation", it.conversationId).put("run", it.runId))
        } })
        // Count the serialized characters (including JSON escaping), without pruning history.
        return root.toString().also { require(it.length <= MAX_STATE_CHARS) { "Pet state too large" } }
    }
    fun decode(raw: String): PetState {
        require(raw.length <= MAX_STATE_CHARS) { "Pet state too large" }
        val root = JSONObject(raw)
        val version = root.getInt("version")
        require(version in 1..2) { "Unsupported pet state version" }
        val s = root.getJSONObject("settings")
        val settings = PetSettings(s.getBoolean("enabled"), s.getBoolean("workReactions"),
            s.getBoolean("globalVisible"), s.getBoolean("audioEnabled"), s.getDouble("scale").toFloat(),
            s.getInt("x"), s.getInt("y"), PetModelSelection(s.getString("assistant"), s.getString("provider"), s.getString("model")))
        val memories = root.getJSONArray("memories").objects().map { m -> PetMemory(
            PetMood.valueOf(m.getString("mood")), m.getString("speech"), m.getInt("spent"),
            m.getLong("at"), PetEvent.valueOf(m.getString("event"))) }
        val tasks = root.getJSONArray("tasks").objects().map { PetTask(it.getString("title"), it.getLong("at")) }
        val usage = root.getJSONArray("usage").objects().map { u -> PetUsage(u.getString("id"),
            u.getString("assistant"), u.getString("provider"), u.getString("model"), PetEvent.valueOf(u.getString("event")),
            PetRequestStatus.valueOf(u.getString("status")), if (u.isNull("input")) null else u.getLong("input"),
            if (u.isNull("output")) null else u.getLong("output")) }
        val completed = if (version == 1) emptyList() else root.getJSONArray("completedRuns").objects().map {
            PetCompletedRun(it.getString("conversation"), it.getString("run"))
        }
        return PetState(settings, root.getInt("satiety"), PetMood.valueOf(root.getString("mood")),
            root.getString("speech"), root.getBoolean("speechVisible"), root.getBoolean("thinking"), memories, tasks, usage, completed)
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
