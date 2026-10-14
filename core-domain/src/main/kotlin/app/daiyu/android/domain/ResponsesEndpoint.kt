// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from RikkaHub ProviderSetting.OpenAI and ResponseAPI endpoint construction.
// See THIRD_PARTY/rikkahub/NOTICE.md for the fixed source revision and changes.
package app.daiyu.android.domain

import java.net.URI

fun responsesEndpoint(address: String, responsesPath: String = ""): String {
    val uri = URI(address)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) {
        "请输入无查询参数的 HTTPS 服务地址"
    }
    val path = uri.path.orEmpty().trimEnd('/')
    require(!path.endsWith("/chat/completions")) { "当前使用 Responses 协议，不能填写 Chat Completions 地址" }
    require(responsesPath.isEmpty() || (responsesPath.startsWith('/') && !responsesPath.startsWith("//") &&
        URI(responsesPath).let { it.scheme == null && it.rawAuthority == null && it.query == null && it.fragment == null })) {
        "Responses 路径须以单个 / 开始，且不含查询参数"
    }
    val baseUrl = address.trimEnd('/')
    if (responsesPath.isNotEmpty()) return "$baseUrl$responsesPath"
    return when (path) {
        "" -> "$baseUrl/v1/responses"
        "/v1" -> "$baseUrl/responses"
        else -> address
    }
}
