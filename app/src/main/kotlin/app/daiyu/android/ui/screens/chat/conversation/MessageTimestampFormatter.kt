package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 将消息创建时间按指定时区格式化为 yyyy-MM-dd HH:mm。 */
object MessageTimestampFormatter {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** Bind enabled to AppearancePreferences.messageTimestampsEnabled in the host projection. */
    fun timestamp(
        message: Message,
        enabled: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        return timestamp(message.createdAt, enabled, zone)
    }

    internal fun timestamp(createdAt: Long?, enabled: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!enabled) return ""
        val time = createdAt ?: return ""
        return format.format(Instant.ofEpochMilli(time).atZone(zone))
    }
}
