package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MessageTimestampFormatterTest {
    private fun message(time: Long?) = Message(MessageId("message"), ConversationId("conversation"),
        RunId("run"), "assistant", emptyList(), RunStatus.RUNNING, createdAt = time)

    @Test fun realTimeUsesExplicitZoneAndExistingMinutePrecision() {
        val message = message(Instant.parse("2025-02-03T04:05:59.123Z").toEpochMilli())
        assertEquals("2025-02-03 04:05", MessageTimestampFormatter.timestamp(message, zone = ZoneId.of("UTC")))
        assertEquals("2025-02-03 12:05", MessageTimestampFormatter.timestamp(message, zone = ZoneId.of("Asia/Shanghai")))
        assertEquals(MessageTimestampFormatter.timestamp(message, zone = ZoneId.of("UTC")),
            MessageTimestampFormatter.timestamp(message.copy(status = RunStatus.COMPLETED), zone = ZoneId.of("UTC")))
    }

    @Test fun unknownAndDisabledHaveNoLabelAndDoNotInventNow() {
        assertEquals("", MessageTimestampFormatter.timestamp(message(null)))
        assertEquals("", MessageTimestampFormatter.timestamp(message(123L), enabled = false))
        assertEquals("1970-01-01 00:00", MessageTimestampFormatter.timestamp(message(0L), zone = ZoneId.of("UTC")))
    }

    @Test fun daylightSavingConversionIsExplicitAndStable() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("2025-03-09 01:59", MessageTimestampFormatter.timestamp(
            message(Instant.parse("2025-03-09T06:59:00Z").toEpochMilli()), zone = zone))
        assertEquals("2025-03-09 03:00", MessageTimestampFormatter.timestamp(
            message(Instant.parse("2025-03-09T07:00:00Z").toEpochMilli()), zone = zone))
    }
}
