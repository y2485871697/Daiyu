package app.daiyu.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver

/** Cross-process test-only cover; never included in either production variant. */
class ReleaseLifecycleCoverActivity : Activity() {
    private var reply: ResultReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { finish() }
    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        reply = intent.getParcelableExtra("reply")
        val target = reply ?: run { finish(); return }
        val control = object : ResultReceiver(handler) {
            override fun onReceiveResult(code: Int, data: Bundle?) { finish() }
        }
        target.send(0, Bundle().apply { putParcelable("finish", control); putInt("task", taskId) })
        handler.postDelayed(expiry, 15_000)
    }
    override fun onPostResume() { super.onPostResume(); reply?.send(1, null) }
    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        reply?.send(2, null)
        super.onDestroy()
    }
}
