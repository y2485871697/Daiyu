package app.daiyu.android.domain.pet

const val PET_MAX_SATIETY = 30_000
const val PET_TASK_WINDOW_MS = 5L * 60 * 60 * 1000

enum class PetMood { IDLE, HUNGRY, HAPPY, ANGRY, EATING, SCARED, SAD, THINKING }
enum class PetSatietyBand { STARVING, HUNGRY, PECKISH, FULL, STUFFED }
fun petSatietyBand(value: Int): PetSatietyBand = when {
    value > 20_000 -> PetSatietyBand.STUFFED
    value >= 10_000 -> PetSatietyBand.FULL
    value >= 5_000 -> PetSatietyBand.PECKISH
    value >= 2_000 -> PetSatietyBand.HUNGRY
    else -> PetSatietyBand.STARVING
}
fun petSatietyPercent(value: Int): Int = value.coerceIn(0, PET_MAX_SATIETY) * 100 / PET_MAX_SATIETY

enum class PetFood(val displayName: String, val satiety: Int, val resourceKey: String) {
    GRAIN("一粒米", 1, "rice_grain"), SPOON("一勺米饭", 1_000, "rice_spoon"),
    BOWL("一碗米饭", 3_000, "rice_bowl"), POT("一锅米饭", 10_000, "rice_pot")
}
data class PetModelSelection(val assistantId: String = "", val providerId: String = "", val modelId: String = "") {
    val configured: Boolean get() = assistantId.isNotBlank() && providerId.isNotBlank() && modelId.isNotBlank()
    init { require(listOf(assistantId, providerId, modelId).all { it.length <= 200 }) }
}
data class PetSettings(
    val enabled: Boolean = false, val workReactions: Boolean = true,
    val globalVisible: Boolean = false, val audioEnabled: Boolean = false,
    val scale: Float = 1f, val x: Int = -1, val y: Int = -1,
    val model: PetModelSelection = PetModelSelection()
) { init { require(scale.isFinite() && scale in 0.5f..1.8f) } }
data class PetReaction(val mood: PetMood, val speech: String)
data class PetMemory(val mood: PetMood, val speech: String, val satietySpent: Int,
    val atMillis: Long, val event: PetEvent)
data class PetTask(val title: String, val atMillis: Long)
/** Stable run identity, not title-based deduplication. Survives host recreation and restore. */
data class PetCompletedRun(val conversationId: String, val runId: String)
enum class PetEvent { FEED, WORK_DONE }
enum class PetRequestStatus { PENDING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
/** Provider usage stays nullable. Satiety estimates are not money or provider usage. */
data class PetUsage(val requestId: String, val assistantId: String, val providerId: String,
    val modelId: String, val event: PetEvent, val status: PetRequestStatus,
    val inputTokens: Long? = null, val outputTokens: Long? = null) {
    init { require(inputTokens == null || inputTokens >= 0); require(outputTokens == null || outputTokens >= 0) }
    val owner: String get() = "pet"
}
data class PetState(
    val settings: PetSettings = PetSettings(), val satiety: Int = 5_000,
    val mood: PetMood = PetMood.IDLE, val speech: String = "", val speechVisible: Boolean = false,
    val thinking: Boolean = false, val memories: List<PetMemory> = emptyList(),
    val recentTasks: List<PetTask> = emptyList(), val usage: List<PetUsage> = emptyList(),
    val completedRuns: List<PetCompletedRun> = emptyList()
) {
    init { require(satiety in 0..PET_MAX_SATIETY); require(memories.size <= 20 && recentTasks.size <= 8) }
    fun forRestore(): PetState = copy(mood = PetMood.IDLE, speech = "", speechVisible = false, thinking = false,
        usage = usage.map { if (it.status == PetRequestStatus.PENDING) it.copy(status = PetRequestStatus.INTERRUPTED) else it })
}
data class PetSnapshot(val state: PetState, val error: String? = null, val closed: Boolean = false)
sealed class PetCommandResult {
    object Applied : PetCommandResult()
    data class Rejected(val reason: String) : PetCommandResult()
    data class Failed(val reason: String) : PetCommandResult()
}
/** Atomic whole-state replacement: throw on read corruption or failed durable write. */
interface PetStore { fun load(): PetState; fun save(state: PetState) }
fun interface PetClock { fun nowMillis(): Long }
fun interface PetCancel { fun cancel() }
fun interface PetScheduler { fun after(delayMillis: Long, task: () -> Unit): PetCancel }
/** No chat history, credentials, tools, main-agent controller or fallback selection crosses this port. */
data class PetModelRequest(val requestId: String, val selection: PetModelSelection,
    val event: PetEvent, val prompt: String, val sessionId: String,
    val usageOwner: String = "pet", val maxOutputTokens: Int = 120, val timeoutMillis: Long = 45_000) {
    val toolsAllowed: Boolean get() = false
    val webSearchAllowed: Boolean get() = false
    val reasoningAllowed: Boolean get() = false
}
sealed class PetModelResult {
    data class Completed(val raw: String, val inputTokens: Long? = null, val outputTokens: Long? = null) : PetModelResult() {
        init { require(inputTokens == null || inputTokens >= 0); require(outputTokens == null || outputTokens >= 0) }
    }
    data class Failed(val reason: String) : PetModelResult()
}
/** Completion once; cancellation may still produce a late usage receipt. No automatic retry. */
fun interface PetModelPort { fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel }
fun interface PetVoicePort { fun speak(requestId: String, text: String): PetCancel }
class InMemoryPetStore(initial: PetState = PetState()) : PetStore {
    private var value = initial
    @Synchronized override fun load(): PetState = value
    @Synchronized override fun save(state: PetState) { value = state }
}
