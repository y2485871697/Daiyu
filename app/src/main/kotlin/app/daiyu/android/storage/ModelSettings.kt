package app.daiyu.android.storage

import app.daiyu.android.model.catalog.*
import java.io.File
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.daiyu.android.domain.responsesEndpoint
import app.daiyu.android.domain.messagesEndpoint
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.UnsupportedProviderException
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.ProviderSnapshot
import java.security.KeyStore
import java.util.UUID
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URI
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelSettings(val endpoint: String, val model: String, val apiKey: String, val batteryEnabled: Boolean, val responsesPath: String = "", val deviceStatusEnabled: Boolean = false, val protocol: ProviderProtocol = ProviderProtocol.RESPONSES, val requestOptions: ProviderRequestOptions = ProviderRequestOptions()) {
    fun enabledTools(): Set<ReadOnlyTool> = buildSet {
        if (batteryEnabled) add(ReadOnlyTool.BATTERY)
        if (deviceStatusEnabled) add(ReadOnlyTool.DEVICE_STATUS)
    }
    fun requestEndpoint(): String = when (protocol) {
        ProviderProtocol.RESPONSES -> responsesEndpoint(endpoint, responsesPath)
        ProviderProtocol.MESSAGES -> {
            require(responsesPath.isEmpty()) { "Messages 不使用 Responses 路径" }
            messagesEndpoint(endpoint)
        }
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> app.daiyu.android.model.NativeCompletionRequest.endpoint(this)
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
    }
    fun requestSnapshot(): ProviderSnapshot = ProviderSnapshot.capture(this)
    override fun toString(): String =
        "ModelSettings(protocol=${protocol.wireName}, endpoint=$endpoint, model=$model, apiKey=<redacted>, batteryEnabled=$batteryEnabled, " +
            "responsesPath=$responsesPath, deviceStatusEnabled=$deviceStatusEnabled, requestOptions=$requestOptions)"
    fun validate() {
        requestEndpoint()
        requestOptions.validate(protocol)
        require(model.isNotBlank() && model.length<=200 && apiKey.isNotBlank() && apiKey.length<=8192) {"请填写模型和密钥"}
        require(!apiKey.contains('\n') && !apiKey.contains('\r')) {"密钥含非法换行"}
    }
}
class CredentialUnavailableException:IllegalStateException("已保存密钥不可用；请重新输入密钥")
data class EditingSettings(val settings:ModelSettings,val credentialUnavailable:Boolean,val requestOptionsUnavailable:Boolean = false)

