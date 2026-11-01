package app.daiyu.android.voice.account

import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Cloud display names only. Every attempt reads the original resource before one alias update. */
internal class DoubaoVoiceAliases(private val transport: AccountHttpTransport) {
    suspend fun rename(ak: String, sk: String, project: String, id: String, name: String) {
        require(ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) { "请先保存同步音色名额的 AK、SK 和项目" }
        require(id.matches(Regex("S_[A-Za-z0-9_-]+")) && name.isNotBlank()) { "此音色不支持云端名称同步" }
        val resource = DoubaoVoiceCatalog(transport).list(ak, sk, project, speakerId = id).singleOrNull()
            ?: throw VoiceCatalogFailure("当前项目没有该音色名额，未改名")
        if (resource.alias == name) return
        val pack = if (resource.instanceNumber.isBlank()) findResourcePack(ak, sk, project, id) else null
        if (pack?.opt("Alias") == name) return
        val instance = if (pack != null) (pack.opt("InstanceNumber") as? String).orEmpty() else resource.instanceNumber
        if (instance.isBlank()) throw VoiceCatalogFailure("音色名额与资源包均未返回实例号，未改名；可稍后重试名称同步。")
        if (pack?.optJSONObject("Details")?.opt("Aliasable") == false) {
            throw VoiceCatalogFailure("此音色资源暂不允许云端改名；本机名称已保留。")
        }
        val result = request("AliasResourcePack", ak, sk, JSONObject().put("ProjectName", project)
            .put("TrainID", id).put("InstanceNumber", instance).put("Alias", name))
        if (result.optString("InstanceNumber") != instance || result.optString("TrainID") != id ||
            result.optString("Alias") != name) throw VoiceCatalogFailure("名称更新结果未确认，请重试名称同步")
    }
    private suspend fun findResourcePack(ak: String, sk: String, project: String, id: String): JSONObject {
        var matched: JSONObject? = null
        var expectedTotal: Int? = null
        for (page in 1..20) {
            // Official voice-clone access resource, not the TTS character-package example.
            val data = request("ResourcePacksStatus", ak, sk, JSONObject().put("ProjectName", project)
                .put("ResourceIDs", JSONArray().put("volc.megatts.voiceclone"))
                .put("Types", JSONArray().put("access"))
                .put("PageNumber", page).put("PageSize", 100))
            val rawTotal = data.opt("TotalCount")
            if (rawTotal !is Int && rawTotal !is Long) throw VoiceCatalogFailure("音色资源包列表总数缺失，未改名")
            val total = (rawTotal as Number).toLong()
            if (total !in 0L..2000L || (expectedTotal != null && total != expectedTotal.toLong())) {
                throw VoiceCatalogFailure("音色资源包列表不完整，未改名")
            }
            expectedTotal = total.toInt()
            if (data.has("PageNumber") && data.opt("PageNumber") != page) throw VoiceCatalogFailure("音色资源包页码不符，未改名")
            if (data.has("PageSize") && data.opt("PageSize") != 100) throw VoiceCatalogFailure("音色资源包页大小不符，未改名")
            val rows = when (val raw = data.opt("Packs")) {
                is JSONArray -> raw
                null, JSONObject.NULL -> if (total == 0L) JSONArray() else throw VoiceCatalogFailure("音色资源包响应缺少 Packs，未改名")
                else -> throw VoiceCatalogFailure("音色资源包列表格式无效，未改名")
            }
            val offset = (page - 1) * 100
            if (offset > total || rows.length() != (total - offset).coerceIn(0L, 100L).toInt()) {
                throw VoiceCatalogFailure("音色资源包页条数不完整，未改名")
            }
            for (index in 0 until rows.length()) {
                val pack = rows.optJSONObject(index) ?: throw VoiceCatalogFailure("音色资源包条目格式无效，未改名")
                if (pack.opt("TrainID") != id) continue
                if (matched != null) throw VoiceCatalogFailure("同一音色匹配到多个资源包，未改名")
                val resourceId = pack.opt("ResourceID")
                if (resourceId != null && resourceId !== JSONObject.NULL && resourceId != "volc.megatts.voiceclone") {
                    throw VoiceCatalogFailure("音色资源包类型不符，未改名")
                }
                val packType = pack.opt("PackType")
                if (packType != null && packType !== JSONObject.NULL && packType != "access") {
                    throw VoiceCatalogFailure("音色资源包类型不符，未改名")
                }
                matched = pack
            }
            if (offset + rows.length() == total.toInt()) break
        }
        return matched ?: throw VoiceCatalogFailure("当前项目未查询到该音色的资源包，未改名")
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
