package app.daiyu.android.model.generation
import org.junit.Assert.*
import org.junit.Test
class ImageTargetSizeRetainedTest {
    @Test fun knownRatiosKeepTheirExactGeometry() {
        assertEquals("1152x2048",ImageTargetSize.resolve("9:16","2k"))
        assertEquals("1024x2048",ImageTargetSize.resolve("1:2","2k"))
        assertEquals("1536x2048",ImageTargetSize.resolve("3:4","2k"))
        assertEquals("4096x2304",ImageTargetSize.resolve("16:9","4k"))
        assertEquals("864x1536",ImageTargetSize.resolve("9:16","1.5k"))
        assertEquals("2044x876",ImageTargetSize.resolve("21:9","2k"))
        assertEquals("2046x1364",ImageTargetSize.resolve("3:2","2k"))
        assertEquals("2048x1152",ImageTargetSize.resolve("16.00:9.00","2k"))
    }
    @Test fun ambiguousAndFractionalTargetsFailInsteadOfSnapping() {
        for ((ratio,tier) in listOf(null to "2k","9:16" to null,"auto" to "2k","9:16" to "8k","1:9999" to "2k")) {
            assertThrows(ImageGenerationParameterException::class.java) { ImageTargetSize.resolve(ratio,tier) }
        }
    }
}
