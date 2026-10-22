package app.daiyu.android.ui

import app.daiyu.android.ui.haptics.setHapticClickListener
import app.daiyu.android.ui.components.prepareDaiyuCenterMotion
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import app.daiyu.android.GenerationMediaOriginal

/** Owns only a verified original descriptor, never a provider URL or a mutable private path. */
internal object GenerationMediaDialog {
    fun image(context: Context, bitmap: Bitmap, closed: () -> Unit): Dialog = Dialog(context).apply {
        prepareDaiyuCenterMotion()
        setTitle("图片")
        val image = ImageView(context).apply { setImageBitmap(bitmap); adjustViewBounds = true }
        setContentView(image)
        setOnDismissListener { image.setImageDrawable(null); bitmap.recycle(); closed() }
    }

    fun video(context: Context, original: GenerationMediaOriginal,
        gate: app.daiyu.android.voice.AudioOwnerGate, owner: app.daiyu.android.voice.VoiceOwner,
        closed: () -> Unit): Dialog {
        val dialog = Dialog(context).apply {
            prepareDaiyuCenterMotion()
        }
        val player = MediaPlayer()
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val surface = SurfaceView(context)
        var ready = false
        var released = false
        var ended = false
        var lease: app.daiyu.android.voice.AudioOwnerGate.Lease? = null
        fun teardown() {
            if (released) return
            released = true; ready = false
            try { player.release() } finally { original.close(); closed() }
        }
        fun release() {
            val token = lease
            if (token == null || !gate.releaseAfter(token) { teardown() }) teardown()
        }
        try {
            lease = gate.acquire(owner, { original.requireActive(); true }) { teardown(); dialog.dismiss() }
        } catch (failure: Exception) { teardown(); throw failure }
        val button = Button(context).apply { text = "正在加载…"; isEnabled = false }
        layout.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (320 * context.resources.displayMetrics.density).toInt()))
        layout.addView(button)
        button.setHapticClickListener {
            if (!ready || released) return@setHapticClickListener
            try {
                original.requireActive(); check(gate.current(lease))
                if (player.isPlaying) { player.pause(); button.text = "播放" }
                else { if (ended) { player.seekTo(0); ended = false }; player.start(); button.text = "暂停" }
            } catch (_: Exception) { dialog.dismiss() }
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released) return
                try {
                    original.requireActive(); check(gate.current(lease))
                    player.setDisplay(holder)
                    player.setDataSource(original.stream.fd, 0L, original.metadata.bytes)
                    player.setOnPreparedListener {
                        if (released) return@setOnPreparedListener
                        try { original.requireActive(); check(gate.current(lease)); ready = true; button.isEnabled = true; it.start(); button.text = "暂停" }
                        catch (_: Exception) { dialog.dismiss() }
                    }
                    player.setOnCompletionListener { ended = true; button.text = "重新播放" }
                    player.setOnErrorListener { _, _, _ -> button.text = "播放失败"; button.isEnabled = false; release(); true }
                    player.prepareAsync()
                } catch (_: Exception) { button.text = "播放失败"; release() }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { release() }
        })
        dialog.setTitle("视频")
        dialog.setContentView(layout)
        dialog.setOnDismissListener { release() }
        return dialog
    }
}
