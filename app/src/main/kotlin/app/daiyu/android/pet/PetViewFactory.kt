package app.daiyu.android.pet

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
private abstract class ObservedPetLayout(context: Context, protected val controller: PetController) : LinearLayout(context) {
    private val ui = Handler(Looper.getMainLooper())
    private var observation: PetCancel? = null
    private var attachment = 0L
    init { orientation = VERTICAL }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attachment++
        val ticket = attachment
        observation = controller.observe { next -> ui.post {
            if (isAttachedToWindow && attachment == ticket) render(next)
        } }
    }
    override fun onDetachedFromWindow() {
        attachment++; observation?.cancel(); observation = null; ui.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
    protected abstract fun render(snapshot: PetSnapshot)
    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
private class PetPanel(context: Context, controller: PetController, assets: PetResourcePort,
    private val overlay: Boolean) : ObservedPetLayout(context, controller) {
    private var cupboard: PetCupboardDialog? = null
    private val assets = assets
    private val bubble = TextView(context)
    private val sprite = PetSpriteView(context, assets)
    private var previousX = 0f; private var previousY = 0f
    private var startX = 0f; private var startY = 0f
    private var moved = false
    private var fadingSpeech: String? = null
    init {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        bubble.setTextColor(0xff1e293b.toInt()); bubble.setBackgroundColor(0xffffffff.toInt())
        bubble.setPadding(dp(10), dp(8), dp(10), dp(8)); bubble.setOnClickListener { controller.dismissSpeech() }
        addView(bubble, LayoutParams(dp(220), LayoutParams.WRAP_CONTENT))
        addView(sprite, LayoutParams(dp(128), dp(128)))
        sprite.id = app.daiyu.android.R.id.pet_sprite
        sprite.setOnClickListener {
            val snapshot = controller.snapshot()
            if (!isAttachedToWindow || !isShown || snapshot.closed || !snapshot.state.settings.enabled ||
                snapshot.error?.contains("存储") == true || snapshot.state.thinking || cupboard?.isShowing == true) return@setOnClickListener
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
                    if (abs(e.rawX - startX) + abs(e.rawY - startY) >= dp(12)) moved = true
                    if (moved && !overlay) {
                        val host = parent as? View
                        val maxX = ((host?.width ?: width) - width).coerceAtLeast(0).toFloat()
                        val maxY = ((host?.height ?: height) - height).coerceAtLeast(0).toFloat()
                        x = (x + e.rawX - previousX).coerceIn(0f, maxX)
                        y = (y + e.rawY - previousY).coerceIn(0f, maxY)
                    }
                    previousX = e.rawX; previousY = e.rawY; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick() else if (!overlay) controller.setPosition(x.roundToInt(), y.roundToInt())
                    moved = false; true
                }
                MotionEvent.ACTION_CANCEL -> { moved = false; true }
                else -> false
            }
        }
    }
    override fun render(snapshot: PetSnapshot) {
        val s = snapshot.state
        // Parent mounting layer arbitrates in-app/overlay visibility, allowing permission-denied fallback.
        val visible = !snapshot.closed && s.settings.enabled && snapshot.error?.contains("存储") != true
        visibility = if (visible) VISIBLE else GONE
        if (!visible) cupboard?.dismiss()
        bubble.text = s.speech; bubble.visibility = if (s.speechVisible) VISIBLE else GONE
        val displayedSpeech = if (visible && s.speechVisible) s.speech else null
        if (displayedSpeech != fadingSpeech) {
            bubble.animate().cancel(); bubble.alpha = 1f; fadingSpeech = displayedSpeech
            if (displayedSpeech != null) bubble.animate().alpha(0f).setStartDelay(2_600).setDuration(500).start()
        }
        sprite.mood = s.mood
        sprite.layoutParams = sprite.layoutParams.apply { width = dp((128 * s.settings.scale).roundToInt()); height = width }
        sprite.setRunning(visible)
        if (visible && !overlay && !moved) post {
            if (!isAttachedToWindow || moved) return@post
            val host = parent as? View ?: return@post
            x = (if (s.settings.x < 0) host.width - width - dp(16) else s.settings.x).coerceIn(0, (host.width - width).coerceAtLeast(0)).toFloat()
            y = (if (s.settings.y < 0) (host.height * .62f).roundToInt() else s.settings.y).coerceIn(0, (host.height - height).coerceAtLeast(0)).toFloat()
        }
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
        bubble.animate().cancel(); fadingSpeech = null; sprite.setRunning(false); super.onDetachedFromWindow()
    }
}
/** The original sheet is 4 frames x 8 square mood rows; draw pixels, never emoji/text substitutes. */
internal class PetSpriteView(context: Context, private val assets: PetResourcePort) : View(context) {
    var mood: PetMood = PetMood.IDLE
        set(value) { if (field != value) frame = 0; field = value; contentDescription = "鲸鱼女仆 ${value.name}"; invalidate() }
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
    private val assistant = EditText(context).apply { hint = "独立桌宠assistant ID（不继承主Agent）"; isSingleLine = true }
    private val provider = EditText(context).apply { hint = "独立provider ID"; isSingleLine = true }
    private val model = EditText(context).apply { hint = "独立model ID"; isSingleLine = true }
    private val scale = SeekBar(context).apply { max = 26 }
    private val memories = TextView(context)
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
        listOf(enabled, work, global, audio).forEach(::add)
        enabled.setOnCheckedChangeListener { _, value -> if (!rendering) host.setEnabled(value) }
        work.setOnCheckedChangeListener { _, value -> if (!rendering) controller.updateSettings(controller.snapshot().state.settings.copy(workReactions = value)) }
        global.setOnCheckedChangeListener { _, value -> if (!rendering) host.setGlobalVisible(value) }
        audio.setOnCheckedChangeListener { _, value -> if (!rendering) controller.updateSettings(controller.snapshot().state.settings.copy(audioEnabled = value)) }
        add(Button(context).apply { text = "刷新系统悬浮权限状态（不跳转设置）"; setOnClickListener { host.refreshOverlayPermission() } })
        group("独立模型")
        add(assistant); add(provider); add(model)
        add(Button(context).apply { text = "保存独立模型选择"; setOnClickListener {
            val fields = listOf(assistant.text.toString().trim(), provider.text.toString().trim(), model.text.toString().trim())
            if (fields.any { it.length > 200 }) { status.text = "ID不能超过200字符"; return@setOnClickListener }
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
                setOnClickListener { host.feed(food) }; foods.add(this)
            }
            row.addView(image, LayoutParams(dp(64), dp(64)))
            row.addView(Button(context).apply {
                text = "${food.displayName} +${food.satiety}（游戏粮食）"; foods.add(this)
                setOnClickListener { host.feed(food) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            cupboard.addView(row)
        }
        group("外观与记忆")
        add(TextView(context).apply { text = "缩放 50%–180%" }); add(scale)
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) controller.setScale(.5f + progress * .05f) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        add(Button(context).apply { text = "重置缩放"; setOnClickListener { controller.setScale(1f) } })
        add(Button(context).apply { text = "清空记忆并取消当前反应"; setOnClickListener { controller.clearMemories() } })
        add(memories); add(usage)
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
        if (!assistant.hasFocus()) assistant.setText(s.settings.model.assistantId)
        if (!provider.hasFocus()) provider.setText(s.settings.model.providerId)
        if (!model.hasFocus()) model.setText(s.settings.model.modelId)
        scale.progress = ((s.settings.scale - .5f) / .05f).roundToInt()
        status.text = snapshot.error ?: "粮食 ${s.satiety}/$PET_MAX_SATIETY (${petSatietyPercent(s.satiety)}%) · ${petSatietyBand(s.satiety)}${if (s.thinking) " · 思考中" else ""}"
        foods.forEach { it.isEnabled = s.settings.enabled && !s.thinking && !snapshot.closed }
        memories.text = if (s.memories.isEmpty()) "暂无记忆" else s.memories.joinToString("\n") { "[${it.mood}] ${it.speech}" }
        val known = s.usage.filter { it.inputTokens != null && it.outputTokens != null }
        usage.text = "费用归属：pet（非主Agent）\n请求${s.usage.size}，有完整用量${known.size}；未报告≠免费\n" +
            "已报告输入${known.sumOf { it.inputTokens ?: 0 }} / 输出${known.sumOf { it.outputTokens ?: 0 }} Token；无价格不估算金额"
        rendering = false
    }
}
