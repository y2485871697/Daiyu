package app.daiyu.android.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/** Shared no-draw indication; click, long-press and selection semantics stay unchanged. */
internal object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WithoutPressRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides NoRippleIndication,
        LocalRippleConfiguration provides null,
        content = content,
    )
}
