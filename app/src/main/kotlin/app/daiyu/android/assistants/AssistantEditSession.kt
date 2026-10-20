package app.daiyu.android.assistants

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class StaleAssistantProfileException : IllegalStateException("助手已被其他操作修改或删除，草稿已保留；请重新打开并合并。")
class ClosedAssistantEditorException : IllegalStateException("助手编辑页面已失效，草稿未提交")

/** One mounted page's authority, not a global active-assistant reference.
 * Call operations from the page's Main scope: Mutex admission then IO preserves click order.
 * Closing revokes queued work immediately. An already admitted CAS may finish, but its late
 * receipt must not navigate or mutate another page. No retry/rebase over external edits.
 */
class AssistantEditSession(private val store: AssistantStore, original: AssistantProfile) {
    private val active = AtomicBoolean(true)
    private val writes = Mutex()
    private var expected = original.copy(enabledSkillIds = original.enabledSkillIds.toList())
    val isActive: Boolean get() = active.get()
    fun close() { active.set(false) }
    fun requireActive() { if (!isActive) throw ClosedAssistantEditorException() }

    suspend fun toggles(memory: Boolean, skills: Set<String>): AssistantProfile = writes.withLock {
        requireActive()
        withContext(Dispatchers.IO) {
            requireActive()
            store.compareAndSet(expected, expected.copy(memoryEnabled = memory, enabledSkillIds = skills.toList()))
        }.also { expected = it }
    }

    suspend fun avatar(writeFile: () -> String): AssistantProfile = writes.withLock {
        requireActive()
        withContext(Dispatchers.IO) {
            requireActive()
            // Check before touching the file as well as atomically before publishing the profile.
            if (store.read(expected.id) != expected) throw StaleAssistantProfileException()
            val file = writeFile()
            requireActive()
            store.compareAndSet(expected, expected.copy(avatarFileName = file))
        }.also { expected = it }
    }

    /** Profile CAS comes first, so a stale page cannot write memory before discovering conflict.
     * Profile and file are separate resources; a later memory conflict is reported, not rolled
     * back over someone else's profile. The caller retains all drafts on any failure.
     */
    suspend fun save(name: String, prompt: String, memory: Boolean, skills: Set<String>,
        description: String = expected.description,
        saveMemory: () -> AssistantMemorySnapshot?): AssistantEditReceipt = writes.withLock {
        requireActive()
        withContext(Dispatchers.IO) {
            requireActive()
            expected = store.compareAndSet(expected, expected.copy(name = name, prompt = prompt,
                memoryEnabled = memory, enabledSkillIds = skills.toList(), description = description))
            requireActive()
            AssistantEditReceipt(expected, saveMemory())
        }
    }
}

data class AssistantEditReceipt(val profile: AssistantProfile, val memory: AssistantMemorySnapshot?)

/** Initial read may supply the base revision, never replace text typed while it was in flight. */
class AssistantMemoryDraft {
    var content: String = ""; private set
    var saved: String = ""; private set
    var revision: String? = null; private set
    private var edits = 0L
    fun beginRead(): Long = edits
    fun edit(value: String) { content = value; edits++ }
    fun completeRead(ticket: Long, snapshot: AssistantMemorySnapshot) {
        saved = snapshot.content
        revision = snapshot.revision
        if (ticket == edits) content = snapshot.content
    }
}
