package app.daiyu.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.concurrent.TimeUnit

/** Local synthetic 64px AVC original; same bounded device-codec technique as
 * VideoChatHostAndroidTest. No provider, credential, download or paid generation is involved.
 * Real encoder/decoder availability is a prerequisite, not a skipped positive assertion.
 */
internal fun encodeOfflineBackupMp4(file: File) {
    val codec = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    var muxerStarted = false
    try {
        val format = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start(); started = true
        val frame = ByteArray(64 * 64 * 3 / 2) { if (it < 64 * 64) 96.toByte() else 128.toByte() }
        val info = MediaCodec.BufferInfo()
        var inputCount = 0
        var outputEnded = false
        var track = -1
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!outputEnded && System.nanoTime() < deadline) {
            if (inputCount <= 3) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (inputCount == 3) codec.queueInputBuffer(index, 0, 0, 300_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                        codec.queueInputBuffer(index, 0, frame.size, inputCount * 100_000L, 0)
                    }
                    inputCount++
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxerStarted)
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        check(outputEnded && muxerStarted) { "AVC backup fixture encoder did not complete" }
    } finally {
        try { if (started) codec.stop() } finally { codec.release() }
        try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
    }
    check(file.length() > 0)
}
