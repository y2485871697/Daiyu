package app.daiyu.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** User-approved destinations retained from the previous Daiyu settings. */
internal fun openRetainedSettingsLink(context: Context, id: String) {
    val target = when (id) {
        "telegram" -> "https://t.me/+6JhB2iRjjDExYWM1"
        "qq" -> "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=174109605&card_type=group&source=qrcode"
        "relay_st" -> "https://st.123336.xyz/join/ZKjSNbdYUh6RbF5_Kdxjrp-_AoAVcMH9"
        "relay_moyu" -> "https://www.u354483.nyat.app:35119/register?aff=5U8U292F5RG6"
        else -> error("unsupported_retained_settings_link")
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
        if (target.startsWith("https://")) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "系统拒绝打开此链接", Toast.LENGTH_LONG).show()
    }
}
