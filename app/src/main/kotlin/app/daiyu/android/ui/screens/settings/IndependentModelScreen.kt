package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.pet.PetModelSelection
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Purpose-specific selection reuses configured providers; there is no second connection form. */
@Composable
internal fun IndependentModelScreen(store: IndependentModelSettings, slot: IndependentModelSettings.Slot,
    assistants: List<AssistantProfile>, pets: PetApplicationHost, onBack: () -> Unit,
    source: SettingsModelSource, modifier: Modifier = Modifier) {
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
    fun select(choice: SettingsModelChoice, assistantId: String?) {
        if (busy) return
        picker = false
        pendingAssistant = null
        busy = true
        problem = null
        val images = if (choice.key == selectedKey) record?.imageInput else null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    source.withModel(choice) { settings -> store.save(slot, settings, assistantId, true, images) }
                    source.remember(feature, choice)
                    store.record(slot)
                }
                record = saved
                label = choice.label
                selectedKey = choice.key
                if (slot == IndependentModelSettings.Slot.PET) pets.updateSettings(
                    pets.host.snapshot().pet.state.settings.copy(model = PetModelSelection(
                        checkNotNull(saved.assistantId), saved.providerId, saved.localModelId)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = "模型选择未保存，请重新选择。" }
            finally { busy = false }
        }
    }
    fun disable() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                record = withContext(Dispatchers.IO) { store.disable(slot); store.record(slot) }
                problem = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = "停用未确认，请重试。" }
            finally { busy = false }
        }
    }
    val title = if (slot == IndependentModelSettings.Slot.PET) "说话模型" else "辅助视觉模型"
    MiuixScaffoldPage(title, onBack, modifier = modifier.testTag("independent-model-${slot.key}")) {
        problem?.let { item("independent-model-status") { SettingsNote(it, error = true) } }
        item("independent-model-selection") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsSwitchRow("使用自定义模型", record?.enabled == true, !busy,
                    tag = "independent-model-enabled") { if (it) picker = true else disable() }
                SettingsRow("选择模型", summary = label ?: "选择模型", tag = "independent-model-configure",
                    onClick = if (busy) null else ({ picker = true }))
            }
        }
    }
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
