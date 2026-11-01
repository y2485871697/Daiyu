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
        val status = if (resource.instanceNumber.isBlank()) findTrainStatus(ak, sk, project, id, resource.state) else null
        if (status?.opt("Alias") == name) return
        val statusInstance = (status?.opt("InstanceNO") as? String).orEmpty()
        val pack = if (resource.instanceNumber.isBlank() && statusInstance.isBlank()) findResourcePack(ak, sk, project, id) else null
        if (pack?.opt("Alias") == name) return
        val instance = when {
            resource.instanceNumber.isNotBlank() -> resource.instanceNumber
            statusInstance.isNotBlank() -> statusInstance
            else -> (pack?.opt("InstanceNumber") as? String).orEmpty()
        }
        if (instance.isBlank()) throw VoiceCatalogFailure("未取得原音色的实例号，未改名；本机名称已保留，可稍后重试名称同步。")
        if (pack?.optJSONObject("Details")?.opt("Aliasable") == false) {
            throw VoiceCatalogFailure("此音色资源暂不允许云端改名；本机名称已保留。")
        }
        val result = request("AliasResourcePack", ak, sk, JSONObject().put("ProjectName", project)
            .put("TrainID", id).put("InstanceNumber", instance).put("Alias", name))
        if (result.optString("InstanceNumber") != instance || result.optString("TrainID") != id ||
            result.optString("Alias") != name) throw VoiceCatalogFailure("名称更新结果未确认，请重试名称同步")
    }
    private suspend fun findTrainStatus(ak: String, sk: String, project: String, id: String, state: String): JSONObject? {
        val states = setOf("Unknown", "Training", "Success", "Active")
        if (state !in states) throw VoiceCatalogFailure("原音色状态无效，未改名；本机名称已保留。")
        // This exact, non-paginated endpoint does not require TotalCount.
        val data = request("ListMegaTTSTrainStatus", ak, sk, JSONObject().put("ProjectName", project)
            .put("State", state).put("SpeakerIDs", JSONArray().put(id)), version = "2025-05-21")
        val rows = data.opt("Statuses") as? JSONArray
            ?: throw VoiceCatalogFailure("原音色状态列表格式无效，未改名")
        if (rows.length() > 1) throw VoiceCatalogFailure("原音色状态匹配不唯一，未改名")
        if (rows.length() == 0) return null
        val status = rows.optJSONObject(0) ?: throw VoiceCatalogFailure("原音色状态条目格式无效，未改名")
        if (status.opt("SpeakerID") != id) throw VoiceCatalogFailure("音色状态返回了未请求的音色，未改名")
        for (field in listOf("InstanceNO", "Alias")) {
            val value = status.opt(field)
            if (value != null && value !== JSONObject.NULL && value !is String) {
                throw VoiceCatalogFailure("原音色状态字段格式无效，未改名")
            }
        }
        val observedState = status.opt("State")
        if (status.has("State") && (observedState !is String || observedState !in states)) {
            throw VoiceCatalogFailure("原音色状态无效，未改名")
        }
        return status
    }
    private suspend fun findResourcePack(ak: String, sk: String, project: String, id: String): JSONObject? {
        var matched: JSONObject? = null
        var expectedTotal: Int? = null
        for (page in 1..20) {
            // Keep all documented billing types in scope; Aliasable still controls writes.
            val data = request("ResourcePacksStatus", ak, sk, JSONObject().put("ProjectName", project)
                .put("ResourceIDs", JSONArray().put("volc.megatts.voiceclone"))
                .put("Types", JSONArray().put("access").put("quota").put("prepaid"))
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
                if (packType != null && packType !== JSONObject.NULL &&
                    (packType !is String || packType !in setOf("access", "quota", "prepaid"))) {
                    throw VoiceCatalogFailure("音色资源包类型不符，未改名")
                }
                matched = pack
            }
            if (offset + rows.length() == total.toInt()) break
        }
        return matched
    }
    private suspend fun request(action: String, ak: String, sk: String, body: JSONObject, version: String = "2025-05-20"): JSONObject {
        currentCoroutineContext().ensureActive()
        val request = DoubaoVoiceCatalog.signedRequest(ak, sk, body.toString(), Instant.now(),
            "Action=$action&Version=$version")
        val response = transport.execute(request, DoubaoVoiceCatalog.AccountHttpTransportLimit)
        currentCoroutineContext().ensureActive()
        return DoubaoVoiceCatalog.readResponse(response, listOf(ak, sk)).optJSONObject("Result")
            ?: throw VoiceCatalogFailure("名称同步响应缺少 Result，未确认名称更新")
    }
}
