package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet

@Composable
internal fun SettingsSponsorDialog(onDismiss: () -> Unit) {
    var wechatSelected by remember { mutableStateOf(true) }
    val assets = LocalContext.current.assets
    val qr = remember(assets, wechatSelected) {
        val name = if (wechatSelected) "donate_wechat.png" else "donate_alipay.jpg"
        assets.open("sponsor/$name").use { BitmapFactory.decodeStream(it).asImageBitmap() }
    }
    ChatSelectionSheet(title = "", onDismiss = onDismiss, showCloseButton = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantPreviewButton("微信", onClick = { wechatSelected = true },
                primary = wechatSelected, secondary = !wechatSelected,
                modifier = Modifier.weight(1f).testTag("sponsor-wechat").semantics {
                    role = Role.Tab; selected = wechatSelected
                })
            AssistantPreviewButton("支付宝", onClick = { wechatSelected = false },
                primary = !wechatSelected, secondary = wechatSelected,
                modifier = Modifier.weight(1f).testTag("sponsor-alipay").semantics {
                    role = Role.Tab; selected = !wechatSelected
                })
        }
        Spacer(Modifier.height(12.dp))
        Image(bitmap = qr,
            contentDescription = if (wechatSelected) "微信收款码" else "支付宝收款码",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("sponsor-qr"))
    }
}
