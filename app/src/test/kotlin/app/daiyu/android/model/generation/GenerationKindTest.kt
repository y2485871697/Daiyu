package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test

class GenerationKindTest {
    @Test fun imageAndVideoIntentsAcceptOnlyMatchingProtocols() {
        assertTrue(GenerationKind.IMAGE.accepts(GenerationProtocol.OPENAI_IMAGES_JSON))
        assertTrue(GenerationKind.IMAGE.accepts(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART))
        assertFalse(GenerationKind.IMAGE.accepts(GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        GenerationProtocol.entries.forEach { protocol ->
            assertEquals(protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART, GenerationKind.VIDEO.accepts(protocol))
        }
    }
}
