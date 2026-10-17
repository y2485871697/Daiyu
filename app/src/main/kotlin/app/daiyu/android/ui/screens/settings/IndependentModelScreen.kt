package app.daiyu.android.ui.screens.settings

import android.text.InputType
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.pet.PetModelSelection
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit independent credentials and persistent registry IDs. No 'copy main model' action. */
@Composable
internal fun IndependentModelScreen(store: IndependentModelSettings, slot: IndependentModelSettings.Slot,
    assistants: List<AssistantProfile>, pets: PetApplicationHost, onBack: () -> Unit,
    modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var loaded by remember(slot) { mutableStateOf<Pair<IndependentModelSettings.Record, ModelSettings>?>(null) }
    var problem by remember(slot) { mutableStateOf<String?>(null) }
    var saving by remember(slot) { mutableStateOf(false) }
    LaunchedEffect(slot) {
        try { loaded = withContext(Dispatchers.IO) { store.record(slot) to store.editing(slot).settings } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { problem = "独立配置读取失败" }
    }
    val value = loaded
    if (value == null) {
        app.daiyu.android.ui.components.MiuixScaffoldPage(
            title = if (slot == IndependentModelSettings.Slot.PET) "桌宠独立模型" else "辅助视觉模型",
            onBack = onBack, modifier = modifier,
        ) {
            item("status") { SettingsNote(problem ?: "读取独立配置…") }
            if (problem != null) item("disable") {
                top.yukonga.miuix.kmp.basic.TextButton(text = "停用独立配置", enabled = !saving,
                    modifier = Modifier.testTag("independent-model-disable"), onClick = {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store.disable(slot) }
                                    problem = "已停用并撤销旧请求授权"
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { problem = "停用未确认；请重试" }
                                finally { saving = false }
                            }
                        }
                    })
            }
        }
        return
    }
    AndroidView(modifier = modifier, factory = { context ->
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun label(text: String) { column.addView(TextView(context).apply { this.text = text }) }
        fun field(hint: String, value: String, secret: Boolean = false): EditText = EditText(context).also {
            it.hint = hint; it.setText(value); it.isSingleLine = true
            if (secret) it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            column.addView(it)
        }
        fun choices(items: List<String>, selected: Int): Spinner = Spinner(context).also {
            it.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            it.setSelection(selected.coerceAtLeast(0)); column.addView(it)
        }
        val (record, settings) = value
        label(if (slot == IndependentModelSettings.Slot.PET) "桌宠独立模型（不使用主聊天模型）" else "辅助视觉独立模型（不使用主聊天模型）")
        label("服务商记录：${record.providerId.ifBlank { "保存时新建" }}\n模型记录：${record.localModelId.ifBlank { "保存时新建" }}")
        val enabled = CheckBox(context).apply { text = "启用此独立配置"; isChecked = record.enabled }
        column.addView(enabled)
        val endpoint = field("HTTPS 服务商地址", settings.endpoint)
        val model = field("模型 API 名（不是记录 ID）", settings.model)
        val key = field("API 密钥（独立加密保存）", settings.apiKey, true)
        label("协议（保存即明确确认该协议及凭据）")
        val supported = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)
        val protocol = choices(listOf("请选择支持的协议（此功能仅支持 Responses/Messages）", "Responses", "Messages"), supported.indexOf(settings.protocol) + 1)
        val path = field("Responses 自定义路径（Messages 请留空）", settings.responsesPath)
        label("图片能力声明（未知拒绝图片，不按名称猜测）")
        val images = choices(listOf("未知", "已确认支持图片", "不支持图片"), when(record.imageInput) { true -> 1; false -> 2; null -> 0 })
        // A declaration belongs to the complete exact connection, not the model's display name.
        // Editing even back to the old value requires a fresh explicit image-capability choice.
        val resetCapability = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { images.setSelection(0) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        listOf(endpoint, model, key, path).forEach { it.addTextChangedListener(resetCapability) }
        var previousProtocol = protocol.selectedItemPosition
        protocol.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != previousProtocol) { images.setSelection(0); key.setText(""); if (supported.getOrNull(position - 1) != ProviderProtocol.RESPONSES) path.setText("") }
                previousProtocol = position
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { images.setSelection(0) }
        }
        val assistant = if (slot == IndependentModelSettings.Slot.PET) {
            label("绑定真实助手")
            choices(listOf("请选择助手") + assistants.map { "${it.name} (${it.id})" },
                assistants.indexOfFirst { it.id == record.assistantId } + 1)
        } else null
        val status = TextView(context); column.addView(status)
        column.addView(Button(context).apply {
            text = "保存独立配置并撤销旧请求授权"
            setOnClickListener {
                if (saving) return@setOnClickListener
                val selectedAssistant = assistant?.selectedItemPosition?.let { assistants.getOrNull(it - 1)?.id }
                if (slot == IndependentModelSettings.Slot.PET && selectedAssistant == null) {
                    status.text = "请显式选择一个存在的助手"; return@setOnClickListener
                }
                val selectedProtocol = supported.getOrNull(protocol.selectedItemPosition - 1)
                if (selectedProtocol == null) { status.text = "此独立功能仅支持 Responses/Messages；请选择支持的协议并重新输入密钥"; return@setOnClickListener }
                val replacement = ModelSettings(endpoint.text.toString().trim(), model.text.toString().trim(),
                    key.text.toString(), false, path.text.toString().trim(), false, selectedProtocol)
                val allow = enabled.isChecked
                val capability = when(images.selectedItemPosition) { 1 -> true; 2 -> false; else -> null }
                saving = true; isEnabled = false
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) {
                        store.save(slot, replacement, selectedAssistant, allow, capability)
                        store.record(slot)
                    } }
                    result.onSuccess { saved ->
                        if (slot == IndependentModelSettings.Slot.PET) {
                            pets.updateSettings(pets.host.snapshot().pet.state.settings.copy(model = PetModelSelection(
                                checkNotNull(saved.assistantId), saved.providerId, saved.localModelId)))
                        }
                        status.text = "已保存独立绑定；不会自动发起模型请求"
                    }.onFailure { status.text = "未保存：${it.message ?: "配置无效"}" }
                    saving = false; isEnabled = true
                }
            }
        })
        column.addView(Button(context).apply {
            text = "停用独立配置（无需读取旧密钥）"
            setOnClickListener {
                if (saving) return@setOnClickListener
                saving = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.disable(slot) } }
                        .onSuccess { enabled.isChecked = false; status.text = "已停用并撤销旧请求授权" }
                        .onFailure { status.text = "停用未确认；请重试" }
                    saving = false
                }
            }
        })
        column.addView(Button(context).apply { text = "返回"; setOnClickListener { onBack() } })
        ScrollView(context).apply { tag = "independent-model-${slot.key}"; addView(column) }
    })
}
