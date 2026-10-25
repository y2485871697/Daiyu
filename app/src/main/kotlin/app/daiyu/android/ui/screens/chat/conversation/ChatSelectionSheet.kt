package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.components.PreviewDialog
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
    showCloseButton: Boolean = true,
    titleFontSize: TextUnit = 18.sp,
    subtitleFontSize: TextUnit = 12.sp,
    footer: @Composable RowScope.() -> Unit = {},
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = usagePalette()
    PreviewDialog(onDismiss, bottomSheet = true, dismissEnabled = dismissEnabled) {
        val available = (maxHeight - if (height == null) 70.dp else 88.dp).coerceAtLeast(0.dp)
        val shape = RoundedCornerShape(25.dp)
        Column(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth()
            .then(if (height == null) Modifier.heightIn(min = minOf(180.dp, available), max = available)
                else Modifier.height(minOf(height, available)))
            .testTag("chat-selection-sheet").clip(shape).previewGlassBlur(shape, 28f, 1.3f)
            .background(Brush.linearGradient(colors.sheet)).border(1.dp, colors.edge, shape)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 15.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 17.dp)
                .size(29.dp, 3.dp).background(colors.toolbar.copy(alpha = .24f), CircleShape))
            Row(Modifier.fillMaxWidth().padding(bottom = 13.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, modifier = Modifier.fillMaxWidth(), color = colors.text, fontSize = titleFontSize, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    subtitle?.let { Text(it, color = colors.muted, fontSize = subtitleFontSize, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                }
                if (showCloseButton) Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                    .clickable(enabled = dismissEnabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
                    contentAlignment = Alignment.Center) {
                    Icon(DaiyuIcons.Close, "关闭", Modifier.size(20.dp), tint = colors.muted)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = height != null), content = content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}