/** A detached preview, not an authorization. Snapshots preserve absent versus explicitly empty fields. */
class NonSecretRestorePlan internal constructor(
    private val storeIdentity: Any,
    before: Map<String, Any?>,
    after: Map<String, Any?>,
    val isNoOp: Boolean,
    val credentialRebindRequired: Boolean
) {
    internal fun belongsTo(identity: Any): Boolean = storeIdentity === identity
    val before: Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap(before))
    val after: Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap(after))
}
class ModelSettingsStore internal constructor(
    context: Context,
    private val keyAliasPrefix: String,
    private val namespace: String = "model-settings",
) {
    constructor(context:Context):this(context,"model-api-key")
    init {
        require(namespace.matches(Regex("[a-zA-Z0-9._-]+"))) { "模型设置 namespace 无效" }
        require(keyAliasPrefix.isNotBlank())
    }
    private val preferences=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
    private val restoreIdentity = Any()
    internal val modelCatalog by lazy { FileSavedModelCatalogPersistence(
        File(context.filesDir, if (namespace == "model-settings") "model-catalog/primary.bin"
            else "model-catalog/$namespace.bin"), CatalogModelParametersCodec) }
    internal fun modelCatalogSnapshot(): SavedModelCatalogSnapshot<CatalogModelParameters> =
        modelCatalog.registerFrom {
            val editing = synchronized(lock) { editingWithoutCredential() }
            check(!editing.requestOptionsUnavailable) { "请求策略已损坏；请先明确修复再导入模型" }
            val current = editing.settings
            check(current.endpoint.isNotBlank() && current.model.isNotBlank()) { "请先保存服务商配置" }
            check(current.protocol != ProviderProtocol.UNKNOWN) { "请先修复模型协议" }
            listOf(CatalogModelParameters.fromCurrent(current))
        }
    /** Read-only, network/credential-free. Does not resurrect a deleted provider or migrate defaults. */
    internal fun localModelCatalogSnapshot(): SavedModelCatalogSnapshot<CatalogModelParameters>? = modelCatalog.current()

    /** Directory browsing needs only the protocol, never key decryption or execution readiness. */
    internal fun localModelCatalogProtocol(): ProviderProtocol = synchronized(lock) {
        readProtocol().also { check(it != ProviderProtocol.UNKNOWN) { "模型协议无效；请到模型设置重新选择" } }
    }

    /** Worker-only strict path: UI must retain this ticket from the displayed local snapshot. */
    internal fun selectCatalogModel(expected: ModelSettings, selection: CatalogModelSelection<CatalogModelParameters>): Unit =
        modelCatalog.consumeSelection(selection) { record -> synchronized(lock) {
            val current = load()
            check(current == expected) { "模型配置已变更，请重新打开选择器" }
            val selected = record.settings.applyTo(current, record.id)
            selected.validate()
            if (selected != current) save(selected)
        } }

    internal fun renameCatalogModel(
        selection: CatalogModelSelection<CatalogModelParameters>, name: String, cancelled: () -> Boolean,
    ): CatalogLocalEditResult = modelCatalog.editSelection(selection, name, { false }, cancelled)

    internal fun deleteCatalogModel(
        selection: CatalogModelSelection<CatalogModelParameters>, cancelled: () -> Boolean,
    ): CatalogLocalEditResult = modelCatalog.editSelection(selection, null,
        { synchronized(lock) {
            check(preferences !in failedStores) { "模型设置存储不可用；请重新保存" }
            preferences.getString("model", "") == selection.record.id
        } }, cancelled)

    internal fun nonSecretConfiguration(): Map<String, Any?> = synchronized(lock) { readNonSecretConfiguration() }
    private fun readNonSecretConfiguration(includeProtocol: Boolean = true): Map<String, Any?> = buildMap {
        listOf("endpoint", "model", "responses-path").forEach { key ->
            if(preferences.contains(key)) put(key, preferences.getString(key, null))
        }
        listOf("battery", "device-status").forEach { key ->
            if(preferences.contains(key)) put(key, preferences.getBoolean(key, false))
        }
        if(includeProtocol && preferences.contains("protocol")) put("protocol", preferences.getString("protocol", null))
    }

    /** Manual recovery only; importing backup metadata never calls this API automatically.
     * Reads the five legacy fields and optional protocol, never all preferences or credential material.
     * Empty JSON is a true no-op; omissions keep their old value and presence.
     */
    fun prepareNonSecretRestore(configuration: JSONObject): NonSecretRestorePlan = synchronized(lock) {
        val supplied = validateRestoreConfiguration(configuration)
        val before = readNonSecretConfiguration()
        val after = LinkedHashMap(before)
        after.putAll(supplied)
        if(supplied["protocol"] == "responses") after.remove("protocol")
        if(supplied.isNotEmpty()) {
            val protocol = after["protocol"] ?: "responses"
            require(protocol in setOf("responses", "messages", "chat_completions", "gemini")) { "模型协议无效" }
            if(protocol != "responses") {
                require(after["responses-path"] == null || after["responses-path"] == "") { "所选协议不使用 Responses 路径" }
                (after["endpoint"] as? String)?.takeIf { it.isNotEmpty() }?.let {
                    ModelSettings(it, after["model"] as? String ?: "restore-model", "validation-only", false,
                        protocol = ProviderProtocol.entries.first { p -> p.wireName == protocol }).requestEndpoint()
                }
            }
            if (protocol == "responses") (after["endpoint"] as? String)?.takeIf { it.isNotEmpty() }?.let {
                responsesEndpoint(it, after["responses-path"] as? String ?: "")
            }
            // Backup data is not authorization to execute device tools.
            after["battery"] = false
            after["device-status"] = false
        }
        val rebind = listOf("endpoint", "responses-path", "model").any { before[it] != after[it] }
            || (before["protocol"] ?: "responses") != (after["protocol"] ?: "responses")
        NonSecretRestorePlan(restoreIdentity, before, after, supplied.isEmpty(), rebind)
    }

    /** Apply only after explicit user approval of this preview. Commit and compensation never touch keys. */
    fun applyNonSecretRestore(plan: NonSecretRestorePlan, authorized: Boolean): Unit = modelCatalog.configurationChange { synchronized(lock) {
        require(authorized) { "恢复配置需要明确授权" }
        require(plan.belongsTo(restoreIdentity)) { "恢复计划不属于当前设置" }
        check(preferences !in failedStores) { "模型设置存储不可用；请重新保存" }
        check(readNonSecretConfiguration() == plan.before) { "模型设置已变化；请重新预览恢复计划" }
        if(plan.isNoOp) return@synchronized
        val changed = configurationFields.filter { name ->
            plan.before.containsKey(name) != plan.after.containsKey(name) || plan.before[name] != plan.after[name]
        }
        // Do not remove an existing gate, even when this preview changes only tool switches.
        val gateWasPresent = preferences.contains(REBIND_REQUIRED)
        val gateWasRequired = credentialRebindRequired()
        val writeGate = plan.credentialRebindRequired && !gateWasRequired
        if(changed.isEmpty() && !writeGate) return@synchronized
        try {
            val editor = preferences.edit()
            changed.forEach { putField(editor, it, plan.after) }
            if(writeGate) editor.putBoolean(REBIND_REQUIRED, true)
            check(editor.commit()) { "配置恢复提交失败" }
            // Rebound connections remain gated until an explicit credential save rotates identity.
            // Tool-only restores still update the active model's durable local policy.
            if (!plan.credentialRebindRequired) {
                val editing = editingWithoutCredential()
                if (!editing.requestOptionsUnavailable) modelCatalog.updateRegisteredModel(editing.settings.model) {
                    CatalogModelParameters.updated(editing.settings, it)
                }
            }
        } catch (_: Exception) {
            val restored = try {
                val editor = preferences.edit()
                changed.forEach { putField(editor, it, plan.before) }
                if(writeGate) {
                    if(gateWasPresent) editor.putBoolean(REBIND_REQUIRED, gateWasRequired)
                    else editor.remove(REBIND_REQUIRED)
                }
                editor.commit()
            } catch (_: Exception) { false }
            if(!restored) failedStores.add(preferences)
            throw IllegalStateException("配置未恢复；原设置补偿失败时须显式重新保存")
        }
    } }

    private fun readProtocol(): ProviderProtocol = try {
        if(!preferences.contains("protocol")) ProviderProtocol.RESPONSES
        else when(preferences.getString("protocol", null)) {
            "responses" -> ProviderProtocol.RESPONSES
            "messages" -> ProviderProtocol.MESSAGES
            "chat_completions" -> ProviderProtocol.CHAT_COMPLETIONS
            "gemini" -> ProviderProtocol.GEMINI
            else -> ProviderProtocol.UNKNOWN
        }
    } catch (_: Exception) { ProviderProtocol.UNKNOWN }

    private fun credentialRebindRequired(): Boolean = try {
        preferences.getBoolean(REBIND_REQUIRED, false)
    } catch (_: Exception) { true } // Malformed gate fails closed before any decryption.

    private fun readRequestOptions(): ProviderRequestOptions = ProviderRequestOptions.decode(
        if (preferences.contains("request-options")) preferences.getString("request-options", null)
            ?: throw IllegalStateException("模型请求配置无效；请重新保存") else null)

    private fun editingWithoutCredential(): EditingSettings {
        val values = readNonSecretConfiguration(includeProtocol = false)
        var optionsUnavailable = false
        val options = try {
            readRequestOptions().also {
                val protocol = readProtocol()
                if (protocol != ProviderProtocol.UNKNOWN) it.validate(protocol)
            }
        } catch (_: Exception) {
            optionsUnavailable = true; ProviderRequestOptions()
        }
        return EditingSettings(ModelSettings(values["endpoint"] as? String ?: "", values["model"] as? String ?: "", "",
            values["battery"] as? Boolean ?: false, values["responses-path"] as? String ?: "",
            values["device-status"] as? Boolean ?: false, readProtocol(), options), true, optionsUnavailable)
    }
    private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun existingKey(alias:String)=keyStore().getKey(alias,null) as? SecretKey
    private fun newKey(alias:String):SecretKey=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    private fun discardKey(alias:String) {try {keyStore().deleteEntry(alias)} catch (_:Exception) {}}
    fun loadForEditing():EditingSettings=synchronized(lock) {
        if(preferences in failedStores || credentialRebindRequired() || readProtocol() == ProviderProtocol.UNKNOWN) return@synchronized editingWithoutCredential()
        val values=preferences.all
        var unavailable=false
        var optionsUnavailable = false
        val options=try { readRequestOptions().also {it.validate(readProtocol())} }
        catch (_:Exception) {unavailable=true;optionsUnavailable=true;ProviderRequestOptions()}
        val apiKey=try {
            val encrypted=values["key"] as? String
            if(encrypted==null) {
                if(values.keys.any {it in setOf("key","iv","key-alias")}) throw CredentialUnavailableException()
                ""
            } else {
                val alias=if("key-alias" in values) values["key-alias"] as? String ?: throw CredentialUnavailableException() else keyAliasPrefix
                // Isolated profiles must never decrypt a copied main-model alias or use its legacy key.
                if (namespace != "model-settings" && !alias.startsWith("$keyAliasPrefix."))
                    throw CredentialUnavailableException()
                val key=existingKey(alias) ?: throw CredentialUnavailableException()
                val iv=values["iv"] as? String ?: throw CredentialUnavailableException()
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),Charsets.UTF_8)
            }
        } catch (_:Exception) {unavailable=true;""}
        EditingSettings(ModelSettings(values["endpoint"] as? String ?: "",values["model"] as? String ?: "",apiKey,
            values["battery"] as? Boolean ?: false,values["responses-path"] as? String ?: "",values["device-status"] as? Boolean ?: false,readProtocol(),options),unavailable,optionsUnavailable)
    }
    fun load():ModelSettings=synchronized(lock) {
        check(readProtocol() != ProviderProtocol.UNKNOWN) {"模型协议无效；请到模型设置重新选择"}
        check(preferences !in failedStores) {"模型设置存储不可用；请重新保存"}
        if(credentialRebindRequired()) throw CredentialUnavailableException()
        val loaded=loadForEditing()
        if(loaded.credentialUnavailable) throw CredentialUnavailableException()
        loaded.settings
    }
    /** Legacy live-remote entry. New local pickers must use selectCatalogModel with their ticket. */
    fun selectModel(expected: ModelSettings, model: String): Unit = modelCatalog.configurationChange { synchronized(lock) {
        check(load() == expected) { "模型配置已变更，请重新打开选择器" }
        val local = modelCatalogSnapshot().selectionFor(model)
        if (local != null) selectCatalogModel(expected, local)
        else if (model != expected.model) {
            // A remotely discovered, not-yet-local model uses detached defaults, NEVER active overrides.
            save(CatalogModelParameters().applyTo(expected, model))
        }
    } }
    fun save(settings:ModelSettings, protocolChangeAuthorized: Boolean = false, requestOptionsReplacementAuthorized: Boolean = false):Unit=modelCatalog.configurationChange { synchronized(lock) {
        settings.validate()
        val previousEditing = loadForEditing()
        val previousConnection = previousEditing.settings
        val connectionChanged = previousEditing.credentialUnavailable ||
            previousConnection.endpoint != settings.endpoint || previousConnection.protocol != settings.protocol ||
            previousConnection.responsesPath != settings.responsesPath || previousConnection.apiKey != settings.apiKey
        val previousOptionsValid = try {
            val previousOptions = readRequestOptions()
            val previousProtocol = readProtocol()
            if (previousProtocol != ProviderProtocol.UNKNOWN) previousOptions.validate(previousProtocol)
            true
        } catch (_: Exception) { false }
        require(previousOptionsValid || requestOptionsReplacementAuthorized) { "请求策略已损坏；替换或重置需要明确确认" }
        require(readProtocol() == settings.protocol || protocolChangeAuthorized) {"切换协议须重新输入密钥并明确确认"}
        val previous=preferences.all
        val owner=previous["key-owner"] as? String ?: UUID.randomUUID().toString()
        val alias="$keyAliasPrefix.$owner.${UUID.randomUUID()}"
        var committed=false
        var attempted=false
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,newKey(alias))
            val encrypted=Base64.encodeToString(cipher.doFinal(settings.apiKey.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
            attempted=true
            committed=preferences.edit().putString("endpoint",settings.endpoint).putString("model",settings.model)
                .putString("responses-path",settings.responsesPath).putBoolean("device-status",settings.deviceStatusEnabled).putBoolean("battery",settings.batteryEnabled)
                .apply {
                    if(settings.protocol != ProviderProtocol.RESPONSES) putString("protocol", settings.protocol.wireName)
                    else remove("protocol")
                }
                .apply {
                    if(settings.requestOptions == ProviderRequestOptions()) remove("request-options")
                    else putString("request-options", settings.requestOptions.encode())
                }
                .putString("key-owner",owner).putString("key-alias",alias).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("key",encrypted)
                .remove(REBIND_REQUIRED).commit()
            check(committed) {"模型设置保存失败"}
            // Still under catalog -> settings locks. A catalog failure compensates preferences below.
            // A connection/key change starts a fresh identity, even if wire model IDs happen to match.
            if (connectionChanged) modelCatalog.replaceRegisteredProvider(listOf(CatalogModelParameters.fromCurrent(settings)))
            else {
                if (previousConnection.model != settings.model && !previousEditing.requestOptionsUnavailable) {
                    modelCatalog.updateRegisteredModel(previousConnection.model) {
                        CatalogModelParameters.updated(previousConnection, it)
                    }
                }
                modelCatalog.updateRegisteredModel(settings.model) { CatalogModelParameters.updated(settings, it) }
            }
        } catch (_:Exception) {
            if(!attempted) discardKey(alias)
            else {
                val restored=try {
                    val editor=preferences.edit()
                    fields.forEach {name->when(val value=previous[name]) {
                        null->editor.remove(name)
                        is String->editor.putString(name,value)
                        is Boolean->editor.putBoolean(name,value)
                        else->error("设置字段类型无效")
                    }}
                    editor.commit()
                } catch (_:Exception) {false}
                if(restored) discardKey(alias) else {
                    failedStores.add(preferences)
                    // A failed commit may already have removed the gate in SharedPreferences' memory.
                    // Keep a pre-existing rebind requirement fail-closed across reopen where storage permits.
                    if(previous[REBIND_REQUIRED] == true) {
                        try { preferences.edit().putBoolean(REBIND_REQUIRED, true).commit() } catch (_: Exception) {}
                    }
                }
            }
            throw IllegalStateException("模型设置未保存；请重新保存")
        }
        if(committed) {
            failedStores.remove(preferences)
            val oldAlias=previous["key-alias"] as? String
            val oldOwner=previous["key-owner"] as? String
            if(oldAlias!=null && oldOwner==owner && oldAlias.startsWith("$keyAliasPrefix.$owner.")) {
                val suffix=oldAlias.removePrefix("$keyAliasPrefix.$owner.")
                if(runCatching {UUID.fromString(suffix).toString()==suffix}.getOrDefault(false)) discardKey(oldAlias)
            }
        }
    } }
    companion object {
        private val lock=Any()
        private val failedStores=java.util.Collections.newSetFromMap(java.util.WeakHashMap<SharedPreferences,Boolean>())
        private const val REBIND_REQUIRED = "credential-rebind-required"
        private val configurationFields = listOf("endpoint", "model", "responses-path", "device-status", "battery", "protocol")
        private val fields = configurationFields + listOf("request-options", "key-owner", "key-alias", "iv", "key", REBIND_REQUIRED)

        private fun putField(editor: SharedPreferences.Editor, name: String, values: Map<String, Any?>) {
            if(!values.containsKey(name)) editor.remove(name)
            else when(val value = values[name]) {
                null -> editor.putString(name, null)
                is String -> editor.putString(name, value)
                is Boolean -> editor.putBoolean(name, value)
                else -> error("设置字段类型无效")
            }
        }

        // Intentionally stricter than backup metadata: this data will become active model settings.
        private fun validateRestoreConfiguration(configuration: JSONObject): Map<String, Any?> = buildMap {
            configuration.keys().forEach { name ->
                require(name in configurationFields) { "拒绝凭据或未知配置字段" }
                val value = configuration.get(name)
                if(name == "battery" || name == "device-status") {
                    require(value is Boolean) { "工具开关须为布尔值" }
                } else {
                    require(value is String) { "配置字段须为文本" }
                    if(name == "protocol") require(value in setOf("responses", "messages", "chat_completions", "gemini")) { "模型协议无效" }
                    require(value.length <= if(name == "model") 200 else 2000) { "配置字段过长" }
                    require(!value.contains('\n') && !value.contains('\r')) { "配置字段含非法换行" }
                    if(value.isNotEmpty()) when(name) {
                        "endpoint" -> {
                            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("服务地址无效") }
                            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                                uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) { "请输入无查询参数的 HTTPS 服务地址" }
                        }
                        "responses-path" -> {
                            responsesEndpoint("https://restore.invalid", value)
                            require(!value.contains("..") && !URI(value).path.orEmpty().contains("..")) { "非法 Responses 路径" }
                        }
                        "model" -> require(value.isNotBlank()) { "模型名称不能为空白" }
                    }
                }
                put(name, value)
            }
        }
    }
}
