package app.daiyu.android.workspace

internal fun workspaceDirectoryName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.isISOControl() } &&
        name.toByteArray(Charsets.UTF_8).size <= 255 && !name.startsWith(".workspace-import-")) {
        "workspace_invalid_directory_name"
    }
    return name
}
