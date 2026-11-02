package app.daiyu.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalPageContentActive = staticCompositionLocalOf { true }

private class RetainedPageLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    private var closed = false
    override val lifecycle: Lifecycle get() = registry
    fun update(parent: Lifecycle.State, active: Boolean) {
        if (closed) return
        val target = if (active) parent else minOf(parent, Lifecycle.State.CREATED)
        if (target == Lifecycle.State.INITIALIZED && registry.currentState != Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
    fun close() {
        if (closed) return
        closed = true
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** One already visited target stays measured; a hidden target is never placed or started. */
@Composable
internal fun PageRetainedContent(visible: Boolean, active: Boolean, resumed: Boolean, content: @Composable (Boolean) -> Unit) {
    var visited by remember { mutableStateOf(false) }
    SideEffect {
        if (visible) visited = true
        else if (!resumed) visited = false
    }
    if (!visible && (!visited || !resumed)) return
    val parent = LocalLifecycleOwner.current
    val currentActive = rememberUpdatedState(active)
    val owner = remember(parent) { RetainedPageLifecycle().also { it.update(parent.lifecycle.currentState, active) } }
    DisposableEffect(parent, owner) {
        owner.update(parent.lifecycle.currentState, currentActive.value)
        val observer = LifecycleEventObserver { _, _ -> owner.update(parent.lifecycle.currentState, currentActive.value) }
        parent.lifecycle.addObserver(observer)
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    SideEffect { owner.update(parent.lifecycle.currentState, active) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalPageContentActive provides active) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = active }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { if (visible) placeable.place(0, 0) }
            }) { content(active) }
    }
}
