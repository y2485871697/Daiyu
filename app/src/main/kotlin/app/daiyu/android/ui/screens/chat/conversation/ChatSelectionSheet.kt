package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.daiyu.android.ui.components.previewGlassBlur
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

/** The preview's model/attachment shell; unlike the assistant editor, short content wraps. */
@Composable
internal fun ChatSelectionSheet(
    title: String,
    onDismiss: () -> Unit,
    height: Dp? = null,
    subtitle: String? = null,
    footer: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = usagePalette()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.matchParentSize().background(Color(if (colors.dark) 0x55000000 else 0x2620172d))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            val available = (maxHeight - 88.dp).coerceAtLeast(0.dp)
            val shape = RoundedCornerShape(25.dp)
            Column(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth()
                .then(if (height == null) Modifier.heightIn(min = minOf(180.dp, available), max = available)
                    else Modifier.height(minOf(height, available)))
                .clip(shape).previewGlassBlur(shape, 28f, 1.3f)
                .background(Brush.linearGradient(colors.sheet)).border(1.dp, colors.edge, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 15.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 17.dp)
                    .size(29.dp, 3.dp).background(colors.toolbar.copy(alpha = .24f), CircleShape))
                Row(Modifier.fillMaxWidth().padding(bottom = 13.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = colors.text, fontSize = 17.sp, lineHeight = 23.8.sp, fontWeight = FontWeight.SemiBold)
                        subtitle?.let { Text(it, color = colors.muted, fontSize = 10.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp)) }
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
                        contentAlignment = Alignment.Center) {
                        Icon(DaiyuIcons.Close, "关闭", Modifier.size(15.dp), tint = colors.muted)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = height != null), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
            }
        }
    }
}
