package app.daiyu.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.daiyu.android.domain.responsesEndpoint
import app.daiyu.android.domain.messagesEndpoint
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.UnsupportedProviderException
import app.daiyu.android.domain.ReadOnlyTool
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

data class ModelSettings(val endpoint: String, val model: String, val apiKey: String, val batteryEnabled: Boolean, val responsesPath: String = "", val deviceStatusEnabled: Boolean = false, val protocol: ProviderProtocol = ProviderProtocol.RESPONSES) {
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
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(protocol.wireName)
    }
    fun requestSnapshot(): ProviderSnapshot = ProviderSnapshot.capture(this)
    override fun toString(): String =
        "ModelSettings(protocol=${protocol.wireName}, endpoint=$endpoint, model=$model, apiKey=<redacted>, batteryEnabled=$batteryEnabled, " +
            "responsesPath=$responsesPath, deviceStatusEnabled=$deviceStatusEnabled)"
    fun validate() {
        requestEndpoint()
        require(model.isNotBlank() && model.length<=200 && apiKey.isNotBlank() && apiKey.length<=8192) {"请填写模型和密钥"}
        require(!apiKey.contains('\n') && !apiKey.contains('\r')) {"密钥含非法换行"}
    }
}
class CredentialUnavailableException:IllegalStateException("已保存密钥不可用；请重新输入密钥")
data class EditingSettings(val settings:ModelSettings,val credentialUnavailable:Boolean)

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
class ModelSettingsStore internal constructor(context:Context,private val keyAliasPrefix:String) {
    constructor(context:Context):this(context,"model-api-key")
    private val preferences=context.getSharedPreferences("model-settings",Context.MODE_PRIVATE)
    private val restoreIdentity = Any()
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
            require(protocol == "responses" || protocol == "messages") { "模型协议无效" }
            if(protocol == "messages") {
                require(after["responses-path"] == null || after["responses-path"] == "") { "Messages 不使用 Responses 路径" }
                (after["endpoint"] as? String)?.takeIf { it.isNotEmpty() }?.let { messagesEndpoint(it) }
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
    fun applyNonSecretRestore(plan: NonSecretRestorePlan, authorized: Boolean): Unit = synchronized(lock) {
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
    }

    private fun readProtocol(): ProviderProtocol = try {
        if(!preferences.contains("protocol")) ProviderProtocol.RESPONSES
        else when(preferences.getString("protocol", null)) {
            "responses" -> ProviderProtocol.RESPONSES
            "messages" -> ProviderProtocol.MESSAGES
            else -> ProviderProtocol.UNKNOWN
        }
    } catch (_: Exception) { ProviderProtocol.UNKNOWN }

    private fun credentialRebindRequired(): Boolean = try {
        preferences.getBoolean(REBIND_REQUIRED, false)
    } catch (_: Exception) { true } // Malformed gate fails closed before any decryption.

    private fun editingWithoutCredential(): EditingSettings {
        val values = readNonSecretConfiguration(includeProtocol = false)
        return EditingSettings(ModelSettings(values["endpoint"] as? String ?: "", values["model"] as? String ?: "", "",
            values["battery"] as? Boolean ?: false, values["responses-path"] as? String ?: "",
            values["device-status"] as? Boolean ?: false, readProtocol()), true)
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
        val apiKey=try {
            val encrypted=values["key"] as? String
            if(encrypted==null) {
                if(values.keys.any {it in setOf("key","iv","key-alias")}) throw CredentialUnavailableException()
                ""
            } else {
                val alias=if("key-alias" in values) values["key-alias"] as? String ?: throw CredentialUnavailableException() else keyAliasPrefix
                val key=existingKey(alias) ?: throw CredentialUnavailableException()
                val iv=values["iv"] as? String ?: throw CredentialUnavailableException()
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),Charsets.UTF_8)
            }
        } catch (_:Exception) {unavailable=true;""}
        EditingSettings(ModelSettings(values["endpoint"] as? String ?: "",values["model"] as? String ?: "",apiKey,
            values["battery"] as? Boolean ?: false,values["responses-path"] as? String ?: "",values["device-status"] as? Boolean ?: false,readProtocol()),unavailable)
    }
    fun load():ModelSettings=synchronized(lock) {
        check(readProtocol() != ProviderProtocol.UNKNOWN) {"模型协议无效；请到模型设置重新选择"}
        check(preferences !in failedStores) {"模型设置存储不可用；请重新保存"}
        if(credentialRebindRequired()) throw CredentialUnavailableException()
        val loaded=loadForEditing()
        if(loaded.credentialUnavailable) throw CredentialUnavailableException()
        loaded.settings
    }
    fun save(settings:ModelSettings, protocolChangeAuthorized: Boolean = false):Unit=synchronized(lock) {
        settings.validate()
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
                    if(settings.protocol == ProviderProtocol.MESSAGES) putString("protocol", "messages")
                    else remove("protocol")
                }
                .putString("key-owner",owner).putString("key-alias",alias).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("key",encrypted)
                .remove(REBIND_REQUIRED).commit()
            check(committed) {"模型设置保存失败"}
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
    }
    companion object {
        private val lock=Any()
        private val failedStores=java.util.Collections.newSetFromMap(java.util.WeakHashMap<SharedPreferences,Boolean>())
        private const val REBIND_REQUIRED = "credential-rebind-required"
        private val configurationFields = listOf("endpoint", "model", "responses-path", "device-status", "battery", "protocol")
        private val fields = configurationFields + listOf("key-owner", "key-alias", "iv", "key", REBIND_REQUIRED)

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
                    if(name == "protocol") require(value == "responses" || value == "messages") { "模型协议无效" }
                    require(value.length <= if(name == "model") 200 else 2000) { "配置字段过长" }
                    require(!value.contains('\n') && !value.contains('\r')) { "配置字段含非法换行" }
                    if(value.isNotEmpty()) when(name) {
                        "endpoint" -> responsesEndpoint(value)
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
