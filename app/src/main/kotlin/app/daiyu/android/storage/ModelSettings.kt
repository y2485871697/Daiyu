package app.daiyu.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.daiyu.android.domain.responsesEndpoint
import app.daiyu.android.domain.ReadOnlyTool
import java.security.KeyStore
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
class ModelSettingsStore(context:Context) {
    private val preferences=context.getSharedPreferences("model-settings",Context.MODE_PRIVATE)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return (store.getKey("model-api-key",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("model-api-key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load():ModelSettings {
        val encrypted=preferences.getString("key",null)
        val apiKey=if(encrypted==null) "" else {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(preferences.getString("iv",null),Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),Charsets.UTF_8)
        }
        return ModelSettings(preferences.getString("endpoint","")!!,preferences.getString("model","")!!,apiKey,preferences.getBoolean("battery",false),preferences.getString("responses-path","")!!,preferences.getBoolean("device-status",false))
    }
    fun save(settings:ModelSettings) {
        settings.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        check(preferences.edit().putString("endpoint",settings.endpoint).putString("model",settings.model)
            .putString("responses-path",settings.responsesPath).putBoolean("device-status",settings.deviceStatusEnabled).putBoolean("battery",settings.batteryEnabled).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("key",Base64.encodeToString(cipher.doFinal(settings.apiKey.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).commit()) {"模型设置保存失败"}
    }
}
