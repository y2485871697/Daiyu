package app.daiyu.android

import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.model.generation.GenerationInput
import app.daiyu.android.model.generation.GenerationProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fake blocking providers exercise physical lifetime, not just rejection of a late UI result. */
class GenerationInputDrainTest {
    private val edit = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART
    private val png = GenerationInput(byteArrayOf(1), "image/png")
    private val inspector = ImageInspector { ImageDimensions("image/png", 1, 1) }

    private fun await(latch: CountDownLatch) = assertTrue("worker/cancellation did not progress",
        latch.await(5, TimeUnit.SECONDS))
    private fun awaitDrain(request: GenerationInputHost.Request) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!request.drained && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue("physical drain was not observed", request.drained)
    }

    private fun awaitCapacity(workers: GenerationInputWorkers) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (workers.pending && System.nanoTime() < deadline) Thread.sleep(2)
        assertFalse("stable manager still owns the operation", workers.pending)
    }

    private inner class Harness(workers: GenerationInputWorkers,
        reader: (String, GenerationInputHost.Request) -> GenerationInput) {
        private val identity = GenerationInputIdentity(Any(), Any(), "chat", 1, 2,
            "draft", 3, "profile", emptyList())
        val ui = ConcurrentLinkedQueue<() -> Unit>()
        var pending = false
        var applied = 0
        var failures = 0
        val host = GenerationInputHost({ identity }, reader, { ui.add(it) },
            { pending = it }, { _, result -> if (result.isSuccess) applied++ else failures++ }, workers)
        fun start(): GenerationInputHost.Request {
            host.resume()
            val request = checkNotNull(host.begin(identity, edit, GenerationInputTarget.REFERENCE, null))
            host.deliver("content://test/image")
            return request
        }
        fun pump() { while (true) (ui.poll() ?: break).invoke() }
        fun awaitCompletion() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (pending && System.nanoTime() < deadline) { pump(); Thread.sleep(2) }
            assertFalse(pending)
        }
    }

    @Test fun blockedOpenCancellationIsOffUiAndPendingWaitsForPhysicalExit() {
        val opened = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val openCancelled = CountDownLatch(1)
        val h = Harness(GenerationInputWorkers()) { _, request ->
            // Fake CancellationSignal.cancel can itself block in a remote provider.
            request.onCancel {
                cancelEntered.countDown()
                releaseCancel.await()
                openCancelled.countDown()
            }
            opened.countDown()
            openCancelled.await()
            throw IOException("cancelled open")
        }
        val request = h.start()
        try {
            await(opened)
            h.host.invalidate() // Must return even though the cancellation lane will block.
            assertTrue(request.cancelled.get())
            await(cancelEntered)
            assertTrue(h.pending); assertFalse(request.drained)
            assertNull(h.host.begin(request.identity, edit, GenerationInputTarget.REFERENCE, null))
            releaseCancel.countDown()
            awaitDrain(request)
            // Drain and logical UI completion are deliberately separate observations.
            assertTrue(h.pending)
            h.awaitCompletion()
            assertEquals(0, h.applied); assertEquals(0, h.failures)
        } finally {
            releaseCancel.countDown(); openCancelled.countDown(); h.host.close()
            awaitDrain(request)
        }
    }

    @Test fun blockedReadClosesStreamEvenWhenSignalCancelBlocksAndDrainIncludesCancelFinally() {
        val reading = CountDownLatch(1)
        val signalEntered = CountDownLatch(1)
        val releaseSignal = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val readerExited = CountDownLatch(1)
        val workers = GenerationInputWorkers()
        val h = Harness(workers) { source, request ->
            request.onCancel { signalEntered.countDown(); releaseSignal.await() }
            val stream = object : InputStream() {
                override fun read(): Int { reading.countDown(); streamClosed.await(); return -1 }
                override fun close() { streamClosed.countDown() }
            }
            try {
                GenerationInputImport.read(source, {
                    request.onCancel { stream.close() }
                    stream
                }, inspector, edit, GenerationInputTarget.REFERENCE, null) { request.cancelled.get() }
            } finally { request.clearCancellation(); readerExited.countDown() }
        }
        val request = h.start()
        try {
            await(reading)
            h.host.invalidate()
            await(signalEntered); await(streamClosed); await(readerExited)
            assertFalse("signal callback still owns physical capacity", request.drained)
            assertTrue(h.pending)
            val replacement = Harness(workers) { _, _ -> error("must not start another provider") }
            replacement.start()
            assertEquals(1, replacement.failures)
            replacement.host.close()
            releaseSignal.countDown()
            awaitDrain(request); h.awaitCompletion()
            assertEquals(0, h.applied)
        } finally {
            releaseSignal.countDown(); streamClosed.countDown(); h.host.close()
            awaitDrain(request)
        }
    }

    @Test fun closeAndRepeatedRecreationCannotReplaceUndrainedProviderOrApplyLateOpen() {
        val opened = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val signalCancelled = CountDownLatch(1)
        val lateStreamClosed = CountDownLatch(1)
        val opens = AtomicInteger()
        val workers = GenerationInputWorkers()
        val old = Harness(workers) { source, request ->
            request.onCancel { signalCancelled.countDown() }
            try {
                GenerationInputImport.read(source, {
                    opens.incrementAndGet(); opened.countDown()
                    // Deliberately ignores cancellation and interruption until the test releases it.
                    releaseOpen.await()
                    val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
                        override fun close() { lateStreamClosed.countDown(); super.close() }
                    }
                    request.onCancel { stream.close() }
                    stream
                }, inspector, edit, GenerationInputTarget.REFERENCE, null) { request.cancelled.get() }
            } finally { request.clearCancellation() }
        }
        val request = old.start()
        try {
            await(opened)
            old.host.close()
            await(signalCancelled)
            assertTrue(old.host.pending); assertFalse(request.drained)
            repeat(32) {
                val replacement = Harness(workers) { _, _ -> opens.incrementAndGet(); png }
                replacement.start()
                assertFalse(replacement.pending); assertEquals(1, replacement.failures)
                replacement.host.close()
            }
            assertEquals(1, opens.get())
            releaseOpen.countDown()
            await(lateStreamClosed); awaitDrain(request); awaitCapacity(workers)
            assertFalse(old.host.pending)
            old.pump(); assertEquals(0, old.applied); assertTrue(old.ui.isEmpty())
            val replacement = Harness(workers) { _, _ -> opens.incrementAndGet(); png }
            replacement.start(); replacement.awaitCompletion()
            assertEquals(1, replacement.applied); assertEquals(2, opens.get())
            replacement.host.close()
        } finally {
            releaseOpen.countDown(); old.host.close(); awaitDrain(request)
        }
    }

    @Test fun activityAdapterAndRecreationKeepCancellationAndStableCapacityWiring() {
        fun source(name: String) = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "app/src/main/kotlin/app/daiyu/android/$name") }.first { it.isFile }.readText()
        val main = source("MainActivity.kt")
        val adapter = main.substringAfter("private val generationInputsDelegate = lazy {")
            .substringBefore("private val generationInputs get()")
        assertTrue(adapter.contains("val resolver = applicationContext.contentResolver"))
        assertTrue(adapter.contains("val cancellation = android.os.CancellationSignal()"))
        assertTrue(adapter.contains("resolver.openAssetFileDescriptor(uri, \"r\", cancellation)"))
        assertTrue(adapter.contains("request.onCancel { cancellation.cancel() }"))
        assertTrue(adapter.contains("request.onCancel { stream.close() }"))
        assertTrue(adapter.contains("finally { request.clearCancellation() }"))
        assertFalse(adapter.contains("val descriptor = contentResolver."))
        assertTrue(main.contains("if (generationInputsDelegate.isInitialized()) generationInputs.close()"))
        val host = source("GenerationInputHost.kt").substringAfter("internal class GenerationInputHost(")
        assertTrue(host.contains("workers: GenerationInputWorkers = GenerationInputWorkers.shared"))
        assertTrue(host.contains("val owner = WeakReference(this)"))
        assertTrue(host.contains("val reader = read"))
        assertTrue(host.contains("if (request?.reading != true) active = null"))
        assertTrue(host.contains("if (exited && cancelling == 0)"))
        assertFalse(host.contains("ThreadPoolExecutor("))
        assertFalse(host.contains(".shutdownNow()"))
        assertFalse(host.contains("awaitTermination("))
    }
}
