package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test

class GenerationRunConfigTest {
    @Test fun freezesCollectionsAndInputBytesBeforeTheParentApproval() {
        val bytes = byteArrayOf(1, 2)
        val references = mutableListOf(GenerationInput(bytes, "image/png"))
        val parameters = mutableMapOf("unsupported" to "original")
        val item = GenerationRunConfig.Item("item", "prompt", references, parameters = parameters)
        val items = mutableListOf(item)
        val batch = GenerationRunConfig.Batch("batch", GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            "https://example.test/v1/images/edits", "key", "model", items)
        val batches = mutableListOf(batch)
        val config = GenerationRunConfig(batches)
        val fingerprint = batch.fingerprint
        val configFingerprint = config.fingerprint
        bytes[0] = 9; references.clear(); parameters.clear(); items.clear(); batches.clear()
        assertEquals(1, config.batches.size)
        val request = batch.requests { GenerationIdentity("operation", it) }.single()
        assertArrayEquals(byteArrayOf(1, 2), request.references.single().bytes)
        assertEquals("original", request.parameters["unsupported"])
        assertEquals(fingerprint, batch.fingerprint)
        assertEquals(configFingerprint, config.fingerprint)
        assertFalse(config.toString().contains("prompt"))
        assertFalse(batch.toString().contains("key"))
    }

    @Test fun configFingerprintIsPersistentAndIncludesPollBoundAndAllBatches() {
        fun batch(id: String) = GenerationRunConfig.Batch(id, GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://a.test/v1/images/generations", "key", "model",
            listOf(GenerationRunConfig.Item("item", "draw")))
        val first = batch("first")
        val second = batch("second")
        val original = GenerationRunConfig(listOf(first, second), 2).fingerprint
        assertEquals(original, GenerationRunConfig(listOf(batch("first"), batch("second")), 2).fingerprint)
        assertNotEquals(original, GenerationRunConfig(listOf(first, second), 3).fingerprint)
        assertNotEquals(original, GenerationRunConfig(listOf(first), 2).fingerprint)
        assertNotEquals(original, GenerationRunConfig(listOf(second, first), 2).fingerprint)
    }

    @Test fun paidEffectFingerprintBindsEveryFrozenRequestField() {
        fun batch(prompt: String = "draw", key: String = "key", endpoint: String = "https://a.test/v1/images/generations") =
            GenerationRunConfig.Batch("batch", GenerationProtocol.OPENAI_IMAGES_JSON, endpoint, key, "model",
                listOf(GenerationRunConfig.Item("item", prompt)))
        val original = batch()
        assertEquals(original.fingerprint, batch().fingerprint)
        assertNotEquals(original.fingerprint, batch(prompt = "other").fingerprint)
        assertNotEquals(original.fingerprint, batch(key = "another-account").fingerprint)
        assertNotEquals(original.fingerprint, batch(endpoint = "https://b.test/v1/images/generations").fingerprint)
    }
}
