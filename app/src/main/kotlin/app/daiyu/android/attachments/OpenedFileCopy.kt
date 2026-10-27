package app.daiyu.android.attachments

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

/** Bounded streaming copy; the caller publishes only after the complete stream has closed. */
internal fun copyOpenedFile(input: InputStream, output: OutputStream, limit: Long, cancelled: () -> Boolean): Long {
    require(limit >= 0)
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        if (cancelled()) throw CancellationException("attachment_cancelled")
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
        if (count < 0) return total
        if (count == 0) continue
        if (count.toLong() > limit - total) throw IOException("attachment_too_large")
        output.write(buffer, 0, count)
        total += count
    }
}
