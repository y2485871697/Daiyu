package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.selectable
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tabTag: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Row(modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, colors.outline), shape)
        .height(IntrinsicSize.Min).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
            val active = selectedIndex == index
            val tint = (if (active) colors.onPrimary else colors.onPrimaryContainer)
                .copy(alpha = if (enabled) 1f else .45f)
            Row(Modifier.weight(1f).testTag(tabTag(index))
                .background(if (active) colors.primary else colors.primaryContainer)
                .selectable(active, enabled = enabled, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 2, textAlign = TextAlign.Center,
                    color = tint)
            }
        }
    }
}
