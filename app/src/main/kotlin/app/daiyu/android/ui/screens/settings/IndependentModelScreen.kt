package app.daiyu.android.ui.screens.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import app.daiyu.android.ui.components.MiuixScaffold
import app.daiyu.android.ui.layout.horizontalCutoutPadding
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

private class IndependentSecretVisibility { var conceal: (() -> Unit)? = null }

/** Explicit independent credentials and persistent registry IDs. No 'copy main model' action. */
@Composable
internal fun IndependentModelScreen(store: IndependentModelSettings, slot: IndependentModelSettings.Slot,
    assistants: List<AssistantProfile>, pets: PetApplicationHost, onBack: () -> Unit,
    modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val secretVisibility = remember(slot) { IndependentSecretVisibility() }
    DisposableEffect(lifecycle, secretVisibility) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) secretVisibility.conceal?.invoke()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            secretVisibility.conceal?.invoke()
            secretVisibility.conceal = null
        }
    }
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
    val form: @Composable (Modifier) -> Unit = { formModifier ->
    AndroidView(modifier = formModifier, factory = { context ->
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
        val key = field("API 密钥（独立加密保存）", settings.apiKey, true).apply {
            tag = "independent-model-secret"; isSaveEnabled = false
        }
        var changingKeyVisibility = false
        var keyVisible = false
        val keyToggle = Button(context).apply { text = "显示密钥"; tag = "independent-model-secret-visibility" }
        fun showKey(visible: Boolean) {
            changingKeyVisibility = true
            try {
                val start = key.selectionStart; val end = key.selectionEnd
                key.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
                if (start >= 0 && end >= 0) key.setSelection(start, end)
                keyVisible = visible; keyToggle.text = if (visible) "隐藏密钥" else "显示密钥"
            } finally { changingKeyVisibility = false }
        }
        keyToggle.setOnClickListener {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showKey(!keyVisible)
        }
        secretVisibility.conceal = { showKey(false) }
        column.addView(keyToggle)
        label("协议（保存即明确确认该协议及凭据）")
        val supported = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)
        val protocol = choices(listOf("请选择支持的协议（此功能仅支持 Responses/Messages）", "Responses", "Messages"), supported.indexOf(settings.protocol) + 1)
        label("Responses 路径")
        val paths = listOf("/v1/responses", "/responses")
        var pathValue = settings.responsesPath
        val path = choices(paths, paths.indexOf(settings.responsesPath).coerceAtLeast(0))
        path.visibility = if (settings.protocol == ProviderProtocol.RESPONSES) android.view.View.VISIBLE else android.view.View.GONE
        label("图片能力声明（未知拒绝图片，不按名称猜测）")
        val images = choices(listOf("未知", "已确认支持图片", "不支持图片"), when(record.imageInput) { true -> 1; false -> 2; null -> 0 })
        images.tag = "independent-model-image-capability"
        // A declaration belongs to the complete exact connection, not the model's display name.
        // Editing even back to the old value requires a fresh explicit image-capability choice.
        val resetCapability = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!changingKeyVisibility) images.setSelection(0)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        listOf(endpoint, model, key).forEach { it.addTextChangedListener(resetCapability) }
        var previousPath = path.selectedItemPosition
        path.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != previousPath) { pathValue = paths[position]; images.setSelection(0) }
                previousPath = position
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        var previousProtocol = protocol.selectedItemPosition
        protocol.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != previousProtocol) {
                    images.setSelection(0); key.setText("")
                    pathValue = if (supported.getOrNull(position - 1) == ProviderProtocol.RESPONSES)
                        paths[path.selectedItemPosition] else ""
                }
                path.visibility = if (supported.getOrNull(position - 1) == ProviderProtocol.RESPONSES) android.view.View.VISIBLE else android.view.View.GONE
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
                    key.text.toString(), false, pathValue, false, selectedProtocol)
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
        if (slot == IndependentModelSettings.Slot.PET) column.addView(Button(context).apply { text = "返回"; setOnClickListener { onBack() } })
        ScrollView(context).apply { tag = "independent-model-${slot.key}"; addView(column) }
    })
    }
    if (slot == IndependentModelSettings.Slot.VISION) {
        MiuixScaffold(title = "辅助视觉模型", onBack = onBack, modifier = modifier) { padding, _, sidePadding ->
            form(Modifier.fillMaxSize().padding(padding).padding(horizontal = sidePadding)
                .horizontalCutoutPadding().navigationBarsPadding().imePadding())
        }
    } else form(modifier)
}
