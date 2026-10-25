package app.daiyu.android.storage

import android.content.Context
import app.daiyu.android.assistants.AssistantStore
import app.daiyu.android.domain.pet.PetModelSelection
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.auxiliary.AuxiliaryModelCandidate
import app.daiyu.android.model.auxiliary.AuxiliaryModelSelection
import app.daiyu.android.pet.ResolvedPetModel
import java.util.UUID

/** Separate encrypted credential namespaces, never aliases for the main chat settings.
 * The binding record is committed last. No database schema/backup migration is involved.
 * Unknown capability stays unknown; only an explicit declaration writes true or false. */
internal class IndependentModelSettings(context: Context) {
    enum class Slot(val key: String) { PET("pet"), VISION("vision") }
    data class Record(val providerId: String, val localModelId: String, val assistantId: String?,
        val enabled: Boolean, val imageInput: Boolean?, val revision: Long)
    data class Frozen(val record: Record, val provider: ProviderSnapshot)
    private val metadata = context.getSharedPreferences("independent-model-bindings", Context.MODE_PRIVATE)
    private val stores = Slot.values().associateWith {
        ModelSettingsStore(context, "independent-${it.key}-key", "independent-${it.key}-model")
    }
    private val lock = Any()
    private val failedBindings = mutableSetOf<Slot>()
    private val editLock = Any()
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var changing = false
    var onChanging: (() -> Unit)? = null

    fun record(slot: Slot): Record = synchronized(lock) {
        val key = slot.key
        Record(metadata.getString("$key.provider", "").orEmpty(),
            metadata.getString("$key.model", "").orEmpty(), metadata.getString("$key.assistant", null),
            metadata.getBoolean("$key.enabled", false),
            if (metadata.contains("$key.images")) metadata.getBoolean("$key.images", false) else null,
            metadata.getLong("$key.revision", 0))
    }
    fun editing(slot: Slot): EditingSettings = synchronized(lock) { stores.getValue(slot).loadForEditing() }
    fun generation(): Long = epoch.get()
    fun isCurrent(generation: Long): Boolean = !changing && epoch.get() == generation
    fun freeze(slot: Slot): Frozen = synchronized(lock) {
        check(!changing && slot !in failedBindings) { "独立模型配置正在变更或保存未确认；请重新保存" }
        val record = record(slot)
        check(record.enabled && record.providerId.isNotBlank() && record.localModelId.isNotBlank()) {
            "独立模型未启用或未配置"
        }
        val settings = stores.getValue(slot).load().also { it.validate() }
        Frozen(record, ProviderSnapshot.capture(settings.copy(usageIdentity = app.daiyu.android.model.UsageModelIdentity(record.providerId, if (slot == Slot.VISION) "辅助视觉" else "女仆模型")), settings.protocol, imageInputSupported = record.imageInput))
    }
    fun visionSelection(): AuxiliaryModelSelection = synchronized(lock) {
        val record = record(Slot.VISION)
        // An unconfirmed write is an unavailable auxiliary route, never an implicit main fallback.
        AuxiliaryModelSelection(record.enabled || Slot.VISION in failedBindings, record.providerId, record.localModelId)
    }
    fun visionCandidate(frozen: Frozen): AuxiliaryModelCandidate = frozen.let {
        AuxiliaryModelCandidate(it.record.providerId, it.record.localModelId, it.provider,
            providerEnabled = it.record.enabled, modelEnabled = it.record.enabled)
    }
    fun resolvePet(selection: PetModelSelection, assistants: AssistantStore): ResolvedPetModel? = runCatching {
        val generation = generation()
        val frozen = freeze(Slot.PET)
        val r = frozen.record
        check(assistants.read(selection.assistantId) != null)
        check(r.assistantId == selection.assistantId && r.providerId == selection.providerId &&
            r.localModelId == selection.modelId)
        check(isCurrent(generation))
        ResolvedPetModel(selection.copy(), frozen.provider.asModelSettings(), isCurrent = { isCurrent(generation) })
    }.getOrNull()

    private fun failClosed(slot: Slot) = synchronized(lock) {
        failedBindings.add(slot)
        // SharedPreferences false/throw may have updated memory already; never retain an enabled
        // unconfirmed mixed binding. Keep the in-process fence even if compensation also fails.
        runCatching { metadata.edit().putBoolean("${slot.key}.enabled", false).commit() }
        Unit
    }

    /** Revocation must remain possible even when the old credential cannot be decrypted. */
    fun disable(slot: Slot) = synchronized(editLock) {
        changing = true
        epoch.incrementAndGet()
        try {
            onChanging?.invoke()
            synchronized(lock) {
                val old = record(slot)
                check(metadata.edit().putBoolean("${slot.key}.enabled", false)
                    .putLong("${slot.key}.revision", old.revision + 1).commit())
                failedBindings.remove(slot)
            }
        } catch (failure: Exception) {
            failClosed(slot)
            throw failure
        } finally {
            epoch.incrementAndGet()
            changing = false
        }
    }

    /** A failed edit revokes old in-flight authority as well. Never resurrect it by editing back. */
    fun save(slot: Slot, settings: ModelSettings, assistantId: String?, enabled: Boolean, images: Boolean?) = synchronized(editLock) {
        changing = true
        epoch.incrementAndGet()
        try {
        onChanging?.invoke()
        // Validation failure is still an edit attempt: revoke old authority and fail closed.
        settings.validate()
        synchronized(lock) {
            val old = record(slot)
            // Disable the binding before touching credentials: partial save cannot expose a mixed record.
            check(metadata.edit().putBoolean("${slot.key}.enabled", false).commit())
            stores.getValue(slot).save(settings, protocolChangeAuthorized = true, requestOptionsReplacementAuthorized = true)
            val edit = metadata.edit()
                .putString("${slot.key}.provider", old.providerId.ifBlank { UUID.randomUUID().toString() })
                .putString("${slot.key}.model", old.localModelId.ifBlank { UUID.randomUUID().toString() })
                .putString("${slot.key}.assistant", assistantId)
                .putBoolean("${slot.key}.enabled", enabled)
                .putLong("${slot.key}.revision", old.revision + 1)
            if (images == null) edit.remove("${slot.key}.images") else edit.putBoolean("${slot.key}.images", images)
            check(edit.commit()) { "独立模型绑定保存失败" }
            failedBindings.remove(slot)
        }
        } catch (failure: Exception) {
            failClosed(slot)
            throw failure
        } finally {
            epoch.incrementAndGet()
            changing = false
        }
    }
}
