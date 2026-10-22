package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.SubAgentProfile
import app.daiyu.android.ui.components.subagent.conversationAgentColors

@Composable
internal fun SubAgentRoleIcon(profile: SubAgentProfile) {
    val icon = when (profile.role) {
        "implementation" -> Icons.Rounded.Build
        "review" -> Icons.Rounded.FactCheck
        "image_generation" -> Icons.Rounded.Image
        "video_generation" -> Icons.Rounded.Videocam
        else -> return
    }
    Box(Modifier.size(40.dp).testTag("subagent-role-${profile.id}"), contentAlignment = Alignment.Center) {
        Icon(icon, "${profile.name}职责：${profile.roleLabel}", Modifier.size(22.dp), tint = conversationAgentColors().accent)
    }
}
