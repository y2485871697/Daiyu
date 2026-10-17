package app.daiyu.android.voice.account

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Retained from frozen doubao/DoubaoVoiceCatalog; control plane, never synthesis credentials. */
internal class DoubaoVoiceCatalog(private val transport: AccountHttpTransport) {
    data class Slot(val id: String, val name: String, val state: String, val remaining: Int)
    suspend fun list(ak: String, sk: String, project: String): List<Slot> {
        require(ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) { "请填写 AK、SK 和项目名" }
        val result = linkedMapOf<String, Slot>()
        for (state in listOf("Unknown", "Training", "Success", "Active")) {
            var expectedTotal: Int? = null
            for (page in 1..20) {
                currentCoroutineContext().ensureActive()
                val body = JSONObject().put("ProjectName", project).put("State", state)
                    .put("PageNumber", page).put("PageSize", 100).toString()
                val response = transport.execute(signedRequest(ak, sk, body, Instant.now()), AccountHttpTransportLimit)
                val root = JSONObject(response.bytes.toString(Charsets.UTF_8))
                responseError(response.status, root, listOf(ak, sk))?.let { error(it) }
                val data = root.getJSONObject("Result")
                // optInt coerces strings/fractions and invents totals when absent. None prove completeness.
                val rawTotal = data.opt("TotalCount")
                check(rawTotal is Int || rawTotal is Long) { "音色列表 TotalCount 缺失或不是非负整数，未取得完整列表" }
                val totalLong = (rawTotal as Number).toLong()
                check(totalLong in 0L..2000L) { "音色列表总数无效或超过 2000 条，请按项目缩小查询范围" }
                val total = totalLong.toInt()
                check(expectedTotal == null || expectedTotal == total) { "音色列表总数在分页中变化，未取得完整列表" }
                expectedTotal = total
                val items = data.getJSONArray("Statuses")
                val offset = (page - 1) * 100
                val expectedCount = (total - offset).coerceIn(0, 100)
                check(offset <= total && items.length() == expectedCount) { "音色列表页数或条数不完整，请重新查询" }
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.getString("SpeakerID")
                    check(id.matches(Regex("S_[A-Za-z0-9_-]+"))) { "音色列表包含无效槽位，未取得完整列表" }
                    check(!result.containsKey(id)) { "音色列表包含重复槽位，未取得完整列表" }
                    result[id] = Slot(id, item.optString("Alias").ifBlank { id }, item.optString("State", state),
                        item.optInt("AvailableTrainingTimes", -1))
                }
                if (offset + items.length() == total) break
                check(page < 20) { "音色超过 2000 条，请按项目缩小查询范围" }
            }
        }
        return result.values.toList()
    }
    companion object {
        private const val QUERY = "Action=BatchListMegaTTSTrainStatus&Version=2025-05-21"
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val AccountHttpTransportLimit = 2 * 1024 * 1024
        private const val HOST = "open.volcengineapi.com"
        internal fun signedRequest(ak: String, sk: String, body: String, now: Instant): Request {
            val date = Signing.date(now); val day = date.take(8); val digest = Signing.hash(body)
            val headers = "content-type;host;x-content-sha256;x-date"
            val scope = "$day/cn-beijing/speech_saas_prod/request"
            val canonical = "POST\n/\n$QUERY\ncontent-type:$CONTENT_TYPE\nhost:$HOST\nx-content-sha256:$digest\nx-date:$date\n\n$headers\n$digest"
            val signature = Signing.signature(sk, day, "speech_saas_prod", "HMAC-SHA256\n$date\n$scope\n${Signing.hash(canonical)}")
            return Request.Builder().url("https://$HOST/?$QUERY").header("Host", HOST)
                .header("Content-Type", CONTENT_TYPE).header("X-Date", date).header("X-Content-Sha256", digest)
                .header("Authorization", "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$headers, Signature=$signature")
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(CONTENT_TYPE.toMediaType())).build()
        }
        internal fun responseError(http: Int, root: JSONObject, secrets: List<String>): String? {
            val meta = root.optJSONObject("ResponseMetadata")
            val error = meta?.optJSONObject("Error")
            if (http in 200..299 && error == null) return null
            fun clean(text: String): String {
                var value = text
                secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { value = value.replace(it, "[redacted]") }
                return value.filter { !it.isISOControl() }.take(300)
            }
            val code = clean(error?.optString("Code").orEmpty())
            val hint = when {
                code.contains("Signature", true) -> "签名校验失败：请核对 AK/SK 是否为同一对。"
                code.contains("Expired", true) || code.contains("Time", true) -> "请求时间无效：请检查手机日期与时间。"
                http == 401 -> "访问密钥鉴权失败：请使用控制面 AK/SK，不是豆包 API Key。"
                http == 403 -> "没有查询权限，请核对项目和 IAM 授权。"
                else -> "读取失败，未取得列表。"
            }
            // Do not display arbitrary service messages (may echo transformed credentials).
            return "$hint HTTP $http · $code · 请求标识：${clean(meta?.optString("RequestId").orEmpty())}"
        }
    }
}

internal object Signing {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun date(now: Instant): String = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
    fun signature(sk: String, day: String, service: String, text: String): String {
        var key = hmac(sk.toByteArray(Charsets.UTF_8), day)
        key = hmac(key, "cn-beijing"); key = hmac(key, service); key = hmac(key, "request")
        return hex(hmac(key, text))
    }
}
