package app.daiyu.android.ui.components

import android.graphics.Bitmap
import app.daiyu.android.assistants.AssistantAssets
import app.daiyu.android.assistants.AssistantProfile

/** No invented profiles, ids, names or selected assistant. Missing avatars use the existing UI fallback. */
internal fun AssistantProfile.toAssistantDisplay(
    avatarBitmap: (String?) -> Bitmap? = { null },
): AssistantDisplay = AssistantDisplay(id = id, name = name, avatar = avatarBitmap(avatarFileName))

/** Invoke off the UI thread when assets perform file decoding; preserve the store's list order. */
internal fun List<AssistantProfile>.toAssistantDisplays(assets: AssistantAssets?): List<AssistantDisplay> =
    map { profile -> profile.toAssistantDisplay { assets?.avatarBitmap(it) } }
