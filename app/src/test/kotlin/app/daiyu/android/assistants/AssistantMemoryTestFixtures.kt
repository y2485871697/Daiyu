package app.daiyu.android.assistants

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

internal class MemoryTestStore(vararg values: AssistantProfile) : AssistantStore {
    override val assistants = MutableStateFlow(values.toList())
    override val selectedId: MutableStateFlow<String> get() = error("must not consult global assistant")
    var beforeCas: (() -> Unit)? = null
    @Synchronized override fun read(id: String) = assistants.value.firstOrNull { it.id == id }
    @Synchronized override fun save(profile: AssistantProfile): AssistantProfile {
        assistants.value = assistants.value.filterNot { it.id == profile.id } + profile
        return profile
    }
    override fun compareAndSet(expected: AssistantProfile, updated: AssistantProfile): AssistantProfile {
        beforeCas?.invoke()
        return synchronized(this) {
            if (read(expected.id) != expected) throw StaleAssistantProfileException()
            require(expected.id == updated.id)
            save(updated)
        }
    }
    @Synchronized override fun delete(id: String) { assistants.value = assistants.value.filterNot { it.id == id } }
    override fun select(id: String) = error("not used")
}

internal class MemoryTestAssets(vararg values: Pair<String, String>) : AssistantAssets {
    private val snapshots = values.associate { it.first to AssistantMemorySnapshot(it.second, "${it.first}-v1") }.toMutableMap()
    val reads = mutableListOf<String>()
    var afterRead: (() -> Unit)? = null
    @Synchronized override fun memory(id: String): AssistantMemorySnapshot {
        reads += id
        val result = snapshots.getValue(id)
        afterRead?.invoke()
        return result
    }
    @Synchronized fun replace(id: String, content: String, revision: String) { snapshots[id] = AssistantMemorySnapshot(content, revision) }
    override fun saveMemory(id: String, content: String, revision: String): AssistantMemorySnapshot = synchronized(this) {
        check(snapshots.getValue(id).revision == revision)
        AssistantMemorySnapshot(content, "$revision-next").also { snapshots[id] = it }
    }
    override fun avatarBitmap(fileName: String?): Bitmap? = null
    override fun saveAvatar(id: String, bitmap: Bitmap): String = error("unused")
    override fun copyAvatar(fileName: String, toId: String): String? = error("unused")
    override fun deleteAvatar(fileName: String?) = error("unused")
    override fun copyMemory(fromId: String, toId: String) = error("no model writes")
    override fun deleteMemory(id: String) = error("no model writes")
}
