package app.daiyu.android.voice.account

import okhttp3.Request
import org.json.JSONObject
import java.time.Instant

/** Retained frozen IAM ListProjects signing/pagination. Explicit read-only request, no TTS key. */
internal class VoiceProjectCatalog(private val transport: AccountHttpTransport) {
    data class Project(val name: String, val displayName: String, val allowed: Boolean)
    data class Page(val projects: List<Project>, val count: Int, val total: Int)
    suspend fun list(ak: String, sk: String): List<Project> {
        require(ak.isNotBlank() && sk.isNotBlank()) { "请先填写 AK 和 SK" }
        return collectPages { offset ->
            val response = transport.execute(signedRequest(ak, sk, offset, Instant.now()), 2 * 1024 * 1024)
            val root = DoubaoVoiceCatalog.readResponse(response, listOf(ak, sk))
            parsePage(root)
        }
    }
    companion object {
        internal fun selectProject(projects: List<Project>, preferred: String): Project =
            projects.firstOrNull { it.allowed && it.name == preferred }
                ?: projects.firstOrNull { it.allowed && it.name == "default" }
                ?: projects.firstOrNull { it.allowed }
                ?: error("没有可访问的项目，请核对 AK／SK 与项目权限后重试")

        private const val HOST = "iam.volcengineapi.com"
        private const val LIMIT = 100
        internal fun signedRequest(ak: String, sk: String, offset: Int, now: Instant): Request {
            require(offset >= 0)
            val query = "Action=ListProjects&Limit=$LIMIT&Offset=$offset&Version=2021-08-01"
            val date = Signing.date(now); val day = date.take(8)
            val headers = "host;x-date"; val scope = "$day/cn-beijing/iam/request"
            val canonical = "GET\n/\n$query\nhost:$HOST\nx-date:$date\n\n$headers\n${Signing.hash("")}"
            val signature = Signing.signature(sk, day, "iam", "HMAC-SHA256\n$date\n$scope\n${Signing.hash(canonical)}")
            return Request.Builder().url("https://$HOST/?$query").get().header("Host", HOST).header("X-Date", date)
                .header("Authorization", "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$headers, Signature=$signature").build()
        }
        internal fun parsePage(root: JSONObject): Page {
            val result = root.getJSONObject("Result"); val items = result.getJSONArray("Projects")
            val total = result.getInt("Total"); check(total >= 0) { "项目列表总数无效" }
            val projects = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index); val name = item.getString("ProjectName")
                check(name.isNotBlank()) { "项目列表包含无效名称" }
                Project(name, item.optString("DisplayName").ifBlank { name }, item.optBoolean("HasPermission", false))
            }
            return Page(projects, items.length(), total)
        }
        internal suspend fun collectPages(fetch: suspend (Int) -> Page): List<Project> {
            val result = linkedMapOf<String, Project>()
            for (page in 0 until 20) {
                val offset = page * LIMIT; val data = fetch(offset)
                data.projects.forEach { result[it.name] = it }
                if (offset + data.count >= data.total) return result.values.toList()
                check(data.count == LIMIT) { "项目列表未完整返回，请重试" }
            }
            error("项目超过 2000 个，请缩小账户可访问范围后重试")
        }
    }
}
