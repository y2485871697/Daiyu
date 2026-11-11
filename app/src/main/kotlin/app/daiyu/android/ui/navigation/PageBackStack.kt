package app.daiyu.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf

internal data class PageBackRouteKey(val root: Any, val path: List<Any>)

internal class PageBackStackRegistry {
    class Entry(val parent: Entry?) {
        var parentPath by mutableStateOf<List<Any>>(emptyList())
        var child by mutableStateOf(false)
        var path by mutableStateOf<List<Any>>(emptyList())
        var target by mutableStateOf<List<Any>>(emptyList())
    }
    val entries = mutableStateListOf<Entry>()
    var freezeCurrent: (List<Any>) -> Unit = {}
    private fun attached(entry: Entry): Boolean = entry in entries && (entry.parent?.let {
        attached(it) && it.path == entry.parentPath
    } ?: true)
    private val current get() = entries.filter { it.child && attached(it) }.maxByOrNull { it.path.size }
    private fun key(root: Any, path: List<Any>): Any = if (path.isEmpty()) root else PageBackRouteKey(root, path)
    fun ancestorKeys(root: Any, fallback: Any?): Set<Any> {
        val path = current?.path.orEmpty()
        return buildSet {
            add(root)
            fallback?.let { add(it) }
            for (depth in 1..path.size) add(key(root, path.take(depth)))
        }
    }
    fun routeKey(root: Any): Any = current?.let { key(root, it.path) } ?: root
    fun targetKey(root: Any, fallback: Any?): Any? = current?.let { key(root, it.target) } ?: fallback
}

internal data class PageBackStackPath(val registry: PageBackStackRegistry, val path: List<Any> = emptyList(), val entry: PageBackStackRegistry.Entry? = null)
internal val LocalPageBackStackPath = compositionLocalOf<PageBackStackPath?> { null }

/** Registers visual subroutes without replacing their live composition or back callbacks. */
@Composable
internal fun PageBackStack(routeKey: Any?, backTargetKey: Any? = null, content: @Composable () -> Unit) {
    val parent = LocalPageBackStackPath.current
    val active = LocalPageContentActive.current && parent != null
    val parentPath = parent?.path.orEmpty()
    val path = if (routeKey == null) parentPath else parentPath + routeKey
    val target = if (backTargetKey == null) parentPath else parentPath + backTargetKey
    val entry = remember(parent?.registry, parent?.entry) { PageBackStackRegistry.Entry(parent?.entry) }
    val child = remember(parent?.registry, entry, path) { parent?.let { PageBackStackPath(it.registry, path, entry) } }
    remember(active, entry, path) {
        if (active && (routeKey != null || entry.child)) checkNotNull(parent).registry.freezeCurrent(path)
        Unit
    }
    DisposableEffect(parent?.registry, entry, active) {
        val registry = parent?.registry
        if (active) checkNotNull(registry).entries.add(entry)
        onDispose { if (active) registry?.entries?.remove(entry) }
    }
    SideEffect {
        entry.child = active && routeKey != null
        entry.parentPath = parentPath
        entry.path = path
        entry.target = target
    }
    CompositionLocalProvider(LocalPageBackStackPath provides child.takeIf { active }, content = content)
}
