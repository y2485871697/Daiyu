package app.daiyu.android.domain

import java.net.URI

fun messagesEndpoint(address: String): String {
    responsesEndpoint(address)
    val path = URI(address).path.orEmpty().trimEnd('/')
    require(!path.endsWith("/responses")) { "Messages 协议不能使用 Responses 地址" }
    val base = address.trimEnd('/')
    return when (path) {
        "" -> "$base/v1/messages"
        "/v1" -> "$base/messages"
        else -> base
    }
}
