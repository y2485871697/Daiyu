package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test

class GenerationKindTest {
    @Test fun imageAndVideoIntentsAcceptOnlyMatchingProtocols() {
        val images = setOf(GenerationProtocol.OPENAI_IMAGES_JSON, GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            GenerationProtocol.OPENAI_IMAGE_EDIT_JSON, GenerationProtocol.OPENAI_IMAGE_REFERENCE_JSON,
            GenerationProtocol.NOVELAI_IMAGE_JSON)
        val videos = setOf(GenerationProtocol.OPENAI_VIDEO_MULTIPART, GenerationProtocol.OPENAI_VIDEO_JSON,
            GenerationProtocol.OPENAI_VIDEOS_GENERATIONS_JSON, GenerationProtocol.OPENAI_VIDEO_GENERATIONS_JSON,
            GenerationProtocol.ARK_CONTENTS_JSON, GenerationProtocol.VIDEO_CHAT_JSON)
        assertEquals(GenerationProtocol.entries.toSet(), images + videos)
        assertTrue(images.intersect(videos).isEmpty())
        GenerationProtocol.entries.forEach { protocol ->
            assertEquals(protocol in images, GenerationKind.IMAGE.accepts(protocol))
            assertEquals(protocol in videos, GenerationKind.VIDEO.accepts(protocol))
        }
    }
}
