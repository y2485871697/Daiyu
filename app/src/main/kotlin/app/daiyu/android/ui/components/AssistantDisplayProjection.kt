package app.daiyu.android.ui.components

import android.graphics.Bitmap
import app.daiyu.android.assistants.AssistantAssets
import app.daiyu.android.assistants.AssistantProfile

/** 沿用助手档案的 ID 和名称；头像缺失时由显示组件使用默认样式。 */
internal fun AssistantProfile.toAssistantDisplay(
    avatarBitmap: (String?) -> Bitmap? = { null },
): AssistantDisplay = AssistantDisplay(id = id, name = name, avatar = avatarBitmap(avatarFileName))

/** Invoke off the UI thread when assets perform file decoding; preserve the store's list order. */
internal fun List<AssistantProfile>.toAssistantDisplays(assets: AssistantAssets?): List<AssistantDisplay> =
    map { profile -> profile.toAssistantDisplay { assets?.avatarBitmap(it) } }
