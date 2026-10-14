package app.daiyu.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.daiyu.android.domain.responsesEndpoint
import app.daiyu.android.domain.ReadOnlyTool
import java.security.KeyStore
import java.util.UUID
import android.content.SharedPreferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelSettings(val endpoint: String, val model: String, val apiKey: String, val batteryEnabled: Boolean, val responsesPath: String = "", val deviceStatusEnabled: Boolean = false) {
    fun enabledTools(): Set<ReadOnlyTool> = buildSet {
        if (batteryEnabled) add(ReadOnlyTool.BATTERY)
        if (deviceStatusEnabled) add(ReadOnlyTool.DEVICE_STATUS)
    }
    fun requestEndpoint(): String = responsesEndpoint(endpoint, responsesPath)
    fun validate() {
        requestEndpoint()
        require(model.isNotBlank() && model.length<=200 && apiKey.isNotBlank() && apiKey.length<=8192) {"请填写模型和密钥"}
        require(!apiKey.contains('\n') && !apiKey.contains('\r')) {"密钥含非法换行"}
    }
}
class CredentialUnavailableException:IllegalStateException("已保存密钥不可用；请重新输入密钥")
data class EditingSettings(val settings:ModelSettings,val credentialUnavailable:Boolean)
class ModelSettingsStore internal constructor(context:Context,private val keyAliasPrefix:String) {
    constructor(context:Context):this(context,"model-api-key")
    private val preferences=context.getSharedPreferences("model-settings",Context.MODE_PRIVATE)
    internal fun nonSecretConfiguration(): Map<String, Any?> = synchronized(lock) {
        buildMap {
            listOf("endpoint", "model", "responses-path").forEach { key ->
                if(preferences.contains(key)) put(key, preferences.getString(key, null))
            }
            listOf("battery", "device-status").forEach { key ->
                if(preferences.contains(key)) put(key, preferences.getBoolean(key, false))
            }
        }
    }
    private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun existingKey(alias:String)=keyStore().getKey(alias,null) as? SecretKey
    private fun newKey(alias:String):SecretKey=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    private fun discardKey(alias:String) {try {keyStore().deleteEntry(alias)} catch (_:Exception) {}}
    fun loadForEditing():EditingSettings=synchronized(lock) {
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
            values["battery"] as? Boolean ?: false,values["responses-path"] as? String ?: "",values["device-status"] as? Boolean ?: false),unavailable)
    }
    fun load():ModelSettings=synchronized(lock) {
        check(preferences !in failedStores) {"模型设置存储不可用；请重新保存"}
        val loaded=loadForEditing()
        if(loaded.credentialUnavailable) throw CredentialUnavailableException()
        loaded.settings
    }
    fun save(settings:ModelSettings):Unit=synchronized(lock) {
        settings.validate()
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
                .putString("key-owner",owner).putString("key-alias",alias).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("key",encrypted).commit()
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
                if(restored) discardKey(alias) else failedStores.add(preferences)
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
        private val fields=listOf("endpoint","model","responses-path","device-status","battery","key-owner","key-alias","iv","key")
    }
}
