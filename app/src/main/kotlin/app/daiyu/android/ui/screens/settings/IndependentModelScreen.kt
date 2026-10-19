package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.pet.PetCommandResult
import app.daiyu.android.domain.pet.PetModelSelection
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class IndependentModelControls(
    val enabled: Boolean,
    val busy: Boolean,
    val label: String?,
    val problem: String?,
    val onEnabledChange: (Boolean) -> Unit,
    val onChoose: () -> Unit,
)

/** One selection owner shared by the standalone visual page and the pet's original five rows. */
@Composable
internal fun IndependentModelSelection(store: IndependentModelSettings, slot: IndependentModelSettings.Slot,
    assistants: List<AssistantProfile>, pets: PetApplicationHost, source: SettingsModelSource,
    content: @Composable (IndependentModelControls) -> Unit) {
    val scope = rememberCoroutineScope()
    val feature = "independent-${slot.key}"
    var record by remember(store, slot) { mutableStateOf<IndependentModelSettings.Record?>(null) }
    var busy by remember(store, slot) { mutableStateOf(true) }
    var problem by remember(store, slot) { mutableStateOf<String?>(null) }
    var label by remember(store, slot) { mutableStateOf(source.label(feature)) }
    var selectedKey by remember(store, slot) { mutableStateOf(source.key(feature)) }
    var picker by remember(store, slot) { mutableStateOf(false) }
    var pendingAssistant by remember(store, slot) { mutableStateOf<SettingsModelChoice?>(null) }
    LaunchedEffect(store, slot) {
        try { record = withContext(Dispatchers.IO) { store.record(slot) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem = "配置读取失败，请重新进入。" }
        finally { busy = false }
    }
    fun applyPetModel(selection: PetModelSelection) {
        when (val result = pets.updateSettings(pets.host.snapshot().pet.state.settings.copy(model = selection))) {
            PetCommandResult.Applied -> Unit
            is PetCommandResult.Rejected -> error(result.reason)
            is PetCommandResult.Failed -> error(result.reason)
        }
    }
    suspend fun refreshAfterFailure() {
        record = withContext(NonCancellable + Dispatchers.IO) { runCatching { store.record(slot) }.getOrNull() }
    }
    fun select(choice: SettingsModelChoice, assistantId: String?) {
        if (busy) return
        picker = false
        pendingAssistant = null
        busy = true
        problem = null
        val images = if (choice.key == selectedKey) record?.imageInput else null
        scope.launch {
            try {
                // Once committed, finish the short host synchronization even if the page closes.
                withContext(NonCancellable) {
                    val saved = withContext(Dispatchers.IO) {
                        source.withModel(choice) { settings -> store.save(slot, settings, assistantId, true, images) }
                        store.record(slot)
                    }
                    if (slot == IndependentModelSettings.Slot.PET) applyPetModel(PetModelSelection(
                        checkNotNull(saved.assistantId), saved.providerId, saved.localModelId))
                    withContext(Dispatchers.IO) { source.remember(feature, choice) }
                    record = saved
                    label = choice.label
                    selectedKey = choice.key
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { refreshAfterFailure(); problem = "模型配置更新未完成，请重新选择。" }
            finally { busy = false }
        }
    }
    fun disable() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    record = withContext(Dispatchers.IO) { store.disable(slot); store.record(slot) }
                    if (slot == IndependentModelSettings.Slot.PET) applyPetModel(PetModelSelection())
                    problem = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { refreshAfterFailure(); problem = "停用未确认，请重试。" }
            finally { busy = false }
        }
    }
    content(IndependentModelControls(record?.enabled == true, busy, label, problem,
        onEnabledChange = { if (!busy) { if (it) picker = true else disable() } },
        onChoose = { if (!busy) picker = true }))
    val title = if (slot == IndependentModelSettings.Slot.PET) "说话模型" else "辅助视觉模型"
    if (picker) SettingsModelPicker(title, source, selectedKey, onSelect = { choice ->
        if (!busy) {
            val assistant = record?.assistantId?.takeIf { id -> assistants.any { it.id == id } }
            if (slot == IndependentModelSettings.Slot.PET && assistant == null) {
                picker = false
                if (assistants.isEmpty()) problem = "请先创建助手。" else pendingAssistant = choice
            } else select(choice, assistant)
        }
    }, onDismiss = { picker = false })
    pendingAssistant?.let { choice ->
        DaiyuChoiceSheet("选择助手", assistants.map { it.name }, onSelect = { index ->
            select(choice, assistants[index].id)
        }, onDismiss = { pendingAssistant = null })
    }
}


@Composable
internal fun IndependentModelScreen(store: IndependentModelSettings, slot: IndependentModelSettings.Slot,
    assistants: List<AssistantProfile>, pets: PetApplicationHost, onBack: () -> Unit,
    source: SettingsModelSource, modifier: Modifier = Modifier) {
    IndependentModelSelection(store, slot, assistants, pets, source) { controls ->
        val title = if (slot == IndependentModelSettings.Slot.PET) "说话模型" else "辅助视觉模型"
        MiuixScaffoldPage(title, onBack, modifier = modifier.testTag("independent-model-${slot.key}")) {
            controls.problem?.let { item("independent-model-status") { SettingsNote(it, error = true) } }
            item("independent-model-selection") {
                SettingsCard(Modifier.padding(top = 12.dp)) {
                    SettingsSwitchRow("使用自定义模型", controls.enabled, !controls.busy,
                        tag = "independent-model-enabled", onCheckedChange = controls.onEnabledChange)
                    SettingsRow("选择模型", summary = controls.label ?: "选择模型", tag = "independent-model-configure",
                        onClick = if (controls.busy) null else controls.onChoose)
                }
            }
        }
    }
}
