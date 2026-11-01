package app.daiyu.android.voice

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

internal object SpeechAudioFixtures {
    const val DATA_OFFSET = 78
    val pcm get() = ByteArray(163532) { (it * 17).toByte() }
    fun uint(value: Long) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    fun read(bytes: ByteArray, offset: Int) = (0..3).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 255L) shl (index * 8))
    }
    fun chunk(tag: String, bytes: ByteArray) = tag.toByteArray() + uint(bytes.size.toLong()) + bytes +
        if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    fun wave(unknownRiff: Boolean = true, unknownData: Boolean = true, oddMetadata: Boolean = false): ByteArray {
        val format = byteArrayOf(1, 0, 1, 0) + uint(24000) + uint(48000) + byteArrayOf(2, 0, 16, 0)
        val metadata = chunk("LIST", ByteArray(if (oddMetadata) 25 else 26) { (it + 32).toByte() })
        val body = "WAVE".toByteArray() + chunk("fmt ", format) + metadata + "data".toByteArray() +
            uint(if (unknownData) 0xffff_ffffL else pcm.size.toLong()) + pcm
        return "RIFF".toByteArray() + uint(if (unknownRiff) 0xffff_ffffL else body.size.toLong()) + body
    }
}

class SpeechAudioFileTest {
    @Test fun finalizesStreamedWaveWithListAndPreservesEveryOtherByte() {
        val input = SpeechAudioFixtures.wave()
        val original = input.copyOf()
        val result = SpeechAudioFile.normalize(input)
        assertEquals(163610, result.size)
        assertEquals(163602L, SpeechAudioFixtures.read(result, 4))
        assertEquals(163532L, SpeechAudioFixtures.read(result, 74))
        assertArrayEquals(SpeechAudioFixtures.pcm, result.copyOfRange(78, result.size))
        for (index in result.indices) if (index !in 4..7 && index !in 74..77) assertEquals(input[index], result[index])
        assertArrayEquals(original, input)
    }
    @Test fun ordinaryWaveIsNotRewritten() {
        val bytes = SpeechAudioFixtures.wave(false, false)
        assertSame(bytes, SpeechAudioFile.normalize(bytes))
    }
    @Test fun knownDataAndUnknownRiffOnlyRewritesRiff() {
        val input = SpeechAudioFixtures.wave(true, false)
        assertArrayEquals(SpeechAudioFixtures.wave(false, false), SpeechAudioFile.normalize(input))
    }
    @Test fun knownRiffAndUnknownDataOnlyRewritesData() {
        val input = SpeechAudioFixtures.wave(false, true)
        assertArrayEquals(SpeechAudioFixtures.wave(false, false), SpeechAudioFile.normalize(input))
    }
    @Test fun oddMetadataPaddingIsRespected() {
        assertArrayEquals(SpeechAudioFixtures.wave(false, false, true),
            SpeechAudioFile.normalize(SpeechAudioFixtures.wave(true, true, true)))
    }
    @Test fun normalTrailingChunksRemainIntact() {
        val input = SpeechAudioFixtures.wave(true, false) + SpeechAudioFixtures.chunk("JUNK", byteArrayOf(1, 2, 3))
        val result = SpeechAudioFile.normalize(input)
        assertEquals(result.size.toLong() - 8, SpeechAudioFixtures.read(result, 4))
        assertArrayEquals(input.copyOfRange(8, input.size), result.copyOfRange(8, result.size))
    }
    @Test fun otherFormatsAndRf64AreNotReinterpretedAsPcm() {
        for (bytes in listOf(byteArrayOf(73, 68, 51), "OggSfixture".toByteArray(),
            "RF64".toByteArray() + SpeechAudioFixtures.wave().copyOfRange(4, 163610))) {
            assertSame(bytes, SpeechAudioFile.normalize(bytes))
        }
    }
    @Test fun invalidChunkBoundaryIsRejectedInsteadOfClamped() {
        val input = SpeechAudioFixtures.wave().also { SpeechAudioFixtures.uint(500000).copyInto(it, 40) }
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(input) }
    }
    @Test fun partialPcmFrameIsRejectedInsteadOfDropped() {
        val input = SpeechAudioFixtures.wave().dropLast(1).toByteArray()
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(input) }
    }
    @Test fun unsupportedStreamedFormatIsNotBlindlyRepaired() {
        val input = SpeechAudioFixtures.wave().also { it[20] = 3 }
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(input) }
    }
    @Test fun mismatchedKnownRiffSizeIsNotSilentlyOverwritten() {
        val input = SpeechAudioFixtures.wave(false, true).also { SpeechAudioFixtures.uint(12).copyInto(it, 4) }
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(input) }
    }
    @Test fun extendedSizeAndTruncatedHeaderAreRejected() {
        val input = SpeechAudioFixtures.wave().also { "ds64".toByteArray().copyInto(it, 36) }
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(input) }
        assertThrows(IOException::class.java) { SpeechAudioFile.normalize(SpeechAudioFixtures.wave().copyOf(14)) }
    }
}
