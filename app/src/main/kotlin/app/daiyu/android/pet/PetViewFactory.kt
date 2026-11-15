package app.daiyu.android.pet

import app.daiyu.android.ui.components.manageInput

import app.daiyu.android.ui.haptics.setHapticClickListener
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import app.daiyu.android.domain.pet.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retained Views. Every view releases observers and animation on detach. Use PetHost to mount. */
internal object PetViewFactory {
    fun createPet(context: Context, controller: PetController, resources: PetResourcePort,
        overlay: Boolean = false): View =
        PetPanel(context, controller, resources, overlay)
    fun createSettings(context: Context, host: PetHost): View = PetSettingsView(context, host)
}
internal abstract class ObservedPetLayout(context: Context, protected val controller: PetController) : LinearLayout(context) {
    private val ui = Handler(Looper.getMainLooper())
    private var observation: PetCancel? = null
    private var attachment = 0L
    init { orientation = VERTICAL }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attachment++
        val ticket = attachment
        observation = controller.observe { next ->
            if (Looper.myLooper() == Looper.getMainLooper()) render(next)
            else ui.post { if (isAttachedToWindow && attachment == ticket) render(next) }
        }
    }
    override fun onDetachedFromWindow() {
        attachment++; observation?.cancel(); observation = null; ui.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
    protected abstract fun render(snapshot: PetSnapshot)
    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
internal class PetPanel(context: Context, controller: PetController, assets: PetResourcePort,
    private val overlay: Boolean) : ObservedPetLayout(context, controller) {
    private var cupboard: PetCupboardDialog? = null
    private val assets = assets
    private val bubble = PetSpeechBubbleView(context)
    private val sprite = PetSpriteView(context, assets)
    private var previousX = 0f; private var previousY = 0f
    private var startX = 0f; private var startY = 0f
    private var moved = false
    init {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        alpha = 0f
        clipChildren = false; clipToPadding = false
        bubble.onDismiss = { controller.dismissSpeech() }
        addView(bubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(sprite, LayoutParams(dp(128), dp(128)))
        sprite.id = app.daiyu.android.R.id.pet_sprite
        sprite.setHapticClickListener {
            val snapshot = controller.snapshot()
            if (!isAttachedToWindow || !isShown || snapshot.closed || !snapshot.state.settings.enabled ||
                snapshot.error?.contains("存储") == true || snapshot.state.thinking || cupboard?.isShowing == true) return@setHapticClickListener
            PetCupboardDialog(context, controller, assets, overlay).also { dialog ->
                cupboard = dialog
                dialog.setOnDismissListener { if (cupboard === dialog) cupboard = null }
                try { dialog.show() }
                catch (failure: android.view.WindowManager.BadTokenException) {
                    cupboard = null; dialog.dismiss()
                    android.util.Log.w("DaiyuPet", "Cupboard window unavailable", failure)
                } catch (failure: SecurityException) {
                    cupboard = null; dialog.dismiss()
                    android.util.Log.w("DaiyuPet", "Cupboard overlay permission unavailable", failure)
                }
            }
        }
        sprite.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; previousX = startX; previousY = startY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - startX) + abs(e.rawY - startY) >= 12f) moved = true
                    if (moved && !overlay) {
                        x += e.rawX - previousX
                        y += e.rawY - previousY
                    }
                    previousX = e.rawX; previousY = e.rawY; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick() else if (!overlay) {
                        val host = parent as? View
                        val next = petPlacement((x + sprite.left).roundToInt(), (y + sprite.top).roundToInt(),
                            host?.width ?: width, host?.height ?: height, petSide, width, height, dp(16))
                        moved = false
                        controller.setPosition(next.spriteX, next.spriteY)
                        placeInApp()
                    }
                    moved = false; true
                }
                MotionEvent.ACTION_CANCEL -> { moved = false; placeInApp(); true }
                else -> false
            }
        }
        render(controller.snapshot())
    }
    internal val petSide: Int get() = sprite.measuredWidth
    internal fun placeInApp() {
        if (overlay || moved) return
        val host = parent as? View ?: return
        if (host.width <= 0 || host.height <= 0 || width <= 0 || height <= 0) return
        val settings = controller.snapshot().state.settings
        val next = petPlacement(settings.x, settings.y, host.width, host.height, petSide, width, height, dp(16))
        x = next.x.toFloat(); y = next.y.toFloat()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val previousLeft = sprite.left; val previousTop = sprite.top
        super.onLayout(changed, left, top, right, bottom)
        if (!overlay && moved) {
            x += previousLeft - sprite.left
            y += previousTop - sprite.top
        } else placeInApp()
        alpha = 1f
    }
    override fun render(snapshot: PetSnapshot) {
        val s = snapshot.state
        // Parent mounting layer arbitrates in-app/overlay visibility, allowing permission-denied fallback.
        val visible = !snapshot.closed && s.settings.enabled && snapshot.error?.contains("存储") != true
        visibility = if (visible) VISIBLE else GONE
        if (!visible) cupboard?.dismiss()
        bubble.present(s.speech.takeIf { visible && s.speechVisible && it.isNotBlank() }, s.settings.scale)
        sprite.mood = s.mood
        val side = (128f * s.settings.scale * resources.displayMetrics.density).roundToInt()
        if (sprite.layoutParams.width != side) sprite.layoutParams = sprite.layoutParams.apply { width = side; height = side }
        sprite.setRunning(visible)
        if (visible) placeInApp()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) cupboard?.dismiss()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) cupboard?.dismiss()
    }
    override fun onDetachedFromWindow() {
        cupboard?.dismiss(); cupboard = null
        sprite.setRunning(false); super.onDetachedFromWindow()
    }
}
/** The original sheet is 4 frames x 8 square mood rows; draw pixels, never emoji/text substitutes. */
internal class PetSpriteView(context: Context, private val assets: PetResourcePort) : View(context) {
    var mood: PetMood = PetMood.IDLE
        set(value) { field = value; contentDescription = "鲸鱼女仆 ${value.name}"; invalidate() }
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val ui = Handler(Looper.getMainLooper())
    private var frame = 0
    private var running = false
    private val tick = object : Runnable { override fun run() {
        if (!running || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        frame = (frame + 1) % 4; invalidate(); ui.postDelayed(this, 188)
    } }
    init { contentDescription = "鲸鱼女仆" }
    fun setRunning(value: Boolean) { running = value; schedule() }
    private fun schedule() {
        ui.removeCallbacks(tick)
        if (running && isAttachedToWindow && isShown && windowVisibility == VISIBLE) ui.postDelayed(tick, 188)
        invalidate()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { ui.removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View constructor can dispatch before Kotlin fields initialize.
        if (handler != null) post { schedule() }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (handler != null) post { schedule() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = assets.spriteSheet() ?: return
        val side = bitmap.width / 4
        canvas.drawBitmap(bitmap, Rect(frame * side, mood.ordinal * side, (frame + 1) * side, (mood.ordinal + 1) * side),
            Rect(0, 0, width, height), paint)
    }
}
private class PetSettingsView(context: Context, private val host: PetHost) : ObservedPetLayout(context, host.controller) {
    private var rendering = false
    private var hostObservation: PetCancel? = null
    private val permission = TextView(context)
    private val status = TextView(context)
    private val enabled = Switch(context).apply { text = "开启鲸鱼女仆" }
    private val work = Switch(context).apply { text = "真实任务完工反应" }
    private val global = Switch(context).apply { text = "全局悬浮（需要系统授权）" }
    private val audio = Switch(context).apply { text = "独立桌宠语音（需注入服务）" }
    private val assistant = EditText(context).apply { manageInput(); hint = "独立桌宠assistant ID（不继承主Agent）"; isSingleLine = true }
    private val provider = EditText(context).apply { manageInput(); hint = "独立provider ID"; isSingleLine = true }
    private var lastModel: PetModelSelection? = null
    private val model = EditText(context).apply { manageInput(); hint = "独立model ID"; isSingleLine = true }
    private val scale = SeekBar(context).apply { max = 26 }
    private val usage = TextView(context)
    private val foods = mutableListOf<View>()
    init {
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        addView(ScrollView(context).apply { addView(body) })
        var section = body
        fun group(title: String) {
            body.addView(TextView(context).apply {
                text = title; textSize = 14f; setPadding(dp(8), dp(20), dp(8), dp(8))
            })
            section = LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
                val surface = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.colorBackground, surface, true)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(surface.data)
                }
            }
            body.addView(section, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        fun add(view: View) { section.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        group("状态与开关")
        add(status); add(permission); add(TextView(context).apply { text = host.resources.status })
        listOf(enabled, work, global, audio).forEach { control ->
            add(control)
            control.setHapticClickListener {}
        }
        enabled.setOnCheckedChangeListener { _, value -> if (!rendering) host.setEnabled(value) }
        work.setOnCheckedChangeListener { _, value -> if (!rendering) controller.updateSettings(controller.snapshot().state.settings.copy(workReactions = value)) }
        global.setOnCheckedChangeListener { _, value -> if (!rendering) host.setGlobalVisible(value) }
        audio.setOnCheckedChangeListener { _, value -> if (!rendering) controller.updateSettings(controller.snapshot().state.settings.copy(audioEnabled = value)) }
        add(Button(context).apply { text = "刷新系统悬浮权限状态（不跳转设置）"; setHapticClickListener { host.refreshOverlayPermission() } })
        group("独立模型")
        add(assistant); add(provider); add(model)
        add(Button(context).apply { text = "保存独立模型选择"; setHapticClickListener {
            val fields = listOf(assistant.text.toString().trim(), provider.text.toString().trim(), model.text.toString().trim())
            if (fields.any { it.length > 200 }) { status.text = "ID不能超过200字符"; return@setHapticClickListener }
            controller.updateSettings(controller.snapshot().state.settings.copy(model = PetModelSelection(fields[0], fields[1], fields[2])))
        } })
        group("投喂")
        val cupboard = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            host.resources.cupboard()?.let { background = BitmapDrawable(context.resources, it) }
        }
        add(cupboard)
        PetFood.entries.forEach { food ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val image = ImageButton(context).apply {
                setImageBitmap(host.resources.food(food.resourceKey)); contentDescription = "投喂${food.displayName}"
                scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0x55ffffff)
                setHapticClickListener { host.feed(food) }; foods.add(this)
            }
            row.addView(image, LayoutParams(dp(64), dp(64)))
            row.addView(Button(context).apply {
                text = "${food.displayName} +${food.satiety}（游戏粮食）"; foods.add(this)
                setHapticClickListener { host.feed(food) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            cupboard.addView(row)
        }
        group("外观")
        add(TextView(context).apply { text = "缩放 50%–180%" }); add(scale)
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) controller.setScale(.5f + progress * .05f) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { app.daiyu.android.ui.haptics.TouchHaptics.click(bar) }
        })
        add(Button(context).apply { text = "重置缩放"; setHapticClickListener { controller.setScale(1f) } })
        add(usage)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hostObservation = host.observe { next ->
            permission.text = "悬浮窗口当前许可：${if (next.canDrawOverlays) "已授权" else "未授权"} · ${next.overlayState}\n" +
                (next.overlayError ?: "全局悬浮随宿主关闭移除，不会自动授权或跳转设置")
        }
    }
    override fun onDetachedFromWindow() { hostObservation?.cancel(); hostObservation = null; super.onDetachedFromWindow() }
    override fun render(snapshot: PetSnapshot) {
        rendering = true
        val s = snapshot.state
        enabled.isChecked = s.settings.enabled; work.isChecked = s.settings.workReactions
        global.isChecked = s.settings.globalVisible; audio.isChecked = s.settings.audioEnabled
        fun sync(field: EditText, previous: String?, next: String) {
            if (previous == null || field.text.toString() == previous) {
                if (field.text.toString() != next) field.setText(next)
            }
        }
        sync(assistant, lastModel?.assistantId, s.settings.model.assistantId)
        sync(provider, lastModel?.providerId, s.settings.model.providerId)
        sync(model, lastModel?.modelId, s.settings.model.modelId)
        lastModel = s.settings.model
        scale.progress = ((s.settings.scale - .5f) / .05f).roundToInt()
        status.text = snapshot.error ?: "粮食 ${s.satiety}/$PET_MAX_SATIETY (${petSatietyPercent(s.satiety)}%) · ${petSatietyBand(s.satiety)}${if (s.thinking) " · 思考中" else ""}"
        foods.forEach { it.isEnabled = s.settings.enabled && !s.thinking && !snapshot.closed }
        val known = s.usage.filter { it.inputTokens != null && it.outputTokens != null }
        usage.text = "费用归属：pet（非主Agent）\n请求${s.usage.size}，有完整用量${known.size}；未报告≠免费\n" +
            "已报告输入${known.sumOf { it.inputTokens ?: 0 }} / 输出${known.sumOf { it.outputTokens ?: 0 }} Token；无价格不估算金额"
        rendering = false
    }
}
