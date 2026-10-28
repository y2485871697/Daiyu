package app.daiyu.android.ui.components

internal data class WorkspacePathPresentation(val name: String, val location: String?)

/** Display only: the original absolute path remains the navigation and clipboard value. */
internal fun workspacePathPresentation(path: String, roots: List<String>): WorkspacePathPresentation {
    val root = roots.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
        ?: return WorkspacePathPresentation(path.substringAfterLast('/').ifEmpty { path }, path)
    if (path == root) return WorkspacePathPresentation("workspace", null)
    val parts = listOf("workspace") + path.removePrefix("$root/").split('/')
    val visible = if (parts.size > 4) listOf(parts.first(), "…") + parts.takeLast(2) else parts
    return WorkspacePathPresentation(parts.last(), visible.joinToString(" / "))
}
