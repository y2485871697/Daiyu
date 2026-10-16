package app.daiyu.android.assistants

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small, process-local assistant owner; no AppState, runtime or remote-service dependency. */
interface AssistantStore {
    val assistants: StateFlow<List<AssistantProfile>>
    val selectedId: StateFlow<String>
    fun list(): List<AssistantProfile> = assistants.value
    fun read(id: String): AssistantProfile? = list().firstOrNull { it.id == id }
    fun save(profile: AssistantProfile): AssistantProfile
    fun delete(id: String)
    fun select(id: String)
}

class SharedPreferencesAssistantStore(private val preferences: SharedPreferences) : AssistantStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("daiyu-assistants", Context.MODE_PRIVATE))

    private val initial = load()
    private val profiles = MutableStateFlow(initial.first)
    private val active = MutableStateFlow(initial.second)
    override val assistants = profiles.asStateFlow()
    override val selectedId = active.asStateFlow()

    init {
        if (preferences.getString(KEY, null) == null) persist(profiles.value, active.value)
    }

    @Synchronized
    override fun save(profile: AssistantProfile): AssistantProfile {
        AssistantStorage.id(profile.id)
        val next = profile.copy(name = profile.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME })
        val current = list()
        val updated = if (current.any { it.id == next.id }) current.map { if (it.id == next.id) next else it } else current + next
        persist(updated, active.value)
        return next
    }

    @Synchronized
    override fun delete(id: String) {
        val remaining = list().filterNot { it.id == id }
        require(remaining.isNotEmpty()) { "必须保留至少一个助手" }
        persist(remaining, active.value.takeIf { it != id } ?: remaining.first().id)
    }

    @Synchronized
    override fun select(id: String) {
        require(read(id) != null) { "助手不存在" }
        persist(list(), id)
    }

    private fun persist(items: List<AssistantProfile>, selected: String) {
        val array = JSONArray()
        items.forEach { profile ->
            array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("prompt", profile.prompt)
                .put("avatarFileName", profile.avatarFileName ?: JSONObject.NULL).put("createdAt", profile.createdAt)
                .put("memoryEnabled", profile.memoryEnabled).put("enabledSkillIds", JSONArray(profile.enabledSkillIds)))
        }
        check(preferences.edit().putString(KEY, JSONObject().put("profiles", array).put("activeId", selected).toString()).commit()) {
            "保存失败，草稿已保留"
        }
        profiles.value = items
        active.value = selected
    }

    private fun load(): Pair<List<AssistantProfile>, String> {
        val raw = preferences.getString(KEY, null)
        if (raw == null) return listOf(defaultProfile()) to AssistantPrompt.DEFAULT_ID
        val snapshot = LegacyAssistantCodec.decode(raw)
        return snapshot.profiles to snapshot.selectedId
    }

    companion object {
        private const val KEY = "assistant-index-v1"
        fun defaultProfile() = AssistantProfile(AssistantPrompt.DEFAULT_ID, AssistantPrompt.DEFAULT_NAME, "", createdAt = System.currentTimeMillis())
    }
}

fun AssistantStore.create(name: String = "新助手", prompt: String = ""): AssistantProfile =
    save(AssistantProfile(UUID.randomUUID().toString(), name.trim().ifBlank { "新助手" }, prompt, createdAt = System.currentTimeMillis()))

fun AssistantStore.duplicate(id: String): AssistantProfile {
    val source = requireNotNull(read(id)) { "助手不存在" }
    return save(source.copy(id = UUID.randomUUID().toString(), name = source.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME } + " 副本",
        avatarFileName = null, createdAt = System.currentTimeMillis()))
}
