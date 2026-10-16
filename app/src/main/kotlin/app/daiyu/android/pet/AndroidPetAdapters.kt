package app.daiyu.android.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.pet.*
import java.util.concurrent.atomic.AtomicBoolean

class AndroidPetScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : PetScheduler {
    override fun after(delayMillis: Long, task: () -> Unit): PetCancel {
        val cancelled = AtomicBoolean(false)
        val runnable = Runnable { if (!cancelled.get()) task() }
        check(handler.postDelayed(runnable, delayMillis)) { "Pet scheduler rejected task" }
        return PetCancel { cancelled.set(true); handler.removeCallbacks(runnable) }
    }
}
/** Four square columns, eight rows in PetMood order. Owned by host, never recycled by a view. */
interface PetResourcePort {
    fun spriteSheet(): Bitmap?
    fun food(resourceKey: String): Bitmap?
    fun cupboard(): Bitmap? = null
    val status: String
}
/** Exact f001fb82 artwork, LICENSE and NOTICE are packaged alongside these six PNGs. */
class WhaleMaidResources(context: Context) : PetResourcePort {
    private val assets = context.applicationContext.assets
    private fun load(name: String): Bitmap = assets.open("whale-maid/$name.png").use {
        requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })) {
            "Invalid whale maid asset: $name"
        }
    }
    private val sprite by lazy { load("pet_sprite").also {
        require(it.width % 4 == 0 && it.height == it.width / 4 * 8) { "Invalid whale maid sprite grid" }
    } }
    private val foods by lazy { PetFood.entries.associate { it.resourceKey to load(it.resourceKey) } }
    private val background by lazy { load("cupboard_bg") }
    override fun spriteSheet(): Bitmap = sprite
    override fun food(resourceKey: String): Bitmap? = foods[resourceKey]
    override fun cupboard(): Bitmap = background
    override val status = "鲸鱼女仆 · AcidGr/dsh-web-whale-maid · MIT（资源目录附许可）"
}
