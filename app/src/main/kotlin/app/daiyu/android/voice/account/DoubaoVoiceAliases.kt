package app.daiyu.android.voice.account

import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Cloud display names only. Every attempt reads the original resource before one alias update. */
internal class DoubaoVoiceAliases(private val transport: AccountHttpTransport) {
    suspend fun rename(ak: String, sk: String, project: String, id: String, name: String) {
        require(ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) { "请先保存同步音色名额的 AK、SK 和项目" }
        require(id.matches(Regex("S_[A-Za-z0-9_-]+")) && name.isNotBlank()) { "此音色不支持云端名称同步" }
        // The purchased-speaker API returns the exact original InstanceNO without resource-type guesses.
        val resource = DoubaoVoiceCatalog(transport).list(ak, sk, project, speakerId = id).singleOrNull()
            ?: throw VoiceCatalogFailure("当前项目没有该音色名额，未改名")
        val instance = resource.instanceNumber
        if (instance.isBlank()) throw VoiceCatalogFailure("音色名额响应缺少实例号，未改名")
        if (resource.alias == name) return
        val result = request("AliasResourcePack", ak, sk, JSONObject().put("ProjectName", project)
            .put("TrainID", id).put("InstanceNumber", instance).put("Alias", name))
        if (result.optString("InstanceNumber") != instance || result.optString("TrainID") != id ||
            result.optString("Alias") != name) throw VoiceCatalogFailure("名称更新结果未确认，请重试名称同步")
    }
    private suspend fun request(action: String, ak: String, sk: String, body: JSONObject): JSONObject {
        currentCoroutineContext().ensureActive()
        val request = DoubaoVoiceCatalog.signedRequest(ak, sk, body.toString(), Instant.now(),
            "Action=$action&Version=2025-05-20")
        val response = transport.execute(request, DoubaoVoiceCatalog.AccountHttpTransportLimit)
        currentCoroutineContext().ensureActive()
        return DoubaoVoiceCatalog.readResponse(response, listOf(ak, sk)).optJSONObject("Result")
            ?: throw VoiceCatalogFailure("名称同步响应缺少 Result，未确认名称更新")
    }
}
