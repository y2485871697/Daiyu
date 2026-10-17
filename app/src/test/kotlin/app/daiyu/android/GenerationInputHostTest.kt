package app.daiyu.android

import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.model.generation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class GenerationInputHostTest {
    private val png = GenerationInput(byteArrayOf(1, 2, 3), "image/png")
    private val edit = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART
    private fun newIdentity() = GenerationInputIdentity(Any(), Any(), "conversation", 8, 9,
        "exact draft", 10, "profile", emptyList())

    private inner class Harness {
        var identity: GenerationInputIdentity? = newIdentity()
        val jobs = mutableListOf<() -> Unit>()
        val returns = mutableListOf<() -> Unit>()
        var pending = false
        var reads = 0
        var applied = 0
        val host = GenerationInputHost(current = { identity },
            read = { _, request -> assertFalse(request.cancelled.get()); reads++; png },
            dispatch = { returns += it }, changed = { pending = it },
            finished = { _, result -> assertTrue(result.isSuccess); applied++ },
            workers = GenerationInputWorkers(execute = { jobs += it }, cancel = { it() }))
        fun begin() = host.begin(checkNotNull(identity), edit, GenerationInputTarget.REFERENCE, null)
        fun drain() { jobs.removeAt(0)(); returns.removeAt(0)() }
    }

    @Test fun pickerResultBeforeResumeCannotReadOrExecuteAndNeedsSameIdentity() {
        val h = Harness()
        assertNull(h.begin())
        h.host.resume()
        assertNotNull(h.begin()); assertTrue(h.pending)
        assertTrue(h.host.pause())
        h.host.deliver("content://local/image")
        assertTrue(h.jobs.isEmpty()); assertEquals(0, h.reads); assertEquals(0, h.applied)
        h.host.resume()
        assertEquals(1, h.jobs.size)
        h.drain()
        assertFalse(h.pending); assertEquals(1, h.reads); assertEquals(1, h.applied)
        h.host.deliver("content://local/duplicate")
        h.host.resume()
        assertTrue(h.jobs.isEmpty()); assertEquals(1, h.applied)
        h.host.close()
    }

    @Test fun resumeBeforeCallbackAlsoImportsOnlyOnce() {
        val h = Harness(); h.host.resume(); assertNotNull(h.begin())
        assertTrue(h.host.pause()); h.host.resume()
        assertTrue(h.jobs.isEmpty())
        h.host.deliver("content://local/image"); h.host.resume(); h.host.resume()
        assertEquals(1, h.jobs.size); h.drain(); assertEquals(1, h.applied)
        h.host.close()
    }

    @Test fun cancellationKeepsExternalTombstoneSoOldCallbackCannotStealNewRequest() {
        val h = Harness(); h.host.resume()
        val old = checkNotNull(h.begin()); h.host.invalidate()
        assertTrue(old.cancelled.get()); assertFalse(h.pending)
        assertNull(h.begin())
        h.host.deliver("content://local/old")
        assertTrue(h.jobs.isEmpty()); assertEquals(0, h.applied)
        assertNotNull(h.begin()); h.host.deliver(null)
        assertFalse(h.pending); assertTrue(h.jobs.isEmpty())
        assertNotNull(h.begin()); h.host.close()
        h.host.deliver("content://local/destroyed"); h.host.resume()
        assertTrue(h.jobs.isEmpty())
    }

    @Test fun everyFrozenIdentityComponentRejectsLateResultIncludingEditThenRevert() {
        val modifications: List<(GenerationInputIdentity) -> GenerationInputIdentity> = listOf(
            { it.copy(entry = Any()) }, { it.copy(visit = Any()) }, { it.copy(conversation = "other") },
            { it.copy(editorGeneration = 99) }, { it.copy(draftRevision = 99) },
            { it.copy(text = "changed") }, { it.copy(formRevision = 99) },
            { it.copy(profileId = "other") }, { it.copy(inputIds = listOf("other")) })
        for (modify in modifications) {
            val h = Harness(); h.host.resume(); val old = checkNotNull(h.begin())
            assertTrue(h.host.pause()); h.identity = modify(checkNotNull(h.identity))
            h.host.deliver("content://local/old"); h.host.resume()
            assertTrue(old.cancelled.get()); assertTrue(h.jobs.isEmpty()); assertEquals(0, h.applied)
            h.host.close()
        }
    }

    @Test fun backgroundWorkerPauseAndSecondPickerPauseCannotResurrectForm() {
        val h = Harness(); h.host.resume(); assertNotNull(h.begin())
        assertTrue(h.host.pause()); h.host.resume()
        assertFalse(h.host.pause()) // Not another exemption for the same external hop.
        h.host.resume(); h.host.deliver("content://local/late")
        assertTrue(h.jobs.isEmpty()); assertEquals(0, h.applied)
        assertNotNull(h.begin()); h.host.deliver("content://local/image")
        h.jobs.removeAt(0)() // Real worker completion queued, UI now backgrounds.
        assertFalse(h.host.pause()); h.host.resume(); h.returns.removeAt(0)()
        assertEquals(0, h.applied); h.host.close()
    }

    @Test fun navigationOrProfileChangeWhileWorkerIsRunningDropsCompletion() {
        for (navigate in listOf(false, true)) {
            val h = Harness(); h.host.resume(); assertNotNull(h.begin())
            h.host.deliver("content://local/image"); h.jobs.removeAt(0)()
            h.identity = if (navigate) null else checkNotNull(h.identity).copy(profileId = "new")
            h.returns.removeAt(0)(); assertEquals(0, h.applied); assertFalse(h.pending)
            h.host.close()
        }
    }

    private fun read(bytes: ByteArray, protocol: GenerationProtocol = edit,
        target: GenerationInputTarget = GenerationInputTarget.REFERENCE, reference: GenerationInput? = null,
        inspector: ImageInspector = ImageInspector { ImageDimensions("image/png", 2, 3) },
        source: String = "content://local/image", cancelled: () -> Boolean = { false }): GenerationInput =
        GenerationInputImport.read(source, { ByteArrayInputStream(bytes) }, inspector,
            protocol, target, reference, cancelled)

    @Test fun boundedOriginalBytesRealMimeDimensionsAndProtocolAreNotProviderMetadata() {
        val bytes = byteArrayOf(1, 2, 3)
        val input = read(bytes)
        assertArrayEquals(bytes, input.bytes); bytes[0] = 9
        assertEquals(1.toByte(), input.bytes[0]); assertEquals("image/png", input.mimeType)
        fun rejects(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
        rejects { read(byteArrayOf()) }
        rejects { read(byteArrayOf(1), source = "file:///private/photo.png") }
        rejects { read(byteArrayOf(1), source = "https://remote.invalid/photo.png") }
        rejects { read(byteArrayOf(1), cancelled = { true }) }
        rejects { read(byteArrayOf(1), protocol = GenerationProtocol.OPENAI_IMAGES_JSON) }
        rejects { read(byteArrayOf(1), target = GenerationInputTarget.MASK) }
        rejects { read(byteArrayOf(1), protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            target = GenerationInputTarget.MASK, reference = png) }
        rejects { read(byteArrayOf(1), inspector = ImageInspector { ImageDimensions("image/jpeg", 2, 3) }) }
        rejects { read(byteArrayOf(1), inspector = ImageInspector { ImageDimensions("image/png", 0, 3) }) }
        rejects { read(byteArrayOf(1), inspector = ImageInspector { ImageDimensions("image/png", 40001, 1000) }) }
        rejects { read(byteArrayOf(1), target = GenerationInputTarget.MASK, reference = png,
            inspector = ImageInspector { ImageDimensions("image/png", if (it.size == 1) 1 else 2, 3) }) }
        assertArrayEquals(byteArrayOf(1), read(byteArrayOf(1), target = GenerationInputTarget.MASK, reference = png).bytes)
        assertEquals("image/jpeg", read(byteArrayOf(1), protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            inspector = ImageInspector { ImageDimensions("image/jpeg", 2, 3) }).mimeType)
    }

    @Test fun readsAtMostTwentyMiBPlusSentinelAndNeverTrustsAvailable() {
        val limit = GenerationRequestPlan.MAX_INPUT_BYTES
        var consumed = 0
        var closed = false
        val source = object : InputStream() {
            override fun available() = Int.MAX_VALUE
            override fun read(): Int { consumed++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(bytes, offset, offset + length, 1.toByte()); consumed += length; return length
            }
            override fun close() { closed = true }
        }
        assertTrue(runCatching { GenerationInputImport.read("content://local/huge", { source },
            ImageInspector { error("oversized input must not reach inspection") }, edit,
            GenerationInputTarget.REFERENCE, null) { false } }.isFailure)
        assertEquals(limit + 1, consumed); assertTrue(closed)
        assertEquals(limit, read(ByteArray(limit)).bytes.size)
    }

    @Test fun roleOrderedContentIdsAndProtocolShapesAreExact() {
        val a = GenerationFormInputs.ids(listOf(png), png)
        assertEquals(2, a.size); assertNotEquals(a[0], a[1])
        assertEquals(a, GenerationFormInputs.ids(listOf(GenerationInput(png.bytes, png.mimeType)), png))
        assertNotEquals(a, GenerationFormInputs.ids(listOf(GenerationInput(byteArrayOf(4), "image/png")), png))
        assertTrue(GenerationFormInputs.supported(edit, listOf(png), png))
        assertFalse(GenerationFormInputs.supported(edit, listOf(png, png), null))
        assertFalse(GenerationFormInputs.supported(GenerationProtocol.OPENAI_IMAGES_JSON, listOf(png), null))
        assertTrue(GenerationFormInputs.supported(GenerationProtocol.OPENAI_VIDEO_MULTIPART, listOf(png), null))
        assertFalse(GenerationFormInputs.supported(GenerationProtocol.OPENAI_VIDEO_MULTIPART, listOf(png), png))
    }
}
