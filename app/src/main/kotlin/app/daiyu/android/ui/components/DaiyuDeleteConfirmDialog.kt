package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun DaiyuDeleteConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    cancelModifier: Modifier = Modifier,
    confirmModifier: Modifier = Modifier,
    confirmText: String = stringResource(R.string.action_delete),
    onOutsideDismiss: () -> Unit = onDismiss,
    modifier: Modifier = Modifier,
) {
    val dark = LocalAppearanceDark.current
    val red = Color(if (dark) 0xFFCB6B70 else 0xFFBB5358)
    val text = Color(if (dark) 0xFFE6EEE7 else 0xFF25362E)
    val glass = Color(if (dark) 0xFF242D28 else 0xFFFFFFFC)
    val shape = RoundedCornerShape(28.dp)
    PreviewDialog(
        onDismiss = onDismiss,
        onOutsideDismiss = onOutsideDismiss,
        scrimColor = Color.Black.copy(alpha = if (dark) .32f else .18f),
        scrimBlurRadius = 3f,
    ) {
        Column(
            modifier.align(Alignment.Center).padding(24.dp)
                .widthIn(max = 360.dp).fillMaxWidth()
                .heightIn(max = (maxHeight - 48.dp).coerceAtLeast(0.dp))
                .testTag("delete-confirm-dialog").semantics { paneTitle = title }
                .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = .06f),
                    spotColor = Color.Black.copy(alpha = if (dark) .3f else .15f))
                .clip(shape).previewGlassBlur(shape, radius = 30f, saturation = 1.45f)
                .background(Brush.linearGradient(listOf(
                    glass.copy(alpha = if (dark) .76f else .72f),
                    glass.copy(alpha = if (dark) .68f else .64f),
                )))
                .border(1.dp, Color.White.copy(alpha = if (dark) .16f else .86f), shape)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .verticalScroll(rememberScrollState())
                .padding(start = 25.dp, end = 25.dp, top = 30.dp, bottom = 23.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(19.dp))
                .background(red.copy(alpha = if (dark) .12f else .09f))
                .testTag("delete-confirm-icon"), contentAlignment = Alignment.Center) {
                Icon(DaiyuIcons.Delete, contentDescription = null, tint = red, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(21.dp))
            Text(title, color = text, fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("delete-confirm-title"))
            Spacer(Modifier.height(27.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                DeleteConfirmButton(stringResource(R.string.action_cancel), onDismiss, text,
                    Color.White.copy(alpha = if (dark) .065f else .48f),
                    Modifier.weight(1f).testTag("dialog-cancel").then(cancelModifier), enabled = cancelEnabled,
                    border = Color(if (dark) 0x0AFFFFFF else 0x10293F30))
                DeleteConfirmButton(confirmText, onConfirm, Color.White, red,
                    Modifier.weight(1f).testTag("dialog-confirm").then(confirmModifier), enabled = confirmEnabled)
            }
        }
    }
}

@Composable
private fun DeleteConfirmButton(
    label: String,
    onClick: () -> Unit,
    text: Color,
    background: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    border: Color = Color.Transparent,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .38f)
        .clip(shape).background(background).border(1.dp, border, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = text, fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}
