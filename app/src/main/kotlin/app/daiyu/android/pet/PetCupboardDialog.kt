package app.daiyu.android.pet

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.pet.*
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.roundToInt

internal val PetCupboardClearAction = SemanticsPropertyKey<() -> Unit>("PetCupboardClearAction")

/** A sprite owns this window; closing it never closes the shared pet runtime. */
internal class PetCupboardDialog(
    context: Context,
    private val controller: PetController,
    private val assets: PetResourcePort,
    overlay: Boolean,
) : ComponentDialog(context) {
    private val ui = Handler(Looper.getMainLooper())
    private var observation: PetCancel? = null
    private var visit = 0L
    private var snapshot by mutableStateOf(controller.snapshot())

    init {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (overlay) .35f else 0f)
            if (overlay) setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        setCanceledOnTouchOutside(true)
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    PetCupboardContent(snapshot, assets,
                        onFeed = { food ->
                            val current = controller.snapshot()
                            if (isShowing && !current.closed && current.state.settings.enabled && !current.state.thinking) {
                                dismiss()
                                controller.feed(food)
                            }
                        }, onScale = { value -> command { controller.setScale(value) } },
                        onClear = { command { controller.clearMemories() } })
                }
            }
        })
    }

    override fun show() {
        super.show()
        val width = minOf(320 * context.resources.displayMetrics.density,
            context.resources.displayMetrics.widthPixels - 48 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onStart() {
        super.onStart()
        val ticket = ++visit
        observation = controller.observe { next -> ui.post {
            if (visit == ticket && isShowing) render(next)
        } }
        render(controller.snapshot())
    }

    override fun onStop() {
        visit++; observation?.cancel(); observation = null; ui.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun command(action: () -> PetCommandResult) {
        val current = controller.snapshot()
        if (!isShowing || current.closed || !current.state.settings.enabled) return
        action()
        render(controller.snapshot())
    }

    private fun render(next: PetSnapshot) {
        if (next.closed || !next.state.settings.enabled || next.error?.contains("存储") == true) dismiss()
        else snapshot = next
    }
}

@Composable
internal fun PetCupboardContent(snapshot: PetSnapshot, assets: PetResourcePort,
    onFeed: (PetFood) -> Unit, onScale: (Float) -> Unit, onClear: () -> Unit) {
    val state = snapshot.state
    val band = petSatietyBand(state.satiety)
    val percent = petSatietyPercent(state.satiety)
    val ink = Color(0xff4a2812)
    val link = Color(0xff1d4ed8)
    val bar = when (band) {
        PetSatietyBand.STARVING -> Color(0xffdc2626)
        PetSatietyBand.HUNGRY -> Color(0xffea580c)
        PetSatietyBand.STUFFED -> Color(0xff3b82f6)
        else -> Color(0xff16a34a)
    }
    val usable = !snapshot.closed && state.settings.enabled && snapshot.error?.contains("存储") != true
    Column(Modifier.widthIn(max = 320.dp).heightIn(max = 520.dp)
        .background(Color(0xfffff7ed), RoundedCornerShape(18.dp))
        .padding(16.dp).verticalScroll(rememberScrollState()).testTag("pet-cupboard"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("女仆储物橱柜", color = ink, fontWeight = FontWeight.Bold)
        Text("${state.satiety} / $PET_MAX_SATIETY ($percent%)", color = bar,
            fontWeight = FontWeight.Medium, modifier = Modifier.testTag("pet-cupboard-satiety"))
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xfffed7aa), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(percent / 100f).height(8.dp).background(bar, RoundedCornerShape(4.dp)))
        }
        Text(if (state.thinking) "思考中" else when (band) {
            PetSatietyBand.STARVING -> "快要饿死了"
            PetSatietyBand.HUNGRY -> "很饿"
            PetSatietyBand.PECKISH -> "不饿，但是不介意吃多点"
            PetSatietyBand.FULL -> "饱腹满足"
            PetSatietyBand.STUFFED -> "吃得有点撑了"
        }, color = ink)
        PetFood.entries.forEach { food ->
            Row(Modifier.fillMaxWidth().testTag("pet-cupboard-food:${food.name}")
                .cupboardClick(usable && !state.thinking) { onFeed(food) },
                verticalAlignment = Alignment.CenterVertically) {
                val image = remember(assets, food) { assets.food(food.resourceKey)?.asImageBitmap() }
                if (image != null) Image(image, null, Modifier.size(48.dp))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(food.displayName, color = ink)
                    Text("+${food.satiety}", color = Color(0xff92400e), fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("体型", color = ink)
            Text("${(state.settings.scale * 100).roundToInt()}%  重置", color = link,
                modifier = Modifier.testTag("pet-cupboard-reset").cupboardClick(usable) { onScale(1f) })
        }
        Slider(value = state.settings.scale * 100f, onValueChange = { onScale(it / 100f) },
            valueRange = 50f..180f, enabled = usable,
            colors = SliderDefaults.colors(thumbColor = Color(0xfff8f6ff), activeTrackColor = Color(0xff62616a),
                inactiveTrackColor = Color(0xffdedce0)),
            modifier = Modifier.fillMaxWidth().testTag("pet-cupboard-scale").semantics { contentDescription = "体型" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("发言手账", color = ink)
            Text("清空", color = link,
                modifier = Modifier.testTag("pet-cupboard-clear").semantics { this[PetCupboardClearAction] = onClear }
                    .cupboardClick(usable, onClear))
        }
        if (state.memories.isEmpty()) Text("还没有发言", color = Color(0xff78716c), fontSize = 12.sp)
        else state.memories.forEach { memory ->
            Text("[${memory.mood.name.lowercase()}] ${memory.speech}", color = Color(0xff44403c), fontSize = 13.sp)
        }
    }
}

private fun Modifier.cupboardClick(enabled: Boolean, action: () -> Unit): Modifier = clickable(
    interactionSource = MutableInteractionSource(), indication = null, enabled = enabled, onClick = action)
