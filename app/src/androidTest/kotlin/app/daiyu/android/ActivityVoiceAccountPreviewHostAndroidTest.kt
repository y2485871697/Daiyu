package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.voice.account.AccountPreviewRequest
import app.daiyu.android.voice.account.VoiceAccountPreviewPort
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.DoubaoSpeech
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONObject

/** Real Android MediaPlayer + existing synthesis decoder; fake Call.Factory, no external HTTP/key.
 * Uses the production singleton gate, does not reset it or use a second audio owner barrier.
 */
@Suppress("DEPRECATION")
class ActivityVoiceAccountPreviewHostAndroidTest : InstrumentationTestCase() {
    private val config get() = SpeechProviderConfig(SpeechEngine.DOUBAO,
        "https://openspeech.bytedance.com", "instrumentation-only", "seed-tts-2.0", "saved")
    private fun request() = AccountPreviewRequest(config,
        DoubaoPersonalVoice("S_instrumentation", DoubaoPersonalVoice.accountFingerprint("instrumentation-only"),
            2, setOf(5), true), "https://do-not-download.example.test/demo?private=ignored")
    private class Events : VoiceAccountPreviewPort.Listener {
        val done = CountDownLatch(1)
        val started = CountDownLatch(1)
        val starts = AtomicInteger()
        val failures = AtomicInteger()
        override fun started() { starts.incrementAndGet(); started.countDown() }
        override fun stopped() { done.countDown() }
        override fun failed() { failures.incrementAndGet(); done.countDown() }
    }
    private class Calls(private val hold: Boolean) : Call.Factory {
        val opened = CountDownLatch(1)
        val count = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val sent = AtomicReference<Request>()
        override fun newCall(request: Request): Call {
            count.incrementAndGet(); sent.set(request)
            return object : Call {
                private var callback: Callback? = null
                @Volatile private var stopped = false
                private val terminal = AtomicBoolean()
                override fun request() = request
                override fun execute(): Response = error("only async existing transport")
                override fun enqueue(responseCallback: Callback) {
                    callback = responseCallback
                    opened.countDown()
                    if (!hold) Thread {
                        // One second of silence exercises prepare/start/completion without a real voice.
                        val wave = DoubaoSpeech.pcmToWav(ByteArray(24_000 * 2))
                        val payload = JSONObject().put("code", 0)
                            .put("audio", Base64.getEncoder().encodeToString(wave)).toString()
                        responseCallback.onResponse(this, Response.Builder().request(request)
                            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(payload.toResponseBody("application/json".toMediaType())).build())
                    }.start()
                }
                override fun cancel() {
                    stopped = true; cancelled.countDown()
                    if (hold) callback?.let { cb ->
                        if (terminal.compareAndSet(false, true)) Thread { cb.onFailure(this, IOException("cancelled")) }.start()
                    }
                }
                override fun isExecuted() = callback != null
                override fun isCanceled() = stopped
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = error("no retry or clone")
            }
        }
    }
    private fun cacheFiles() = instrumentation.targetContext.cacheDir.listFiles()
        .orEmpty().filter { it.name.startsWith("account-preview-") }.map { it.name }.toSet()

    fun testExplicitSynthesisUsesPersonalFourthArgumentAndRealSpeakingThenReleasesCache() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = Calls(hold = false); val events = Events(); val before = cacheFiles()
        lateinit var host: ActivityVoiceAccountPreviewHost
        instrumentation.runOnMainSync {
            host = ActivityVoiceAccountPreviewHost(scope, config, { true }) { req ->
                AccountPreviewSpeechPlayer(instrumentation.targetContext, scope, req, { true }, AccountPreviewCalls(calls))
            }
            assertEquals(0, calls.count.get())
            host.admissionChanged()
            assertEquals(0, calls.count.get())
            host.play(request(), events)
        }
        try {
            assertTrue("real MediaPlayer SPEAKING was not observed", events.started.await(10, TimeUnit.SECONDS))
            assertTrue("playback did not finish/drain", events.done.await(10, TimeUnit.SECONDS))
            assertEquals(1, events.starts.get()); assertEquals(0, events.failures.get())
            assertEquals(1, calls.count.get())
            val sent = checkNotNull(calls.sent.get())
            assertEquals(DoubaoSpeech.UNIDIRECTIONAL_URL, sent.url.toString())
            assertEquals("seed-icl-2.0", sent.header("X-Api-Resource-Id"))
            val body = Buffer(); checkNotNull(sent.body).writeTo(body)
            val params = JSONObject(body.readUtf8()).getJSONObject("req_params")
            assertEquals("S_instrumentation", params.getString("speaker"))
            assertEquals(ActivityVoiceAccountPreviewHost.PREVIEW_TEXT, params.getString("text"))
            assertEquals(before, cacheFiles())
        } finally { instrumentation.runOnMainSync { host.close(); scope.cancel() } }
    }

    fun testStopWhileConnectingCancelsActualCallAndWaitsForCallbackDrainWithoutStarting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = Calls(hold = true); val events = Events()
        lateinit var host: ActivityVoiceAccountPreviewHost
        instrumentation.runOnMainSync {
            host = ActivityVoiceAccountPreviewHost(scope, config, { true }) { req ->
                AccountPreviewSpeechPlayer(instrumentation.targetContext, scope, req, { true }, AccountPreviewCalls(calls))
            }
            host.play(request(), events)
        }
        try {
            assertTrue(calls.opened.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { host.stop() }
            assertTrue(calls.cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(events.done.await(5, TimeUnit.SECONDS))
            assertEquals(0, events.starts.get()); assertEquals(0, events.failures.get())
            // After confirmed drain the SAME singleton admits a fresh preview, no reset/second gate.
            instrumentation.runOnMainSync { host.play(request(), Events()); host.stop() }
        } finally { instrumentation.runOnMainSync { host.close(); scope.cancel() } }
    }
}
