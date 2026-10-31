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
        var matched: JSONObject? = null
        var expectedTotal: Int? = null
        for (page in 1..20) {
            // The official SDK permits omitted ResourceIDs/Types. Never guess a resource filter.
            val data = request("ResourcePacksStatus", ak, sk, JSONObject().put("ProjectName", project)
                .put("PageSize", 100).put("PageNumber", page))
            val rawTotal = data.opt("TotalCount")
            check(rawTotal is Int || rawTotal is Long) { "资源列表总数缺失" }
            val total = (rawTotal as Number).toLong()
            check(total in 0L..2000L && (expectedTotal == null || expectedTotal.toLong() == total)) { "资源列表不完整" }
            expectedTotal = total.toInt()
            if (data.has("PageNumber")) check(data.getInt("PageNumber") == page) { "资源列表页码不符" }
            if (data.has("PageSize")) check(data.getInt("PageSize") == 100) { "资源列表页大小不符" }
            val packs = data.optJSONArray("Packs") ?: if (total == 0L) JSONArray() else error("资源列表缺少 Packs")
            val offset = (page - 1) * 100
            check(offset <= total && packs.length() == (total - offset).coerceIn(0L, 100L).toInt()) { "资源列表条数不完整" }
            for (index in 0 until packs.length()) {
                val pack = packs.getJSONObject(index)
                if (pack.optString("TrainID") != id) continue
                check(matched == null) { "同一音色匹配到多个资源，未改名" }
                check(pack.optString("InstanceNumber").isNotBlank()) { "音色资源缺少实例号" }
                matched = pack
            }
            if (offset + packs.length() == total.toInt()) break
        }
        val resource = matched ?: error("当前项目没有该音色资源，未改名")
        if (resource.optString("Alias") == name) return
        check(resource.optJSONObject("Details")?.optBoolean("Aliasable", true) != false) { "此音色资源不允许改名" }
        val instance = resource.getString("InstanceNumber")
        val result = request("AliasResourcePack", ak, sk, JSONObject().put("ProjectName", project)
            .put("TrainID", id).put("InstanceNumber", instance).put("Alias", name))
        check(result.optString("InstanceNumber") == instance && result.optString("TrainID") == id &&
            result.optString("Alias") == name) { "名称更新结果未确认，请重试名称同步" }
    }
    private suspend fun request(action: String, ak: String, sk: String, body: JSONObject): JSONObject {
        currentCoroutineContext().ensureActive()
        val request = DoubaoVoiceCatalog.signedRequest(ak, sk, body.toString(), Instant.now(),
            "Action=$action&Version=2025-05-20")
        val response = transport.execute(request, DoubaoVoiceCatalog.AccountHttpTransportLimit)
        currentCoroutineContext().ensureActive()
        return DoubaoVoiceCatalog.readResponse(response, listOf(ak, sk)).optJSONObject("Result")
            ?: error("名称同步响应缺少 Result")
    }
}
