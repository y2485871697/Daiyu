package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GenerationCancellationTest {
    @Test fun disconnectAbortsEveryBlockingWirePhaseWithoutInterruptAndReportsActualExit() {
        for (point in Point.values()) {
            val connection = BlockingConnection(point)
            val cancellation = GenerationCancellation()
            val worker = Worker { UrlConnectionGenerationHttp { connection }.execute(wire("POST"), cancellation) }
            try {
                await(connection.entered)
                val requested = cancellation.cancel()
                assertTrue(connection.disconnected.count == 0L)
                assertTrue(requested.cancellationRequested)
                assertFalse("request must not report worker exit at $point", requested.isDrained)
                assertFalse(cancellation.awaitDrained(1).isDrained)
                assertFalse(worker.thread.isInterrupted)
                connection.exit.countDown()
                assertTrue(cancellation.awaitDrained(2000).isDrained)
                assertTrue(worker.result() is IOException)
                if (point == Point.READ) assertTrue(connection.inputClosed)
                if (point == Point.WRITE) assertTrue(connection.outputClosed)
            } finally {
                connection.exit.countDown()
                cancellation.cancel()
                worker.join()
            }
        }
    }

    @Test fun cancellationIsStickyForAConnectionReturnedLateByFactory() {
        val factoryEntered = CountDownLatch(1)
        val factoryReturn = CountDownLatch(1)
        val connection = BlockingConnection(Point.READ)
        val cancellation = GenerationCancellation()
        val worker = Worker {
            UrlConnectionGenerationHttp {
                factoryEntered.countDown()
                await(factoryReturn)
                connection
            }.execute(wire("POST"), cancellation)
        }
        try {
            await(factoryEntered)
            assertFalse(cancellation.cancel().isDrained)
            assertEquals(1L, connection.disconnected.count)
            factoryReturn.countDown()
            assertTrue(worker.result() is GenerationCancelledException)
            assertEquals(0L, connection.disconnected.count)
            assertEquals(0, connection.wireEntries)
            assertTrue(cancellation.snapshot().isDrained)
        } finally {
            factoryReturn.countDown()
            connection.exit.countDown()
            cancellation.cancel()
            worker.join()
        }
    }

    @Test fun lateStreamIsClosedAfterRacingCancellation() {
        val getterEntered = CountDownLatch(1)
        val getterReturn = CountDownLatch(1)
        var closed = false
        val connection = object : HttpURLConnection(URL("https://api.example/a")) {
            var disconnects = 0
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnects++ }
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = -1L
            override fun getInputStream(): InputStream {
                getterEntered.countDown()
                await(getterReturn)
                return object : InputStream() {
                    override fun read(): Int = throw AssertionError("cancelled stream must not be read")
                    override fun close() { closed = true }
                }
            }
        }
        val cancellation = GenerationCancellation()
        val worker = Worker { UrlConnectionGenerationHttp { connection }.execute(wire(), cancellation) }
        try {
            await(getterEntered)
            assertFalse(cancellation.cancel().isDrained)
            getterReturn.countDown()
            assertTrue(worker.result() is GenerationCancelledException)
            assertTrue(closed)
            assertTrue(connection.disconnects >= 2)
            assertTrue(cancellation.snapshot().isDrained)
        } finally {
            getterReturn.countDown()
            worker.join()
        }
    }

    @Test fun getterStartingAfterCancellationCheckStillRequiresActualExit() {
        val checked = CountDownLatch(1)
        val startGetter = CountDownLatch(1)
        val getterEntered = CountDownLatch(1)
        val returnGetter = CountDownLatch(1)
        var closed = false
        val connection = object : HttpURLConnection(URL("https://api.example/a")) {
            var disconnects = 0
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnects++ }
            override fun getInputStream(): InputStream {
                getterEntered.countDown()
                await(returnGetter)
                return object : InputStream() {
                    override fun read(): Int = throw AssertionError("cancelled stream must not be read")
                    override fun close() { closed = true }
                }
            }
        }
        val cancellation = GenerationCancellation()
        val worker = Worker {
            val execution = cancellation.begin()
            try {
                execution.register(connection)
                cancellation.throwIfCancelled()
                checked.countDown()
                await(startGetter) // model the scheduling gap between the check and platform getter
                execution.register(connection.inputStream)
            } finally { execution.finish() }
        }
        try {
            await(checked)
            assertFalse(cancellation.cancel().isDrained)
            assertEquals(1, connection.disconnects)
            startGetter.countDown()
            await(getterEntered)
            // Disconnect before getter entry is not a platform guarantee against later blocking.
            // Keep the exit gate closed; another pre-getter check cannot make this race disappear.
            assertFalse(cancellation.awaitDrained(1).isDrained)
            returnGetter.countDown()
            assertTrue(worker.result() is GenerationCancelledException)
            assertTrue(closed)
            assertEquals(2, connection.disconnects)
            assertTrue(cancellation.snapshot().isDrained)
        } finally {
            startGetter.countDown()
            returnGetter.countDown()
            worker.join()
        }
    }

    @Test fun pendingCleanupIsObservableAndDoesNotHoldTheStateLock() {
        val connection = BlockingConnection(Point.READ, blockClose = true)
        val cancellation = GenerationCancellation()
        val worker = Worker { UrlConnectionGenerationHttp { connection }.execute(wire(), cancellation) }
        var cancelWorker: Worker? = null
        try {
            await(connection.entered)
            cancelWorker = Worker { cancellation.cancel() }
            await(connection.closeEntered)
            val state = cancellation.snapshot()
            assertTrue(state.pendingCleanup > 0)
            assertFalse(state.isDrained)
            connection.exit.countDown()
            worker.join()
            assertFalse(cancellation.awaitDrained(1).isDrained)
            connection.closeReturn.countDown()
            cancelWorker.join()
            assertTrue(cancellation.awaitDrained(2000).isDrained)
        } finally {
            connection.exit.countDown()
            connection.closeReturn.countDown()
            worker.join()
            cancelWorker?.join()
        }
    }

    @Test fun everyExecutionIsDisconnectedBeforeABlockingStreamClose() {
        val first = BlockingConnection(Point.READ, blockClose = true)
        val second = BlockingConnection(Point.READ)
        val cancellation = GenerationCancellation()
        val firstWorker = Worker { UrlConnectionGenerationHttp { first }.execute(wire(), cancellation) }
        var secondWorker: Worker? = null
        var cancelWorker: Worker? = null
        try {
            // Ensure A was registered first, so per-execution cleanup would block before aborting B.
            await(first.entered)
            secondWorker = Worker { UrlConnectionGenerationHttp { second }.execute(wire(), cancellation) }
            await(second.entered)
            cancelWorker = Worker { cancellation.cancel() }
            await(first.closeEntered)
            assertEquals("B must be disconnected while A.close is still blocked", 0L, second.disconnected.count)
            assertEquals(1L, first.closeReturn.count)
            val closing = cancellation.snapshot()
            assertEquals(2, closing.activeExecutions)
            assertEquals(2, closing.pendingCleanup)
            assertFalse(closing.isDrained)
            // A second cancel must neither take these resources again nor wait on their close.
            assertEquals(2, cancellation.cancel().pendingCleanup)
            first.exit.countDown()
            second.exit.countDown()
            firstWorker.join()
            secondWorker.join()
            val exited = cancellation.awaitDrained(1)
            assertEquals(0, exited.activeExecutions)
            assertEquals(2, exited.pendingCleanup)
            assertFalse(exited.isDrained)
            first.closeReturn.countDown()
            assertTrue(cancelWorker.result() is GenerationCancellation.Snapshot)
            val drained = cancellation.awaitDrained(2000)
            assertTrue(drained.isDrained)
            assertEquals(0, drained.cleanupFailures)
            assertTrue(first.inputClosed)
            assertTrue(second.inputClosed)
        } finally {
            first.exit.countDown()
            second.exit.countDown()
            first.closeReturn.countDown()
            firstWorker.join()
            secondWorker?.join()
            cancelWorker?.join()
        }
    }

    @Test fun cleanupFailuresDoNotSkipOtherConnectionsOrStreams() {
        val events = mutableListOf<String>()
        val cancellation = GenerationCancellation()
        fun connection(name: String) = object : HttpURLConnection(URL("https://api.example/$name")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() {
                events.add("$name.disconnect")
                if (name == "A") throw IOException("disconnect failed")
            }
        }
        fun stream(name: String) = object : InputStream() {
            override fun read() = -1
            override fun close() {
                events.add("$name.close")
                if (name == "A") throw IOException("close failed")
            }
        }
        val first = cancellation.begin()
        val second = cancellation.begin()
        try {
            first.register(connection("A"))
            first.register(stream("A"))
            second.register(connection("B"))
            second.register(stream("B"))
            val cancelled = cancellation.cancel()
            assertEquals(listOf("A.disconnect", "B.disconnect", "A.close", "B.close"), events)
            assertEquals(2, cancelled.cleanupFailures)
            assertEquals(0, cancelled.pendingCleanup)
            assertEquals(2, cancelled.activeExecutions)
            assertFalse(cancelled.isDrained)
            assertEquals(cancelled, cancellation.cancel())
        } finally {
            first.finish()
            second.finish()
        }
        assertTrue(cancellation.snapshot().isDrained)
        assertEquals(2, cancellation.snapshot().cleanupFailures)
    }

    @Test fun oldCancellationCannotDisconnectNewSessionEvenWithSharedTransport() {
        val old = BlockingConnection(Point.READ)
        val current = BlockingConnection(Point.READ)
        val oldCancel = GenerationCancellation()
        val currentCancel = GenerationCancellation()
        val transport = UrlConnectionGenerationHttp { url -> if (url.path == "/old") old else current }
        val oldWorker = Worker { transport.execute(wire(url = "https://api.example/old"), oldCancel) }
        val currentWorker = Worker { transport.execute(wire(url = "https://api.example/new"), currentCancel) }
        try {
            await(old.entered)
            await(current.entered)
            oldCancel.cancel()
            oldCancel.cancel()
            assertEquals(0L, old.disconnected.count)
            assertEquals(1L, current.disconnected.count)
            assertFalse(currentCancel.snapshot().cancellationRequested)
            assertFalse(currentCancel.snapshot().isDrained)
        } finally {
            old.exit.countDown()
            current.exit.countDown()
            oldCancel.cancel()
            currentCancel.cancel()
            oldWorker.join()
            currentWorker.join()
        }
    }

    @Test fun postCancelledDuringReadIsUnknownAndFreshRunCannotRepeatReservedPost() {
        val store = TestAttemptStore()
        val assets = TestGenerationAssets()
        val connection = BlockingConnection(Point.READ)
        val cancellation = GenerationCancellation()
        var connections = 0
        val transport = UrlConnectionGenerationHttp { connections++; connection }
        val io = GenerationIo(assets, store, { true }, transport, cancellation = cancellation)
        val client = ImageGenerationClient(io)
        val plan = client.prepare(generationRequest())
        val worker = Worker { client.submit(plan) }
        try {
            await(connection.entered)
            cancellation.cancel()
            connection.exit.countDown()
            assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), worker.result())
            assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), store.results[plan.identity])
            val next = ImageGenerationClient(GenerationIo(assets, store, { true }, transport))
            assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), next.submit(plan))
            assertEquals(1, connections)
        } finally {
            connection.exit.countDown()
            cancellation.cancel()
            worker.join()
        }
    }

    @Test fun cancellationBetweenReservationAndExecuteIsUnknownWithoutSendingOrRetrying() {
        val cancellation = GenerationCancellation()
        val ledger = TestAttemptStore()
        val store = object : GenerationAttemptStore {
            override fun reserve(attempt: GenerationAttempt): GenerationReservation = ledger.reserve(attempt).also {
                cancellation.cancel()
            }
            override fun finish(attempt: GenerationAttempt, submission: Submission) = ledger.finish(attempt, submission)
        }
        val http = TestGenerationHttp()
        val assets = TestGenerationAssets()
        val client = ImageGenerationClient(GenerationIo(assets, store, { true }, http, cancellation = cancellation))
        val plan = client.prepare(generationRequest())
        assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), client.submit(plan))
        val fresh = ImageGenerationClient(GenerationIo(assets, ledger, { true }, http))
        assertEquals(Submission.Unknown(GenerationFailure.CANCELLED), fresh.submit(plan))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun pollAndBothDownloadKindsClassifyDisconnectedReadsAsCancelled() {
        for (operation in listOf("poll", "authenticated", "cdn")) {
            val cancellation = GenerationCancellation()
            val connection = BlockingConnection(Point.READ)
            val authorized = mutableListOf<String>()
            val assets = TestGenerationAssets()
            val io = GenerationIo(assets, TestAttemptStore(), { authorized.add(it); true },
                UrlConnectionGenerationHttp { connection }, cancellation = cancellation)
            val client = VideoGenerationClient(io)
            val plan = client.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
            val handle = GenerationHandle(plan.identity, plan.fingerprint, "job1", plan.endpoint + "/job1",
                plan.endpoint + "/job1/content")
            val url = if (operation == "authenticated") handle.contentUrl else "https://cdn.example/video.mp4"
            val worker = Worker {
                if (operation == "poll") client.poll(plan, handle)
                else io.downloadAndCommit(plan, GenerationOutput(0,
                    GenerationSource.Remote(url, authenticated = operation == "authenticated")))
            }
            try {
                await(connection.entered)
                cancellation.cancel()
                connection.exit.countDown()
                val expected = if (operation == "poll") Submission.Unknown(GenerationFailure.CANCELLED, handle = handle)
                    else GenerationAssetResult.Failed(GenerationFailure.CANCELLED)
                assertEquals(expected, worker.result())
                if (operation != "poll") assertEquals(listOf(url), authorized)
                assertEquals(if (operation == "cdn") null else "Bearer secret-key",
                    connection.getRequestProperty("Authorization"))
                assertTrue(assets.commits.isEmpty())
                assertEquals(0, assets.inspections)
            } finally {
                connection.exit.countDown()
                cancellation.cancel()
                worker.join()
            }
        }
    }

    @Test fun cleanupFailureIsObservableSeparatelyFromExecutionExit() {
        val cancellation = GenerationCancellation()
        val connection = object : HttpURLConnection(URL("https://api.example/a")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { throw IOException("cleanup failed") }
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = -1L
            override fun getContentType() = "application/json"
            override fun getInputStream(): InputStream = "{}".byteInputStream()
        }
        assertEquals(200, UrlConnectionGenerationHttp { connection }.execute(wire(), cancellation).statusCode)
        val state = cancellation.snapshot()
        assertTrue(state.isDrained)
        assertEquals(1, state.cleanupFailures)
        assertEquals(0, state.pendingCleanup)
        assertFalse(state.cancellationRequested)
    }

    @Test fun cancellationBeforeExecutionDoesNotCreateAConnection() {
        val cancellation = GenerationCancellation()
        cancellation.cancel()
        var connections = 0
        val transport = UrlConnectionGenerationHttp { connections++; BlockingConnection(Point.READ) }
        try {
            transport.execute(wire("POST"), cancellation)
            fail("cancelled request was executed")
        } catch (_: GenerationCancelledException) { /* no request sent */ }
        assertEquals(0, connections)
        assertTrue(cancellation.snapshot().isDrained)
    }

    @Test fun legacySamExecutionRemainsCompatibleButCancelDoesNotInventAbortOrExit() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val legacy = GenerationHttp { entered.countDown(); await(exit); jsonResponse("{}") }
        val cancellation = GenerationCancellation()
        val worker = Worker { legacy.execute(wire(), cancellation) }
        try {
            await(entered)
            assertFalse(cancellation.cancel().isDrained)
            exit.countDown()
            assertTrue(worker.result() is GenerationCancelledException)
            assertTrue(cancellation.snapshot().isDrained)
        } finally { exit.countDown(); worker.join() }
    }

    private enum class Point { OUTPUT_OPEN, WRITE, RESPONSE, INPUT_OPEN, READ }

    private class BlockingConnection(private val point: Point, private val blockClose: Boolean = false) :
        HttpURLConnection(URL("https://api.example/a")) {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closeReturn = CountDownLatch(1)
        var wireEntries = 0
        @Volatile var inputClosed = false
        @Volatile var outputClosed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        private fun block(at: Point) {
            wireEntries++
            if (point != at) return
            entered.countDown()
            await(disconnected) // only a real disconnect, never thread interrupt, releases this I/O
            await(exit) // hold the worker so cancellation is observably not the same as exit
            throw IOException("disconnected")
        }
        override fun getResponseCode(): Int { block(Point.RESPONSE); return 200 }
        override fun getContentLengthLong() = -1L
        override fun getContentType() = "video/mp4"
        override fun getOutputStream(): OutputStream {
            block(Point.OUTPUT_OPEN)
            return object : OutputStream() {
                override fun write(value: Int) { block(Point.WRITE) }
                override fun close() { outputClosed = true }
            }
        }
        override fun getInputStream(): InputStream {
            block(Point.INPUT_OPEN)
            return object : InputStream() {
                override fun read(): Int { block(Point.READ); return -1 }
                override fun close() {
                    closeEntered.countDown()
                    if (blockClose) await(closeReturn)
                    inputClosed = true
                }
            }
        }
    }

    private class Worker(action: () -> Any) {
        private val value = AtomicReference<Any?>()
        val thread = Thread {
            try { value.set(action()) } catch (failure: Throwable) { value.set(failure) }
        }.apply { isDaemon = true; start() }
        fun join() { thread.join(3000); assertFalse("worker failed to exit", thread.isAlive) }
        fun result(): Any? { join(); return value.get() }
    }

    private companion object {
        fun await(latch: CountDownLatch) { assertTrue("timed out waiting for test phase", latch.await(3, TimeUnit.SECONDS)) }
        fun wire(method: String = "GET", url: String = "https://api.example/a") =
            GenerationHttpRequest(method, url, emptyMap(), if (method == "POST") byteArrayOf(1) else null, 100)
    }
}
