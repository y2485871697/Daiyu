package app.daiyu.android

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.util.IdentityHashMap

/**
 * Test-only delivery seam, not a send/result fake. The controller's real posted Messages
 * are held before enqueueing, then dispatched unchanged by the real main Looper. Only
 * this controller is gated: framework lifecycle and Compose queues continue normally.
 *
 * A global MessageQueue sync barrier also blocks ActivityThread lifecycle messages; a
 * blocking main Runnable cannot exercise framework destruction. Keep this reflective
 * seam local to instrumentation rather than adding a production scheduling API.
 * Install/hold/release/close on main, before releasing the worker's preparation latch.
 */
internal class TextSendDeliveryGate(private val controller: ChatController) : AutoCloseable {
    private val field = ChatController::class.java.getDeclaredField("handler").apply { isAccessible = true }
    private val original = field.get(controller) as Handler
    private val deferred = IdentityHashMap<Message, Boolean>()
    private var held: MutableList<Pair<Message, Long>>? = null
    private var delivered = 0
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean = synchronized(this@TextSendDeliveryGate) {
            val batch = held
            if (batch == null) super.sendMessageAtTime(msg, uptimeMillis)
            else {
                batch.add(msg to uptimeMillis)
                true
            }
        }

        override fun dispatchMessage(msg: Message) {
            try { super.dispatchMessage(msg) }
            finally {
                synchronized(this@TextSendDeliveryGate) {
                    if (deferred.remove(msg) != null) delivered++
                }
            }
        }

        fun enqueueHeld(msg: Message, uptimeMillis: Long) {
            check(super.sendMessageAtTime(msg, uptimeMillis))
        }
    }

    init {
        requireMain()
        check(original.looper === Looper.getMainLooper())
        field.set(controller, handler)
        check(field.get(controller) === handler) { "controller delivery gate was not installed" }
    }

    fun hold() {
        requireMain()
        synchronized(this) {
            check(held == null)
            held = mutableListOf()
            delivered = 0
        }
    }

    val heldCount: Int get() = synchronized(this) { held?.size ?: 0 }
    val deliveredCount: Int get() = synchronized(this) { delivered }

    /** Returns the number of real messages released, including the old Activity callback. */
    fun release(): Int {
        requireMain()
        return synchronized(this) {
            val batch = held ?: return@synchronized 0
            held = null
            batch.forEach { (msg, time) ->
                deferred[msg] = true
                handler.enqueueHeld(msg, time)
            }
            batch.size
        }
    }

    override fun close() {
        requireMain()
        release()
        field.set(controller, original)
    }

    private fun requireMain() = check(Looper.myLooper() === Looper.getMainLooper())
}
